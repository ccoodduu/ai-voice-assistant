# Tasker HTTP Server Setup

## Enable HTTP Server

1. Open Tasker → Preferences → Network
2. Enable "Allow Remote Access"
3. Set Port: **1821**

## Create Profiles

For each endpoint, create a Profile with Event → "HTTP Request Received".

### Torch Control

**Profile: Torch On**
- Event: HTTP Request Received
- Path: `/torch/on`
- Task: Torch → Set On

**Profile: Torch Off**
- Event: HTTP Request Received
- Path: `/torch/off`
- Task: Torch → Set Off

### Brightness

**Profile: Set Brightness**
- Event: HTTP Request Received
- Path Pattern: `/brightness/*`
- Task:
  1. Variable Set: `%level` to `%http_path_element2`
  2. Display Brightness: `%level`

### Volume

**Profile: Set Volume**
- Event: HTTP Request Received
- Path Pattern: `/volume/*/*`
- Task:
  1. Variable Set: `%stream` to `%http_path_element2`
  2. Variable Set: `%level` to `%http_path_element3`
  3. Audio → Set Volume (use %stream and %level)

### Vibrate

**Profile: Vibrate**
- Event: HTTP Request Received
- Path Pattern: `/vibrate/*`
- Task:
  1. Variable Set: `%duration` to `%http_path_element2`
  2. Alert → Vibrate: `%duration` ms

### Text to Speech

**Profile: Say**
- Event: HTTP Request Received
- Path Pattern: `/say/*`
- Task:
  1. Variable Set: `%text` to `%http_path_element2`
  2. Say: `%text`

### Notification

**Profile: Notify**
- Event: HTTP Request Received
- Path Pattern: `/notify/*/*`
- Task:
  1. Variable Set: `%title` to `%http_path_element2`
  2. Variable Set: `%body` to `%http_path_element3`
  3. Notify: Title `%title`, Text `%body`

### Battery Status

**Profile: Battery**
- Event: HTTP Request Received
- Path: `/battery/status`
- Task:
  1. HTTP Response: `{"level": %BATT, "charging": %CHARGING}`

### Ping

**Profile: Ping**
- Event: HTTP Request Received
- Path: `/ping`
- Task:
  1. HTTP Response: `pong`

## Testing

From your Pi (or any device on Tailscale):

```bash
# Replace with your phone's Tailscale IP
curl http://100.64.0.1:1821/ping
curl http://100.64.0.1:1821/torch/on
curl http://100.64.0.1:1821/torch/off
```
