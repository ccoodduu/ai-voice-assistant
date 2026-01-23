# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Voice-controlled AI assistant using Gemini Multimodal Live API with phone control via MCP (Model Context Protocol). Runs on a Raspberry Pi, connects to an Android phone via Tailscale VPN.

## Architecture

```
Android App ←──WebRTC/WS──→ AI Server (Pi) ←──SSE──→ Tasker MCP (Pi)
    │                           │                         │
    │                           ↓                         ↓
    │                    Gemini Live API           Phone HTTP (Tasker)
    └──────────────────────────────────────────────────────┘
                         Tailscale VPN
```

**Components:**
- `ai-server/` - WebRTC/WebSocket server bridging phone audio to Gemini, handles MCP tool calls
- `phone-tasker-mcp/` - MCP server exposing Tasker actions (torch, volume, weather, WoL)
- `android-app/` - Kotlin/Compose app with overlay support for voice interaction
- `gemini-live-mcp/` - Legacy local microphone mode (rarely used)

## Raspberry Pi Deployment

**SSH access:**
```bash
ssh pi@3d-printer
```

**Sync and deploy workflow:**
```bash
# On local machine - commit and push
git add -A && git commit -m "message" && git push

# On Pi - pull and restart (via SSH or run locally on Pi)
ssh pi@3d-printer "cd ~/ai-voice-assistant && git pull --ff-only && docker compose up -d ai-server"
```

**Hot-reload:** Source code is volume-mounted with `PYTHONPATH=/app`, so Python changes take effect on container restart without rebuild.

**Full rebuild (slow on Pi due to limited RAM):**
```bash
ssh pi@3d-printer "cd ~/ai-voice-assistant && docker compose build ai-server"
```

## Docker Commands

```bash
# Start all services
docker compose up -d

# Restart specific service
docker compose restart ai-server

# View logs
docker logs ai-server --tail 50
docker logs tasker-mcp --tail 50

# Rebuild single service
docker compose build ai-server
docker compose build tasker-mcp
```

## Local Development

```bash
# AI Server (WebSocket mode)
cd ai-server
pip install -e .
python -m src.main --mode websocket --ws-port 8765

# AI Server (WebRTC mode - with echo cancellation)
python -m src.main --mode webrtc --webrtc-port 8766

# Tasker MCP
cd phone-tasker-mcp
pip install -e .
python -m tasker_mcp.server --transport sse --port 8100
```

## Environment Variables

Required in `.env` on Pi:
```
GOOGLE_API_KEY=<gemini-api-key>
TASKER_PHONE_HOST=100.123.253.113  # Phone's Tailscale IP
TASKER_PHONE_PORT=1821
```

## Key Files

- `ai-server/src/websocket_server.py` - WebSocket server, MCP bridge, phone audio handling
- `ai-server/src/webrtc_server.py` - WebRTC server with echo cancellation support
- `ai-server/src/gemini_live.py` - Gemini Live API client with tool calling
- `phone-tasker-mcp/tasker_mcp/server.py` - MCP tools (torch, volume, weather, WoL)
- `docker-compose.yml` - Container orchestration

## MCP Tool Schema

When adding MCP tools, ensure schemas are Gemini-compatible:
- Use simple types: `string`, `number`, `integer`, `boolean`
- Array items must have explicit `type` field
- Avoid `anyOf`/`oneOf` - the bridge flattens these to first non-null type

## Ports

| Service | Port | Transport |
|---------|------|-----------|
| Tasker MCP | 8100 | SSE |
| AI Server (WebSocket) | 8765 | WebSocket |
| AI Server (WebRTC) | 8766 | HTTP (signaling) |
| Phone Tasker HTTP | 1821 | HTTP |
