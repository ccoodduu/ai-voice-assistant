"""WebRTC server for phone audio streaming to Gemini with echo cancellation."""

import asyncio
import fractions
import json
import logging
import time
import uuid
from typing import Optional

from aiohttp import web
from aiortc import RTCPeerConnection, RTCSessionDescription, RTCIceCandidate
from aiortc.mediastreams import MediaStreamTrack, AudioStreamTrack
from av import AudioFrame, AudioResampler as AVResampler
import numpy as np

from .gemini_live import GeminiLiveClient
from .websocket_server import MCPBridge

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

WEBRTC_SAMPLE_RATE = 48000
GEMINI_INPUT_RATE = 16000
GEMINI_OUTPUT_RATE = 24000


class AudioResampler:
    """Resample audio between WebRTC (48kHz) and Gemini (16kHz/24kHz)."""

    def __init__(self, from_rate: int, to_rate: int):
        self.from_rate = from_rate
        self.to_rate = to_rate
        self._resampler = AVResampler(
            format='s16',
            layout='mono',
            rate=to_rate,
        )

    def resample(self, audio_data: bytes, input_rate: int = None) -> bytes:
        """Resample audio data to target rate."""
        input_rate = input_rate or self.from_rate
        if input_rate == self.to_rate:
            return audio_data

        samples = np.frombuffer(audio_data, dtype=np.int16)
        frame = AudioFrame.from_ndarray(
            samples.reshape(1, -1),
            format='s16',
            layout='mono'
        )
        frame.sample_rate = input_rate

        resampled_frames = self._resampler.resample(frame)
        if resampled_frames:
            output = resampled_frames[0].to_ndarray()
            return output.tobytes()
        return b''


class GeminiAudioSender(AudioStreamTrack):
    """MediaStreamTrack that sends Gemini audio responses to the client."""

    kind = "audio"

    def __init__(self):
        super().__init__()
        self._queue: asyncio.Queue[bytes] = asyncio.Queue(maxsize=100)
        self._resampler = AudioResampler(GEMINI_OUTPUT_RATE, WEBRTC_SAMPLE_RATE)
        self._timestamp = 0
        self._samples_per_frame = 960  # 20ms at 48kHz
        self._start_time = None

    def add_audio(self, audio_data: bytes):
        """Add audio data from Gemini to be sent to client."""
        try:
            resampled = self._resampler.resample(audio_data, GEMINI_OUTPUT_RATE)
            self._queue.put_nowait(resampled)
        except asyncio.QueueFull:
            logger.warning("Audio queue full, dropping frame")

    async def recv(self) -> AudioFrame:
        """Called by aiortc to get the next audio frame."""
        if self._start_time is None:
            self._start_time = time.time()

        try:
            audio_data = await asyncio.wait_for(self._queue.get(), timeout=0.1)
        except asyncio.TimeoutError:
            samples = np.zeros(self._samples_per_frame, dtype=np.int16)
            audio_data = samples.tobytes()

        samples = np.frombuffer(audio_data, dtype=np.int16)
        if len(samples) < self._samples_per_frame:
            samples = np.pad(samples, (0, self._samples_per_frame - len(samples)))
        elif len(samples) > self._samples_per_frame:
            samples = samples[:self._samples_per_frame]

        frame = AudioFrame.from_ndarray(
            samples.reshape(1, -1),
            format='s16',
            layout='mono'
        )
        frame.sample_rate = WEBRTC_SAMPLE_RATE
        frame.pts = self._timestamp
        frame.time_base = fractions.Fraction(1, WEBRTC_SAMPLE_RATE)
        self._timestamp += self._samples_per_frame

        return frame


