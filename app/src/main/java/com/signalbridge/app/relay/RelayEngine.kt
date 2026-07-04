package com.signalbridge.app.relay

import android.os.SystemClock
import com.signalbridge.app.data.ConnectionHealth
import com.signalbridge.app.data.GovernorState
import com.signalbridge.app.data.RelayState
import com.signalbridge.app.data.RelayStateHolder
import com.signalbridge.app.util.SBLog
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/**
 * The relay engine — orchestrates both WebSocket connections and routes messages.
 *
 * Lifecycle:
 *  1. Connect to Intiface Central → Buttplug handshake → scan devices
 *  2. Connect to VPS server → JWT auth → send device list
 *  3. Enter relay loop: route server commands to Intiface, respond with acks
 *  4. Heartbeat watchdog: if no server ping in 12s, trigger local emergency stop
 *  5. On disconnect: clean up, transition to ERROR, retry after 5s
 *
 * Key safety invariant: reconnection always lands in IDLE, never auto-resumes ACTIVE.
 *
 * @param serverUrl Base URL of the VPS server (e.g., "https://signal-bridge.duckdns.org")
 * @param token JWT authentication token
 * @param intifaceUrl WebSocket URL for Intiface Central (e.g., "ws://127.0.0.1:12345")
 * @param onNotificationUpdate Callback to update the foreground service notification
 */
