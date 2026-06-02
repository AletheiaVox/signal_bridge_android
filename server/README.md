# Signal Bridge Remote

Remote MCP server for AI-controlled intimate hardware via [Buttplug.io](https://buttplug.io) and [Intiface Central](https://intiface.com/central/).

Lets Claude (or any MCP client) control Bluetooth toys over the internet, with proper authentication, safety systems, and multi-user support.

## How It Works

```
Claude ──(MCP over HTTPS)──→ VPS Server ──(WebSocket)──→ Phone ──(Bluetooth)──→ Toy
```

Three tiers, all traffic flows through the VPS:

1. **Your phone** runs Intiface Central (Bluetooth to toys) + the relay client (WebSocket to VPS)
2. **A VPS** ($5/month DigitalOcean, Linode, etc.) runs auth, MCP endpoint, and WebSocket relay
3. **Claude** connects to the VPS as a remote MCP server

The phone makes an *outbound* connection to the VPS — no port forwarding, no firewall config, works behind any network.

## Safety

**Dead man's switch**: The server pings your phone every 2 seconds. If your phone stops responding for 6 seconds (configurable), all devices are immediately stopped. The phone relay also has a local failsafe — if it loses server connection, it stops all devices before anything else.

Hardware will never be left running unattended after a connection failure.

## Security

- JWT authentication on all endpoints
- Per-IP rate limiting on auth (5/minute), commands (120/minute), and globally (300/minute)
- Automatic temporary IP bans after 20 failed auth attempts
- WebSocket connection limits per IP (3 max)
- Registration can be locked after your users are set up

These defaults are tuned to handle targeted harassment while being invisible to normal use. Adjust in `.env` if needed.

---

## Setup Guide

### Prerequisites

- Python 3.10+
- A VPS (DigitalOcean, Linode, Hetzner — $5/month is fine)
- Intiface Central v3 installed on your phone (or desktop for testing)
- A Bluetooth toy paired in Intiface
- Docker (recommended) or bare Python

### Step 1: Deploy the Server

**Option A: Docker (recommended)**

```bash
# On your VPS
git clone <this-repo> signal-bridge-remote
cd signal-bridge-remote/server

# Create your config
cp .env.example .env

# Generate a secret key
python3 -c "import secrets; print(secrets.token_hex(32))"
# Paste it into .env as SB_SECRET_KEY

# Build and run
docker compose up -d

# Check it's running
curl http://localhost:8420/health
```

**Option B: Bare Python**

```bash
cd server
pip install -r requirements-server.txt

# Create .env (same as above)
cp .env.example .env
# Edit .env, set SB_SECRET_KEY

# Run
uvicorn server.app:app --host 0.0.0.0 --port 8420
```

**Important**: Put this behind a reverse proxy (Caddy or nginx) with HTTPS. Caddy is easiest:

```
# Caddyfile
signal-bridge.yourdomain.com {
    reverse_proxy localhost:8420
}
```

### Step 2: Create Your Account

```bash
# Register
curl -X POST https://signal-bridge.yourdomain.com/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username": "yourname", "password": "your-secure-password"}'

# Response includes your token:
# {"user_id": "...", "username": "yourname", "token": "eyJ..."}
```

Save that token — you'll need it for both the phone client and Claude config.

To get a fresh token later:
```bash
curl -X POST https://signal-bridge.yourdomain.com/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "yourname", "password": "your-secure-password"}'
```

### Step 3: Set Up the Phone Relay

On the device running Intiface Central (phone or desktop):

```bash
pip install -r requirements-phone.txt

# Start Intiface Central first, make sure your toy is connected

# Then run the relay client
python phone/relay_client.py \
  --server wss://signal-bridge.yourdomain.com/ws/phone \
  --token YOUR_JWT_TOKEN_HERE
```

You should see:
```
Connected to Intiface at ws://127.0.0.1:12345
Found 2 device(s)
Authenticated with server!
```

**On Android**: Install [Termux](https://termux.dev/), install Python (`pkg install python`), then run the relay client as above.

**On iOS**: Currently requires a desktop intermediary. iOS doesn't have a good Python runtime. Run the relay client on a desktop that's on the same network as your phone, and point Intiface at the desktop's Bluetooth adapter.

### Step 4: Configure Claude Desktop

Add to your Claude Desktop MCP config (`claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "signal-bridge": {
      "url": "https://signal-bridge.yourdomain.com/mcp",
      "transport": {
        "type": "streamableHttp"
      },
      "headers": {
        "Authorization": "Bearer YOUR_JWT_TOKEN_HERE"
      }
    }
  }
}
```

> **Note**: The exact remote MCP config format may vary by Claude Desktop version. If the above doesn't work, check the [MCP docs](https://modelcontextprotocol.io) for the current client configuration format.

Restart Claude Desktop. You should now be able to ask Claude to `list_devices` and see your toys.

---

## Available Tools

### Device Control (Output)

All output tools accept: `device` (name or "all"), `intensity` (0.0–1.0), `duration` (seconds, 0=indefinite)

| Tool | Description |
|------|-------------|
| `vibrate` | Vibration. Most common output type. |
| `rotate` | Rotation or sonic pulse. Device-specific. |
| `oscillate` | Thrusting / oscillation. Device-specific. |
| `constrict` | Compression / squeeze. Device-specific. |
| `temperature` | Heating / cooling. Device-specific. |
| `led` | LED light control. Device-specific. |
| `position` | Linear positioning. Device-specific. |
| `spray` | Spray / liquid output. Device-specific. |
| `stop` | Immediately stop all outputs on a device. |

### Patterns

All patterns accept: `device`, `output_type` (any of the above, default "vibrate"), `intensity`, `duration`

| Tool | Description |
|------|-------------|
| `pulse` | Rhythmic on/off (0.5s on, 0.3s off) |
| `wave` | Smooth sine-wave intensity modulation |
| `escalate` | Gradual ramp from 0% to peak |

### Sensors

| Tool | Description |
|------|-------------|
| `read_battery` | Battery level (0–100%) |
| `read_sensor` | Generic sensor: battery, rssi, pressure, button, depth, position |

### Discovery

| Tool | Description |
|------|-------------|
| `list_devices` | Show all connected devices with capabilities |
| `scan_devices` | Rescan for new/reconnected Bluetooth devices |

---

## Configuration Reference

All settings via environment variables (or `.env` file):

| Variable | Default | Description |
|----------|---------|-------------|
| `SB_SECRET_KEY` | (required) | JWT signing key. Generate a random one. |
| `SB_PORT` | 8420 | Server port |
| `SB_REGISTRATION_OPEN` | true | Allow new user registration |
| `SB_TOKEN_EXPIRY_HOURS` | 168 | JWT lifetime (1 week) |
| `SB_RATE_LIMIT_AUTH` | 5/minute | Auth endpoint rate limit per IP |
| `SB_RATE_LIMIT_COMMANDS` | 120/minute | Command rate limit per user |
| `SB_RATE_LIMIT_GLOBAL` | 300/minute | Global rate limit per IP |
| `SB_MAX_WS_PER_IP` | 3 | Max WebSocket connections per IP |
| `SB_BAN_THRESHOLD` | 20 | Failed attempts before temp ban |
| `SB_BAN_DURATION_MINUTES` | 30 | How long bans last |
| `SB_HEARTBEAT_INTERVAL` | 2.0 | Seconds between heartbeat pings |
| `SB_HEARTBEAT_TIMEOUT` | 6.0 | Seconds before declaring phone dead |

---

## Architecture

```
┌──────────────────────────────────────────────────────┐
│                    VPS SERVER                         │
│                                                      │
│  ┌──────────┐  ┌─────────────┐  ┌────────────────┐  │
│  │  OAuth   │  │   MCP       │  │   WebSocket    │  │
│  │  Auth    │  │   Endpoint  │  │   Relay Hub    │  │
│  │          │  │  (tools)    │  │   (phones)     │  │
│  └──────────┘  └──────┬──────┘  └───────┬────────┘  │
│                       │                  │           │
│                  ┌────┴──────────────────┴────┐      │
│                  │     Session Registry       │      │
│                  │   user → phone session     │      │
│                  └───────────────────────────┘       │
│                           │                          │
│                  ┌────────┴────────┐                 │
│                  │  Dead Man's     │                 │
│                  │  Switch         │                 │
│                  └─────────────────┘                 │
└──────────────────────────────────────────────────────┘
         ▲                                    ▲
         │ HTTPS (MCP JSON-RPC)               │ WSS (commands)
         │                                    │
    Claude Desktop                     Phone Relay Client
                                              │
                                       Intiface Central
                                              │
                                         Bluetooth
                                              │
                                           Toys
```

---

## Sharing With Others

1. Deploy the server with registration open
2. Share the server URL with your community
3. They register, set up their phone relay, configure Claude
4. Once everyone's registered, set `SB_REGISTRATION_OPEN=false`

Each user's devices are isolated — nobody can control anyone else's hardware.

---

## Troubleshooting

**"No phone connected"** — Make sure the relay client is running and authenticated. Check server logs.

**Devices not showing up** — Open Intiface Central, scan for devices there first. Then restart the relay client.

**Lag or jitter** — Check your VPS location vs. your actual location. Closer = lower latency. Also check mobile signal strength.

**Phone disconnects frequently** — Keep the Intiface app in the foreground. iOS especially kills background connections. Android: disable battery optimization for Intiface and Termux.

**Rate limited** — Normal use won't hit limits. If you're developing/testing rapidly, temporarily increase `SB_RATE_LIMIT_COMMANDS`.

---

## License

Do whatever you want with this. Be safe, have fun, treat each other well.
