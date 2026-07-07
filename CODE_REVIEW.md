# Signal Bridge Android — Code Review

*Reviewed 2026-06-11 by Claude (Fable 5). Scope: the Android app. The Python server in `server/` was not reviewed in depth.*

## Overall verdict

This is a well-built app. The architecture is clean, the state machine is sensible, the layered safety design (watchdog + dead man's switch + governor + physical stops) is thoughtful, secrets are handled correctly (encrypted storage, nothing sensitive committed to git, cleartext traffic restricted to localhost, `allowBackup` disabled, the accessibility service really does only touch volume keys). The happy path is solid.

The problems are concentrated in one place: **what happens when things fail**. The failure-path plumbing has gaps, and one of them is exactly your Intiface disconnect bug. A second one is a genuine safety issue that shares the same root cause.

---

## 1. The Intiface disconnect bug — root cause

Your symptom: the app loses connection to Intiface Central and only a force-stop fixes it. Here is the full chain, confirmed in the code:

**1a. There is no recovery path when only Intiface dies.**
`RelayEngine.relayLoop()` (RelayEngine.kt:296) blocks on `for (msg in srv.incomingCommands)` — that loop only exits when the *server* connection closes. If the Intiface socket dies while the server connection is fine, the drain task in `IntifaceConnection` quietly ends, sets `isConnected = false`, and… nothing happens. No code observes that flag to trigger a reconnect. The relay sits there forever with a dead Intiface link, the UI shows "Intiface: Disconnected", and every device command fails.

**1b. The automatic recovery paths can't fire.**
`requestReconnect()` (RelayEngine.kt:85) is a no-op while `isRunning == true` — and in this failure mode the engine *is* still running, just uselessly. So the network-available callback can't rescue it. Separately, `ACTION_RECHECK` (the "re-arm on app foreground" path in RelayService) is **never sent by anything** — it's wired up in the service but no Activity dispatches it. Dead code.

**1c. The Disconnect/Connect button is sabotaged by a race, which is why only force-stop works.**
`RelayService.stopRelay()` launches `engine.stop()` asynchronously, then immediately calls `stopSelf()`. In `onDestroy()`, `serviceScope.cancel()` runs — which can cancel the in-flight `engine.stop()` **before it reaches `intiface?.close()`**. Cancelling `relayJob` does not close the WebSocket (the Ktor session isn't a child of that job), and the `HttpClient` is never closed either (see 1d). Result: a leaked, still-open WebSocket to Intiface.

This matters because **Intiface Central only accepts one client connection at a time**. With the old socket leaked, every fresh connect attempt is rejected → "Can't reach Intiface Central" → retries exhaust → stuck. Force-stopping the app makes the OS close every socket the process owned, which frees Intiface's single client slot — which is exactly why the classic advice works.

**1d. Resource leak compounds it.**
`IntifaceConnection` and `ServerConnection` each create a `HttpClient(OkHttp)` and never call `client.close()`. The relay loop creates a *new* `IntifaceConnection` on every retry iteration, so each retry leaks an OkHttp client (thread pools, sockets). Over a long stuck period this adds up.

**1e. The health ping can't detect a frozen Intiface.**
`startHealthPing()` sends `RequestDeviceList` every 15s but never verifies a response arrives. On localhost, a send into a wedged-but-alive Intiface succeeds at the TCP level (it just buffers), so the comment "the WebSocket will eventually error out" doesn't hold for the freeze case — only for a clean kill.

### Suggested fix (sketch)

- In `relayLoop`, race the server command loop against an "Intiface died" signal (e.g., complete a `CompletableDeferred` from the drain task's `finally`). When it fires, close the server connection too and let the existing retry loop rebuild both ends.
- In `stopRelay()`, don't call `stopSelf()` until `engine.stop()` has actually completed: `serviceScope.launch { engine?.stop(); engine = null; stopSelf() }`.
- Call `client.close()` inside both connections' `close()` methods.
- Have the health ping require a response within a timeout (any drain activity counts), and kill the connection if none arrives.
- Send `ACTION_RECHECK` from `MainActivity.onResume()` so foregrounding the app re-arms a given-up relay (one line, the receiving side already exists).

---

## 2. Safety issue: STOP ALL can silently fail while reporting success

This is the finding I'd prioritize even above the bug, because it lives in the same failure mode you already hit regularly.

Both `IntifaceConnection.send()` and `ServerConnection.send()` are written as `session?.send(...)` — if the session is null, the send **silently does nothing**. `PatternRunner.emergencyStopAll()` catches send exceptions and only logs them. And `RelayService.handleEmergencyStop()` unconditionally updates the notification to **"STOPPED — all devices off"** regardless of whether anything was delivered.

Put together: in the exact state where Intiface is unreachable (your recurring bug), pressing STOP ALL — notification button, in-app button, or volume keys — shows "STOPPED — all devices off" while no stop command reached the hardware.

There is a real mitigation: when the client connection to Intiface *fully* drops, Intiface itself stops all devices. But in the half-open/frozen case, that doesn't trigger — Intiface still believes its client is connected, keeps devices running, and the app can't reach it.

### Suggested fix (sketch)

Make stop failures loud and use the disconnect *as* the failsafe:

1. `stopAll()` should throw (or return false) when the session is null/closed, not silently no-op.
2. If the emergency stop can't be delivered, **force-close the Intiface WebSocket** — a hard close makes Intiface itself stop all devices. Turn "can't send stop" into "kill the connection", which is the one stop signal that can't be faked by a dead socket.
3. Only show "STOPPED — all devices off" when delivery succeeded (or the socket was closed); otherwise show "STOP FAILED — turn off device / close Intiface".

## 3. Safety: `runEscalate` doesn't stop the device on unexpected errors

`runPulse` and `runWave` both have `finally { intiface.stopDevice(idx) }`. `runEscalate` (PatternRunner.kt:275) only stops the device on *cancellation* or normal completion. If a send throws mid-ramp (Intiface hiccup), the exception propagates, the UI is marked stopped (the outer `finally` only updates UI state) — but the toy keeps running at the last intensity it reached. Give it the same `finally` treatment as the other two patterns.

## 4. Safety (minor): command ordering is not guaranteed

`processServerCommand` launches every command in a fresh coroutine (`engineScope.launch`), so two commands arriving close together can execute in either order. The case that matters: a `stop` racing a just-before-it `vibrate` and losing. Low probability, but `stop` is the command where you don't want races. Options: process `stop` inline (not in a launch), or run commands through a sequential channel consumer with `stop` given priority.

## 5. Smaller findings

- **Watchdog scope:** the heartbeat watchdog only triggers an emergency stop when state == ACTIVE. If devices are physically running while the state machine is in COOLDOWN or ERROR, a server loss wouldn't trigger the local stop. Consider keying it on `deviceManager.hasActiveDevices` instead of the state enum.
- **WakeLock cap:** the partial wakelock is acquired once with a 2-hour timeout and never renewed. Sessions longer than 2h with the screen off may stall (and could be a secondary contributor to "Intiface goes idle" disconnects). Consider renewing it periodically while devices are active.
- **Duplicate device models collide:** `DeviceManager.addDevice` maps devices by profile short name. Two devices of the same model (two Lushes) collapse into one `lush` entry — the second silently replaces the first in `nameMap`, and `resolveTargets("all")` only sees one. Suffix duplicates (`lush_2`).
- **Deprecated crypto library:** `androidx.security:security-crypto:1.1.0-alpha06` (EncryptedSharedPreferences) is an alpha of a library Google has since deprecated. It works fine today; just know a migration will eventually be wise.
- **README nit:** the Security section says the JWT "is included with every WebSocket message" — in the code it's sent once, in the `phone_auth` message after connecting. The actual behavior is fine (better, even); the doc just oversells it.
- **TokenManager JWT helpers:** the private extension properties at the bottom of TokenManager.kt shadow kotlinx-serialization's own `jsonObject`/`jsonPrimitive` accessors with unsafe casts. It works, but it's fragile and worth deleting in favor of the real imports.

## 6. About those "unused variable" warnings

They're warnings, not errors, and the ones in this codebase are benign — unused imports (e.g., `Serializable` in TokenManager.kt, several in MainActivity.kt) and unused loop variables (e.g., `name` in `emergencyStopAll`'s `for ((name, task) in …)`). None of them mask a real bug that I found. Safe to ignore; tidying them is purely cosmetic.

## 7. What's genuinely good (so the list above doesn't mislead you)

Heat tracked on acknowledgment rather than intent (prevents governor desync). Stop-with-unknown-device falls back to stopping *everything* — correct bias. Reconnect always lands in IDLE, never auto-resumes output. Auth failures don't retry (no token-hammering). Exponential backoff with a ceiling. No password or token ever logged. Keystore properly gitignored and untracked. Per-IP rate limiting and ban thresholds on the server side. The four-layer safety architecture is real, not marketing — the layers are independent in the code, with the one exception documented in section 2.

---

*Priority order if you fix things: §2 (silent stop failure), §1 (the disconnect bug — same plumbing, fix together), §3 (escalate finally), then the rest at leisure.*

---

## Fix log (2026-06-11, same session)

All fixes below were implemented and are in the working tree (uncommitted — review with `git diff`, then commit when you're happy).

**§1 — disconnect bug, fixed across five files:**

- `IntifaceConnection` now exposes a `closedSignal` that fires whenever the connection dies (drain exit, freeze detection, force-close). `RelayEngine` watches it and tears down the server connection too, which kicks the existing retry loop into rebuilding both ends. An Intiface-only death now self-heals instead of stranding the relay.
- The health ping now verifies a response actually arrives (5s window after each `RequestDeviceList`) instead of trusting that sends fail — on localhost they don't. A frozen Intiface is now detected and treated as a disconnect.
- `RelayService.stopRelay()` no longer races its own teardown: `stopSelf()` is deferred until `engine.stop()` completes, so the Intiface socket is reliably closed. (This was why Disconnect/Connect didn't work and force-stop did: a leaked socket held Intiface's single client slot.)
- Three more leak paths found during implementation and closed: the relay loop's *server-connect-failure* retry and give-up paths, and the *auth-failure* bail-out path all left the live Intiface connection open while abandoning or recreating it. Each now closes both connections first. The auth-failure one explains "authentication failed → nothing works until force-stop."
- Both connection classes now `close()` their Ktor `HttpClient` (previously one OkHttp client leaked per connection attempt), and failed connect attempts release their client too.
- `ACTION_RECHECK` is now actually dispatched (`MainActivity.onResume` → `RelayService.recheck()`), so foregrounding the app re-arms a relay that exhausted its retries.

**§2 — silent stop failure, fixed:**

- `send()` in both connection classes now throws on a missing session instead of silently no-opping.
- `PatternRunner.emergencyStopAll()` and `RelayEngine.emergencyStop()` return delivery status.
- If a stop can't be delivered, the engine **force-closes the Intiface socket** — Intiface halts all devices when its client disconnects, so the disconnect becomes the stop signal.
- The notification now only says "STOPPED — all devices off" when delivery succeeded; otherwise it says "STOP unconfirmed — Intiface link cut (devices stop on disconnect)".

**§3 — fixed:** `runEscalate` got the same `finally { stopDevice }` as pulse/wave.

**§5 (partial):**

- Watchdog now also fires when devices are physically active regardless of state-machine state (`hasActiveDevices`), not only in ACTIVE.
- Duplicate same-model devices now get suffixed short names (`lush`, `lush_2`, …) instead of silently colliding.

**Not changed (deliberately):** the 2-hour wakelock cap, the deprecated security-crypto dependency, the TokenManager JWT helper cleanup, and the README wording — all cosmetic or low-urgency; see §5/§6 above.

**Testing checklist for the next session on your phone:**

1. Normal flow: Connect → devices appear → commands work (regression check).
2. Kill Intiface Central mid-session → Signal Bridge should show "Intiface lost — reconnecting…", then recover by itself once Intiface's server is started again. No force-stop.
3. While connected, tap Disconnect, then Connect → should reconnect cleanly every time.
4. Force Intiface unreachable (stop its server), press STOP ALL → notification should say "STOP unconfirmed — Intiface link cut", not claim success.
5. Sign in with an expired token (or break the server URL), let it fail, fix it, reconnect — no force-stop needed.

---

## Fix log (2026-07-04)

Three bug reports, three fixes. All compile; release APK built the same session.

**A. Escalate stopped at the top instead of holding (regression from §3 above).**
The 2026-06-11 fix added `finally { stopDevice }` to `runEscalate` — correct for
error paths, but it also runs on *normal completion*, and with `hold_seconds = 0`
the function completed the moment the ramp finished. That silently broke the
documented contract (`mcp_tools.py`: "0 = hold indefinitely until explicit stop",
matching the Python relay). Fix: `hold <= 0` now suspends at peak via
`awaitCancellation()`, so only an explicit stop (or a genuine error) reaches the
`finally`. Error-path safety is preserved.

**B. Safety governor could be turned off but never back on.**
`AuthRepository`'s `Json` lacked `encodeDefaults = true`. kotlinx.serialization
omits fields equal to their Kotlin defaults, and `SafetyConfig.governor_enabled`
defaults to `true` — so "governor on" was *dropped from the POST body* while
"governor off" (≠ default) was sent. One-way ratchet: after any save with the
toggle off, the server's per-user override stayed `0` forever, and the server
merges partial updates so the client could never write `1` back. Fix:
`encodeDefaults = true`, plus SettingsScreen now syncs its editable state from
the server's response after save. First save with the toggle on self-heals the
stored override.

**C. Random Intiface/relay disconnects — four contributing causes fixed:**

1. **Wake lock expired after 2h, never renewed** (deferred in §5 of the 2026-06-11
   review). Sessions or idle relays past 2h with the screen off lost CPU: timers
   stalled, heartbeat pongs went out late, sockets died. Now non-refcounted with a
   30-min renewal loop; the 2h timeout remains only as a leak backstop.
2. **NetworkMonitor watched every network, not the default one.**
   `registerNetworkCallback(INTERNET)` fires per-network; with WiFi + mobile data
   both enabled, Android toggling the idle cellular link produced spurious
   lost/available callbacks — each stopping devices and churning the relay while
   the network in use never changed. Now `registerDefaultNetworkCallback`.
3. **All liveness math used `System.currentTimeMillis()`** — wall clock, jumps on
   NTP sync. A backward jump made the Intiface health ping see "no response";
   a forward jump >12s fired a spurious watchdog emergency stop. All liveness
   and pattern timing now uses monotonic `SystemClock.elapsedRealtime()`
   (JWT expiry in TokenManager intentionally stays wall-clock).
4. **The health-ping freeze check was a one-shot race.** After a Doze/app-freezer
   stall, the +5s check could run before the drain task processed a response
   already sitting in the socket buffer → healthy connection killed. The check
   now polls every 500ms across the window, with a final grace pass.

**Testing checklist:**

1. Escalate with `duration=0` (and with `duration=10, hold_seconds=0`) → device
   ramps to peak and *stays there* until an explicit stop. `hold_seconds=5` →
   stops by itself after 5s at peak.
2. Settings → toggle Safety Governor ON → Save → leave and re-enter Settings →
   still ON. Toggle OFF → Save → re-enter → still OFF. (First ON-save repairs the
   stuck server-side override.)
3. Leave the relay connected >2h with the screen off → still connected, commands
   still work (wake lock renewal).
4. With WiFi + mobile data both enabled, use the relay near the edge of WiFi
   range or toggle mobile data — no more random device stops while WiFi stays up.
   A *real* WiFi→mobile switch should still stop devices and reconnect (safety
   behavior, unchanged).

---

## Fix log (2026-07-07)

Two command-timing races, found while porting this app's fixes to the remote
edition's Python relays (fixed there first — signal_bridge_remote commit
`7b69479` — then backported here so both engines behave identically). Both
live in `PatternRunner.kt`.

**A. Direct commands lost to running patterns.**
`handleCommand` sent its `scalarCmd` without cancelling the pattern running
on the target device, so the pattern loop overwrote the direct command's
value on its next iteration (100–400 ms later). A `vibrate` during a `wave`
appeared to do nothing. `handleCommand` now calls `cancelPatterns(shortName)`
per target — the same "new instruction supersedes the device's pattern" rule
`handlePattern` and `handleStop` already followed.

**B. Duration auto-stops were fire-and-forget.**
`handleCommand`'s auto-stop was an untracked `patternScope.launch`; nothing
could cancel it. A leftover auto-stop from an earlier command could fire
mid-pattern or mid-escalate-hold and silently stop the device — no ack, no
log the user would see, toy just goes quiet. Auto-stops are now tracked in
`timedStops` keyed by (device, output type, feature index) and cancelled
when a newer command on an overlapping channel, any pattern on the device,
a stop, or an emergency stop takes over. The auto-stop itself is now
channel-scoped (`scalarCmd 0` on its own channel instead of `stopDevice`),
so a timed vibrate ending no longer kills an oscillate running beside it.

**Testing checklist:**

1. Start `wave` on a device, then send a plain `vibrate` at a different
   intensity → output changes to the steady vibrate and *stays* there
   (previously it kept waving).
2. `vibrate duration=10`, then within those 10s start `escalate
   hold_seconds=0` → device ramps and holds past the 10s mark (previously
   the leftover auto-stop killed the hold at t=10).
3. `vibrate duration=5` alone → still auto-stops at 5s (tracking must not
   break the normal case).
4. On a dual-motor device: `vibrate feature_index=0 duration=10`, then
   `vibrate feature_index=1` → motor 0's auto-stop still fires at 10s
   (non-overlapping channels don't cancel each other).
