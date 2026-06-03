package com.signalbridge.app.relay

import com.signalbridge.app.util.SBLog
import kotlinx.coroutines.*
import kotlin.math.sin

/**
 * Runs timed patterns (pulse, wave, escalate) on devices via Intiface.
 *
 * Port of PatternRunner from termux_relay_v3.py.
 *
 * Uses SupervisorJob per device so cancelling one pattern doesn't affect others.
 * cancelChildren() is used for stop-all to kill all running patterns.
 */
class PatternRunner(
    private val intiface: IntifaceConnection,
    private val devices: DeviceManager,
    private val onDeviceStateChanged: ((List<com.signalbridge.app.data.DeviceInfo>) -> Unit)? = null,
) {
    private val TAG = "PatternRunner"

    // Active pattern jobs keyed by device short name
    private val activeTasks = mutableMapOf<String, Job>()

    // Parent scope for all pattern coroutines — SupervisorJob so one failure
    // doesn't cascade to others
    private val patternScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Execute a command from the server. Returns an ack payload.
     */
    suspend fun runCommand(type: String, payload: Map<String, Any?>): CommandAck {
        return when (type) {
            "command" -> handleCommand(payload)
            "pattern" -> handlePattern(payload)
            "stop" -> handleStop(payload)
            "scan" -> handleScan()
            "read_sensor" -> CommandAck(false, "Sensors not supported in Android relay", "")
            else -> CommandAck(false, "Unknown command type: $type", "")
        }
    }

    /**
     * Cancel all running patterns and stop all devices. Used for emergency stop.
     */
    suspend fun emergencyStopAll() {
        SBLog.safety("PatternRunner: emergency stop — cancelling all patterns")
        for ((name, task) in activeTasks) {
            task.cancel()
        }
        activeTasks.clear()
        devices.setAllStopped()
        notifyDeviceState()
        try {
            intiface.stopAll()
        } catch (e: Exception) {
            SBLog.e(TAG, "Failed to send StopAllDevices: ${e.message}")
        }
    }

    /**
     * Cancel patterns for a specific device.
     */
    suspend fun cancelPatterns(device: String = "all") {
        if (device == "all") {
            for ((_, task) in activeTasks) {
                task.cancel()
            }
            activeTasks.clear()
        } else {
            activeTasks.remove(device)?.cancel()
        }
    }

    fun destroy() {
        patternScope.cancel()
        activeTasks.clear()
    }

    /** Push updated device activity state to the UI. */
    private fun notifyDeviceState() {
        onDeviceStateChanged?.invoke(devices.buildDeviceInfoList())
    }

    // ── Command Handlers ────────────────────────────────────────────

    private suspend fun handleCommand(payload: Map<String, Any?>): CommandAck {
        val device = payload["device"] as? String ?: "all"
        val intensity = (payload["intensity"] as? Number)?.toFloat() ?: 0.5f
        val outputType = (payload["action"] as? String)
            ?: (payload["output_type"] as? String)
            ?: "vibrate"
        val duration = (payload["duration"] as? Number)?.toFloat() ?: 0f
        val featureIndex = (payload["feature_index"] as? Number)?.toInt()
        val requestId = payload["request_id"] as? String ?: ""

        val targets = devices.resolveTargets(device)
        if (targets.isEmpty()) {
            val available = devices.availableDevices()
            return CommandAck(false, "Device not found. Available: $available", requestId)
        }

        SBLog.i(TAG, "Command: $outputType intensity=$intensity duration=$duration feature_index=$featureIndex targets=${targets.map { it.first }}")

        for ((shortName, idx) in targets) {
            val adj = devices.applyFloor(intensity, shortName)
            SBLog.i(TAG, "  $shortName: raw=$intensity adjusted=$adj")
            intiface.scalarCmd(idx, adj, outputType, featureIndex)
            devices.setDeviceActive(shortName, intensity)
        }
        notifyDeviceState()

        val names = targets.map { it.first }

        // Auto-stop after duration
        if (duration > 0) {
            patternScope.launch {
                delay((duration * 1000).toLong())
                for ((shortName, idx) in targets) {
                    try { intiface.stopDevice(idx) } catch (_: Exception) {}
                    devices.setDeviceStopped(shortName)
                }
                notifyDeviceState()
                SBLog.i(TAG, "Auto-stopped after ${duration}s")
            }
        }

        return CommandAck(true, "Set $outputType $intensity on ${names.joinToString()}", requestId, names)
    }

    private suspend fun handlePattern(payload: Map<String, Any?>): CommandAck {
        val pattern = payload["pattern"] as? String ?: "pulse"
        val device = payload["device"] as? String ?: "all"
        val intensity = (payload["intensity"] as? Number)?.toFloat() ?: 0.6f
        val duration = (payload["duration"] as? Number)?.toFloat() ?: 10f
        val outputType = (payload["action"] as? String)
            ?: (payload["output_type"] as? String)
            ?: "vibrate"
        val hold = (payload["hold_seconds"] as? Number)?.toFloat() ?: 0f
        val featureIndex = (payload["feature_index"] as? Number)?.toInt()
        val requestId = payload["request_id"] as? String ?: ""

        val targets = devices.resolveTargets(device)
        if (targets.isEmpty()) {
            return CommandAck(false, "Device not found", requestId)
        }

        for ((shortName, idx) in targets) {
            // Cancel any existing pattern on this device
            cancelPatterns(shortName)

            val floor = devices.getIntensityFloor(shortName)
            devices.setDeviceActive(shortName, intensity)

            val task = when (pattern) {
                "pulse" -> patternScope.launch {
                    try {
                        runPulse(idx, outputType, intensity, duration, floor, shortName, featureIndex)
                    } finally {
                        devices.setDeviceStopped(shortName)
                        notifyDeviceState()
                    }
                }
                "wave" -> patternScope.launch {
                    try {
                        runWave(idx, outputType, intensity, duration, floor, shortName, featureIndex)
                    } finally {
                        devices.setDeviceStopped(shortName)
                        notifyDeviceState()
                    }
                }
                "escalate" -> patternScope.launch {
                    try {
                        runEscalate(idx, outputType, intensity, duration, hold, floor, shortName, featureIndex)
                    } finally {
                        devices.setDeviceStopped(shortName)
                        notifyDeviceState()
                    }
                }
                else -> return CommandAck(false, "Unknown pattern: $pattern", requestId)
            }
            activeTasks[shortName] = task
        }
        notifyDeviceState()

        val names = targets.map { it.first }
        return CommandAck(true, "Pattern $pattern started on ${names.joinToString()}", requestId, names)
    }

    private suspend fun handleStop(payload: Map<String, Any?>): CommandAck {
        val device = payload["device"] as? String ?: "all"
        val requestId = payload["request_id"] as? String ?: ""

        var targets = devices.resolveTargets(device)
        var fallback = false

        // Safety: if device not found, stop ALL as fallback
        if (device != "all" && targets.isEmpty()) {
            fallback = true
            targets = devices.resolveTargets("all")
        }

        for ((shortName, idx) in targets) {
            cancelPatterns(shortName)
            intiface.stopDevice(idx)
            devices.setDeviceStopped(shortName)
        }

        if (device == "all") {
            intiface.stopAll()
            activeTasks.clear()
            devices.setAllStopped()
        }
        notifyDeviceState()

        val names = targets.map { it.first }

        return if (fallback) {
            val available = devices.availableDevices().joinToString()
            CommandAck(true, "Unknown device — stopped ALL as safety fallback. Available: $available", requestId, names)
        } else {
            CommandAck(true, "Stopped ${names.joinToString().ifEmpty { "all" }}", requestId, names)
        }
    }

    private suspend fun handleScan(): CommandAck {
        intiface.scan(duration = 5000)
        return CommandAck(true, "Scan complete — ${devices.deviceCount} device(s)", "")
    }

    // ── Pattern Implementations ─────────────────────────────────────

    private suspend fun runPulse(idx: Int, outputType: String, intensity: Float, duration: Float, floor: Float, shortName: String, featureIndex: Int? = null) {
        try {
            val startTime = System.currentTimeMillis()
            var on = true
            // duration <= 0 = run indefinitely until an explicit stop cancels this job,
            // matching how plain commands treat duration=0 (stay on until stop).
            while (duration <= 0f || System.currentTimeMillis() - startTime < duration * 1000) {
                if (on) {
                    val adj = applyFloor(intensity, floor)
                    intiface.scalarCmd(idx, adj, outputType, featureIndex)
                    devices.setDeviceActive(shortName, intensity)
                } else {
                    intiface.scalarCmd(idx, 0f, outputType, featureIndex)
                    devices.setDeviceActive(shortName, 0.01f) // still "active" during off-phase
                }
                on = !on
                delay(400)
            }
        } catch (_: CancellationException) {
        } finally {
            try { intiface.stopDevice(idx) } catch (_: Exception) {}
        }
    }

    private suspend fun runWave(idx: Int, outputType: String, intensity: Float, duration: Float, floor: Float, shortName: String, featureIndex: Int? = null) {
        try {
            val startTime = System.currentTimeMillis()
            // duration <= 0 = run indefinitely until an explicit stop cancels this job.
            while (duration <= 0f || System.currentTimeMillis() - startTime < duration * 1000) {
                val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                val raw = ((sin(elapsed * 2.0) + 1.0) / 2.0 * intensity).toFloat()
                val adj = applyFloor(raw, floor)
                intiface.scalarCmd(idx, adj, outputType, featureIndex)
                devices.setDeviceActive(shortName, raw)
                delay(100)
            }
        } catch (_: CancellationException) {
        } finally {
            try { intiface.stopDevice(idx) } catch (_: Exception) {}
        }
    }

    private suspend fun runEscalate(idx: Int, outputType: String, peak: Float, duration: Float, hold: Float, floor: Float, shortName: String, featureIndex: Int? = null) {
        try {
            val steps = 20
            for (i in 0..steps) {
                val raw = (i.toFloat() / steps) * peak
                val adj = applyFloor(raw, floor)
                intiface.scalarCmd(idx, adj, outputType, featureIndex)
                devices.setDeviceActive(shortName, raw)
                delay((duration * 1000 / steps).toLong())
            }
            devices.setDeviceActive(shortName, peak)
            if (hold > 0) {
                delay((hold * 1000).toLong())
            }
            intiface.stopDevice(idx)
        } catch (_: CancellationException) {
            try { intiface.stopDevice(idx) } catch (_: Exception) {}
        }
    }

    private fun applyFloor(raw: Float, floor: Float): Float {
        if (raw <= 0.01f) return 0f
        return if (floor > 0f) {
            (floor + raw * (1f - floor)).coerceIn(0f, 1f)
        } else {
            raw.coerceIn(0f, 1f)
        }
    }
}

/**
 * Ack result from command processing.
 */
data class CommandAck(
    val success: Boolean,
    val message: String,
    val requestId: String,
    val devicesAffected: List<String> = emptyList(),
)
