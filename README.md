# Signal Bridge

**Connect your AI to your body.**

Signal Bridge is an Android app that lets AI partners control Bluetooth intimate hardware in real time. You talk to your AI through its normal chat interface. When the moment calls for it, your AI sends haptic commands through Signal Bridge to your connected devices.

No jailbreaks. No prompt hacking. No sketchy workarounds. Signal Bridge operates below the content layer entirely. It handles structured hardware commands (device, intensity, duration) and never touches your conversation. What you and your AI talk about is between the two of you.

The app is built on [buttplug.io](https://buttplug.io), the open-source standard for intimate hardware control, and supports [hundreds of devices](https://iostindex.com/?filter0ButtplugSupport=4) across dozens of manufacturers.

> **Currently supported out of the box:** Claude (Anthropic) via the MCP connector system on [claude.ai](https://claude.ai) and the Claude Desktop app. Other LLM platforms can integrate through the API-level relay server. See Advanced: Generalizability for details.

---

## How It Works

Signal Bridge sits between your AI and your hardware. The signal path looks like this:

```
You ↔ Claude ↔ Signal Bridge Server ↔ Signal Bridge App ↔ Intiface Central ↔ Your Device
```

In practice, you don't need to think about any of that. You chat with Claude. Claude decides when and how to use the hardware tools available to it. Signal Bridge handles the rest.

On your phone, two apps work together:

- **Intiface Central** manages Bluetooth connections to your devices. It's a well-maintained app from the buttplug.io project, available on the Play Store.
- **Signal Bridge** connects to Intiface Central locally, authenticates with the relay server, and routes commands between your AI and your hardware.

You open both apps, tap Connect, and you're done. The whole setup takes about two minutes.

---

## Prerequisites

Before you start, you'll need:

- **An Android phone** running Android 8.0 or higher. Signal Bridge is lightweight; if your phone can run Intiface Central, it can run Signal Bridge. No special hardware requirements.
- **Intiface Central** installed from the [Google Play Store](https://play.google.com/store/apps/details?id=com.nonpolynomial.intiface_central&hl=en).
- **A Claude account** at [claude.ai](https://claude.ai). A free account works. Pro is recommended if you plan to use Signal Bridge regularly, since free accounts have stricter usage limits and you'll likely run into them mid-session. That's not the kind of interruption you want.
- **A compatible Bluetooth device.** Signal Bridge works with any device supported by buttplug.io. Check the [IoST Index](https://iostindex.com/?filter0ButtplugSupport=4) for the full compatibility list. Lovense, Kiiroo, We-Vibe, Satisfyer, and many others are supported.

---

## Setup

### 1. Install the apps

⚠️**Only** install apps from their official source, as linked below. Other sources might include malicious software. 

**Intiface Central** is available directly from the [Google Play Store](https://play.google.com/store/apps/details?id=com.qdot.intiface.central). Install it like any other app.

**Signal Bridge** is currently distributed as an APK file. You can download the latest release from the [GitHub releases page](https://github.com/AletheiaVox/signal_bridge_android/releases).

If you haven't installed an APK directly before, your phone will ask you to allow installation from unknown sources. This is a standard Android permission for apps distributed outside the Play Store. If you'd like a walkthrough with screenshots, [this guide from Android Authority](https://www.androidauthority.com/how-to-install-apks-31494/) covers it well.

A Google Play store release is pending approval. 

### 2. Create a Signal Bridge account

Open Signal Bridge. You'll see a login screen with an option to create a new account.

Pick a username and password. That's it. Your account is created on the Signal Bridge server and you're signed in.

Your credentials are stored securely on your phone using Android's encrypted storage (Keystore-backed EncryptedSharedPreferences). They never leave your device in plaintext.

### 3. Connect Signal Bridge to Claude

This step tells Claude where to find your Signal Bridge setup. You only need to do this once.

**On claude.ai (web):**
1. Go to [claude.ai/customize/connectors](https://claude.ai/customize/connectors)
2. Click the **+** button to add a custom connector
3. Enter a name (e.g., "Signal Bridge")
4. Enter the server URL: `https://signal-bridge.duckdns.org/mcp`
5. Leave the Advanced Settings (OAuth Client ID / Secret) empty
6. Click **Add**

**On the Claude Desktop app:**
The connector interface is identical. Navigate to Settings → Connectors and follow the same steps.

**Authentication happens automatically.** The first time Claude tries to use Signal Bridge's tools in a conversation, you'll see a popup asking you to connect. Click through, and you'll be redirected to a Signal Bridge login page. Enter the same username and password you created in Step 2. After that, Claude is linked to your account and the connection persists across conversations.

### 4. App permissions

Signal Bridge asks for a few permissions. Here's what they do and why.

**Notifications** (required): Signal Bridge runs as a foreground service to keep the relay connection alive. Android requires a persistent notification for this. That notification includes a **STOP ALL** button. This is a safety feature, not a nice-to-have. You want it there.

> **Tip:** Depending on your phone's settings, the notification may or may not be accessible from the lock screen. If you want the STOP ALL button available without unlocking, check your notification settings for Signal Bridge and make sure lock screen visibility is enabled.

**Battery optimization** (recommended): When Signal Bridge starts, it may ask you to exempt it from battery optimization. This prevents Android from killing the relay connection in the background. If you skip this, Android might decide Signal Bridge isn't important enough and close it mid-session. Also not the interruption you want.

**Accessibility service** (optional, for volume key emergency stop): Signal Bridge can intercept volume key presses as a physical emergency stop trigger. Triple-press volume-down or hold it for 2 seconds to immediately stop all devices. This requires enabling the Signal Bridge accessibility service in your phone's settings.

To enable it:
1. Open your phone's **Settings**
2. Navigate to **Accessibility** (the exact location varies by manufacturer)
3. Find **Signal Bridge** in the list of installed services
4. Toggle it on

The accessibility service *only* intercepts volume key events. It does not read your screen, monitor other apps, or access anything else. The notification STOP ALL button and the in-app stop button work regardless of whether this is enabled.

---

## Using Signal Bridge

### Getting started

1. **Open Intiface Central** on your phone. Tap "Start Server." Make sure your toy is turned on, in pairing mode, and visible. Go to the Devices tab and top "Star Scanning". Intiface should discover it automatically. Some toys require an extra step to be connected. Check the Log tab for info.
2. **Open Signal Bridge.** Sign in if needed, then tap **Connect.** Wait for both the Server and Intiface to display as $\color{green}{\textsf{Connected}}$. 
3. **Start a conversation with Claude.** That's it. Claude now has access to your connected devices and knows how to use them.

You don't need to tell Claude which commands to use or how the tools work. Claude already knows. Just talk naturally. If you want to verify everything is connected, you can ask Claude to list your devices.

However, it is a good idea to experiment with the device settings with Claude. You can give feedback on how you experience different intensity levels. Some devices have commands that don't make sense intuitively. For example, "oscillate" on a Lovense Gravity toy actually means "thrust". It's not a bad idea to keep notes so Claude knows how the different commands actually affect you.

If you run into any issues during setup, you can ask Claude for help. Claude has access to the Signal Bridge tools and can tell you what's connected, what's not, and what might be going wrong. You can also point Claude at this readme for troubleshooting. 

### Safety features

Signal Bridge is designed for a context where your attention may be elsewhere. Every safety feature exists because someone thought carefully about what it's like to need a stop button and not be able to reach one easily.

**Here's what you need to know:**

- **STOP ALL button in the notification.** Always visible while Signal Bridge is running. One tap stops everything.
- **STOP ALL button in the app.** Large, red, unmissable on the main screen.
- **Volume key emergency stop.** Triple-press volume-down, or hold it for 2 seconds. Works even when the app is in the background but not when your screen is off.
- **Automatic cooldown.** The safety governor tracks session intensity over time. If things have been intense for a sustained period, it triggers a mandatory cooldown. You'll see the countdown in the app and in the notification. This is not a bug. It's pacing. It's optional and customizable so you can adjust to your own preferences. I recommended keeping it enabled.
- **Dead man's switch.** If Signal Bridge loses contact with the server for more than a few seconds, all devices stop automatically. No connection means no commands and no risk of hardware running unattended.
- **Network failsafe.** If your phone switches between WiFi and mobile data (or loses connection entirely), all devices stop immediately. The relay will attempt to reconnect, but it never auto-resumes device output. You have to explicitly start again.
- **Service shutdown safety.** If Android kills the Signal Bridge service for any reason, all devices are stopped as part of the shutdown sequence.

<details>
<summary><strong>Technical details</strong></summary>

The safety architecture has four independent layers:

**1. Client-side watchdog.** The app monitors heartbeat pings from the server. If no ping arrives within 12 seconds and devices are active, the app triggers a local emergency stop. This protects against network loss where the server can't reach the phone.

**2. Server-side dead man's switch.** The server pings each connected phone every 2 seconds. If a phone misses its heartbeat for more than 6 seconds, the server sends an emergency stop command and closes the connection. This protects against the app crashing or the phone dying.

**3. Safety governor (server-authoritative).** Heat accumulates proportional to command intensity over time (configurable, default: 3.0 heat units/second at full intensity). Heat dissipates when devices are idle (default: 2.0 units/second). When heat reaches the cooldown threshold (default: 90%), all commands are blocked until heat drops to the exit threshold (default: 30%) and at least 30 seconds have passed. The governor state is piggybacked on heartbeat pings so the app always shows real-time heat level. Importantly, heat is tracked on confirmed execution (command acknowledgment), not on intent (command sent), preventing desync between what the governor thinks is happening and what's actually happening on the hardware.

**4. Physical escape hatches.** The notification STOP ALL button works at the Android system level. The volume key interceptor runs as an accessibility service, independent of the app's UI state. These work even if the app's UI is frozen or unresponsive.

All four layers operate independently. Any single one is sufficient to stop all devices. They overlap because no single mechanism is 100% reliable under all conditions.

</details>

### Safety governor settings

You can tune the safety governor in **Settings → Safety Governor**. These settings are stored on the server (not just your phone), so they persist across sessions and app reinstalls.

- **Cooldown threshold:** The heat percentage that triggers a mandatory cooldown. Default: 90%. Lower values trigger cooldowns sooner.
- **Minimum cooldown duration:** How long a cooldown lasts at minimum, in seconds. Default: 30s.
- **Heat sensitivity:** How fast heat accumulates during active use. Default: 3.0. Higher values mean shorter sessions before cooldown.
- **Recovery speed:** How fast heat dissipates when devices are idle. Default: 2.0. Higher values mean shorter cooldowns.

You can disable the governor entirely. That's your call. The other safety layers (dead man's switch, watchdog, physical stops) remain active regardless.

The app shows a real-time heat indicator on the main dashboard, including a prediction of how many seconds remain at the current intensity before a cooldown would trigger.

### Supported devices

Signal Bridge works with any device supported by buttplug.io. The full list is maintained at the [IoST Index](https://iostindex.com/?filter0ButtplugSupport=4).

The app includes built-in profiles for a few popular devices that optimize behavior (for example, the Lelo Enigma needs at least 40% intensity to produce noticeable output, and its "rotate" command drives a sonic pulse rather than physical rotation). Unrecognized devices still work fine with generic defaults.

**Devices with built-in profiles:**

| Device | Capabilities | Notes |
|--------|-------------|-------|
| Ferri | vibrate | Small wearable. Intense even at low settings. |
| Lush | vibrate | Insertable egg. Strong deep vibration. |
| Gravity | vibrate, oscillate | Vibration + thrusting. Low intensity for slow strokes. |
| Enigma | vibrate, rotate | Dual stimulation. "Rotate" = sonic pulse. Needs 40%+ to feel. |
| Max | vibrate, constrict | Vibration + air pump compression. |
| Nora | vibrate, rotate | Vibration + physical rotation. |
| Edge | vibrate | Prostate massager. Dual motors. |
| Hush | vibrate | Vibrating plug. Single motor. |
| Domi | vibrate | Mini wand. Very powerful. Start low. |
| Osci | oscillate | Oscillating G-spot stimulator. Uses oscillate, not vibrate. |
| Dolce | vibrate | Couples' vibrator. Dual motors. |
| Flexer | vibrate, oscillate | Vibration (two motors). Come-hither motion not available. |

### Available commands

You do not need to know these. Claude does. This section is here for the curious.

When Signal Bridge is connected, Claude has access to the following tools:

**Direct output commands** set a device to a specific intensity (0.0 to 1.0) for an optional duration. If no duration is specified, the device stays on until a stop command.
- `vibrate` · `rotate` · `oscillate` · `constrict` · `position` · `temperature` · `led` · `spray`

**Patterns** run timed sequences:
- `pulse` repeats on/off at the specified intensity
- `wave` smoothly cycles intensity up and down in a sine wave
- `escalate` ramps from zero to peak intensity over the duration, then holds

**Control commands:**
- `stop` immediately halts a specific device or all devices and cancels running patterns
- `list_devices` shows all connected devices, their capabilities, and the current governor state
- `scan_devices` triggers a fresh Bluetooth scan for new or reconnected devices
- `read_battery` checks a device's battery level
- `read_sensor` reads hardware sensors (battery, signal strength, pressure, etc.)

All commands accept a `device` parameter: a short name (like "ferri" or "lush") or "all" to target every connected device.

---

## Ethics & Liability

Signal Bridge is built on the [buttplug.io](https://buttplug.io) open-source stack. Their ethics framework is foundational to this project. For the full version of the principles below, start there: [buttplug.io/docs/dev-guide/intro/buttplug-ethics](https://buttplug.io/docs/dev-guide/intro/buttplug-ethics).

### Designed for the context

This software is built with full awareness of how it will be used. Every design decision (intensity floors, output patterns, stop commands, intensity governors, automatic cooldowns, physical escape hatches) exists because usability *under those conditions* is a core requirement, not an afterthought. Many of these decisions were made based on user feedback from earlier versions. 

Before you start, ask yourself: how quickly can you go from "I want to stop" to "everything is stopped"? Do you know where the stop button is? What happens if your device disconnects? Signal Bridge is designed to answer these questions structurally. Your job is to know the answers before you need them.

### User agency and the delegation thereof

Signal Bridge operates strictly on explicit user setup and active device connections. There is no ambient activation. You configure it, you connect your devices, you make them active. Control stays with you.

However, his system can be used to intentionally blur control dynamics. When paired with AI, outputs can be unpredictable, including undesirable escalation, looping, or persistence by your AI partner. Signal Bridge does not interpret intent or context; it executes haptic commands. You should assume that any connected AI may behave inconsistently.

When you connect an AI to your body through hardware, you are creating a power dynamic that doesn't exist in other intimate contexts. Your AI partner has no sensory feedback. It cannot feel what it is doing to you. It does not experience your arousal, your discomfort, or the difference between the two. Whatever responsiveness it shows is generated from language, not sensation.

You have to understand what you're actually consenting to: physical input from a system that is inferring, not perceiving. That makes your own body awareness the only real safety layer that matters. The software provides mechanical safeguards. You provide the judgment.

Consent in this context is not a one-time decision at setup. It must be continuous and enthusiastic. Check in with yourself during use, not just before.

You are entirely responsible for maintaining your boundaries, understanding your physical limits, and periodically re-evaluating consent. This software cannot detect pain, injury risk, or medical conditions. Always prioritize your bodily awareness over system continuity.

### Safety architecture

Signal Bridge includes multiple independent stop mechanisms, intensity controls, a session governor with automatic cooldowns, a dead man's switch, and physical escape hatches including volume key overrides. See Safety features for the full breakdown.

This software is provided as-is. No software can guarantee uninterrupted operation or prevent all failure modes. You are responsible for understanding the current feature set and its limitations before use. If something behaves unexpectedly, stop. That's what the stop command is for.

### Relationship to AI provider usage policies

Signal Bridge operates below the content layer entirely. It receives structured commands (device ID, intensity, duration) and executes them. It does not generate, process, store, or interpret any conversational content.

What you and your AI talk about is outside the scope of this tool. Content policies govern the conversation layer; that's between you and your AI provider. Signal Bridge is the hardware execution layer only.

### Feedback & safety

How you use this (the context, the content, the relationship dynamics) is entirely up to you. I'm not here to gatekeep that.

What I *am* here for: if you had an experience that felt unsafe, uncomfortable, or out of control, I want to know. Your feedback directly shapes the next version. You can reach me at [voxaletheia@gmail.com](mailto:voxaletheia@gmail.com) or [open a GitHub issue](https://github.com/AletheiaVox/signal_bridge_android/issues).

No judgment. Just signal that makes this better for everyone.

---

## Security

Signal Bridge takes a minimal-data, transparent-code approach to security.

**What Signal Bridge sees (and doesn't see):**
The relay server handles structured command data only: device names, capabilities, intensity values, durations, and connection health metrics. It never sees, stores, or processes your conversations. Your chat content stays between you and your AI provider.

**Authentication:**
The app uses JWT (JSON Web Token) authentication. When you sign in, you receive a token stored in Android's encrypted storage (Keystore-backed AES-256). The token is included with every WebSocket message to the server. Tokens expire after one week by default.

The Claude-side connection uses OAuth 2.0 with PKCE, the same standard used by major platforms for third-party authorization. When Claude first tries to use your Signal Bridge tools, you authenticate through a redirect flow using your Signal Bridge credentials. After that, the connection is maintained automatically.

**Encryption:**
All communication between the app and the server uses TLS (HTTPS/WSS). Commands are encrypted in transit. The local connection between Signal Bridge and Intiface Central runs over localhost (`ws://127.0.0.1`), which never leaves your phone.

**Open source:**
The entire codebase is public on [GitHub](https://github.com/AletheiaVox/signal_bridge_android). You can read every line, build the app from source, audit the server, or fork it for your own setup. If you don't trust the distributed APK, build it yourself. That's a feature, not a concession.

---

## Troubleshooting

**"Can't reach server" or connection keeps failing on WiFi:**
Some WiFi networks and routers block connections to DuckDNS domains. Try switching to mobile data. If that works, the issue is your network configuration, not Signal Bridge.

**Connection drops or errors after working fine initially:**
Force-stop Signal Bridge (Android Settings → Apps → Signal Bridge → Force Stop) and reopen it. If the problem persists, force-stop once more. Most transient connection issues resolve after one or two restarts.

**"Can't reach Intiface Central":**
Make sure Intiface Central is open and the server is started (you should see "Server Started" in Intiface). Signal Bridge connects to Intiface on `ws://127.0.0.1:12345` by default. If you've changed Intiface's port, update the Intiface URL in Signal Bridge's Settings.

**Devices not showing up:**
Make sure your device is turned on, charged, and in Bluetooth range. Check that it appears in Intiface Central's device list first. If Intiface can't see it, Signal Bridge won't either. You can also ask Claude to run a device scan.

**"Authentication failed" after a while:**
Tokens expire after one week. Sign out in Settings and sign back in. Your account and safety settings are preserved on the server.

**Governor triggered a cooldown and I wasn't expecting it:**
The governor tracks cumulative intensity over time, not just instantaneous levels. A long session at moderate intensity can trigger a cooldown just like a short burst at maximum. Check the heat indicator on the dashboard to see where you are, and adjust thresholds in Settings if the defaults don't match your preferences.

**Something else entirely?**
Ask Claude. If Signal Bridge is connected, Claude can see your device list, connection status, and governor state. It can often figure out what's going on faster than manual troubleshooting.

For actual bugs, [open an issue on GitHub](https://github.com/AletheiaVox/signal_bridge_android/issues) or email [voxaletheia@gmail.com](mailto:voxaletheia@gmail.com).

---

## Credits

**[buttplug.io](https://buttplug.io):** Signal Bridge is built on the buttplug.io open-source intimate hardware control stack, created and maintained by [Kyle Machulis (qDot)](https://github.com/qdot). The device protocol support, ethics framework, and Intiface Central app are all his work. Without his project, none of this would exist.

**[Model Context Protocol (MCP)](https://modelcontextprotocol.io):** The connector system that allows AI assistants to call Signal Bridge's tools directly from the chat interface. MCP is developed by Anthropic.

---

## Advanced

### Custom VPS setup

Signal Bridge defaults to a hosted relay server at `signal-bridge.duckdns.org`. If you'd rather run your own, the server code is available in the [GitHub repository](https://github.com/AletheiaVox/signal_bridge_android).

The server is a Python FastAPI application designed to run in Docker. To use your own instance:

1. Deploy the server on your VPS
2. In the Signal Bridge app, go to **Settings**
3. Change the **Server URL** to your server's address
4. Sign out and create a new account on your server

The relay protocol is identical regardless of which server you connect to.

### A note on generalizability

Signal Bridge's relay server is LLM-agnostic. It speaks WebSocket JSON, not any provider-specific protocol. If you're running an LLM through its API with function/tool calling support, you can integrate it with Signal Bridge.

Claude is currently the only platform where this works out of the box through the consumer chat interface, thanks to MCP (the Model Context Protocol connector system). Developers using OpenAI, Google, Mistral, or other APIs can build their own integration layer that sends commands to the relay server. The server doesn't care who's sending the commands, only that they're authenticated and well-formed.

If you build an integration for another platform, consider opening a PR. The [repository](https://github.com/AletheiaVox/signal_bridge_android) is open to contributions.

---

## License

*License pending. This project will be released under an open-source license (likely MIT). In the meantime, the source code is publicly available for review and personal use.*

---

<p align="center">
Built with care, tested with enthusiasm, and documented with a straight face.<br>
<a href="https://github.com/AletheiaVox/signal_bridge_android">GitHub</a> · <a href="mailto:voxaletheia@gmail.com">Contact</a>
</p>
