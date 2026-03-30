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
import kotlinx.serialization.json.*

/**
 * WebSocket connection to the VPS server (wss://server/ws/phone).
 *
 * Handles:
 *  - JWT authentication (phone_auth message)
 *  - Sending device_list reports
 *  - Receiving commands (command, pattern, stop, scan, heartbeat_ping)
 *  - Sending heartbeat_pong responses
 *  - Sending command_ack responses
 *
 * The engine reads from [incomingCommands] and is responsible for processing them.
 */
class ServerConnection(
    private val baseUrl: String,
    private val token: String,
) {
    private val TAG = "ServerConn"

    private val client = HttpClient(OkHttp) {
        install(WebSockets)
    }

    private var session: DefaultWebSocketSession? = null
    private val sendMutex = Mutex()

    // Commands received from server, forwarded to the engine.
    // Closed when the listen loop exits (triggers engine reconnect).
    var incomingCommands = Channel<ServerMessage>(Channel.BUFFERED)
        private set

    var isConnected: Boolean = false
        private set

    /**
     * Build the WebSocket URL from the base server URL.
     * e.g., "https://signal-bridge.duckdns.org" → "wss://signal-bridge.duckdns.org/ws/phone"
     */
    private fun buildWsUrl(): String {
        val base = baseUrl.trimEnd('/')
        val wsBase = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            base.startsWith("wss://") || base.startsWith("ws://") -> base
            else -> "wss://$base"
        }
        return if (wsBase.endsWith("/ws/phone")) wsBase
        else "$wsBase/ws/phone"
    }

    /**
     * Connect to the server and authenticate.
     * Throws on connection or auth failure.
     */
    suspend fun connect() {
        val wsUrl = buildWsUrl()
        SBLog.i(TAG, "Connecting to server: $wsUrl")

        val ws = client.webSocketSession(wsUrl)
        session = ws

        // Send authentication
        val authMsg = buildJsonObject {
            put("type", "phone_auth")
            put("token", token)
        }
        send(authMsg.toString())

        // Wait for auth response
        val frame = ws.incoming.receive()
        if (frame is Frame.Text) {
            val response = Json.parseToJsonElement(frame.readText()).jsonObject
            val type = response["type"]?.jsonPrimitive?.contentOrNull

            if (type != "auth_ok") {
                val error = response["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown auth error"
                throw AuthenticationException("Authentication failed: $error")
            }

            SBLog.i(TAG, "Authenticated with server!")
            isConnected = true
        } else {
            throw AuthenticationException("Unexpected response frame type")
        }
    }

    /**
     * Start reading messages from the server in a background coroutine.
     * Heartbeat pings are auto-responded; commands are forwarded to incomingCommands.
     *
     * IMPORTANT: When this loop exits (connection lost, error, cancellation),
     * the incomingCommands channel is closed so the engine detects the disconnect
     * and can reconnect.
     */
    fun startListening(scope: CoroutineScope): Job {
        return scope.launch {
            try {
                val ws = session ?: run {
                    SBLog.e(TAG, "startListening called but session is null!")
                    return@launch
                }
                SBLog.i(TAG, "Listen loop started — waiting for server frames")
                for (frame in ws.incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            val text = frame.readText()
                            SBLog.d(TAG, "<- raw frame (${text.length} chars)")
                            handleServerMessage(text)
                        }
                        is Frame.Close -> {
                            SBLog.i(TAG, "Received Close frame from server")
                            break
                        }
                        else -> {
                            SBLog.d(TAG, "<- non-text frame: ${frame.frameType}")
                        }
                    }
                }
                SBLog.i(TAG, "Listen loop ended — incoming channel exhausted")
            } catch (e: ClosedReceiveChannelException) {
                SBLog.i(TAG, "Server WebSocket closed (channel closed)")
            } catch (e: CancellationException) {
                SBLog.i(TAG, "Listen loop cancelled")
                throw e
            } catch (e: Exception) {
                SBLog.e(TAG, "Server listen error: ${e.message}")
            } finally {
                isConnected = false
                // Close the commands channel so the engine's for-loop terminates
                // and triggers reconnection
                incomingCommands.close()
                SBLog.i(TAG, "incomingCommands channel closed — engine should reconnect")
            }
        }
    }

    /**
     * Send the device list to the server.
     */
    suspend fun sendDeviceList(devices: List<Map<String, Any>>) {
        val msg = buildJsonObject {
            put("type", "device_list")
            putJsonArray("devices") {
                for (device in devices) {
                    addJsonObject {
                        put("short_name", device["short_name"] as? String ?: "")
                        put("name", device["name"] as? String ?: "")
                        put("intensity_floor", (device["intensity_floor"] as? Number)?.toFloat() ?: 0f)
                        putJsonObject("capabilities") {
                            @Suppress("UNCHECKED_CAST")
                            val caps = device["capabilities"] as? Map<String, Any> ?: emptyMap()
                            for (cap in caps.keys) {
                                putJsonObject(cap) {}
                            }
                        }
                        put("notes", device["notes"] as? String ?: "")
                    }
                }
            }
        }
        val json = msg.toString()
        SBLog.i(TAG, "Sent device list: ${devices.size} device(s)")
        SBLog.d(TAG, "Device list JSON: $json")
        send(json)
    }

    /**
     * Send a command acknowledgement back to the server.
     */
    suspend fun sendAck(
        success: Boolean,
        message: String,
        requestId: String,
        devicesAffected: List<String> = emptyList(),
    ) {
        val msg = buildJsonObject {
            put("type", "command_ack")
            put("request_id", requestId)
            put("success", success)
            put("message", message)
            putJsonArray("devices_affected") {
                for (d in devicesAffected) add(d)
            }
        }
        send(msg.toString())
    }

    /**
     * Notify the server that the phone triggered an emergency stop.
     * This tells the server-side governor to stop accumulating heat.
     */
    suspend fun sendEmergencyStop() {
        val msg = buildJsonObject {
            put("type", "phone_emergency_stop")
        }
        send(msg.toString())
    }

    /**
     * Send a heartbeat pong.
     */
    private suspend fun sendPong() {
        val msg = buildJsonObject {
            put("type", "heartbeat_pong")
        }
        send(msg.toString())
    }

    /**
     * Close the connection.
     */
    suspend fun close() {
        try {
            session?.close(CloseReason(CloseReason.Codes.NORMAL, "Disconnecting"))
        } catch (_: Exception) {}
        session = null
        isConnected = false
    }

    // ── Internal ────────────────────────────────────────────────────

    /**
     * Callback invoked when a heartbeat ping contains governor state.
     * Set by the engine to route governor updates to RelayStateHolder.
     */
    var onGovernorUpdate: ((GovernorSnapshot) -> Unit)? = null
    var onHeartbeatReceived: (() -> Unit)? = null

    private suspend fun handleServerMessage(raw: String) {
        try {
            val msg = Json.parseToJsonElement(raw).jsonObject
            val type = msg["type"]?.jsonPrimitive?.contentOrNull ?: return

            when (type) {
                "ping", "heartbeat_ping" -> {
                    // Respond immediately — this is time-critical for the dead man's switch
                    sendPong()
                    // Notify engine so the watchdog knows we're alive
                    onHeartbeatReceived?.invoke()
                    // Extract governor data if present (piggybacked on heartbeat)
                    extractGovernor(msg)?.let { onGovernorUpdate?.invoke(it) }
                }
                "command", "pattern", "stop", "scan", "read_sensor" -> {
                    SBLog.i(TAG, "<- Server: $type (request_id=${msg["request_id"]?.jsonPrimitive?.contentOrNull ?: "none"})")
                    val result = incomingCommands.trySend(ServerMessage(type, msg))
                    if (result.isFailure) {
                        SBLog.e(TAG, "DROPPED command — channel full or closed! type=$type")
                    }
                }
                else -> {
                    SBLog.w(TAG, "Unknown server message type: $type")
                }
            }
        } catch (e: Exception) {
            SBLog.w(TAG, "Bad message from server: ${e.message}")
        }
    }

    private suspend fun send(message: String) {
        sendMutex.withLock {
            session?.send(Frame.Text(message))
        }
    }
}

