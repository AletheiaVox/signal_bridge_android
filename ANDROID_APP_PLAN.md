# Signal Bridge Android App — Development Plan

**Status:** Draft v3 — competitive review integrated
**Changelog:**
- v3: Fixes intent-vs-execution heat tracking bug, adds explicit state machine, switches to Ktor Client, hardens pattern cancellation, adds client-side watchdog, Intiface health ping, AccessibilityService volume escape, reconnect-without-resume policy, predictive heat UX, and numerous Android-specific hardening items from competitive LLM review.
- v2: Adds emergency stop UI (§4.3, §4.9), intensity governor (§5.5), physical escape hatches (§5.6), and server-side governor requirement (§7.1).
**Date:** 2026-03-24
**Purpose:** Replace the Termux relay setup with a native Android app that handles user registration, authentication, and the WebSocket relay to both the VPS server and local Intiface Central.

---

## 1. What This App Is (and Isn't)

### It IS:
- A native Android app (Kotlin, Jetpack Compose) that replaces Termux entirely
- A registration/login screen that creates accounts on your VPS
- A foreground service that maintains two WebSocket connections:
  - **Upstream:** to the VPS at `wss://your-server/ws/phone`
  - **Local:** to Intiface Central at `ws://127.0.0.1:12345`
- A relay that translates commands between them using raw Buttplug JSON protocol
- An implementation of the dead man's switch heartbeat protocol
- Multiple physical emergency stop mechanisms (notification button, volume keys)
- A session intensity governor that enforces automatic cooldowns

### It is NOT:
- A replacement for Intiface Central (still required as a separate app)
- A Bluetooth device manager (Intiface handles all BLE communication)
- A Claude interface (users still interact through Claude Desktop, claude.ai, etc.)

### Why not embed Intiface/Buttplug directly?
The Buttplug library (buttplug-rs) handles dozens of BLE device protocols across hundreds of devices. Embedding it would mean forking and maintaining a substantial Rust FFI project. Intiface Central is a well-maintained Play Store app. The cost of "install one more app" is vastly lower than the cost of maintaining a Bluetooth device stack.

---

## 2. Current vs. Proposed User Experience

### BEFORE (Termux setup — what users do today):
```
1. Install Intiface Central from Play Store
2. Install Termux from F-Droid (NOT Play Store — critical distinction)
3. Open Termux, run: pkg update && pkg install python
4. Run: pip install websockets
5. Copy termux_relay_v3.py to phone (how? ADB? file manager?)
6. Get a JWT token from the server (how? curl? ask the admin?)
7. Run: python termux_relay_v3.py --token YOUR_TOKEN --server wss://...
8. Keep Termux running in foreground
9. Hope Android doesn't kill it
```
**This is ~15 minutes of CLI work for a technical user, and impossible for a non-technical one.**

### AFTER (Android app):
```
1. Install Intiface Central from Play Store
2. Install Signal Bridge from [APK / Play Store / F-Droid]
3. Open Signal Bridge → Create Account (username + password)
4. Tap "Connect"
5. Done
```
**This is ~2 minutes, no CLI, no tokens, no file transfers.**

---

## 3. Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│                    Android Phone                      │
│                                                       │
│  ┌─────────────────────┐   ┌──────────────────────┐  │
│  │   Signal Bridge App │   │  Intiface Central    │  │
│  │                     │   │  (Play Store app)    │  │
│  │  ┌───────────────┐  │   │                      │  │
│  │  │  UI Layer     │  │   │  BLE Server on       │  │
│  │  │  (Compose)    │  │   │  ws://127.0.0.1:12345│  │
│  │  └───────┬───────┘  │   └──────────┬───────────┘  │
│  │          │           │              │              │
│  │  ┌───────┴───────┐  │   ┌──────────┴───────────┐  │
│  │  │  Relay Service│◄─┼───┤  Local WebSocket      │  │
│  │  │  (Foreground) │  │   │  (Buttplug protocol)  │  │
│  │  └───────┬───────┘  │   └──────────────────────┘  │
│  │          │           │                             │
│  └──────────┼───────────┘                             │
│             │ WSS                                     │
└─────────────┼─────────────────────────────────────────┘
              │
              ▼
