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
        capabilities = mapOf("vibrate" to "external clitoral vibration"),
        intensityFloor = 0.0f,
        notes = "Small wearable. Intense even at low settings."
    ),
    DeviceProfile(
        shortName = "lush",
        matchStrings = listOf("Lush"),
        capabilities = mapOf("vibrate" to "internal egg vibration"),
        intensityFloor = 0.0f,
        notes = "Insertable egg. Strong deep vibration."
    ),
    DeviceProfile(
        shortName = "gravity",
        matchStrings = listOf("Gravity"),
        capabilities = mapOf("vibrate" to "shaft vibration", "oscillate" to "thrusting motion"),
        intensityFloor = 0.0f,
        notes = "Vibration + thrusting. Use 0.05+ intensity for slow strokes."
    ),
    DeviceProfile(
        shortName = "enigma",
        matchStrings = listOf("Enigma"),
        capabilities = mapOf("vibrate" to "G-spot thumping stimulation", "rotate" to "clitoral sonic pulse"),
        intensityFloor = 0.4f,
        notes = "Dual stimulation. 'rotate' = sonic pulse. Needs 40%+ to feel."
    ),
    DeviceProfile(
        shortName = "max",
        matchStrings = listOf("Max"),
        capabilities = mapOf("vibrate" to "internal vibration", "constrict" to "air pump compression"),
        intensityFloor = 0.0f,
        notes = "Vibration + air pump constriction."
    ),
    DeviceProfile(
        shortName = "nora",
        matchStrings = listOf("Nora"),
        capabilities = mapOf("vibrate" to "internal vibration", "rotate" to "internal rotation"),
        intensityFloor = 0.0f,
        notes = "Vibration + actual physical rotation."
    ),
    DeviceProfile(
        shortName = "edge",
        matchStrings = listOf("Edge"),
        capabilities = mapOf("vibrate" to "dual motor vibration (feature_index 0 = base, 1 = tip)"),
        intensityFloor = 0.0f,
        notes = "Prostate massager. Two vibration motors addressable via feature_index: 0 = base motor, 1 = tip motor. Omit feature_index to drive both together."
    ),
    DeviceProfile(
        shortName = "hush",
        matchStrings = listOf("Hush"),
        capabilities = mapOf("vibrate" to "vibration"),
        intensityFloor = 0.0f,
        notes = "Vibrating plug. Simple single-motor."
    ),
    DeviceProfile(
        shortName = "domi",
        matchStrings = listOf("Domi"),
        capabilities = mapOf("vibrate" to "powerful wand vibration"),
        intensityFloor = 0.0f,
        notes = "Mini wand. Very powerful. Start low."
    ),
    DeviceProfile(
        shortName = "osci",
        matchStrings = listOf("Osci"),
        capabilities = mapOf("oscillate" to "oscillating stimulation"),
        intensityFloor = 0.0f,
        notes = "Oscillating G-spot stimulator. Uses oscillate, not vibrate."
    ),
    DeviceProfile(
        shortName = "dolce",
        matchStrings = listOf("Dolce"),
        capabilities = mapOf("vibrate" to "dual vibration (feature_index 0 = internal, 1 = external)"),
        intensityFloor = 0.0f,
        notes = "Couples' vibrator. Two vibration motors addressable via feature_index: 0 = internal motor, 1 = external clitoral motor. Omit feature_index to drive both together."
    ),
    DeviceProfile(
        shortName = "flexer",
        matchStrings = listOf("Flexer"),
        capabilities = mapOf("vibrate" to "vibration", "oscillate" to "come-hither motion"),
        intensityFloor = 0.0f,
        notes = "Vibration + finger-like come-hither oscillation."
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
