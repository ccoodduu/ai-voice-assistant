"""Main entry point for AI voice assistant."""

import argparse
import asyncio
import os
import signal
import sys

from .audio import AudioStream
from .gemini_live import GeminiLiveClient
from .mcp_client import MCPToolBridge
from .websocket_server import run_websocket_server
from .webrtc_server import run_webrtc_server


async def run_assistant():
    """Run the voice assistant with Gemini + MCP integration."""
    print("Starting AI Voice Assistant...")
    print("Connecting to MCP server...")

    mcp = MCPToolBridge()
    try:
        await mcp.connect()
        print(f"Connected! Found {len(mcp.tools)} tools:")
        for name in mcp.tools:
            print(f"  - {name}")
    except Exception as e:
        print(f"Warning: Could not connect to MCP server: {e}")
        print("Running without phone control tools.")

    gemini = GeminiLiveClient()

    if mcp.tools:
        gemini.register_tools(
            mcp.get_gemini_tool_declarations(),
            mcp.call_tool,
        )

    audio = AudioStream()
    shutdown_event = asyncio.Event()

    def handle_shutdown(*args):
        print("\nShutting down...")
        shutdown_event.set()

    signal.signal(signal.SIGINT, handle_shutdown)
    signal.signal(signal.SIGTERM, handle_shutdown)

    try:
        with audio:
            print("Audio streams ready.")
            print("Connecting to Gemini Live API...")

            async def send_audio():
                async for chunk in audio.read_audio():
                    if shutdown_event.is_set():
                        break
                    try:
                        await gemini.send_audio(chunk)
                    except Exception as e:
                        if not shutdown_event.is_set():
                            print(f"Audio send error: {e}")

            async def receive_responses():
                async for event in gemini.connect():
                    if shutdown_event.is_set():
                        break

                    if event["type"] == "connected":
                        print("Connected to Gemini! Listening...")
                        print("(Speak to interact, Ctrl+C to quit)")

                    elif event["type"] == "text":
                        print(f"Assistant: {event['data']}")

                    elif event["type"] == "audio":
                        audio.play_audio(event["data"])

                    elif event["type"] == "tool_calls":
                        for call in event["data"]:
                            status = "✓" if call["success"] else "✗"
                            print(f"  {status} {call['name']}")

                    elif event["type"] == "turn_complete":
                        pass

            audio_task = asyncio.create_task(send_audio())
            response_task = asyncio.create_task(receive_responses())

            await shutdown_event.wait()

            audio_task.cancel()
            response_task.cancel()

            try:
                await asyncio.gather(audio_task, response_task)
            except asyncio.CancelledError:
                pass

    except Exception as e:
        print(f"Error: {e}")
        raise


def main():
    """Entry point."""
    parser = argparse.ArgumentParser(description="AI Voice Assistant Server")
    parser.add_argument(
        "--mode",
        choices=["local", "websocket", "webrtc"],
        default="local",
        help="Run mode: local (microphone), websocket (phone streaming), or webrtc (with echo cancellation)",
    )
    parser.add_argument(
        "--ws-port",
        type=int,
        default=8765,
        help="WebSocket server port (default: 8765)",
    )
    parser.add_argument(
        "--webrtc-port",
        type=int,
        default=8766,
        help="WebRTC signaling server port (default: 8766)",
    )
    parser.add_argument(
        "--mcp-url",
        type=str,
        default="http://localhost:8100/sse",
        help="MCP server SSE URL (default: http://localhost:8100/sse)",
    )
    parser.add_argument(
        "--spotify-mcp",
        action="store_true",
        help="Enable Spotify MCP (hosted at open-mcp.org)",
    )
    parser.add_argument(
        "--studieplus-mcp",
        action="store_true",
        help="Enable Studie+ MCP for school homework",
    )
    args = parser.parse_args()

    if not os.getenv("GOOGLE_API_KEY"):
        print("Error: GOOGLE_API_KEY environment variable not set")
        sys.exit(1)

    if args.mode == "local":
        asyncio.run(run_assistant())
    else:
        additional_mcps = []
        if args.spotify_mcp:
            spotify_token = os.getenv("SPOTIFY_MCP_TOKEN")
            if spotify_token:
                print("Spotify MCP enabled with token")
                additional_mcps.append({
                    "name": "spotify",
                    "url": "https://spotify.server.open-mcp.org/latest/mcp",
                    "transport": "http",
                    "headers": {"Authorization": f"Bearer {spotify_token}"},
                })
            else:
                print("Warning: SPOTIFY_MCP_TOKEN not set, skipping Spotify MCP")

        if args.studieplus_mcp:
            print("Studie+ MCP enabled")
            additional_mcps.append({
                "name": "studieplus",
                "url": "http://localhost:8101/sse",
                "transport": "sse",
            })

        if args.mode == "websocket":
            print(f"Starting WebSocket server on port {args.ws_port}...")
            print(f"MCP server: {args.mcp_url}")
            asyncio.run(run_websocket_server(
                port=args.ws_port,
                mcp_url=args.mcp_url,
                additional_mcps=additional_mcps if additional_mcps else None
            ))
        elif args.mode == "webrtc":
            print(f"Starting WebRTC signaling server on port {args.webrtc_port}...")
            print(f"MCP server: {args.mcp_url}")
            asyncio.run(run_webrtc_server(
                port=args.webrtc_port,
                mcp_url=args.mcp_url,
                additional_mcps=additional_mcps if additional_mcps else None
            ))


if __name__ == "__main__":
    main()
