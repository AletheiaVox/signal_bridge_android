package com.signalbridge.app.relay

import kotlinx.serialization.json.*

/**
 * Raw Buttplug v3 protocol messages.
 *
 * Buttplug uses a JSON array of single-key objects:
 *   [{"RequestServerInfo": {"Id": 1, "ClientName": "...", "MessageVersion": 3}}]
 *
 * We don't use a full Buttplug library — just raw JSON, same as termux_relay_v3.py.
 */

// ── Outgoing (to Intiface) ─────────────────────────────────────────

fun buildRequestServerInfo(id: Int): String {
    val msg = buildJsonArray {
        addJsonObject {
            putJsonObject("RequestServerInfo") {
                put("Id", id)
                put("ClientName", "Signal Bridge Android")
                put("MessageVersion", 3)
            }
        }
    }
    return msg.toString()
}

fun buildStartScanning(id: Int): String {
    val msg = buildJsonArray {
        addJsonObject {
            putJsonObject("StartScanning") {
                put("Id", id)
            }
        }
    }
    return msg.toString()
}

fun buildRequestDeviceList(id: Int): String {
    val msg = buildJsonArray {
        addJsonObject {
            putJsonObject("RequestDeviceList") {
                put("Id", id)
            }
        }
    }
    return msg.toString()
}

fun buildScalarCmd(id: Int, deviceIndex: Int, scalars: List<ScalarEntry>): String {
    val msg = buildJsonArray {
        addJsonObject {
            putJsonObject("ScalarCmd") {
                put("Id", id)
                put("DeviceIndex", deviceIndex)
                putJsonArray("Scalars") {
                    for (s in scalars) {
                        addJsonObject {
                            put("Index", s.index)
                            put("Scalar", s.scalar.toDouble())
                            put("ActuatorType", s.actuatorType)
                        }
                    }
                }
            }
        }
    }
    return msg.toString()
}

fun buildStopDeviceCmd(id: Int, deviceIndex: Int): String {
    val msg = buildJsonArray {
        addJsonObject {
            putJsonObject("StopDeviceCmd") {
                put("Id", id)
                put("DeviceIndex", deviceIndex)
            }
        }
    }
    return msg.toString()
}

fun buildStopAllDevices(id: Int): String {
    val msg = buildJsonArray {
        addJsonObject {
            putJsonObject("StopAllDevices") {
                put("Id", id)
            }
        }
    }
    return msg.toString()
}

// ── Data holders ────────────────────────────────────────────────────

data class ScalarEntry(
    val index: Int,
    val scalar: Float,
    val actuatorType: String,
)

/**
 * Parsed Buttplug device info from DeviceAdded / DeviceList messages.
 */
data class ButtplugDevice(
    val deviceIndex: Int,
    val deviceName: String,
    val scalarActuators: List<ActuatorInfo>,
)

data class ActuatorInfo(
    val index: Int,
    val actuatorType: String,
    val stepCount: Int,
)

// ── Incoming (from Intiface) ────────────────────────────────────────

/**
 * Parse a Buttplug JSON array message into typed events.
 * Returns a list because Buttplug can batch multiple messages.
 */
fun parseButtplugMessages(raw: String): List<ButtplugEvent> {
    return try {
        val array = Json.parseToJsonElement(raw).jsonArray
        array.mapNotNull { element ->
            val obj = element.jsonObject
            when {
                "ServerInfo" in obj -> {
                    val info = obj["ServerInfo"]!!.jsonObject
                    ButtplugEvent.ServerInfo(
                        serverName = info["ServerName"]?.jsonPrimitive?.contentOrNull ?: "Intiface",
                        messageVersion = info["MessageVersion"]?.jsonPrimitive?.intOrNull ?: 0,
                    )
                }
                "DeviceAdded" in obj -> {
                    val dev = obj["DeviceAdded"]!!.jsonObject
                    ButtplugEvent.DeviceAdded(parseDevice(dev))
                }
                "DeviceRemoved" in obj -> {
                    val rem = obj["DeviceRemoved"]!!.jsonObject
                    ButtplugEvent.DeviceRemoved(
                        deviceIndex = rem["DeviceIndex"]?.jsonPrimitive?.intOrNull ?: -1,
                    )
                }
                "DeviceList" in obj -> {
                    val dl = obj["DeviceList"]!!.jsonObject
                    val devices = dl["Devices"]?.jsonArray?.map { devEl ->
                        parseDevice(devEl.jsonObject)
                    } ?: emptyList()
                    ButtplugEvent.DeviceList(devices)
                }
                "Ok" in obj -> ButtplugEvent.Ok
                "Error" in obj -> {
                    val err = obj["Error"]!!.jsonObject
                    ButtplugEvent.Error(
                        message = err["ErrorMessage"]?.jsonPrimitive?.contentOrNull ?: "Unknown error",
                        errorCode = err["ErrorCode"]?.jsonPrimitive?.intOrNull ?: 0,
                    )
                }
                "ScanningFinished" in obj -> ButtplugEvent.ScanningFinished
                else -> null // Unknown message type — ignore
            }
        }
    } catch (e: Exception) {
        emptyList()
    }
}

private fun parseDevice(obj: JsonObject): ButtplugDevice {
    val index = obj["DeviceIndex"]?.jsonPrimitive?.intOrNull ?: -1
    val name = obj["DeviceName"]?.jsonPrimitive?.contentOrNull ?: "Unknown"

    val actuators = mutableListOf<ActuatorInfo>()
    val scalarCmd = obj["DeviceMessages"]?.jsonObject?.get("ScalarCmd")?.jsonArray
    scalarCmd?.forEachIndexed { i, feature ->
        val featureObj = feature.jsonObject
        actuators.add(ActuatorInfo(
            index = featureObj["Index"]?.jsonPrimitive?.intOrNull ?: i,
            actuatorType = featureObj["ActuatorType"]?.jsonPrimitive?.contentOrNull ?: "Vibrate",
            stepCount = featureObj["StepCount"]?.jsonPrimitive?.intOrNull ?: 20,
        ))
    }

    return ButtplugDevice(
        deviceIndex = index,
        deviceName = name,
        scalarActuators = actuators,
    )
}

sealed class ButtplugEvent {
    data class ServerInfo(val serverName: String, val messageVersion: Int) : ButtplugEvent()
    data class DeviceAdded(val device: ButtplugDevice) : ButtplugEvent()
    data class DeviceRemoved(val deviceIndex: Int) : ButtplugEvent()
    data class DeviceList(val devices: List<ButtplugDevice>) : ButtplugEvent()
    data object Ok : ButtplugEvent()
    data class Error(val message: String, val errorCode: Int) : ButtplugEvent()
    data object ScanningFinished : ButtplugEvent()
}
