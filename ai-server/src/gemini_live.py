"""Gemini Multimodal Live API client with function calling support."""

import asyncio
import base64
import json
import os
from typing import Any, AsyncGenerator, Callable

from google import genai
from google.genai import types


class GeminiLiveClient:
    """Client for Gemini Multimodal Live API with tool support."""

    def __init__(
        self,
        model: str = "gemini-2.0-flash-exp",
        system_instruction: str | None = None,
    ):
        self.client = genai.Client(api_key=os.getenv("GOOGLE_API_KEY"))
        self.model = model
        self.system_instruction = system_instruction or (
            "You are a helpful voice assistant that can control a phone via Tasker. "
            "When the user asks you to do something with their phone (like turning on "
            "the flashlight, adjusting volume, etc.), use the available tools. "
            "Keep responses concise and natural for voice interaction."
        )
        self.tools: list[dict] = []
        self.tool_handlers: dict[str, Callable] = {}
        self.session = None

    def register_tools(self, declarations: list[dict], handler: Callable):
        """Register tools from MCP bridge.

        Args:
            declarations: Gemini-format tool declarations
            handler: Async function to call tools (name, args) -> result
        """
        self.tools = declarations
        self._tool_handler = handler

    def _build_config(self) -> types.LiveConnectConfig:
        """Build the Live API connection config."""
        config = types.LiveConnectConfig(
            response_modalities=["AUDIO"],
            system_instruction=types.Content(
                parts=[types.Part(text=self.system_instruction)]
            ),
        )

        if self.tools:
            config.tools = [
                types.Tool(
                    function_declarations=[
                        types.FunctionDeclaration(**tool) for tool in self.tools
                    ]
                )
            ]

        return config

    async def connect(self) -> AsyncGenerator[dict, None]:
        """Connect to Gemini Live API and yield events."""
        config = self._build_config()

        async with self.client.aio.live.connect(
            model=self.model,
            config=config,
        ) as session:
            self.session = session
            yield {"type": "connected"}

            async for response in session.receive():
                event = await self._process_response(response)
                if event:
                    yield event

    async def _process_response(self, response) -> dict | None:
        """Process a response from the Live API."""
        if response.server_content:
            content = response.server_content

            if content.model_turn:
                for part in content.model_turn.parts:
                    if part.text:
                        return {"type": "text", "data": part.text}
                    if part.inline_data:
                        return {
                            "type": "audio",
                            "data": part.inline_data.data,
                            "mime_type": part.inline_data.mime_type,
                        }

            if content.turn_complete:
                return {"type": "turn_complete"}

        if response.tool_call:
            return await self._handle_tool_call(response.tool_call)

        return None

    async def _handle_tool_call(self, tool_call) -> dict:
        """Handle function call from Gemini."""
        results = []

        for fc in tool_call.function_calls:
            name = fc.name
            args = dict(fc.args) if fc.args else {}

            try:
                result = await self._tool_handler(name, args)
                results.append({
                    "name": name,
                    "success": True,
                    "result": result,
                })
            except Exception as e:
                results.append({
                    "name": name,
                    "success": False,
                    "error": str(e),
                })

            response = types.LiveClientToolResponse(
                function_responses=[
                    types.FunctionResponse(
                        name=name,
                        response={"result": result if results[-1]["success"] else results[-1]["error"]},
                    )
                    for name, result in [(r["name"], r.get("result", r.get("error"))) for r in results]
                ]
            )
            await self.session.send(input=response)

        return {"type": "tool_calls", "data": results}

    async def send_audio(self, audio_data: bytes, mime_type: str = "audio/pcm;rate=16000"):
        """Send audio data to the Live API."""
        if not self.session:
            raise RuntimeError("Not connected")

        await self.session.send(
            input=types.LiveClientRealtimeInput(
                media_chunks=[
                    types.Blob(data=audio_data, mime_type=mime_type)
                ]
            )
        )

    async def send_text(self, text: str):
        """Send text input to the Live API."""
        if not self.session:
            raise RuntimeError("Not connected")

        await self.session.send(
            input=types.LiveClientContent(
                turns=[
                    types.Content(parts=[types.Part(text=text)])
                ],
                turn_complete=True,
            )
        )
