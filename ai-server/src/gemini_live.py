"""Gemini Multimodal Live API client with function calling support."""

import asyncio
import base64
import json
import logging
import os
from typing import Any, AsyncGenerator, Callable

from google import genai
from google.genai import types

logger = logging.getLogger(__name__)


class GeminiLiveClient:
    """Client for Gemini Multimodal Live API with tool support."""

    def __init__(
        self,
        model: str | None = None,
        system_instruction: str | None = None,
        response_mode: str = "audio",
    ):
        self.client = genai.Client(api_key=os.getenv("GOOGLE_API_KEY"))
        self.response_mode = response_mode
        # Use native audio model for audio mode, standard model for text mode
        if model:
            self.model = model
        elif response_mode == "audio":
            self.model = "gemini-2.5-flash-native-audio-preview-12-2025"
        else:
            self.model = "gemini-2.0-flash-exp"
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
        modalities = ["AUDIO"] if self.response_mode == "audio" else ["TEXT"]

        config = types.LiveConnectConfig(
            response_modalities=modalities,
            system_instruction=types.Content(
                parts=[types.Part(text=self.system_instruction)]
            ),
            input_audio_transcription=types.AudioTranscriptionConfig(),
        )

        # Only add output transcription in audio mode
        if self.response_mode == "audio":
            config.output_audio_transcription = types.AudioTranscriptionConfig()

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

            # SDK's receive() stops after turn_complete, so we loop to continue
            while True:
                async for response in session.receive():
                    event = await self._process_response(response)
                    if event:
                        yield event
                # After turn completes, loop back to receive next turn
                logger.debug("Turn complete, waiting for next input...")

    async def _process_response(self, response) -> dict | None:
        """Process a response from the Live API."""
        if response.server_content:
            content = response.server_content

            if content.model_turn:
                for part in content.model_turn.parts:
                    if part.text:
                        # Skip thinking/reasoning output (has thought attribute)
                        if hasattr(part, 'thought') and part.thought:
                            logger.debug(f"Skipping thought: {part.text[:50]}...")
                            continue
                        text = part.text.strip()
                        # Filter out thinking patterns (markdown headers are usually thinking)
                        if (text.startswith('<think>') or
                            text.startswith('<thinking>') or
                            text.startswith('**')):
                            logger.debug(f"Skipping thinking: {text[:50]}...")
                            continue
                        # Skip if it looks like internal reasoning
                        if "I've hit a snag" in text or "I'm leaning toward" in text or "Clarification is needed" in text:
                            logger.debug(f"Skipping reasoning: {text[:50]}...")
                            continue
                        logger.debug(f"Text response: {text[:50]}...")
                        return {"type": "text", "data": text}
                    if part.inline_data:
                        return {
                            "type": "audio",
                            "data": part.inline_data.data,
                            "mime_type": part.inline_data.mime_type,
                        }

            if content.output_transcription:
                return {
                    "type": "output_transcription",
                    "text": content.output_transcription.text,
                }

            if content.input_transcription:
                return {
                    "type": "input_transcription",
                    "text": content.input_transcription.text,
                }

            if content.turn_complete:
                return {"type": "turn_complete"}

        if response.tool_call:
            return await self._handle_tool_call(response.tool_call)

        return None

    async def _handle_tool_call(self, tool_call) -> dict:
        """Handle function call from Gemini."""
        logger.info(f"Tool call received: {tool_call}")
        results = []
        function_responses = []

        for fc in tool_call.function_calls:
            logger.info(f"Calling tool: {fc.name} with args: {fc.args}")
            name = fc.name
            call_id = fc.id
            args = dict(fc.args) if fc.args else {}

            try:
                result = await self._tool_handler(name, args)
                logger.info(f"Tool {name} result: {result}")
                results.append({
                    "name": name,
                    "success": True,
                    "result": result,
                })
                function_responses.append(
                    types.FunctionResponse(
                        id=call_id,
                        name=name,
                        response={"result": str(result)},
                    )
                )
            except Exception as e:
                results.append({
                    "name": name,
                    "success": False,
                    "error": str(e),
                })
                function_responses.append(
                    types.FunctionResponse(
                        id=call_id,
                        name=name,
                        response={"error": str(e)},
                    )
                )

        response = types.LiveClientToolResponse(function_responses=function_responses)
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