class WebRTCPeerSession:
    """Handles a single WebRTC peer connection with Gemini integration."""

    def __init__(self, mcp: MCPBridge):
        self.session_id = str(uuid.uuid4())
        self.mcp = mcp
        self.pc: Optional[RTCPeerConnection] = None
        self.gemini: Optional[GeminiLiveClient] = None
        self.audio_sender: Optional[GeminiAudioSender] = None
        self._running = False
        self._gemini_task: Optional[asyncio.Task] = None
        self._resampler = AudioResampler(WEBRTC_SAMPLE_RATE, GEMINI_INPUT_RATE)
        self._data_channel = None
        self._gemini_connected = asyncio.Event()

    async def create_offer_response(self, offer_sdp: str) -> str:
        """Process client offer and return server answer."""
        self.pc = RTCPeerConnection()
        self._running = True

        self.audio_sender = GeminiAudioSender()
        self.pc.addTrack(self.audio_sender)

        @self.pc.on("track")
        async def on_track(track: MediaStreamTrack):
            logger.info(f"Session {self.session_id}: Received {track.kind} track")
            if track.kind == "audio":
                asyncio.create_task(self._handle_audio_track(track))

        @self.pc.on("datachannel")
        def on_datachannel(channel):
            logger.info(f"Session {self.session_id}: Data channel opened: {channel.label}")
            self._data_channel = channel

            @channel.on("message")
            async def on_message(message):
                await self._handle_data_message(message)

        @self.pc.on("connectionstatechange")
        async def on_connectionstatechange():
            logger.info(f"Session {self.session_id}: Connection state: {self.pc.connectionState}")
            if self.pc.connectionState == "connected":
                await self._start_gemini_session()
            elif self.pc.connectionState in ("failed", "closed", "disconnected"):
                await self.close()

        offer = RTCSessionDescription(sdp=offer_sdp, type="offer")
        await self.pc.setRemoteDescription(offer)

        answer = await self.pc.createAnswer()
        await self.pc.setLocalDescription(answer)

        logger.info(f"Session {self.session_id}: Created answer")
        return self.pc.localDescription.sdp

    async def add_ice_candidate(self, candidate: dict):
        """Add ICE candidate from client."""
        if self.pc and candidate:
            ice = RTCIceCandidate(
                sdpMid=candidate.get("sdpMid"),
                sdpMLineIndex=candidate.get("sdpMLineIndex"),
                candidate=candidate.get("candidate", "")
            )
            await self.pc.addIceCandidate(ice)

    async def _start_gemini_session(self):
        """Start Gemini Live session."""
        self._gemini_connected.clear()
        self.gemini = GeminiLiveClient(response_mode="audio")

        if self.mcp.tools:
            self.gemini.register_tools(
                self.mcp.get_gemini_tool_declarations(),
                self.mcp.call_tool,
            )

        self._gemini_task = asyncio.create_task(self._receive_from_gemini())

        try:
            await asyncio.wait_for(self._gemini_connected.wait(), timeout=10.0)
            await self._send_session_ready()
        except asyncio.TimeoutError:
            logger.error(f"Session {self.session_id}: Timeout waiting for Gemini")

    async def _receive_from_gemini(self):
        """Receive responses from Gemini and forward to client."""
        logger.info(f"Session {self.session_id}: Connecting to Gemini...")
        try:
            async for event in self.gemini.connect():
                if not self._running:
                    break

                if event["type"] == "connected":
                    logger.info(f"Session {self.session_id}: Connected to Gemini")
                    self._gemini_connected.set()

                elif event["type"] == "audio":
                    if self.audio_sender:
                        self.audio_sender.add_audio(event["data"])

                elif event["type"] == "turn_complete":
                    logger.info(f"Session {self.session_id}: Gemini turn complete")
                    await self._send_json({"type": "turn_complete"})

                elif event["type"] == "text":
                    await self._send_json({
                        "type": "assistant_text",
                        "text": event["data"],
                    })

                elif event["type"] == "input_transcription":
                    await self._send_json({
                        "type": "user_transcript",
                        "text": event["text"],
                    })

                elif event["type"] == "output_transcription":
                    await self._send_json({
                        "type": "assistant_transcript",
                        "text": event["text"],
                    })

                elif event["type"] == "tool_calls":
                    for call in event["data"]:
                        await self._send_json({
                            "type": "tool_call",
                            "name": call["name"],
                            "status": "success" if call["success"] else "error",
                        })
        except Exception as e:
            logger.error(f"Session {self.session_id}: Gemini error - {e}")
        finally:
            logger.info(f"Session {self.session_id}: Gemini task ended")

    async def _handle_audio_track(self, track: MediaStreamTrack):
        """Process incoming audio from client and send to Gemini."""
        logger.info(f"Session {self.session_id}: Starting audio processing")
        try:
            while self._running:
                try:
                    frame = await asyncio.wait_for(track.recv(), timeout=1.0)
                except asyncio.TimeoutError:
                    continue

                if self.gemini and self.gemini.session:
                    audio_data = frame.to_ndarray().tobytes()
                    resampled = self._resampler.resample(audio_data, frame.sample_rate)
                    await self.gemini.send_audio(resampled)
        except Exception as e:
            if self._running:
                logger.error(f"Session {self.session_id}: Audio track error - {e}")
        finally:
            logger.info(f"Session {self.session_id}: Audio processing ended")

    async def _handle_data_message(self, message: str):
        """Handle control messages from data channel."""
        try:
            data = json.loads(message)
            msg_type = data.get("type")

            if msg_type == "text_input":
                text = data.get("text", "").strip()
                if text and self.gemini and self.gemini.session:
                    logger.info(f"Session {self.session_id}: Text input: {text[:50]}...")
                    await self._send_json({"type": "user_transcript", "text": text})
                    await self.gemini.send_text(text)

            elif msg_type == "control":
                action = data.get("action")
                if action == "end_session":
                    await self.close()

        except json.JSONDecodeError:
            logger.warning(f"Session {self.session_id}: Invalid JSON: {message}")

    async def _send_session_ready(self):
        """Send session ready message to client."""
        await self._send_json({
            "type": "session_ready",
            "session_id": self.session_id,
            "mode": "audio",
            "input_format": {"sample_rate": WEBRTC_SAMPLE_RATE, "channels": 1, "encoding": "pcm_s16le"},
            "output_format": {"sample_rate": WEBRTC_SAMPLE_RATE, "channels": 1, "encoding": "pcm_s16le"},
        })

    async def _send_json(self, data: dict):
        """Send JSON message via data channel."""
        if self._data_channel and self._data_channel.readyState == "open":
            self._data_channel.send(json.dumps(data))

    async def close(self):
        """Close the session."""
        self._running = False

        if self._gemini_task and not self._gemini_task.done():
            self._gemini_task.cancel()
            try:
                await self._gemini_task
            except asyncio.CancelledError:
                pass

        if self.pc:
            await self.pc.close()

        logger.info(f"Session {self.session_id}: Closed")


