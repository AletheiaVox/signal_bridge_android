package com.signalbridge.app.relay

import android.os.SystemClock
import com.signalbridge.app.util.SBLog
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebSocket connection to Intiface Central running on the same device.
 *
 * Handles:
 *  - Buttplug v3 handshake (RequestServerInfo)
 *  - Device scanning + tracking (DeviceAdded, DeviceRemoved, DeviceList)
 *  - Sending ScalarCmd / StopDeviceCmd / StopAllDevices
 *  - Drain task that reads all incoming Intiface messages
 *  - Health ping (RequestDeviceList every 15s to detect frozen Intiface)
 */
class IntifaceConnection(
    private val url: String = "ws://127.0.0.1:12345",
) {
    private val TAG = "IntifaceConn"

    // Bounds so a half-open / stalled socket surfaces as a retryable error instead of
    // hanging connect() forever (which no retry ceiling could rescue).
    private val CONNECT_TIMEOUT_MS = 8_000L
    private val HANDSHAKE_TIMEOUT_MS = 5_000L
    private val PING_RESPONSE_TIMEOUT_MS = 5_000L

    private val client = HttpClient(OkHttp) {
        install(WebSockets)
    }

    private var session: DefaultWebSocketSession? = null
    private var drainJob: Job? = null
    private var healthPingJob: Job? = null
    private val sendMutex = Mutex()
    private val msgId = AtomicInteger(0)

    // Devices tracked from Buttplug events
    private val _devices = mutableMapOf<Int, ButtplugDevice>()
    val devices: Map<Int, ButtplugDevice> get() = _devices.toMap()

    // Channel for the engine to receive device events
    val deviceEvents = Channel<ButtplugEvent>(Channel.BUFFERED)

    var isConnected: Boolean = false
        private set

    /**
     * Completed when the connection is lost — drain task exit, health-ping
     * freeze detection, forceClose(), or close(). The engine awaits this to
     * trigger a full reconnect. Fixes the "Intiface dies but the relay never
     * notices" failure mode (the relay loop previously only watched the
     * server connection).
     */
    val closedSignal = CompletableDeferred<Unit>()

    /**
     * Timestamp of the last frame received from Intiface (freeze detection).
     * SystemClock.elapsedRealtime, never currentTimeMillis — the wall clock jumps
     * on NTP sync, and a backward jump made a healthy connection look frozen.
     */
    @Volatile
    private var lastRxTime = 0L

    private fun nextId(): Int = msgId.incrementAndGet()

    /**
     * Connect to Intiface and perform the Buttplug v3 handshake.
     * Throws on failure.
     */
    suspend fun connect() {
        SBLog.i(TAG, "Connecting to Intiface at $url")

        val ws = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { client.webSocketSession(url) }
            ?: throw Exception("Timed out opening WebSocket to Intiface ($url)")
        session = ws
        isConnected = true
        lastRxTime = SystemClock.elapsedRealtime()

        // Buttplug handshake: RequestServerInfo
        send(buildRequestServerInfo(nextId()))

        // Read handshake response — bounded so a silent socket becomes a retryable error.
        val frame = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { ws.incoming.receive() }
        if (frame == null) {
            try { ws.close(CloseReason(CloseReason.Codes.NORMAL, "handshake timeout")) } catch (_: Exception) {}
            session = null
            isConnected = false
            throw Exception("Intiface handshake timed out — no ServerInfo received")
        }
        if (frame is Frame.Text) {
            val events = parseButtplugMessages(frame.readText())
            for (event in events) {
                when (event) {
                    is ButtplugEvent.ServerInfo -> {
                        SBLog.i(TAG, "Connected to ${event.serverName} (protocol v${event.messageVersion})")
                    }
                    is ButtplugEvent.Error -> {
                        throw Exception("Buttplug handshake error: ${event.message}")
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * Start the background drain task that reads all Intiface messages.
     * Device events are forwarded to the deviceEvents channel.
     */
    fun startDrain(scope: CoroutineScope) {
        drainJob = scope.launch {
            try {
                val ws = session ?: return@launch
                for (frame in ws.incoming) {
                    lastRxTime = SystemClock.elapsedRealtime()
                    if (frame is Frame.Text) {
                        val events = parseButtplugMessages(frame.readText())
                        for (event in events) {
                            handleEvent(event)
                        }
                    }
                }
            } catch (e: ClosedReceiveChannelException) {
                SBLog.i(TAG, "Intiface WebSocket closed")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SBLog.e(TAG, "Drain error: ${e.message}")
            } finally {
                isConnected = false
                // Signal the engine so it can tear down + reconnect.
                closedSignal.complete(Unit)
            }
        }
    }

    /**
     * Start the health ping — sends RequestDeviceList every 15s and verifies
     * that *something* comes back. A frozen Intiface on localhost will happily
     * accept sends into the TCP buffer forever, so send success alone proves
     * nothing; only received frames do. On failure (send error or no response)
     * the connection is force-closed, which completes [closedSignal] and lets
     * the engine reconnect.
     */
    fun startHealthPing(scope: CoroutineScope) {
        healthPingJob = scope.launch {
            while (isActive) {
                delay(15_000)
                val pingSentAt = SystemClock.elapsedRealtime()
                try {
                    send(buildRequestDeviceList(nextId()))
                } catch (e: Exception) {
                    SBLog.e(TAG, "Health ping send failed: ${e.message} — closing connection")
                    forceClose()
                    break
                }
                // RequestDeviceList always produces a DeviceList response. Poll for
                // it instead of a single check at +5s: after a scheduling stall
                // (Doze, app freezer, GC) the response can be sitting in the socket
                // buffer while this coroutine wakes before the drain task — a
                // one-shot check lost that race and killed a healthy connection.
                var responded = lastRxTime >= pingSentAt
                while (!responded && SystemClock.elapsedRealtime() - pingSentAt < PING_RESPONSE_TIMEOUT_MS) {
                    delay(500)
                    responded = lastRxTime >= pingSentAt
                }
                if (!responded) {
                    // Grace pass: if the whole timeout elapsed while the process was
                    // suspended, give the drain task one beat to catch up.
                    delay(1_000)
                    responded = lastRxTime >= pingSentAt
                }
                if (!responded) {
                    SBLog.e(TAG, "Health ping got no response in ${PING_RESPONSE_TIMEOUT_MS}ms — Intiface frozen? Closing connection")
                    forceClose()
                    break
                }
            }
        }
    }

    /**
     * Scan for devices. Sends StartScanning, waits, then requests device list.
     */
    suspend fun scan(duration: Long = 5000) {
        SBLog.i(TAG, "Scanning for devices...")
        send(buildStartScanning(nextId()))
        delay(duration)
        send(buildRequestDeviceList(nextId()))
        delay(1000) // Give Intiface time to respond with DeviceList
        SBLog.i(TAG, "Scan complete — ${_devices.size} device(s) found")
    }

    /**
     * Send a ScalarCmd to a device (vibrate, rotate, etc.)
     *
     * @param featureIndex optional actuator index to target a specific motor.
     *   When null, all actuators matching [actuatorType] are driven together.
     *   Useful for dual-motor devices like Dolce (0 = internal, 1 = external).
     */
    suspend fun scalarCmd(
        deviceIndex: Int,
        intensity: Float,
        actuatorType: String = "Vibrate",
        featureIndex: Int? = null,
    ) {
        val device = _devices[deviceIndex] ?: return
        val scalars = mutableListOf<ScalarEntry>()

        // Find matching actuators, optionally filtered by feature index
        for (actuator in device.scalarActuators) {
            if (actuator.actuatorType.equals(actuatorType, ignoreCase = true)) {
                if (featureIndex != null && actuator.index != featureIndex) continue
                scalars.add(ScalarEntry(
                    index = actuator.index,
                    scalar = intensity.coerceIn(0f, 1f),
                    actuatorType = actuator.actuatorType,
                ))
            }
        }

        // Fallback: if no matching actuator found, use requested index (or 0)
        if (scalars.isEmpty()) {
            scalars.add(ScalarEntry(
                index = featureIndex ?: 0,
                scalar = intensity.coerceIn(0f, 1f),
                actuatorType = actuatorType,
            ))
        }

        send(buildScalarCmd(nextId(), deviceIndex, scalars))
    }

    /**
     * Stop a single device.
     */
    suspend fun stopDevice(deviceIndex: Int) {
        send(buildStopDeviceCmd(nextId(), deviceIndex))
    }

    /**
     * Emergency: stop ALL devices.
     */
    suspend fun stopAll() {
        send(buildStopAllDevices(nextId()))
    }

    /**
     * Close the connection gracefully.
     */
    suspend fun close() {
        healthPingJob?.cancel()
        drainJob?.cancel()
        try {
            session?.close(CloseReason(CloseReason.Codes.NORMAL, "Disconnecting"))
        } catch (_: Exception) {}
        session = null
        isConnected = false
        _devices.clear()
        closedSignal.complete(Unit)
        deviceEvents.close() // ends any engine coroutine consuming device events
        // Release OkHttp threads/sockets — previously leaked (one client per
        // connection attempt), which compounded across reconnect retries.
        try { client.close() } catch (_: Exception) {}
    }

    /**
     * Hard-close the connection immediately (no close handshake).
     *
     * Safety note: Intiface stops ALL devices itself when its client
     * disconnects, so when a stop command can't be delivered over a dead or
     * frozen socket, cutting the connection IS the stop signal. This is the
     * last-resort failsafe used by the engine's emergency stop.
     */
    fun forceClose() {
        SBLog.safety("IntifaceConn: force-closing connection (failsafe — Intiface stops devices on client disconnect)")
        isConnected = false
        try { session?.cancel() } catch (_: Exception) {}
        session = null
        closedSignal.complete(Unit)
        deviceEvents.close()
        try { client.close() } catch (_: Exception) {}
    }

    // ── Internal ────────────────────────────────────────────────────

    private fun handleEvent(event: ButtplugEvent) {
        when (event) {
            is ButtplugEvent.DeviceAdded -> {
                _devices[event.device.deviceIndex] = event.device
                SBLog.i(TAG, "Device added: ${event.device.deviceName} (index ${event.device.deviceIndex})")
                deviceEvents.trySend(event)
            }
            is ButtplugEvent.DeviceRemoved -> {
                val removed = _devices.remove(event.deviceIndex)
                SBLog.i(TAG, "Device removed: ${removed?.deviceName ?: "index ${event.deviceIndex}"}")
                deviceEvents.trySend(event)
            }
            is ButtplugEvent.DeviceList -> {
                for (dev in event.devices) {
                    _devices[dev.deviceIndex] = dev
                }
                SBLog.i(TAG, "Device list updated: ${_devices.size} device(s)")
                deviceEvents.trySend(event)
            }
            is ButtplugEvent.Error -> {
                SBLog.e(TAG, "Buttplug error: ${event.message} (code ${event.errorCode})")
            }
            is ButtplugEvent.ScanningFinished -> {
                SBLog.i(TAG, "Scanning finished")
            }
            else -> {} // Ok, ServerInfo — no action needed post-handshake
        }
    }

    private suspend fun send(message: String) {
        // Throw instead of silently no-opping on a missing session — a silent
        // no-op here meant StopAllDevices could "succeed" without ever being
        // sent. Callers (engine emergency stop) need to KNOW delivery failed.
        val ws = session ?: throw IllegalStateException("Intiface not connected")
        sendMutex.withLock {
            ws.send(Frame.Text(message))
        }
    }
}
