# Signal Bridge Privacy Policy

**Last updated:** March 30, 2026

Signal Bridge is built on a simple principle: we collect the minimum data needed to make the app work, and nothing else.

## What Signal Bridge collects

**Account information:** When you create an account, Signal Bridge stores your username and a hashed version of your password on the relay server. Your password is hashed with bcrypt before storage. The server never stores or has access to your plaintext password.

**Authentication tokens:** When you sign in, the server issues a JWT (JSON Web Token) that is stored in encrypted storage on your device. This token authenticates your connection to the relay server and expires after one week.

**Device information:** When you connect, Signal Bridge receives your device names, capabilities, and connection status from Intiface Central. This information is held in memory on the server for the duration of your session to route commands to the correct hardware. It is not stored after you disconnect.

**Safety settings:** If you customize your safety governor settings (cooldown thresholds, heat sensitivity, etc.), those preferences are stored in a database on the server, associated with your account, so they persist across sessions.

**Command data:** During an active session, structured hardware commands (device name, output type, intensity, duration) pass through the relay server to reach your phone. These commands are transient. They are routed and discarded. They are not logged, stored, or analyzed.

## What Signal Bridge does not collect

**Conversation content.** Signal Bridge never sees, processes, stores, or has access to anything you or your AI say to each other. Your conversations stay between you and your AI provider. Signal Bridge operates below the content layer and only handles structured hardware commands.

**Analytics or telemetry.** Signal Bridge does not include any analytics SDKs, tracking pixels, crash reporting services, or telemetry of any kind. There is no Google Analytics, no Firebase, no Sentry, no anything. If something breaks, we find out because you tell us.

**Location data.** Signal Bridge does not request or access your location.

**Contacts, files, camera, microphone, or any other device data.** Signal Bridge only accesses the permissions listed in the app (network, notifications, foreground service, vibration, and optionally the accessibility service for volume key emergency stop). Nothing else.

## Third-party services

**Intiface Central** runs as a separate app on your phone and manages Bluetooth connections to your devices. Signal Bridge communicates with Intiface Central over a local WebSocket connection (`ws://127.0.0.1`) that never leaves your device. Intiface Central has its own privacy practices; see [buttplug.io](https://buttplug.io) for details.

**Your AI provider** (e.g., Claude by Anthropic) connects to Signal Bridge through the relay server using the MCP connector system. Your AI provider sends structured commands to Signal Bridge on your behalf. Signal Bridge does not control what your AI provider collects or stores. Refer to your AI provider's privacy policy for their data practices.

## Data storage and security

Account data and safety settings are stored in a SQLite database on the relay server. Authentication tokens on your device are encrypted using Android Keystore-backed AES-256 encryption. All communication between the app and the server uses TLS (HTTPS/WSS).

The default relay server is hosted at `signal-bridge.duckdns.org`. If you run your own server, your data stays on your infrastructure entirely.

## Data retention and deletion

Session data (device info, commands, governor heat state) exists only in memory and is discarded when you disconnect.

Account data (username, hashed password, safety settings) is retained until you request deletion. To delete your account and all associated data, contact [voxaletheia@gmail.com](mailto:voxaletheia@gmail.com).

## Children

Signal Bridge is not intended for use by anyone under the age of 18. Accounts for minors are not permitted.

## Open source

Signal Bridge's source code is publicly available at [github.com/AletheiaVox/signal_bridge_android](https://github.com/AletheiaVox/signal_bridge_android). You can verify every claim in this policy by reading the code.

## Changes to this policy

If this policy changes, the updated version will be posted here with a new "last updated" date. Material changes will be noted in the app's release notes.

## Contact

Questions, concerns, or data deletion requests: [voxaletheia@gmail.com](mailto:voxaletheia@gmail.com)
