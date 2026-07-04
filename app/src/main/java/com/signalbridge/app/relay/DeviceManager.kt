package com.signalbridge.app.relay

import com.signalbridge.app.data.DeviceInfo
import com.signalbridge.app.data.DeviceProfile
import com.signalbridge.app.data.BUILT_IN_PROFILES
import com.signalbridge.app.data.matchDeviceProfile
import com.signalbridge.app.util.SBLog

/**
 * Maps Buttplug device indices to Signal Bridge short names and profiles.
 * Mirrors the name_map / profiles logic from termux_relay_v3.py.
 */
class DeviceManager {
    private val TAG = "DeviceManager"

    // short_name → Buttplug device index
    private val nameMap = mutableMapOf<String, Int>()

    // Buttplug device index → ButtplugDevice info
    private val bpDevices = mutableMapOf<Int, ButtplugDevice>()

    // short_name → DeviceProfile (matched from built-in profiles)
    private val matchedProfiles = mutableMapOf<String, DeviceProfile>()

    // ── Activity tracking (drives UI intensity display) ──────────
    // short_name → current intensity (0f = idle)
    private val activeIntensity = mutableMapOf<String, Float>()

    /**
     * Register a device discovered via Buttplug.
     */
    fun addDevice(device: ButtplugDevice) {
        bpDevices[device.deviceIndex] = device

        val profile = matchDeviceProfile(device.deviceName)
        val base = profile?.shortName
            ?: device.deviceName.lowercase().replace(" ", "_")

        // If the name is already taken by a DIFFERENT device (two Lushes!),
        // suffix it: lush, lush_2, lush_3… Re-adding the same device (e.g.
        // from a DeviceList refresh) keeps its existing name.
        var shortName = base
        var n = 2
        while (nameMap[shortName] != null && nameMap[shortName] != device.deviceIndex) {
            shortName = "${base}_${n++}"
        }

        nameMap[shortName] = device.deviceIndex
        if (profile != null) {
            matchedProfiles[shortName] = profile
        }

        SBLog.i(TAG, "Mapped: ${device.deviceName} → '$shortName' (index ${device.deviceIndex})")
    }

    /**
     * Remove a device by Buttplug index.
     */
    fun removeDevice(deviceIndex: Int) {
        val shortName = nameMap.entries.find { it.value == deviceIndex }?.key
        if (shortName != null) {
            nameMap.remove(shortName)
            matchedProfiles.remove(shortName)
        }
        bpDevices.remove(deviceIndex)
        SBLog.i(TAG, "Removed device index $deviceIndex (was '$shortName')")
    }

    /**
     * Clear all device mappings (on disconnect/reconnect).
     */
    fun clear() {
        nameMap.clear()
        bpDevices.clear()
        matchedProfiles.clear()
    }

    /**
     * Resolve a device name ("ferri", "all", etc.) to (shortName, buttplugIndex) pairs.
     */
    fun resolveTargets(device: String): List<Pair<String, Int>> {
        if (device == "all") {
            return nameMap.entries.map { it.key to it.value }
        }
        val index = nameMap[device] ?: return emptyList()
        return listOf(device to index)
    }

    /**
     * Get the intensity floor for a device (from profile, or 0).
     */
    fun getIntensityFloor(shortName: String): Float {
        return matchedProfiles[shortName]?.intensityFloor ?: 0f
    }

    /**
     * Apply intensity floor: maps raw 0..1 to floor..1 range.
     * Zero input stays zero (off is off).
     */
    fun applyFloor(raw: Float, shortName: String): Float {
        if (raw <= 0.01f) return 0f
        val floor = getIntensityFloor(shortName)
        return if (floor > 0f) {
            (floor + raw * (1f - floor)).coerceIn(0f, 1f)
        } else {
            raw.coerceIn(0f, 1f)
        }
    }

    /**
     * Get list of available device short names.
     */
    fun availableDevices(): List<String> = nameMap.keys.toList()

    /**
     * Get Buttplug device index by short name.
     */
    fun getIndex(shortName: String): Int? = nameMap[shortName]

    /**
     * Build the device_list report to send to the VPS server.
     * Matches the format from termux_relay_v3.py's get_device_list().
     */
    fun buildDeviceListReport(): List<Map<String, Any>> {
        return nameMap.map { (shortName, idx) ->
            val bpDev = bpDevices[idx]
            val profile = matchedProfiles[shortName]
            val bpName = bpDev?.deviceName ?: shortName

            val capabilities = mutableMapOf<String, Map<String, String>>()

            // Get capabilities from Buttplug device info
            bpDev?.scalarActuators?.forEach { actuator ->
                val type = actuator.actuatorType.lowercase()
                if (type in listOf("vibrate", "rotate", "oscillate", "constrict")) {
                    capabilities[type] = emptyMap()
                }
            }

            // Fallback to profile capabilities if Buttplug didn't report any
            if (capabilities.isEmpty() && profile != null) {
                for (cap in profile.capabilities.keys) {
                    capabilities[cap] = emptyMap()
                }
            }

            // Friendly name: capitalized profile short name (e.g. "Lush") when matched,
            // otherwise the raw Buttplug/Bluetooth name. Never the notes/description.
            val friendlyName = profile?.shortName?.replaceFirstChar { it.uppercase() } ?: bpName

            mapOf(
                "short_name" to shortName,
                "name" to friendlyName,
                "intensity_floor" to (profile?.intensityFloor ?: 0f),
                "capabilities" to capabilities,
                "notes" to (profile?.notes ?: bpName),
            )
        }
    }

    /**
     * Build DeviceInfo list for the UI (RelayStateHolder.devices).
     */
    fun buildDeviceInfoList(): List<DeviceInfo> {
        return nameMap.map { (shortName, idx) ->
            val bpDev = bpDevices[idx]
            val profile = matchedProfiles[shortName]
            val intensity = activeIntensity[shortName] ?: 0f
            DeviceInfo(
                shortName = shortName,
                // Prefer the capitalized profile short name ("Lush") over the raw
                // Bluetooth name ("Lovense Lush"); fall back to BT name, then short name.
                displayName = profile?.shortName?.replaceFirstChar { it.uppercase() }
                    ?: bpDev?.deviceName ?: shortName,
                capabilities = profile?.capabilities ?: emptyMap(),
                intensityFloor = profile?.intensityFloor ?: 0f,
                isActive = intensity > 0f,
                currentIntensity = intensity,
            )
        }
    }

    // ── Activity tracking ───────────────────────────────────────────

    /**
     * Mark a device as active at a given intensity.
     * Call when a command or pattern step sets a new level.
     */
    fun setDeviceActive(shortName: String, intensity: Float) {
        activeIntensity[shortName] = intensity.coerceIn(0f, 1f)
    }

    /**
     * Mark a device as idle (stopped).
     */
    fun setDeviceStopped(shortName: String) {
        activeIntensity.remove(shortName)
    }

    /**
     * Mark all devices as idle.
     */
    fun setAllStopped() {
        activeIntensity.clear()
    }

    /**
     * True if any device is currently active.
     */
    val hasActiveDevices: Boolean get() = activeIntensity.isNotEmpty()

    val deviceCount: Int get() = nameMap.size
}
