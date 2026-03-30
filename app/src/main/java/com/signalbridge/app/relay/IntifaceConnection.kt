package com.signalbridge.app.relay

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

    private fun nextId(): Int = msgId.incrementAndGet()

    /**
     * Connect to Intiface and perform the Buttplug v3 handshake.
     * Throws on failure.
     */
    suspend fun connect() {
        SBLog.i(TAG, "Connecting to Intiface at $url")

        val ws = client.webSocketSession(url)
        session = ws
        isConnected = true

        // Buttplug handshake: RequestServerInfo
        send(buildRequestServerInfo(nextId()))

        // Read handshake response
        val frame = ws.incoming.receive()
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
            }
        }
    }

    /**
     * Start the health ping — sends RequestDeviceList every 15s.
     * If Intiface is frozen/dead, the WebSocket will eventually error out.
     */
    fun startHealthPing(scope: CoroutineScope) {
        healthPingJob = scope.launch {
            while (isActive) {
                delay(15_000)
                try {
                    send(buildRequestDeviceList(nextId()))
                } catch (e: Exception) {
                    SBLog.e(TAG, "Health ping failed: ${e.message}")
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
     */
    suspend fun scalarCmd(deviceIndex: Int, intensity: Float, actuatorType: String = "Vibrate") {
        val device = _devices[deviceIndex] ?: return
        val scalars = mutableListOf<ScalarEntry>()

        // Find matching actuators
        for (actuator in device.scalarActuators) {
            if (actuator.actuatorType.equals(actuatorType, ignoreCase = true)) {
                scalars.add(ScalarEntry(
                    index = actuator.index,
                    scalar = intensity.coerceIn(0f, 1f),
                    actuatorType = actuator.actuatorType,
                ))
            }
        }

        // Fallback: if no matching actuator found, use index 0
        if (scalars.isEmpty()) {
            scalars.add(ScalarEntry(
                index = 0,
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
        sendMutex.withLock {
            session?.send(Frame.Text(message))
        }
    }
}
