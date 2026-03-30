package com.signalbridge.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Relay state machine — shared between service and UI.
 *
 * DISCONNECTED → CONNECTING → IDLE → ACTIVE → COOLDOWN
 *      ↑              ↓        ↓       ↓         ↓
 *      └──────── ERROR ←───────┴───────┴─────────┘
 *
 * Key transition rule: CONNECTING → IDLE (never auto-resume to ACTIVE).
 */
enum class RelayState {
    DISCONNECTED,
    CONNECTING,
    IDLE,
    ACTIVE,
    COOLDOWN,
    ERROR,
}

/**
 * Device info as reported by Intiface via Buttplug protocol.
 */
data class DeviceInfo(
    val shortName: String,
    val displayName: String,
    val capabilities: Map<String, String>,
    val intensityFloor: Float,
    val isActive: Boolean = false,
    val currentIntensity: Float = 0f,
)

/**
 * Governor state piggybacked on heartbeat pings from server.
 */
data class GovernorState(
    val heatPct: Float = 0f,
    val inCooldown: Boolean = false,
    val cooldownRemaining: Int = 0,
    val cooldownCount: Int = 0,
    val predictedSeconds: Int? = null,  // "~Xs at current intensity"
)

/**
 * Connection health details for the Dashboard.
 */
data class ConnectionHealth(
    val serverConnected: Boolean = false,
    val intifaceConnected: Boolean = false,
    val intifaceHealthy: Boolean = false,
    val lastHeartbeatAgo: Long = 0L,  // ms since last ping received
)

/**
 * Single source of truth for the relay UI state.
 * The RelayService writes to this; the UI reads from it.
 */
object RelayStateHolder {

    private val _state = MutableStateFlow(RelayState.DISCONNECTED)
    val state: StateFlow<RelayState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val devices: StateFlow<List<DeviceInfo>> = _devices.asStateFlow()

    private val _governor = MutableStateFlow(GovernorState())
    val governor: StateFlow<GovernorState> = _governor.asStateFlow()

    private val _health = MutableStateFlow(ConnectionHealth())
    val health: StateFlow<ConnectionHealth> = _health.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun updateState(newState: RelayState) {
        _state.value = newState
    }

    fun updateDevices(newDevices: List<DeviceInfo>) {
        _devices.value = newDevices
    }

    fun updateGovernor(newGovernor: GovernorState) {
        _governor.value = newGovernor
    }

    fun updateHealth(newHealth: ConnectionHealth) {
        _health.value = newHealth
    }

    fun setError(message: String?) {
        _error.value = message
    }

    fun reset() {
        _state.value = RelayState.DISCONNECTED
        _devices.value = emptyList()
        _governor.value = GovernorState()
        _health.value = ConnectionHealth()
        _error.value = null
    }
}