class RelayEngine(
    private val serverUrl: String,
    private val token: String,
    private val intifaceUrl: String,
    private val onNotificationUpdate: (String) -> Unit,
) {
    private val TAG = "RelayEngine"

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var intiface: IntifaceConnection? = null
    private var server: ServerConnection? = null
    private var deviceManager: DeviceManager? = null
    private var patternRunner: PatternRunner? = null

    private var relayJob: Job? = null
    private var watchdogJob: Job? = null
    private var deviceEventJob: Job? = null
    private var intifaceMonitorJob: Job? = null

    // SystemClock.elapsedRealtime — monotonic. currentTimeMillis jumps on NTP
    // sync, and a forward jump >12s while devices were running fired a spurious
    // watchdog emergency stop.
    @Volatile
    private var lastHeartbeatTime: Long = 0L

    @Volatile
    private var isRunning = false

    // Exponential backoff: 1s, 2s, 4s, 8s, 16s, 30s cap. Lenient ceiling — combined with
    // connectivity-based auto-recover (RelayService wires NetworkMonitor.onNetworkAvailable
    // -> requestReconnect), so a transient Intiface/localhost blip won't strand the relay
    // the way the old ceiling of 5 (~31s) did.
    private var retryCount = 0
    private val MAX_RETRIES = 12
    private fun nextBackoffMs(): Long {
        val delay = minOf(1000L * (1L shl retryCount), 30_000L)
        retryCount++
        return delay
    }
    private fun resetBackoff() { retryCount = 0 }

    /**
     * Start the relay engine. Runs the connect-relay loop in the background.
     */
    fun start() {
        if (isRunning) return
        isRunning = true
        resetBackoff()
        ConnectionStateMachine.transition(RelayState.CONNECTING)

        relayJob = engineScope.launch {
            relayLoop()
        }
    }

    /**
     * Re-arm the relay after it has terminally given up (e.g. connectivity returned,
     * or the user re-opened the app). No-op if the engine is still running, so it's
     * safe to call repeatedly from a network callback.
     */
    fun requestReconnect() {
        if (isRunning) return
        SBLog.i(TAG, "requestReconnect — restarting relay loop")
        start()
    }

    /**
     * Stop the relay engine gracefully.
     */
    suspend fun stop() {
        isRunning = false
        watchdogJob?.cancel()
        deviceEventJob?.cancel()
        intifaceMonitorJob?.cancel()
        relayJob?.cancel()

        patternRunner?.emergencyStopAll()
        patternRunner?.destroy()

        try { server?.close() } catch (_: Exception) {}
        try { intiface?.close() } catch (_: Exception) {}

        server = null
        intiface = null
        deviceManager = null
        patternRunner = null

        RelayStateHolder.reset()
        SBLog.i(TAG, "Engine stopped")
    }

    /**
     * Emergency stop: stop all devices, cancel all patterns, update state.
     * Does NOT disconnect — unless the stop can't be delivered, in which case
     * the Intiface socket is force-closed as a failsafe (Intiface stops all
     * devices itself when its client disconnects).
     *
     * @return true if the stop was delivered to Intiface, false if delivery
     *   failed and the connection was cut instead. Callers should surface
     *   false to the user — never claim "all devices off" on a failed send.
     */
    suspend fun emergencyStop(): Boolean {
        SBLog.safety("EMERGENCY STOP triggered in RelayEngine")
        val runner = patternRunner
        val intf = intiface

        val delivered = when {
            runner != null -> runner.emergencyStopAll()
            intf != null -> try {
                intf.stopAll()
                true
            } catch (e: Exception) {
                SBLog.safety("Direct StopAllDevices failed: ${e.message}")
                false
            }
            else -> true // nothing connected — nothing can be running through us
        }

        if (!delivered) {
            // Last resort: cut the Intiface connection. The disconnect IS the
            // stop signal — Intiface halts all devices when its client drops.
            SBLog.safety("Stop not deliverable — force-closing Intiface connection as failsafe")
            try { intf?.forceClose() } catch (_: Exception) {}
        }

        // Tell the server so the governor stops accumulating heat
        try {
            server?.sendEmergencyStop()
        } catch (_: Exception) {}

        // Also send an ack so any pending command gets resolved
        try {
            server?.sendAck(
                success = true,
                message = "Emergency stop triggered on phone",
                requestId = "emergency",
                devicesAffected = deviceManager?.availableDevices() ?: emptyList(),
            )
        } catch (_: Exception) {}

        // Force to IDLE — not DISCONNECTED, so we can see what happened
        ConnectionStateMachine.forceTransition(RelayState.IDLE)
        return delivered
    }

    // ── Main relay loop ─────────────────────────────────────────────

    private suspend fun relayLoop() {
        while (isRunning) {
            try {
                // Step 1: Connect to Intiface
                val intf = IntifaceConnection(intifaceUrl)
                intiface = intf

                try {
                    intf.connect()
                } catch (e: Exception) {
                    // Release the failed client — previously leaked one OkHttp
                    // client (threads + possibly a half-open socket) per retry.
                    try { intf.close() } catch (_: Exception) {}
                    SBLog.e(TAG, "Can't reach Intiface Central: ${e.message}")
                    RelayStateHolder.setError("Can't reach Intiface Central. Is it running?")
                    updateHealth(intifaceConnected = false)
                    ConnectionStateMachine.transition(RelayState.ERROR)
                    onNotificationUpdate("Can't reach Intiface")
                    if (retryCount >= MAX_RETRIES) {
                        SBLog.e(TAG, "Max retries ($MAX_RETRIES) reached — giving up")
                        onNotificationUpdate("Connection failed — tap to retry")
                        isRunning = false
                        return
                    }
                    val backoff = nextBackoffMs()
                    SBLog.i(TAG, "Retry ${retryCount}/$MAX_RETRIES in ${backoff}ms")
                    delay(backoff)
                    continue
                }

                intf.startDrain(engineScope)
                intf.startHealthPing(engineScope)

                // Step 2: Scan for devices
                intf.scan(duration = 5000)

                val dm = DeviceManager()
                deviceManager = dm
                for ((_, device) in intf.devices) {
                    dm.addDevice(device)
                }

                // Listen for device events (add/remove) from Intiface
                deviceEventJob = engineScope.launch {
                    for (event in intf.deviceEvents) {
                        when (event) {
                            is ButtplugEvent.DeviceAdded -> {
                                dm.addDevice(event.device)
                                RelayStateHolder.updateDevices(dm.buildDeviceInfoList())
                                onNotificationUpdate("Connected (${dm.deviceCount} devices)")
                                // Send updated device list to server
                                try { server?.sendDeviceList(dm.buildDeviceListReport()) } catch (_: Exception) {}
                            }
                            is ButtplugEvent.DeviceRemoved -> {
                                dm.removeDevice(event.deviceIndex)
                                RelayStateHolder.updateDevices(dm.buildDeviceInfoList())
                                onNotificationUpdate("Connected (${dm.deviceCount} devices)")
                                try { server?.sendDeviceList(dm.buildDeviceListReport()) } catch (_: Exception) {}
                            }
                            is ButtplugEvent.DeviceList -> {
                                for (dev in event.devices) {
                                    dm.addDevice(dev)
                                }
                                RelayStateHolder.updateDevices(dm.buildDeviceInfoList())
                                onNotificationUpdate("Connected (${dm.deviceCount} devices)")
                            }
                            else -> {}
                        }
                    }
                }

                updateHealth(intifaceConnected = true, intifaceHealthy = true)

                SBLog.i(TAG, "Devices ready: ${dm.availableDevices()}")

                // Step 3: Connect to server
                val srv = ServerConnection(serverUrl, token)
                server = srv

                try {
                    srv.connect()
                } catch (e: AuthenticationException) {
                    SBLog.e(TAG, "Auth failed: ${e.message}")
                    // Close BOTH connections before bailing. Previously the
                    // live Intiface connection was left open here, holding
                    // Intiface's single client slot and blocking every future
                    // connect until the app was force-stopped.
                    try { srv.close() } catch (_: Exception) {}
                    deviceEventJob?.cancel()
                    try { intf.close() } catch (_: Exception) {}
                    RelayStateHolder.setError("Authentication failed. Try signing out and back in.")
                    ConnectionStateMachine.transition(RelayState.ERROR)
                    onNotificationUpdate("Auth failed")
                    // Don't retry on auth failure — user needs to fix token
                    isRunning = false
                    return
                } catch (e: Exception) {
                    SBLog.e(TAG, "Can't reach server: ${e.message}")
                    // Same cleanup: the retry path loops back to Step 1 and
                    // builds a NEW IntifaceConnection — the old one must be
                    // closed or it leaks (and blocks the single client slot).
                    try { srv.close() } catch (_: Exception) {}
                    deviceEventJob?.cancel()
                    try { intf.close() } catch (_: Exception) {}
                    RelayStateHolder.setError("Can't reach server: ${e.message}")
                    updateHealth(serverConnected = false)
                    ConnectionStateMachine.transition(RelayState.ERROR)
                    onNotificationUpdate("Can't reach server")
                    if (retryCount >= MAX_RETRIES) {
                        SBLog.e(TAG, "Max retries ($MAX_RETRIES) reached — giving up")
                        onNotificationUpdate("Connection failed — tap to retry")
                        isRunning = false
                        return
                    }
                    val backoff = nextBackoffMs()
                    SBLog.i(TAG, "Retry ${retryCount}/$MAX_RETRIES in ${backoff}ms")
                    delay(backoff)
                    continue
                }

                // Send device list
                val deviceList = dm.buildDeviceListReport()
                SBLog.i(TAG, "Device list to send: ${deviceList.map { it["short_name"] }}")
                if (deviceList.isNotEmpty()) {
                    srv.sendDeviceList(deviceList)
                }

                updateHealth(serverConnected = true, intifaceConnected = true, intifaceHealthy = true)
                RelayStateHolder.updateDevices(dm.buildDeviceInfoList())
                RelayStateHolder.setError(null)

                // Successfully connected — reset backoff counter
                resetBackoff()

                // Transition to IDLE (never auto-resume to ACTIVE)
                ConnectionStateMachine.transition(RelayState.IDLE)
                onNotificationUpdate("Connected (${dm.deviceCount} devices)")

                // Step 4: Create pattern runner with device state callback
                val runner = PatternRunner(intf, dm) { updatedDevices ->
                    RelayStateHolder.updateDevices(updatedDevices)
                }
                patternRunner = runner

                // Step 5: Start heartbeat watchdog
                lastHeartbeatTime = SystemClock.elapsedRealtime()
                startWatchdog()
                SBLog.i(TAG, "Relay fully connected — waiting for commands")

                // Wire heartbeat callback so watchdog knows we're alive
                srv.onHeartbeatReceived = {
                    lastHeartbeatTime = SystemClock.elapsedRealtime()
                }

                // Wire governor updates from heartbeat pings
                srv.onGovernorUpdate = { gov ->
                    handleGovernorUpdate(gov, dm)
                }

                // Step 6: Start listening for server commands
                // IMPORTANT: start listening BEFORE entering the command loop,
                // so we don't miss frames that arrive between connect() and here.
                val listenJob = srv.startListening(engineScope)

                // Step 6b: Monitor Intiface liveness. Previously, an
                // Intiface-only death left the relay stranded forever: the
                // command loop below only exits when the SERVER connection
                // closes. Now, when Intiface's closedSignal fires, we close
                // the server connection too, which ends the command loop and
                // lets this retry loop rebuild both ends.
                intifaceMonitorJob?.cancel()
                intifaceMonitorJob = engineScope.launch {
                    intf.closedSignal.await()
                    if (!isRunning) return@launch
                    SBLog.w(TAG, "Intiface connection lost — tearing down to reconnect")
                    RelayStateHolder.setError("Lost connection to Intiface — reconnecting…")
                    updateHealth(intifaceConnected = false, intifaceHealthy = false)
                    onNotificationUpdate("Intiface lost — reconnecting…")
                    try { srv.close() } catch (_: Exception) {}
                }

                // Step 7: Process commands from server
                // This loop exits when incomingCommands is closed (connection lost)
                // or when the engine is cancelled.
                try {
                    for (msg in srv.incomingCommands) {
                        processServerCommand(msg, runner, srv, dm)
                    }
                    // If we get here, incomingCommands was closed → connection dropped
                    SBLog.w(TAG, "Command channel closed — server connection lost")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SBLog.e(TAG, "Command processing error: ${e.message}")
                }

                listenJob.cancel()
                intifaceMonitorJob?.cancel()

            } catch (e: CancellationException) {
                SBLog.i(TAG, "Relay loop cancelled")
                return
            } catch (e: Exception) {
                SBLog.e(TAG, "Relay error: ${e.message}")
            }

            // Clean up for retry
            watchdogJob?.cancel()
            deviceEventJob?.cancel()
            intifaceMonitorJob?.cancel()
            patternRunner?.destroy()
            try { server?.close() } catch (_: Exception) {}
            try { intiface?.close() } catch (_: Exception) {}
            server = null
            intiface = null
            deviceManager = null
            patternRunner = null

            if (isRunning) {
                if (retryCount >= MAX_RETRIES) {
                    SBLog.e(TAG, "Max retries ($MAX_RETRIES) reached — giving up")
                    RelayStateHolder.setError("Connection lost. Tap Connect to try again.")
                    ConnectionStateMachine.forceTransition(RelayState.DISCONNECTED)
                    onNotificationUpdate("Disconnected — tap to retry")
                    isRunning = false
                } else {
                    val backoff = nextBackoffMs()
                    SBLog.i(TAG, "Reconnecting in ${backoff}ms (attempt $retryCount/$MAX_RETRIES)")
                    ConnectionStateMachine.forceTransition(RelayState.CONNECTING)
                    onNotificationUpdate("Reconnecting (${retryCount}/$MAX_RETRIES)...")
                    delay(backoff)
                }
            }
        }
    }

    // ── Command Processing ──────────────────────────────────────────

    private suspend fun processServerCommand(
        msg: ServerMessage,
        runner: PatternRunner,
        server: ServerConnection,
        dm: DeviceManager,
    ) {
        // Update heartbeat timestamp (any message from server counts)
        lastHeartbeatTime = SystemClock.elapsedRealtime()

        // Parse the payload into a simple map for the PatternRunner
        val payload = msg.payload.toMap()

        // Run command in background so we don't block the command channel
        engineScope.launch {
            try {
                val ack = runner.runCommand(msg.type, payload)
                server.sendAck(ack.success, ack.message, ack.requestId, ack.devicesAffected)
                SBLog.i(TAG, "-> Ack: ${ack.message}")

                // If it was a scan, send updated device list
                if (msg.type == "scan") {
                    server.sendDeviceList(dm.buildDeviceListReport())
                    RelayStateHolder.updateDevices(dm.buildDeviceInfoList())
                }

                // Update relay state based on device activity
                if (ack.success) {
                    if (dm.hasActiveDevices) {
                        ConnectionStateMachine.transition(RelayState.ACTIVE)
                    } else if (ConnectionStateMachine.currentState == RelayState.ACTIVE) {
                        // All devices stopped — return to IDLE
                        ConnectionStateMachine.transition(RelayState.IDLE)
                    }
                }

            } catch (e: Exception) {
                SBLog.e(TAG, "Command processing error: ${e.message}")
                try {
                    server.sendAck(false, "Error: ${e.message}", payload["request_id"] as? String ?: "")
                } catch (_: Exception) {}
            }
        }
    }

    // ── Heartbeat Watchdog ──────────────────────────────────────────

    /**
     * Client-side watchdog: if we haven't heard from the server in 12s,
     * trigger a local emergency stop. This protects against network loss
     * where the server's dead man's switch can't reach us.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = engineScope.launch {
            while (isActive) {
                delay(3000) // Check every 3 seconds
                val sinceLastPing = SystemClock.elapsedRealtime() - lastHeartbeatTime

                // Update health info
                RelayStateHolder.updateHealth(ConnectionHealth(
                    serverConnected = server?.isConnected == true,
                    intifaceConnected = intiface?.isConnected == true,
                    intifaceHealthy = intiface?.isConnected == true,
                    lastHeartbeatAgo = sinceLastPing,
                ))

                // Key on actual device activity, not just the ACTIVE state —
                // devices can be physically running while the state machine
                // sits in COOLDOWN/ERROR, and those need the watchdog too.
                val devicesRunning = deviceManager?.hasActiveDevices == true
                if (sinceLastPing > 12_000 &&
                    (ConnectionStateMachine.currentState == RelayState.ACTIVE || devicesRunning)
                ) {
                    SBLog.safety("WATCHDOG: No server ping in ${sinceLastPing}ms — local emergency stop!")
                    val delivered = emergencyStop()
                    onNotificationUpdate(
                        if (delivered) "WATCHDOG: Lost server — stopped all"
                        else "WATCHDOG: Stop unconfirmed — Intiface link cut"
                    )
                }
            }
        }
    }

    // ── Governor / Cooldown ────────────────────────────────────────

    /**
     * Process governor state received from server heartbeat.
     * Drives the ACTIVE → COOLDOWN → IDLE transitions.
     */
    private fun handleGovernorUpdate(gov: GovernorSnapshot, dm: DeviceManager) {
        // Push to UI
        RelayStateHolder.updateGovernor(
            GovernorState(
                heatPct = gov.heatPct,
                inCooldown = gov.inCooldown,
                cooldownRemaining = gov.cooldownRemaining,
                cooldownCount = gov.cooldownCount,
                predictedSeconds = gov.predictedSeconds,
            )
        )

        // Drive state transitions based on server-side governor
        val current = ConnectionStateMachine.currentState
        if (gov.inCooldown && current == RelayState.ACTIVE) {
            ConnectionStateMachine.transition(RelayState.COOLDOWN)
            onNotificationUpdate("Cooldown (${gov.cooldownRemaining}s)")
            SBLog.i(TAG, "Governor: entering cooldown (${gov.cooldownRemaining}s)")
        } else if (!gov.inCooldown && current == RelayState.COOLDOWN) {
            ConnectionStateMachine.transition(RelayState.IDLE)
            onNotificationUpdate("Connected (${dm.deviceCount} devices)")
            SBLog.i(TAG, "Governor: cooldown ended, back to IDLE")
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun updateHealth(
        serverConnected: Boolean = server?.isConnected == true,
        intifaceConnected: Boolean = intiface?.isConnected == true,
        intifaceHealthy: Boolean = intiface?.isConnected == true,
    ) {
        RelayStateHolder.updateHealth(ConnectionHealth(
            serverConnected = serverConnected,
            intifaceConnected = intifaceConnected,
            intifaceHealthy = intifaceHealthy,
            lastHeartbeatAgo = SystemClock.elapsedRealtime() - lastHeartbeatTime,
        ))
    }
}

/**
 * Convert a JsonObject to a simple Map<String, Any?> for the PatternRunner.
 */
private fun JsonObject.toMap(): Map<String, Any?> {
    return entries.associate { (key, value) ->
        key to value.toAny()
    }
}

private fun JsonElement.toAny(): Any? {
    return when (this) {
        is JsonPrimitive -> {
            if (isString) content
            else booleanOrNull ?: intOrNull ?: longOrNull ?: floatOrNull ?: doubleOrNull ?: content
        }
        is JsonArray -> map { it.toAny() }
        is JsonObject -> toMap()
        else -> null
    }
}