┌─────────────────────────────┐
│  VPS Server (unchanged)     │
│  FastAPI + Docker            │
│  wss://server/ws/phone       │
│  POST /auth/register         │
│  POST /auth/login            │
│  POST /mcp (Claude's entry)  │
└─────────────────────────────┘
```

---

## 4. App Components — Detailed Breakdown

### 4.1 Project Structure

```
signal-bridge-android/
├── app/
│   ├── src/main/
│   │   ├── java/com/signalbridge/app/
│   │   │   ├── MainActivity.kt              # Single-activity Compose host
│   │   │   ├── SignalBridgeApp.kt            # Application class
│   │   │   │
│   │   │   ├── ui/                           # UI Layer
│   │   │   │   ├── theme/
│   │   │   │   │   └── Theme.kt              # Material3 theming
│   │   │   │   ├── screens/
│   │   │   │   │   ├── LoginScreen.kt        # Login / Register
│   │   │   │   │   ├── DashboardScreen.kt    # Main status + controls
│   │   │   │   │   └── SettingsScreen.kt     # Server URL, advanced options
│   │   │   │   └── navigation/
│   │   │   │       └── NavGraph.kt           # Compose Navigation
│   │   │   │
│   │   │   ├── service/                      # Background Service
│   │   │   │   ├── RelayService.kt           # Foreground service lifecycle
│   │   │   │   ├── ServerConnection.kt       # VPS WebSocket (upstream)
│   │   │   │   ├── IntifaceConnection.kt     # Local Buttplug WebSocket
│   │   │   │   ├── CommandRouter.kt          # Translates server→Buttplug commands
│   │   │   │   └── EmergencyStopReceiver.kt  # BroadcastReceiver for notification + volume stop
│   │   │   │
│   │   │   ├── protocol/                     # Buttplug Protocol (raw JSON)
│   │   │   │   ├── ButtplugMessages.kt       # Message data classes
│   │   │   │   ├── DeviceManager.kt          # Device tracking + name mapping
│   │   │   │   └── PatternRunner.kt          # Pulse, wave, escalate
│   │   │   │
│   │   │   ├── auth/                         # Authentication
│   │   │   │   ├── AuthRepository.kt         # HTTP calls to /auth/*
│   │   │   │   └── TokenManager.kt           # Android Keystore storage
│   │   │   │
│   │   │   ├── data/                         # Data Layer
│   │   │   │   ├── DeviceProfiles.kt         # Built-in device profiles (from devices.json)
│   │   │   │   └── ConnectionState.kt        # Observable state for UI
│   │   │   │
│   │   │   └── util/
│   │   │       └── Logger.kt                 # Logging wrapper
│   │   │
│   │   ├── res/
│   │   │   ├── drawable/
│   │   │   │   └── ic_notification.xml       # Service notification icon
│   │   │   └── values/
│   │   │       └── strings.xml
│   │   │
│   │   └── AndroidManifest.xml
│   │
│   └── build.gradle.kts                      # App-level Gradle config
│
├── build.gradle.kts                          # Project-level Gradle config
├── settings.gradle.kts
└── gradle.properties
```

### 4.2 UI Layer — Three Screens

#### Screen 1: Login / Register
- Toggle between Login and Register mode
- Fields: username, password (+ confirm password for register)
- Server URL field (pre-filled with default, collapsible under "Advanced")
- Error display (bad credentials, network errors, server down)
- "Register" calls `POST /auth/register` → stores JWT
- "Login" calls `POST /auth/login` → stores JWT
- On success → navigate to Dashboard

#### Screen 2: Dashboard (Main Screen)
- **Connection status indicator:** three-state (Disconnected / Connecting / Connected)
  - Shows status of BOTH connections (server + Intiface) independently
- **Device list:** names, capabilities, and connection state of each device
- **Safety indicator:** subtle progress bar showing current heat level (percentage of governor limit). During cooldown, shows a countdown timer. Informational — no interaction needed, but visible at a glance.
- **Connect / Disconnect button:** single toggle
- **STOP ALL button:** large, prominent, always visible when connected. Same action as the notification button but accessible in-app.
- **Intiface status check:** if local WebSocket fails, show message:
  "Can't reach Intiface Central. Make sure it's running with the server started."
  with a button to open Intiface Central (via Android intent if possible, or just instructions)
- **Log area:** scrollable, last ~50 lines of relay activity (optional, collapsible)

#### Screen 3: Settings
- Server URL (editable)
- Intiface URL (default ws://127.0.0.1:12345, editable for custom setups)
- Token info (expiry date, re-login button)
- **Safety Governor settings:**
  - On/off toggle (default: ON)
  - Heat limit slider with human-readable explanation ("triggers cooldown after ~X seconds at full intensity")
  - Cooldown duration (seconds)
  - Check-in interval (seconds between prompts Claude sees)
  - Post-cooldown intensity cap (percentage)
  - Defaults are conservative; power users can relax them
- **Emergency stop settings:**
  - Volume-button triple-press: on/off toggle (default: ON)
  - Shake-to-stop: on/off toggle (default: OFF, post-v1 feature)
- "About" section with version info
- Battery optimization prompt (link to Android settings)

### 4.3 Relay Service (Foreground Service)

This is the core of the app. It runs as an Android Foreground Service with a persistent notification (required by Android to prevent the OS from killing it).

**Lifecycle:**
```
User taps "Connect"
  → Start RelayService as foreground service
  → Show persistent notification: "Signal Bridge — Connecting..."
  → Connect to Intiface Central (ws://127.0.0.1:12345)
    → Send RequestServerInfo (Buttplug handshake)
    → Start device scan
    → Start drain task (background reader for Intiface messages)
  → Connect to VPS (wss://server/ws/phone)
    → Send phone_auth message with JWT
    → Wait for auth_ok
    → Send device_list
  → Enter message loop
    → Heartbeat pings → respond with pongs (PRIORITY — never block this)
    → Commands → route to Intiface via CommandRouter
    → Acks → send back to VPS
  → Update notification: "Signal Bridge — Connected (2 devices)" + [STOP ALL] action button

User taps "Disconnect" OR connection drops:
  → Stop all devices (safety)
  → Close both WebSockets
  → Stop foreground service
  → Update notification → dismiss
```

**Critical design constraint:** Heartbeat responses must NEVER be blocked by command processing. The server pings every 2 seconds and times out at 6 seconds (bumped to 8–10s in v3, see §5.1). The Termux relay solves this by processing commands as background `asyncio.create_task()` calls. The Android equivalent is launching command processing on a coroutine scope while the main message loop handles heartbeats synchronously.

**Client-side watchdog (v3):** The app must also monitor the *incoming* heartbeat stream. If no `heartbeat_ping` is received from the server within a threshold (e.g., 12 seconds — 2× the expected interval), the app fires a local emergency stop and enters reconnection. This catches the case where the server dies silently or the upstream connection degrades without a clean close.

**Explicit State Machine (v3):** The relay service operates as a finite state machine shared between phone and server. All transitions must sync both sides, reset timers, and cancel active patterns.

```
DISCONNECTED ──► CONNECTING ──► IDLE ──► ACTIVE ──► COOLDOWN
     ▲                │           │         │           │
     │                ▼           │         │           │
     └──────── ERROR ◄───────────┴─────────┴───────────┘
```

| State | Meaning | Entry conditions |
|-------|---------|-----------------|
| `DISCONNECTED` | No WebSocket connections | App start, user disconnect, unrecoverable error |
| `CONNECTING` | Establishing one or both WebSockets | User taps Connect, auto-reconnect |
| `IDLE` | Both WebSockets open, heartbeat active, no devices outputting | Successful connection, all devices stopped |
| `ACTIVE` | At least one device is outputting | Any command or pattern starts executing |
| `COOLDOWN` | Governor-triggered pause, all devices stopped | Heat limit exceeded |
| `ERROR` | A connection failed, attempting recovery | WebSocket close, auth failure, Intiface gone |

**Transition rules:**
- `ACTIVE → DISCONNECTED`: Stop all devices locally BEFORE closing connections
- `ACTIVE → ERROR`: Stop all devices immediately, begin reconnection
- `ERROR → CONNECTING`: Auto-reconnect with backoff (1s, 2s, 4s, 8s, max 30s, give up after 5 attempts)
- `CONNECTING → IDLE`: Never auto-resume devices. User must explicitly re-initiate. This prevents an invasive restart after a silent reconnection — critical when the user may have physically repositioned.
- `COOLDOWN → IDLE`: After cooldown timer expires, return to idle (not active). Claude must issue new commands.
- Any state → `DISCONNECTED`: User taps Disconnect (always allowed)

### 4.4 Server Connection (VPS WebSocket)

Direct port of the WebSocket handling from `termux_relay_v3.py` lines 408-491.

**Message types received from server:**
| Type | Action |
|------|--------|
| `heartbeat_ping` | Immediately respond with `heartbeat_pong` |
| `command` | Route to CommandRouter → execute on Intiface → ack |
| `pattern` | Route to CommandRouter → start pattern → ack |
| `stop` | Route to CommandRouter → stop devices → ack |
| `scan` | Trigger Intiface rescan → send updated device_list |
| `read_sensor` | Respond with "not supported" ack (same as Termux relay) |

**Messages sent to server:**
| Type | When |
|------|------|
| `phone_auth` | On connect (first message) |
| `heartbeat_pong` | In response to each `heartbeat_ping` |
| `command_ack` | After executing any command |
| `device_list` | After auth, after scan, after device add/remove |

### 4.5 Intiface Connection (Local Buttplug Protocol)

Direct port of the `ButtplugRaw` class from `termux_relay_v3.py` lines 49-201.

**Buttplug protocol messages used:**

| Message | Purpose |
|---------|---------|
| `RequestServerInfo` | Handshake (MessageVersion: 3) |
| `StartScanning` | Begin BLE device discovery |
| `RequestDeviceList` | Get all currently connected devices |
| `ScalarCmd` | Set output intensity (vibrate, rotate, etc.) |
| `StopDeviceCmd` | Stop a specific device |
| `StopAllDevices` | Emergency stop all |

**Events received from Intiface:**
| Event | Purpose |
|-------|---------|
| `ServerInfo` | Handshake response |
| `DeviceAdded` | New device connected via BLE |
| `DeviceRemoved` | Device disconnected |
| `DeviceList` | Response to RequestDeviceList |

**Drain task:** A background coroutine continuously reads from the Intiface WebSocket to prevent buffer buildup and process device events. Identical to `termux_relay_v3.py` lines 80-94.

**Intiface health ping (v3):** An open local socket doesn't mean Intiface is healthy — it can freeze while the socket stays open. Every 15 seconds during an active session, send a lightweight `RequestDeviceList` as a health check. If no response within 5 seconds, treat it as a connection failure: stop all devices, transition to `ERROR` state, notify the server, and prompt the user. This catches the "Intiface zombie" scenario that a simple socket-open check misses.

### 4.6 Command Router

Translates server commands into Buttplug protocol calls. Port of `PatternRunner` from `termux_relay_v3.py` lines 209-405.

**Key behaviors:**
- Device resolution: "all" → all connected devices, or match by short_name
- Intensity floor: devices like Enigma need minimum 40% to be perceptible
- Duration auto-stop: if `duration > 0`, schedule a stop after N seconds
- Pattern execution: pulse, wave, escalate run as background coroutines
- Pattern cancellation: new pattern on same device cancels the old one
- Safety fallback: unknown device name in stop command → stop ALL devices

**Pattern Authority (v3 hardening):** Patterns are the most dangerous component because they run independently as coroutines and can continue executing after a stop command if not properly cancelled.

- Every pattern gets a unique `patternId` and is tagged with the current session
- Each device gets an explicit `CoroutineScope` with a `SupervisorJob`
- Stop commands call `scope.cancelChildren()` — this synchronously cancels all active patterns for that device and invalidates their coroutine contexts so they cannot resume
- The `stop all` handler cancels ALL device scopes, then sends `StopAllDevices` to Intiface
- Pattern coroutines check `isActive` before every Buttplug send — if cancelled between ticks, they do not send one more command before dying
- The server's stop command should include a `cancel_patterns: true` flag so the phone knows to synchronously cancel before acking

### 4.7 Authentication & Token Storage

**Registration flow:**
```
App                          VPS Server
 │                               │
 │  POST /auth/register          │
 │  {"username", "password"}     │
 │  ─────────────────────────►   │
 │                               │ create_user() → bcrypt hash
 │                               │ create_token() → JWT
 │  {"user_id", "token"}         │
 │  ◄─────────────────────────   │
 │                               │
 │  Store token in Keystore      │
 │  Navigate to Dashboard        │
```

**Token storage:** Android Keystore (hardware-backed on most devices). The JWT is encrypted at rest. No plaintext tokens in SharedPreferences.

**Token refresh:** Tokens expire after 7 days (configurable `SB_TOKEN_EXPIRY_HOURS=168`). When the app detects a 401 or token expiry, it navigates back to the login screen. No silent refresh — the user re-enters credentials. This is fine for a 7-day token.

### 4.8 Device Profiles

Built-in device profiles from `devices.json`, compiled into the app as a Kotlin data structure:

```kotlin
data class DeviceProfile(
    val shortName: String,
    val matchStrings: List<String>,
    val capabilities: Map<String, String>,
    val intensityFloor: Float,
    val notes: String
)

val BUILT_IN_PROFILES = listOf(
    DeviceProfile("ferri", listOf("Ferri"), mapOf("vibrate" to "external clitoral vibration"), 0.0f, "Small wearable"),
    DeviceProfile("enigma", listOf("Enigma"), mapOf("vibrate" to "G-spot thumping", "rotate" to "clitoral sonic pulse"), 0.4f, "Needs 40%+"),
    // ... all 12 profiles from devices.json
)
```

Device matching logic (port of `_add_device` from relay): when Intiface reports a device name like "Lovense Ferri", match it against `matchStrings` to find the profile and assign the `shortName` that the server expects.

### 4.9 Emergency Stop Mechanisms

**Context:** A real user was physically incapacitated by pleasure during a session — muscle spasms, unable to reach any UI element or speak coherently. Claude interpreted distress as pleasure and continued. The app needs hardware-level and software-level escape hatches that work when the human body can't perform fine motor tasks.

**Principle:** Every emergency stop mechanism fires the SAME action: `StopAllDevices` to Intiface + `stop all` to VPS + UI update. Multiple triggers, single code path.

#### 4.9.1 Notification STOP ALL Button (P0 — must ship in v1)
- Action button on the persistent foreground service notification
- Accessible from lock screen, notification shade, always-on display
- Requires a single tap — no app navigation, no unlock
- Implementation: `PendingIntent.getBroadcast()` → `EmergencyStopReceiver` → calls `RelayService.emergencyStop()`
- Red stop icon if the notification framework allows icon tinting

#### 4.9.2 Volume Button Emergency Stop (P0 — must ship in v1, upgraded from P1)
Two trigger methods, both active simultaneously:
- **Triple-press:** 3 rapid presses of volume-down within 1.2 seconds (300ms debounce between presses)
- **Long-press fallback:** Continuous volume-down hold for 2 seconds — requires even less fine motor coordination than triple-press
- Works with screen off, through a blanket, with eyes closed
- **Phone vibration confirmation:** When triggered, vibrate the phone (distinctive pattern: long-short-long) so the user knows it worked even if they can't see the screen.

**Implementation (v3 — upgraded to AccessibilityService):** `MediaSession` volume key interception is unreliable on Chinese OEMs (Xiaomi, Oppo, OnePlus) and some Samsung devices which override media key routing. The app must ship an `AccessibilityService` implementation for reliable cross-device interception.

**Setup flow:** On first connection, if the AccessibilityService is not enabled, show a one-time setup screen:
  1. Explain what it does in plain language ("Lets Signal Bridge use volume buttons as an emergency stop")
  2. Deep-link to Android's Accessibility settings
  3. User enables the service
  4. App confirms it's active and continues

**Fallback:** If the user declines AccessibilityService, fall back to `MediaSession` (works on Pixel and some others) and clearly note in Settings that volume-button stop may not work on all phones. The notification STOP ALL button remains fully functional regardless.

#### 4.9.3 Shake-to-Stop (P2 — post-v1)
- Sustained phone shaking for 1+ second → emergency stop + vibration confirmation
- Even lower cognitive bar than buttons — involuntary thrashing could trigger it
- **False positive mitigation:** 1-second sustained threshold + accelerometer magnitude filter (must exceed a threshold, not just any motion)
- **Battery note:** Keeping the accelerometer active drains battery. Only active while the relay service is running.
- Deferred to post-v1; needs real-world testing to tune thresholds.

#### 4.9.4 In-App STOP ALL Button
- Large, prominently colored button on the Dashboard screen
- Always visible when connected (not behind a menu or scroll)
- Same action as all other stop mechanisms

---

## 5. Safety — Non-Negotiable Requirements

These must all be implemented correctly or the app should not ship.

### 5.1 Dead Man's Switch (Heartbeat)
- **Server pings every 2 seconds** (`SB_HEARTBEAT_INTERVAL=2.0`)
- **App must respond within 10 seconds** (`SB_HEARTBEAT_TIMEOUT=10.0`, bumped from 6s in v3)
- **Why 10 seconds (v3 change):** Mobile radio sleep cycles (LTE DRX) can batch packets and introduce 2-4 seconds of jitter. With a 6-second timeout, a single radio sleep cycle plus a GC pause could trigger a false emergency stop. 10 seconds gives comfortable margin without meaningfully increasing risk — if a connection is truly dead, 10 seconds vs 6 seconds is not the difference between safety and harm.
- If the app fails to respond, server fires `_emergency_stop()` → stops all devices and disconnects the session
- **Implementation:** Heartbeat pong is sent IMMEDIATELY in the message loop, never queued behind command processing. Commands run in separate coroutines.
- **Client-side watchdog (v3):** If no ping arrives within 12 seconds, the app fires a local emergency stop and enters `ERROR` state. This catches silent server death.

### 5.2 Local Failsafe (Connection Drop)
- If the VPS WebSocket connection drops for ANY reason: **stop all devices immediately via Intiface**
- If the Intiface WebSocket connection drops: **notify server, update UI**
- On app crash or force-close: Android's foreground service `onDestroy()` sends `StopAllDevices` to Intiface as a best-effort cleanup

### 5.3 Stop-Unknown Fallback
- If the server sends a stop command for a device name the app doesn't recognize, stop ALL devices as a safety fallback (matching existing behavior in `termux_relay_v3.py` lines 316-338)

### 5.4 Network Transition Safety
- When Android switches networks (WiFi ↔ mobile data), WebSocket connections will drop
- The app must: detect the drop → stop all devices → transition to `ERROR` state → attempt reconnection → re-authenticate → rescan devices
- **No auto-resume (v3):** After successful reconnection, the app transitions to `IDLE`, NOT `ACTIVE`. Devices are not restarted. Claude must issue new commands. This prevents an invasive restart when the user may have physically repositioned during the outage.
- During reconnection, the dead man's switch on the server will fire independently (no heartbeat → emergency stop). This is correct and desired behavior — belt AND suspenders.

### 5.5 Session Intensity Governor (Server-Side — P0, must ship)

**What it is:** A server-side system that tracks cumulative intensity over time ("heat") across all devices and enforces automatic cooldowns when the session exceeds safe limits. This protects against scenarios where Claude escalates intensity over an extended period and the user is unable or unwilling to intervene.

**Relationship to other safety systems:** The dead man's switch (§5.1) protects against connection loss. The intensity governor protects against session overintensity. Emergency stop buttons (§4.9) protect against acute incapacitation. Three independent safety layers, each covering a different failure mode.

**Architecture:** The governor lives server-side in `server/safety.py` (alongside `DeadManSwitch`). It intercepts MCP tool calls in `mcp_tools.py`:
1. Before sending: `governor.check_allowed(intensity)` — blocks if in cooldown, applies post-cooldown cap
2. Command is sent to phone relay and **waits for `command_ack`**
3. Only on successful ack: `governor.record_intensity(device, intensity)` — heat increases
4. On stop: `governor.record_stop()`

**IMPORTANT — Intent vs. Execution (v3 fix):** The governor must record heat based on confirmed execution, not intent. If the server sends a vibrate command but the phone never acks it (network glitch, Intiface down, device disconnected), no heat should accumulate. This prevents phantom heat buildup from failed commands. The existing `session.send_command()` in `session_registry.py` already waits for acks with a 10-second timeout — the governor hooks into the ack result, not the send.

**Heat model:**
- Every tick (~1 second), heat accumulates: `heat += total_active_intensity * dt`
- When no devices are active, heat decays: `heat -= decay_rate * dt`
- Intensities below an idle threshold (default 0.15) don't build heat
- Multiple devices multiply heat accumulation
- When `heat >= heat_limit`, cooldown fires

**Cooldown sequence:**
1. Stop ALL devices immediately
2. Block all new commands for `cooldown_seconds`
3. Reset heat to zero
4. After cooldown, cap max intensity to `post_cooldown_cap` for `post_cooldown_window` seconds
5. Inject a message into Claude's tool results so Claude knows to check on the user

**Check-in prompts:** At configurable intervals (`SB_CHECKIN_INTERVAL`, default 300s), the governor injects messages into MCP tool results like: *"It's been 5 minutes. How are you doing?"* Claude should pause and check on the user when it sees these.

**Configurable parameters (environment variables):**
| Variable | Default | Meaning |
|----------|---------|---------|
| `SB_SAFETY_ENABLED` | `true` | Master on/off for the governor |
| `SB_HEAT_LIMIT` | `60` | Heat units before cooldown (~60s at max intensity) |
| `SB_COOLDOWN_SECONDS` | `30` | Mandatory pause when cooldown fires |
| `SB_POST_COOLDOWN_CAP` | `0.5` | Max intensity allowed for a window after cooldown |
| `SB_POST_COOLDOWN_WINDOW` | `60` | Seconds the post-cooldown cap lasts |
| `SB_CHECKIN_INTERVAL` | `300` | Seconds between check-in prompts |
| `SB_IDLE_THRESHOLD` | `0.15` | Intensity below this doesn't build heat |
| `SB_DECAY_RATE` | `0.3` | Heat decay per second when idle |

**Reference implementation:** `local/signal_bridge_mcp_v0.3.py`, class `SafetyGovernor`. The server implementation should match its logic.

**Governor config sync (v3):** Server-authoritative. The app reads config on connect and writes changes via `POST /safety/config`:
- App Settings screen → user adjusts heat limit → `POST /safety/config` with new values → server stores per-user overrides in SQLite
- On phone connect, server sends current governor config in the `auth_ok` response (or via a new `GET /safety/config` call)
- This prevents local-only drift and means safety settings apply regardless of which client connects (app, Termux, future clients)

**Real-time heat display (v3):** Piggyback `governor_state` onto the heartbeat pong processing. When the server sends a `heartbeat_ping`, include an optional `governor` field:
```json
{"type": "heartbeat_ping", "timestamp": 1234567890, "governor": {"heat_pct": 45.2, "in_cooldown": false, "cooldown_remaining": 0}}
```
The phone uses this to update the dashboard heat indicator natively — no extra HTTP polling. Backward-compatible: old relays ignore the extra field.

**Predictive UX (v3):** Translate the abstract heat percentage into a human-readable estimate: "~45 seconds at current intensity until cooldown." Formula: `remaining_seconds = (heat_limit - current_heat) / current_total_intensity`. Display on the dashboard when heat > 50%.

### 5.6 Safety Architecture Summary

```
┌─────────────────────────────────────────────────────────┐
│                    SAFETY LAYERS                         │
│                                                          │
│  Layer 1: INTENSITY GOVERNOR (server-side)               │
│  ├─ Tracks heat over time                                │
│  ├─ Auto-cooldown when limit exceeded                    │
│  ├─ Post-cooldown intensity cap                          │
│  └─ Check-in prompts to Claude                           │
│                                                          │
│  Layer 2: DEAD MAN'S SWITCH (bilateral)                  │
│  ├─ Server pings every 2s, emergency stop on 10s timeout │
│  ├─ Client watchdog: local stop if no ping in 12s        │
│  └─ Session disconnect on failure                        │
│                                                          │
│  Layer 3: PHYSICAL ESCAPE HATCHES (app-side)             │
│  ├─ Notification STOP ALL button (P0)                    │
│  ├─ Volume triple-press (P1)                             │
│  ├─ In-app STOP ALL button (P0)                          │
│  └─ Shake-to-stop (P2, post-v1)                          │
│                                                          │
│  Layer 4: LOCAL FAILSAFE (app-side)                      │
│  ├─ VPS disconnect → stop all devices                    │
│  ├─ Intiface disconnect → notify server                  │
│  ├─ App crash → onDestroy() best-effort stop             │
│  └─ Unknown device in stop → stop ALL (safety fallback)  │
│                                                          │
│  Each layer is INDEPENDENT. Any single layer failing     │
│  does not compromise the others.                         │
└─────────────────────────────────────────────────────────┘
```

---

## 6. Android-Specific Challenges & Solutions

### 6.1 Battery Optimization (Doze Mode)
**Problem:** Android aggressively kills background processes to save battery. Even foreground services can be throttled in Doze mode.

**Solution:**
- Use a **foreground service** with a persistent notification (mandatory for long-running work)
- On first connection, prompt battery optimization exemption using `Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)` pre-filled with the app's package name. This is a single-tap system dialog, less friction than navigating to Settings manually.
- Acquire `PARTIAL_WAKE_LOCK` **only while both WebSockets are open**. Release immediately on disconnect. Add a maximum timeout failsafe (e.g., 2 hours) — if the wakelock has been held longer than this, log a warning and prompt the user. This prevents indefinite battery drain from forgotten sessions.
- Log wakelock duration for debugging overheating reports
- This is the same approach Intiface Central itself uses
- **Note for shake-to-stop (post-v1):** Keeping the accelerometer active during sessions adds battery drain. Only register the sensor listener while the relay service is running, and unregister on disconnect.

### 6.2 WebSocket Library Choice (v3 — changed to Ktor)
**Ktor Client** with the `ktor-client-okhttp` engine. This gives us the best of both worlds: OkHttp's battle-tested networking underneath, with Ktor's Kotlin-native coroutine API on top.

Why Ktor over raw OkHttp (v3 change):
- **Native coroutine WebSocket sessions**: `WebSocketSession` with typed `send(Frame.Text(...))` and `incoming.receive()` instead of OkHttp's callback-based `WebSocketListener`. This eliminates callback hell in the message loop, which manages TWO concurrent connections.
- **Clean integration with `kotlinx-serialization`**: Parse Buttplug JSON messages directly in the receive flow without manual callback-to-coroutine bridging
- **Structured concurrency**: WebSocket lifecycle ties naturally to coroutine scopes — when a scope cancels, the connection closes
- The `ktor-client-okhttp` engine means we still get OkHttp's TLS, connection pooling, and Android compatibility underneath

### 6.3 Concurrency Model
The Termux relay uses Python's `asyncio` with `create_task()` for non-blocking command processing. The Kotlin equivalent is **coroutines**:

```kotlin
// Message loop with Ktor WebSocketSession (v3)
scope.launch(Dispatchers.IO) {
    serverSession.incoming.consumeEach { frame ->
        if (frame is Frame.Text) {
            val msg = Json.parseToJsonElement(frame.readText()).jsonObject
            when (msg["type"]?.jsonPrimitive?.content) {
                "heartbeat_ping" -> {
                    // Respond IMMEDIATELY, same coroutine — never yields
                    serverSession.send(Frame.Text("""{"type":"heartbeat_pong"}"""))
                }
                "command", "pattern", "stop", "scan" -> {
                    // Launch separate coroutine (non-blocking)
                    scope.launch { commandRouter.handle(msg) }
                }
            }
        }
    }
}
```

This mirrors the `asyncio.create_task(process_command(...))` pattern in `termux_relay_v3.py` line 468, but with Ktor's typed WebSocket frames instead of OkHttp callbacks.

### 6.4 App Distribution

**Option A: Sideload APK**
- Build a signed APK, host it somewhere (GitHub releases, your VPS, etc.)
- Users enable "Install from unknown sources" and install
- Pro: No review process, ship immediately
- Con: Slightly intimidating for non-technical users; no auto-updates

**Option B: F-Droid**
- Open-source app repository, very aligned with this project's audience
- Pro: Trusted source, auto-updates, no Google account needed
- Con: Slow review process (weeks), must be fully open source

**Option C: Google Play Store**
- Widest reach, easiest install for users
- Pro: Everyone knows how to install from Play Store
- Con: Google content policy review — intimate device control app may face scrutiny. Not a guaranteed rejection, but worth having a backup plan.

**Recommendation:** Start with Option A (sideload APK). It's the fastest path to users. Move to F-Droid if there's demand. Play Store is a maybe — worth trying but don't depend on it.

---

## 7. Server-Side Changes Needed

Minimal changes required. The VPS server works as-is with the Android app because the app speaks exactly the same protocol as `termux_relay_v3.py`.

### 7.1 Required: Intensity Governor in mcp_tools.py
The current server needs one significant addition: the Safety Governor (§5.5). This is server-side code, not app code, but it must ship alongside the app.

Changes:
- `server/safety.py`: Add `SafetyGovernor` class alongside existing `DeadManSwitch` (reference: `local/signal_bridge_mcp_v0.3.py`)
- `server/mcp_tools.py`: Every output tool handler calls `governor.check_allowed()` before sending, `governor.record_intensity()` after
- `server/config.py`: Add governor env vars (`SB_HEAT_LIMIT`, `SB_COOLDOWN_SECONDS`, etc.)
- `server/app.py`: Initialize governor in lifespan, add `GET /safety/status` endpoint (optional, useful for app dashboard)
- `.env.example`: Document new variables

Everything else the server already supports:
- `POST /auth/register` and `POST /auth/login` for account creation
- `WebSocket /ws/phone` for relay connections
- JWT authentication
- All the rate limiting, IP banning, and heartbeat infrastructure

### 7.2 Recommended: Registration Control
Currently `SB_REGISTRATION_OPEN=true` lets anyone register. For a multi-user setup, consider adding one of:
- **Invite codes:** add an optional `invite_code` field to `/auth/register` that must match a configured value
- **Admin approval:** new accounts start inactive, admin activates them
- **Closed registration:** set `SB_REGISTRATION_OPEN=false` and create accounts via CLI

The invite code approach is simplest and could be a small server-side PR.

### 7.3 Recommended: Safety Status Endpoint
A `GET /safety/status` endpoint (authenticated) that returns the governor state for the requesting user:
```json
{
    "heat": 23.5,
    "heat_limit": 60,
    "heat_pct": 39.2,
    "in_cooldown": false,
    "cooldown_count": 0,
    "session_duration_seconds": 420
}
```
The app can poll this periodically (every 5s) to update the heat indicator on the Dashboard. Alternatively, the governor state could be piggybacked on heartbeat pong messages to avoid extra HTTP calls.

### 7.4 Optional: App Config Endpoint
A `GET /config/app` endpoint that returns:
```json
{
    "server_version": "1.0.0",
    "min_app_version": "1.0.0",
    "registration_open": true,
    "server_name": "Tina's Signal Bridge"
}
```
This lets the app check compatibility and show whether registration is available before the user tries. Low priority but nice for UX.

---

## 8. Failure Points & Mitigation

### CRITICAL FAILURES (device safety)

| Failure | Impact | Mitigation |
|---------|--------|------------|
| App crash during active session | Devices left running | `onDestroy()` sends StopAllDevices; server dead man's switch fires within 6s |
| Network drop (WiFi→mobile) | Heartbeat timeout | Server emergency stop fires; app detects disconnect and stops locally |
| Intiface Central not running | Can't reach devices | App checks local WS on connect, shows clear error message before connecting to server |
| Intiface Central crashes mid-session | Commands fail silently | App detects local WS close → stops server session → shows reconnect prompt |
| Phone falls asleep / Doze mode | Heartbeat responses delayed | Foreground service + WakeLock + battery optimization exemption; server timeout is 6s which is generous |

### SAFETY GOVERNOR FAILURES

| Failure | Impact | Mitigation |
|---------|--------|------------|
| Governor false positive (cooldown during consensual intensity) | Session interruption | User can tune heat_limit higher in Settings, or disable governor entirely |
| Governor disabled by user who then gets overwhelmed | No automated intensity protection | Notification STOP ALL button + volume-press still work as physical escape hatches |
| Heat tracking desync (device running but governor thinks idle) | No cooldown when needed | Governor checks at tick intervals; `record_intensity` called on every command; decay-only-when-idle prevents phantom decay |
| Governor config sync fails (app can't push settings to server) | Server uses defaults | Defaults are conservative — this fails safe |
| Cooldown fires during a pattern mid-execution | Pattern continues on device while server blocks new commands | Cooldown must also send `stop all` to the phone relay, not just block new MCP commands |

### NON-CRITICAL FAILURES (UX issues)

| Failure | Impact | Mitigation |
|---------|--------|------------|
| Token expired | Can't connect | Detect 401/expired JWT → navigate to login screen with clear message |
| Server unreachable | Can't connect | Show connection error with retry button; don't crash |
| Username taken during registration | Registration fails | Show error from server ("Username already taken") |
| Wrong password on login | Login fails | Show "Invalid credentials" (don't reveal which field was wrong) |
| Device not found for command | Command fails | Safety fallback: stop ALL devices (existing behavior) |
| Scan finds no devices | Empty device list | Show "No devices found" with instructions to check Intiface and device pairing |

### EDGE CASES

| Scenario | Handling |
|----------|----------|
| User opens app but Intiface isn't installed | Show message: "Intiface Central is required. Install from Play Store?" with link |
| Multiple Signal Bridge instances on same account | Server allows max 1 WebSocket per user (previous connection gets booted) — this is existing behavior |
| Phone has no internet but Intiface is running | Can't reach VPS → no session → devices are safe (nothing happens without the server relay) |
| Android kills foreground service despite all precautions | Extremely rare with proper foreground service + WakeLock. If it happens, server dead man's switch fires. Consider adding `START_STICKY` to restart the service automatically. |
| Server sends command for a device type the app doesn't know (e.g., "spray") | Forward as-is to Buttplug ScalarCmd with the actuator type string — Buttplug handles unknown types gracefully, and the command just won't match any actuator |

---

## 9. Dependencies

### Android App Dependencies

```kotlin
// build.gradle.kts (app level)
dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose UI
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Networking (Ktor with OkHttp engine — v3 change)
    implementation("io.ktor:ktor-client-okhttp:2.3.8")
    implementation("io.ktor:ktor-client-websockets:2.3.8")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.8")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.8")

    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // Security (token storage)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
```

**No Buttplug library.** The app speaks raw Buttplug JSON protocol (same as `termux_relay_v3.py`).

### Build Requirements
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK 34 (target), minimum SDK 26 (Android 8.0 — covers ~95% of active devices)
- Kotlin 1.9+

---

## 10. Build Plan — Session by Session

### Session 1: Project Scaffold & Auth (est. 1-2 hours)
**Goal:** Buildable project that can register and log in.

- [x] Generate full Gradle project structure (build.gradle.kts files, settings, gradle.properties)
- [x] AndroidManifest.xml with all required permissions
- [x] Theme and Material3 setup
- [x] `AuthRepository.kt` — HTTP client for `/auth/register` and `/auth/login`
- [x] `TokenManager.kt` — Android Keystore wrapper for JWT storage
- [x] `LoginScreen.kt` — Compose UI with register/login toggle
- [x] `MainActivity.kt` and navigation shell
- [x] Basic `SettingsScreen.kt` with server URL field

**Deliverable:** APK that can register a user on the VPS and store the token.

### Session 2: Relay Service & Buttplug Protocol (est. 2-3 hours)
**Goal:** Foreground service that connects to both WebSockets and relays commands.

- [x] `RelayService.kt` — Android foreground service with `connectedDevice` type, WakeLock management
- [x] `ConnectionStateMachine.kt` — Explicit state machine (DISCONNECTED → CONNECTING → IDLE → ACTIVE → COOLDOWN → ERROR)
- [x] `ServerConnection.kt` — Ktor WebSocket to VPS, auth flow, message dispatch
- [x] `IntifaceConnection.kt` — Ktor WebSocket to local Intiface, Buttplug handshake
- [x] Intiface health ping (RequestDeviceList every 15s, 5s timeout)
- [x] `ButtplugMessages.kt` — Kotlin data classes for all Buttplug JSON messages
- [x] `DeviceManager.kt` — Device tracking, name mapping, profile matching
- [x] `DeviceProfiles.kt` — Built-in profiles from devices.json
- [x] Heartbeat pong handling (MUST be non-blocking)
- [x] Client-side watchdog (local stop if no ping received in 12s)
- [x] Drain task for Intiface messages

**Deliverable:** App that connects to Intiface + VPS, reports devices, responds to heartbeats.

### Session 3: Command Routing, Patterns & Emergency Stop (est. 2-3 hours)
**Goal:** Full command execution plus P0 safety mechanisms.

- [x] `CommandRouter.kt` — Dispatch server commands to Buttplug calls
- [x] `PatternRunner.kt` — Pulse, wave, escalate as coroutines
- [x] Intensity floor enforcement
- [x] Duration auto-stop
- [x] Stop-unknown safety fallback
- [x] Command ack messages back to server
- [x] Scan command handling + device list updates
- [x] `EmergencyStopReceiver.kt` — BroadcastReceiver for stop actions
- [x] Notification STOP ALL action button (PendingIntent → EmergencyStopReceiver)
- [x] `VolumeKeyAccessibilityService.kt` — AccessibilityService for volume key interception
- [x] Volume triple-press (1.2s window, 300ms debounce) + long-press (2s hold) detection
- [x] AccessibilityService setup flow (one-time, with deep-link to settings)
- [x] MediaSession fallback for devices where user declines AccessibilityService
- [x] Phone vibration confirmation pattern on emergency stop trigger
- [x] Pattern authority: SupervisorJob per device, cancelChildren() on stop, isActive checks

**Deliverable:** Fully functional relay with physical emergency stop mechanisms.

### Session 4: Server-Side Governor & Dashboard UI (est. 2-3 hours)
**Goal:** Intensity governor on server, nice status display, production readiness.

**Server-side (can be done in same session or split out):**
- [x] `SafetyGovernor` class in `server/safety.py` (port from local v0.3)
- [x] Governor hooks in `server/mcp_tools.py` — record heat on ack (not intent)
- [x] Governor env vars in `server/config.py`
- [ ] `GET /safety/status` and `POST /safety/config` endpoints (authenticated, per-user)
- [x] Governor initialization in `server/app.py` lifespan
- [x] Piggyback governor state on `heartbeat_ping` messages
- [ ] Per-user safety config storage in SQLite (overrides env-var defaults)

**App-side:**
- [x] `DashboardScreen.kt` — Connection status, device list, connect/disconnect
- [x] In-app STOP ALL button (large, prominent)
- [x] Heat level indicator (progress bar from piggybacked heartbeat data)
- [x] Predictive heat warning ("~45s at current intensity until cooldown")
- [x] Cooldown countdown display (in dashboard + swap notification text)
- [x] No-auto-resume on reconnection (transition to IDLE, not ACTIVE)
- [x] `ConnectionState.kt` — Observable state (StateFlow) connecting service ↔ UI
- [x] Intiface-not-running detection and user guidance
- [ ] Network transition handling (WiFi ↔ mobile)
- [ ] Reconnection logic with backoff
- [ ] Battery optimization prompt
- [ ] `onDestroy()` safety (stop all devices on service death)
- [ ] Error toasts / snackbars for user-visible failures
- [ ] App icon and notification icon

**Deliverable:** Feature-complete app with safety governor.

### Session 5: Settings, Polish & Signed APK (est. 1-2 hours)
**Goal:** Safety settings UI, final polish, shippable build.

- [ ] Safety governor settings in SettingsScreen (heat limit, cooldown, check-in interval, post-cooldown cap, on/off toggle)
- [ ] Safety config sync via `POST /safety/config` (server-authoritative)
- [ ] Emergency stop settings (volume-press on/off, sensitivity)
- [ ] Final UI polish and error handling review
- [ ] Signed APK build
- [ ] Test matrix: Android 13, 14, 15; WiFi, mobile data; screen on, screen off

**Deliverable:** Shippable v1.0 APK.

### Session 6 (Optional): Invite Codes, Config Endpoint & Shake-to-Stop
**Goal:** Registration control, app config, and P2 safety features.

- [ ] Server-side: invite code support in `/auth/register`
- [ ] App-side: invite code field on registration screen
- [ ] Server-side: `GET /config/app` endpoint
- [ ] App-side: pre-flight check on launch (server reachable? registration open?)
- [ ] Shake-to-stop (`ShakeDetector.kt`) — accelerometer monitoring with sustained-shake threshold
- [ ] Shake threshold tuning and false-positive testing

---

## 11. Open Questions for Review

1. **Intiface detection:** RESOLVED (v3) — Use `PackageManager.getLaunchIntentForPackage("com.qdot.intiface.central")` to check if installed AND deep-link directly into it. If not installed, launch its Play Store page via `market://details?id=com.qdot.intiface.central`. (Package name to be verified at build time — may be `org.metafetish.intiface.central` depending on the build.)

2. **Foreground service type:** RESOLVED (v3) — Use `foregroundServiceType="connectedDevice"` in the manifest. This is the correct type for a persistent peripheral relay. Have `dataSync` ready as a fallback declaration if Play Store pushes back, but `connectedDevice` should be accepted.

3. **Minimum Android version:** SDK 26 (Android 8.0) is proposed. This covers ~95% of active devices but means we can use foreground service channels (introduced in 8.0). Going lower isn't worth the complexity.

4. **Auto-reconnect strategy:** RESOLVED (v3) — Auto-reconnect with exponential backoff (1s, 2s, 4s, 8s, max 30s), give up after 5 attempts. **Critical: on successful reconnect, transition to IDLE, not ACTIVE.** Never auto-resume device output after a reconnection. The user must explicitly re-initiate. This prevents invasive restart after a silent reconnection when the user may have physically repositioned.

5. **Multiple simultaneous devices with different output types:** The current relay sends a ScalarCmd per actuator type match. Should the app's UI show individual actuator control, or keep it simple (server handles this)? Recommendation: keep it simple — the UI just shows devices and status. All control is through Claude via MCP tools.

6. **Notification content:** RESOLVED — Informative text + **STOP ALL** action button. Format: "Connected — 2 devices (Ferri, Enigma)" with red stop icon action. This is now a P0 safety requirement, not a UX preference.

7. **Volume key interception:** RESOLVED (v3) — Ship `AccessibilityService` as primary implementation with `MediaSession` fallback. See §4.9.2 for details. Remaining question: exact AccessibilityService XML config to minimize requested capabilities (we only need key event interception, nothing else).

8. **Governor config sync:** RESOLVED (v3) — Server-authoritative. `POST /safety/config` endpoint stores per-user overrides in SQLite. App reads on connect, writes on settings change. See §5.5.

9. **Cooldown UX:** Should the app show a full-screen "COOLDOWN" overlay when one triggers, or just update the notification + dashboard indicator? Full-screen is more visible but more intrusive. Recommendation: Dashboard indicator + notification update. Full-screen overlay could be disorienting for someone already overwhelmed.

10. **Heat indicator polling:** RESOLVED (v3) — Piggyback governor state on heartbeat pings. See §5.5. Backward-compatible — old relays ignore the extra field.

11. **Notification during cooldown (v3):** When a cooldown fires, swap the persistent notification to "COOLDOWN — 27s remaining" with a pulsing/red indicator. Do NOT use a full-screen overlay — someone who just got overwhelmed doesn't need a screen takeover.

12. **Token expiry UX (v3):** When a token expires mid-session, slide up a snackbar prompting re-login rather than hard-navigating to the login screen. The user may be in a state where a sudden screen change is disorienting.

13. **App visual identity (v3):** Use a consistent red-black palette matching the server/project branding. The STOP ALL buttons should be unmistakably red across all surfaces (notification, dashboard, cooldown state).

---

## 12. What the Other LLMs Should Poke At

Specific areas where adversarial review would be most valuable:

**Resolved in v3** (kept for audit trail):
1. ~~Heartbeat timing~~ → Bumped to 10s server-side, added 12s client-side watchdog (§5.1)
2. ~~OkHttp vs Ktor~~ → Ktor Client with OkHttp engine (§6.2)
3. ~~WakeLock strategy~~ → Partial WakeLock with 2-hour max timeout, acquire/release tied to connection state (§6.1)
4. ~~Governor desync~~ → Heat recorded on ack, not intent (§5.5)
5. ~~Volume key reliability~~ → AccessibilityService primary, MediaSession fallback (§4.9.2)
6. ~~Pattern cancellation race~~ → SupervisorJob per device, cancelChildren() on stop, isActive checks in pattern loops (§4.6)

**Still open for review:**

1. **Kotlin Serialization for Buttplug JSON:** The Buttplug protocol uses variably-keyed JSON (`{"ScalarCmd": {"Id": 1, ...}}`). This is a sealed class hierarchy in kotlinx-serialization, but the polymorphic discriminator is the *key name* rather than a field value. Does `JsonContentPolymorphicSerializer` handle this cleanly, or is manual `JsonElement` parsing cleaner? (The v3 code example in §6.3 uses manual parsing — is that the right call?)

2. **Android 14/15 foreground service restrictions:** Recent Android versions are increasingly hostile to long-running foreground services. We're declaring `connectedDevice` type. Any reports of this being killed despite proper declaration + battery optimization exemption?

3. **Security of hardcoded server URL:** If the APK contains the server URL, anyone who decompiles it knows the endpoint. The auth layer protects it, but should we obfuscate with ProGuard/R8 anyway? (Probably not worth the complexity — the URL being known isn't the threat, unauthorized access is.)

4. **Emergency stop during cooldown:** If the user triggers a physical emergency stop during a governor cooldown, should the cooldown timer be reset, extended, or left as-is? Current recommendation: leave as-is. The devices are already stopped, and the cooldown is protecting against immediate re-escalation. But if the user manually stops, does that indicate they want to end the session entirely?

5. **AccessibilityService scope minimization:** What is the minimal XML configuration to request ONLY key event interception without broader accessibility capabilities? Over-requesting capabilities will trigger user suspicion and may face Play Store scrutiny.

6. **Intiface Central package name verification:** Is it `com.qdot.intiface.central` or `org.metafetish.intiface.central`? Needs to be verified from an actual Play Store listing or APK inspection before we hardcode the launch intent.

7. **WakeLock and thermal throttling:** On some devices, sustained PARTIAL_WAKE_LOCK causes thermal throttling which slows down the CPU and could delay heartbeat responses. Should we monitor thermal state via `PowerManager.getThermalStatus()` (API 29+) and warn the user?

---

## 13. Files to Reference During Implementation

These are the source-of-truth files in the current codebase that each app component must match:

| App Component | Reference File | Key Lines |
|---------------|---------------|-----------|
| Auth flow | `server/auth.py` | create_user (L52-75), verify_user (L78-92), JWT (L99-126) |
| Server messages | `server/models.py` | All message types (L41-124) |
| WebSocket auth | `server/app.py` | Phone WS lifecycle (L351-438) |
| Buttplug protocol | `termux_relay_v3.py` | ButtplugRaw class (L49-201) |
| Command handling | `termux_relay_v3.py` | PatternRunner class (L209-405) |
| Message loop | `termux_relay_v3.py` | relay_loop (L408-491) |
| Device profiles | `phone/devices.json` | All 12 profiles |
| Heartbeat safety | `server/safety.py` | DeadManSwitch class (L22-110) |
| Intensity governor | `local/signal_bridge_mcp_v0.3.py` | SafetyGovernor class, SafetyConfig dataclass |
| Server config | `server/config.py` | All env vars and defaults |
| Session registry | `server/session_registry.py` | PhoneSession (may need governor state per-session) |