class WebRTCSignalingServer:
    """HTTP server for WebRTC signaling."""

    def __init__(self, host: str = "0.0.0.0", port: int = 8766):
        self.host = host
        self.port = port
        self.mcp: Optional[MCPBridge] = None
        self.sessions: dict[str, WebRTCPeerSession] = {}
        self.app = web.Application()
        self._setup_routes()

    def _setup_routes(self):
        """Setup HTTP routes."""
        self.app.router.add_post("/rtc/offer", self._handle_offer)
        self.app.router.add_post("/rtc/ice", self._handle_ice)
        self.app.router.add_get("/rtc/health", self._handle_health)
        self.app.router.add_options("/rtc/offer", self._handle_cors)
        self.app.router.add_options("/rtc/ice", self._handle_cors)

    def _cors_headers(self) -> dict:
        """Return CORS headers."""
        return {
            "Access-Control-Allow-Origin": "*",
            "Access-Control-Allow-Methods": "POST, GET, OPTIONS",
            "Access-Control-Allow-Headers": "Content-Type",
        }

    async def _handle_cors(self, request: web.Request) -> web.Response:
        """Handle CORS preflight."""
        return web.Response(headers=self._cors_headers())

    async def _handle_offer(self, request: web.Request) -> web.Response:
        """Handle SDP offer from client."""
        try:
            data = await request.json()
            offer_sdp = data.get("sdp")
            offer_type = data.get("type")

            if not offer_sdp or offer_type != "offer":
                return web.json_response(
                    {"error": "Invalid offer"},
                    status=400,
                    headers=self._cors_headers()
                )

            session = WebRTCPeerSession(self.mcp)
            self.sessions[session.session_id] = session

            answer_sdp = await session.create_offer_response(offer_sdp)

            return web.json_response(
                {
                    "sdp": answer_sdp,
                    "type": "answer",
                    "session_id": session.session_id,
                },
                headers=self._cors_headers()
            )

        except Exception as e:
            logger.error(f"Error handling offer: {e}")
            return web.json_response(
                {"error": str(e)},
                status=500,
                headers=self._cors_headers()
            )

    async def _handle_ice(self, request: web.Request) -> web.Response:
        """Handle ICE candidate from client."""
        try:
            data = await request.json()
            session_id = data.get("session_id")
            candidate = data.get("candidate")

            session = self.sessions.get(session_id)
            if not session:
                return web.json_response(
                    {"error": "Session not found"},
                    status=404,
                    headers=self._cors_headers()
                )

            if candidate:
                await session.add_ice_candidate(candidate)

            return web.json_response({"status": "ok"}, headers=self._cors_headers())

        except Exception as e:
            logger.error(f"Error handling ICE: {e}")
            return web.json_response(
                {"error": str(e)},
                status=500,
                headers=self._cors_headers()
            )

    async def _handle_health(self, request: web.Request) -> web.Response:
        """Health check endpoint."""
        return web.json_response({
            "status": "ok",
            "sessions": len(self.sessions),
            "tools": len(self.mcp.tools) if self.mcp else 0,
        }, headers=self._cors_headers())

    async def start(self, mcp_url: str = "http://localhost:8100/sse", additional_mcps: list[dict] = None):
        """Start the signaling server."""
        self.mcp = MCPBridge()
        await self.mcp.connect(mcp_url)
        if additional_mcps:
            await self.mcp.connect_servers(additional_mcps, retries=5)

        logger.info(f"Starting WebRTC signaling server on http://{self.host}:{self.port}")
        logger.info(f"MCP tools available: {len(self.mcp.tools)}")

        runner = web.AppRunner(self.app)
        await runner.setup()
        site = web.TCPSite(runner, self.host, self.port)
        await site.start()

        await asyncio.Future()


async def run_webrtc_server(
    port: int = 8766,
    mcp_url: str = "http://localhost:8100/sse",
    additional_mcps: list[dict] = None
):
    """Run the WebRTC signaling server."""
    server = WebRTCSignalingServer(port=port)
    await server.start(mcp_url, additional_mcps)
