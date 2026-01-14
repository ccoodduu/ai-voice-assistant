# AI Voice Assistant

Voice-controlled AI assistant using Gemini Multimodal Live API with Tasker phone control via MCP.

## Components

This project consists of multiple repositories:

- **[gemini-live-mcp](https://github.com/ccoodduu/gemini-live-mcp)** - Gemini Live API client with MCP integration
- **[phone-tasker-mcp](https://github.com/ccoodduu/phone-tasker-mcp)** - MCP server exposing Tasker actions as tools

## Architecture

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│   Raspberry Pi  │     │  Nothing Phone   │     │     Gemini      │
│                 │     │                  │     │   Live API      │
│  ┌───────────┐  │     │  ┌────────────┐  │     │                 │
│  │ AI Server │──┼─────┼──│  Tasker    │  │     │  (Voice + AI)   │
│  │           │  │ HTTP│  │  HTTP Srv  │  │     │                 │
│  └─────┬─────┘  │     │  └────────────┘  │     └────────┬────────┘
│        │        │     │                  │              │
│  ┌─────┴─────┐  │     │   Tailscale      │              │
│  │ MCP Server│  │     │   100.x.x.x      │         WebSocket
│  └───────────┘  │     │                  │              │
│                 │     └──────────────────┘              │
│   Microphone ◄──┼──────────────────────────────────────┘
│     Speaker     │
└─────────────────┘
```

## Setup

### 1. Configure Environment

```bash
cp .env.example .env
# Edit .env with your values:
# - GOOGLE_API_KEY: Your Gemini API key
# - TASKER_PHONE_HOST: Your phone's Tailscale IP
```

### 2. Tasker Setup on Phone

Create HTTP Server profiles in Tasker for each endpoint:

| Path | Action |
|------|--------|
| `/torch/on` | Flashlight On |
| `/torch/off` | Flashlight Off |
| `/brightness/:level` | Set Screen Brightness |
| `/volume/:stream/:level` | Set Volume |
| `/vibrate/:ms` | Vibrate |
| `/say/:text` | Text to Speech |
| `/notify/:title/:text` | Show Notification |
| `/battery/status` | Return battery info |
| `/ping` | Return "pong" |

### 3. Run with Docker

```bash
docker compose up --build
```

### 4. Run Locally (Development)

```bash
# Terminal 1: MCP Server
cd phone-tasker-mcp
pip install -e .
tasker-mcp

# Terminal 2: AI Server
cd ai-server
pip install -e .
ai-server
```

## Available Tools

The MCP server exposes these tools to Gemini:

- `torch_on` / `torch_off` / `toggle_torch` - Control flashlight
- `set_brightness` - Adjust screen brightness (0-255)
- `set_volume` - Set volume for media/ring/alarm/notification
- `vibrate` - Vibrate the phone
- `say_text` - Text-to-speech
- `send_notification` - Push notification
- `get_battery_status` - Check battery
- `launch_app` - Open an app
- `take_photo` - Capture photo
- `phone_ping` - Check connectivity

## Voice Commands Examples

- "Turn on the flashlight"
- "Set the brightness to 50 percent"
- "What's the battery level?"
- "Set media volume to 10"
- "Say hello world on my phone"
