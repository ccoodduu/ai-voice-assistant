"""MCP Client for connecting to Tasker MCP server."""

import asyncio
import json
from typing import Any

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client


class MCPToolBridge:
    """Bridge between MCP tools and Gemini function calling."""

    def __init__(self):
        self.session: ClientSession | None = None
        self.tools: dict[str, dict] = {}
        self._connected = False

    async def connect(self, server_command: str = "tasker-mcp"):
        """Connect to MCP server and discover available tools."""
        server_params = StdioServerParameters(
            command=server_command,
            args=[],
        )

        async with stdio_client(server_params) as (read, write):
            async with ClientSession(read, write) as session:
                self.session = session
                await session.initialize()
                await self._discover_tools()
                self._connected = True

    async def _discover_tools(self):
        """Discover available tools from MCP server."""
        if not self.session:
            return

        result = await self.session.list_tools()
        for tool in result.tools:
            self.tools[tool.name] = {
                "name": tool.name,
                "description": tool.description,
                "input_schema": tool.inputSchema,
            }

    def get_gemini_tool_declarations(self) -> list[dict]:
        """Convert MCP tools to Gemini function declarations format."""
        declarations = []
        for name, tool in self.tools.items():
            declaration = {
                "name": name,
                "description": tool["description"],
            }
            if tool["input_schema"].get("properties"):
                declaration["parameters"] = {
                    "type": "object",
                    "properties": tool["input_schema"]["properties"],
                    "required": tool["input_schema"].get("required", []),
                }
            declarations.append(declaration)
        return declarations

    async def call_tool(self, name: str, arguments: dict[str, Any]) -> Any:
        """Execute an MCP tool and return the result."""
        if not self.session:
            raise RuntimeError("Not connected to MCP server")

        result = await self.session.call_tool(name, arguments)
        return result.content


class MCPHTTPClient:
    """HTTP-based MCP client for when MCP server exposes HTTP transport."""

    def __init__(self, base_url: str = "http://localhost:8000"):
        self.base_url = base_url.rstrip("/")
        self.tools: dict[str, dict] = {}

    async def discover_tools(self):
        """Discover tools via HTTP endpoint."""
        import httpx

        async with httpx.AsyncClient() as client:
            response = await client.get(f"{self.base_url}/tools")
            if response.status_code == 200:
                tools_data = response.json()
                for tool in tools_data:
                    self.tools[tool["name"]] = tool

    def get_gemini_tool_declarations(self) -> list[dict]:
        """Convert MCP tools to Gemini function declarations format."""
        declarations = []
        for name, tool in self.tools.items():
            declaration = {
                "name": name,
                "description": tool.get("description", ""),
            }
            schema = tool.get("input_schema", {})
            if schema.get("properties"):
                declaration["parameters"] = {
                    "type": "object",
                    "properties": schema["properties"],
                    "required": schema.get("required", []),
                }
            declarations.append(declaration)
        return declarations

    async def call_tool(self, name: str, arguments: dict[str, Any]) -> dict:
        """Call a tool via HTTP."""
        import httpx

        async with httpx.AsyncClient() as client:
            response = await client.post(
                f"{self.base_url}/tools/{name}",
                json=arguments,
            )
            return response.json()
