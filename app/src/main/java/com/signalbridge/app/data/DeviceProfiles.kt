package com.signalbridge.app.data

/**
 * Built-in device profiles from devices.json.
 * Maps Buttplug device names to Signal Bridge short names + capabilities.
 */
data class DeviceProfile(
    val shortName: String,
    val matchStrings: List<String>,
    val capabilities: Map<String, String>,
    val intensityFloor: Float,
    val notes: String,
)

val BUILT_IN_PROFILES = listOf(
    DeviceProfile(
        shortName = "ferri",
        matchStrings = listOf("Ferri"),
        capabilities = mapOf("vibrate" to "compact eccentric-mass actuator, high response at low duty cycle"),
        intensityFloor = 0.0f,
        notes = "Small form factor. High effective output at low values; recommend starting below 0.3."
    ),
    DeviceProfile(
        shortName = "lush",
        matchStrings = listOf("Lush"),
        capabilities = mapOf("vibrate" to "single eccentric-mass actuator, low-frequency dominant"),
        intensityFloor = 0.0f,
        notes = "Compact ovoid form factor. High output efficiency at low command levels."
    ),
    DeviceProfile(
        shortName = "gravity",
        matchStrings = listOf("Gravity"),
        capabilities = mapOf(
            "vibrate" to "eccentric-mass actuator along main axis",
            "oscillate" to "linear reciprocating actuator; stroke rate scales with intensity"
        ),
        intensityFloor = 0.0f,
        notes = "Dual-mode device. Oscillate requires intensity >= 0.05 to engage; low values map to slow cycle rate."
    ),
    DeviceProfile(
        shortName = "enigma",
        matchStrings = listOf("Enigma"),
        capabilities = mapOf(
            "vibrate" to "high-amplitude pulsed actuator (impact-style, not continuous waveform)",
            "rotate" to "sonic-frequency acoustic actuator (note: maps to acoustic output, NOT mechanical rotation)"
        ),
        intensityFloor = 0.4f,
        notes = "Two independent actuators. Threshold response: effective activation threshold ≈0.4. Rotate channel is acoustic, not rotational."
    ),
    DeviceProfile(
        shortName = "max",
        matchStrings = listOf("Max"),
        capabilities = mapOf(
            "vibrate" to "eccentric-mass actuator",
            "constrict" to "pneumatic compression actuator; intensity maps to chamber pressure"
        ),
        intensityFloor = 0.0f,
        notes = "Two independent actuators. Constrict is air-pump driven; intensity = compression force."
    ),
    DeviceProfile(
        shortName = "nora",
        matchStrings = listOf("Nora"),
        capabilities = mapOf(
            "vibrate" to "eccentric-mass actuator",
            "rotate" to "mechanical rotational actuator (true rotation, distinct from enigma's acoustic channel)"
        ),
        intensityFloor = 0.0f,
        notes = "Two independent actuators. Rotate is mechanical, not acoustic."
    ),
    DeviceProfile(
        shortName = "edge",
        matchStrings = listOf("Edge"),
        capabilities = mapOf("vibrate" to "two independent eccentric-mass actuators; address individually via feature_index"),
        intensityFloor = 0.0f,
        notes = "Dual-actuator device. Use feature_index 0 or 1 to drive motors separately."
    ),
    DeviceProfile(
        shortName = "hush",
        matchStrings = listOf("Hush"),
        capabilities = mapOf("vibrate" to "single eccentric-mass actuator"),
        intensityFloor = 0.0f,
        notes = "Single-actuator device, no quirks."
    ),
    DeviceProfile(
        shortName = "domi",
        matchStrings = listOf("Domi"),
        capabilities = mapOf("vibrate" to "high-output eccentric-mass actuator, broad-spectrum"),
        intensityFloor = 0.0f,
        notes = "High maximum output relative to other devices in this set. Typical operating range: 0.05–0.30."
    ),
    DeviceProfile(
        shortName = "osci",
        matchStrings = listOf("Osci"),
        capabilities = mapOf("oscillate" to "linear reciprocating actuator (no vibrate channel)"),
        intensityFloor = 0.0f,
        notes = "Single-channel device. Responds only to oscillate, not vibrate."
    ),
    DeviceProfile(
        shortName = "dolce",
        matchStrings = listOf("Dolce"),
        capabilities = mapOf("vibrate" to "two independent eccentric-mass actuators; address individually via feature_index"),
        intensityFloor = 0.0f,
        notes = "Dual-actuator device. Use feature_index 0 or 1 to drive motors separately."
    ),
    DeviceProfile(
        shortName = "flexer",
        matchStrings = listOf("Flexer"),
        capabilities = mapOf(
            "vibrate" to "eccentric-mass actuator",
            "oscillate" to "articulated curling actuator (pivots rather than reciprocates linearly)"
        ),
        intensityFloor = 0.0f,
        notes = "Two independent actuators. Oscillate uses curling articulation, not linear stroke."
    ),
)

/**
 * Match a Buttplug device name (e.g., "Lovense Ferri") to a profile short name.
 * Returns the profile or null if no match.
 */
fun matchDeviceProfile(buttplugName: String): DeviceProfile? {
    val lower = buttplugName.lowercase()
    return BUILT_IN_PROFILES.find { profile ->
        profile.matchStrings.any { match -> lower.contains(match.lowercase()) }
    }
}