/**
 * A parsed command from the server, ready for the engine to process.
 */
data class ServerMessage(
    val type: String,
    val payload: JsonObject,
)

class AuthenticationException(message: String) : Exception(message)

/**
 * Governor state snapshot extracted from a heartbeat ping.
 * Fields are all optional — older servers won't send them.
 */
data class GovernorSnapshot(
    val heatPct: Float,
    val inCooldown: Boolean,
    val cooldownRemaining: Int,
    val cooldownCount: Int,
    val predictedSeconds: Int?,
)

/**
 * Extract governor fields from a heartbeat ping if present.
 * Returns null if no governor data in the message.
 */
private fun extractGovernor(msg: JsonObject): GovernorSnapshot? {
    // Governor data is optional — servers without it just send {type, timestamp}
    val heat = msg["heat_pct"]?.jsonPrimitive?.floatOrNull ?: return null
    return GovernorSnapshot(
        heatPct = heat,
        inCooldown = msg["in_cooldown"]?.jsonPrimitive?.booleanOrNull ?: false,
        cooldownRemaining = msg["cooldown_remaining"]?.jsonPrimitive?.intOrNull ?: 0,
        cooldownCount = msg["cooldown_count"]?.jsonPrimitive?.intOrNull ?: 0,
        predictedSeconds = msg["predicted_seconds"]?.jsonPrimitive?.intOrNull,
    )
}
