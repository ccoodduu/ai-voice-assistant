"""WebSocket server for phone audio streaming to Gemini."""

import asyncio
import json
import logging
import uuid
from contextlib import AsyncExitStack
from typing import Optional

import websockets
from websockets.server import WebSocketServerProtocol

from mcp import ClientSession
from mcp.client.sse import sse_client

from .gemini_live import GeminiLiveClient

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class MCPBridge:
    """MCP client supporting SSE transport for phone-tasker-mcp."""

    def __init__(self):
        self.exit_stack = AsyncExitStack()
        self.sessions: dict[str, ClientSession] = {}
        self.tools: dict[str, dict] = {}

    async def connect(self, sse_url: str = "http://localhost:8100/sse", retries: int = 5):
        """Connect to MCP server via SSE with retry logic."""
        for attempt in range(retries):
            try:
                sse_transport = await self.exit_stack.enter_async_context(
                    sse_client(sse_url)
                )
                read, write = sse_transport
                session = await self.exit_stack.enter_async_context(
                    ClientSession(read, write)
                )
                await session.initialize()
                self.sessions["tasker"] = session

                response = await session.list_tools()
                for tool in response.tools:
                    self.tools[tool.name] = {
                        "name": tool.name,
                        "description": tool.description,
                        "input_schema": tool.inputSchema,
                    }
                logger.info(f"Connected to MCP with {len(self.tools)} tools")
                return True
            except Exception as e:
                logger.warning(f"MCP connection attempt {attempt + 1}/{retries} failed: {e}")
                if attempt < retries - 1:
                    await asyncio.sleep(2)
                    self.exit_stack = AsyncExitStack()
                else:
                    logger.error(f"Failed to connect to MCP after {retries} attempts")
                    return False

    def get_gemini_tool_declarations(self) -> list[dict]:
        """Convert MCP tools to Gemini function declarations format."""
        declarations = []
        for name, tool in self.tools.items():
            declaration = {
                "name": name,
                "description": tool["description"],
            }
            if tool["input_schema"].get("properties"):
                clean_props = {}
                for prop_name, prop_def in tool["input_schema"]["properties"].items():
                    clean_prop = {"type": prop_def.get("type", "string")}
                    if "description" in prop_def:
                        clean_prop["description"] = prop_def["description"]
                    if "enum" in prop_def:
                        clean_prop["description"] = clean_prop.get("description", "") + f" (allowed values: {', '.join(prop_def['enum'])})"
                    clean_props[prop_name] = clean_prop
                declaration["parameters"] = {
                    "type": "object",
                    "properties": clean_props,
                    "required": tool["input_schema"].get("required", []),
                }
            declarations.append(declaration)
        return declarations

    async def call_tool(self, name: str, arguments: dict):
        """Execute an MCP tool."""
        for session in self.sessions.values():
            response = await session.list_tools()
            tool_names = [t.name for t in response.tools]
            if name in tool_names:
                result = await session.call_tool(name, arguments)
                return result.content
        raise ValueError(f"Tool {name} not found")

    async def cleanup(self):
        """Clean up connections."""
        await self.exit_stack.aclose()


class PhoneAudioBridge:
    """Bridges a phone WebSocket connection to Gemini Live API."""

    def __init__(
        self,
        websocket: WebSocketServerProtocol,
        mcp: MCPBridge,
    ):
        self.websocket = websocket
        self.mcp = mcp
        self.session_id = str(uuid.uuid4())
        self.gemini: Optional[GeminiLiveClient] = None
        self._running = False

    async def run(self):
        """Main loop handling bidirectional audio streaming."""
        self._running = True
        logger.info(f"Session {self.session_id}: Starting")

        try:
            self.gemini = GeminiLiveClient()
            if self.mcp.tools:
                self.gemini.register_tools(
                    self.mcp.get_gemini_tool_declarations(),
                    self.mcp.call_tool,
                )

            await self._send_json({
                "type": "session_ready",
                "session_id": self.session_id,
                "input_format": {"sample_rate": 16000, "channels": 1, "encoding": "pcm_s16le"},
                "output_format": {"sample_rate": 24000, "channels": 1, "encoding": "pcm_s16le"},
            })
            logger.info(f"Session {self.session_id}: Sent session_ready, starting tasks")

            async with asyncio.TaskGroup() as tg:
                tg.create_task(self._receive_from_phone())
                tg.create_task(self._receive_from_gemini())

        except* websockets.ConnectionClosed as eg:
            logger.info(f"Session {self.session_id}: Phone disconnected - {eg.exceptions}")
        except* Exception as eg:
            logger.error(f"Session {self.session_id}: Error - {eg.exceptions}")
            try:
                await self._send_error("session_error", str(eg.exceptions))
            except:
                pass
        finally:
            self._running = False
            logger.info(f"Session {self.session_id}: Ended")

    async def _receive_from_phone(self):
        """Receive audio from phone and forward to Gemini."""
        logger.info(f"Session {self.session_id}: _receive_from_phone started")
        try:
            async for message in self.websocket:
                if not self._running:
                    break

                if isinstance(message, bytes):
                    if self.gemini and self.gemini.session:
                        await self.gemini.send_audio(message)
                else:
                    try:
                        data = json.loads(message)
                        await self._handle_control_message(data)
                    except json.JSONDecodeError:
                        logger.warning(f"Invalid JSON from phone: {message}")
        finally:
            logger.info(f"Session {self.session_id}: _receive_from_phone ended")

    async def _receive_from_gemini(self):
        """Receive responses from Gemini and forward to phone."""
        logger.info(f"Session {self.session_id}: Connecting to Gemini...")
        try:
            async for event in self.gemini.connect():
                if not self._running:
                    break

                if event["type"] == "connected":
                    logger.info(f"Session {self.session_id}: Connected to Gemini")

                elif event["type"] == "audio":
                    await self.websocket.send(event["data"])

                elif event["type"] == "turn_complete":
                    logger.info(f"Session {self.session_id}: Gemini turn complete")

                elif event["type"] == "text":
                    await self._send_json({
                        "type": "transcript",
                        "text": event["data"],
                        "is_final": True,
                    })

                elif event["type"] == "tool_calls":
                    for call in event["data"]:
                        await self._send_json({
                            "type": "tool_call",
                            "name": call["name"],
                            "status": "success" if call["success"] else "error",
                        })
        finally:
            logger.info(f"Session {self.session_id}: _receive_from_gemini ended")

    async def _handle_control_message(self, data: dict):
        """Handle control messages from phone."""
        msg_type = data.get("type")

        if msg_type == "hello":
            logger.info(f"Session {self.session_id}: Hello from {data.get('client_id')}")

        elif msg_type == "control":
            action = data.get("action")
            if action == "end_session":
                self._running = False

    async def _send_json(self, data: dict):
        """Send JSON message to phone."""
        try:
            await self.websocket.send(json.dumps(data))
        except websockets.ConnectionClosed:
            pass

    async def _send_error(self, code: str, message: str):
        """Send error message to phone."""
        await self._send_json({
            "type": "error",
            "code": code,
            "message": message,
        })


class WebSocketServer:
    """WebSocket server accepting phone audio connections."""

    def __init__(self, host: str = "0.0.0.0", port: int = 8765):
        self.host = host
        self.port = port
        self.mcp: Optional[MCPBridge] = None
        self.active_sessions: dict[str, PhoneAudioBridge] = {}

    async def start(self, mcp_url: str = "http://localhost:8100/sse"):
        """Start the WebSocket server."""
        self.mcp = MCPBridge()
        await self.mcp.connect(mcp_url)

        logger.info(f"Starting WebSocket server on ws://{self.host}:{self.port}")

        async with websockets.serve(
            self._handle_connection,
            self.host,
            self.port,
            ping_interval=30,
            ping_timeout=10,
        ):
            await asyncio.Future()

    async def _handle_connection(self, websocket: WebSocketServerProtocol):
        """Handle a new phone connection."""
        bridge = PhoneAudioBridge(websocket, self.mcp)
        self.active_sessions[bridge.session_id] = bridge

        try:
            await bridge.run()
        finally:
            del self.active_sessions[bridge.session_id]


async def run_websocket_server(port: int = 8765, mcp_url: str = "http://localhost:8100/sse"):
    """Run the WebSocket server."""
    server = WebSocketServer(port=port)
    await server.start(mcp_url)
