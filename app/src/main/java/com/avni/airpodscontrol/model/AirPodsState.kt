package com.avni.airpodscontrol.model

enum class ConnectionPhase { IDLE, SCANNING, NEARBY, CONNECTED, ERROR }

data class NearbyAirPods(
    val address: String,
    val modelId: Int,
    val modelName: String,
    val rssi: Int,
    val leftBattery: Int?,
    val rightBattery: Int?,
    val caseBattery: Int?,
    val leftCharging: Boolean,
    val rightCharging: Boolean,
    val caseCharging: Boolean,
    val lidOpen: Boolean?,
    val pairedBroadcast: Boolean,
    val connectionState: String,
    val lastSeenAt: Long
)

data class AirPodsState(
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val bluetoothEnabled: Boolean = false,
    val pairedAirPodsName: String? = null,
    val pairedAirPodsAddress: String? = null,
    val lastSeenName: String? = null,
    val lastSeenAddress: String? = null,
    val detectedModelName: String? = null,
    val detectedModelId: Int? = null,
    val rssi: Int? = null,
    val leftBattery: Int? = null,
    val rightBattery: Int? = null,
    val caseBattery: Int? = null,
    val leftCharging: Boolean? = null,
    val rightCharging: Boolean? = null,
    val caseCharging: Boolean? = null,
    val lidOpen: Boolean? = null,
    val rawManufacturerData: String? = null,
    val rawScanRecord: String? = null,
    val appleFrameType: Int? = null,
    val appleFrameLength: Int? = null,
    val appleFrameLikelyAirPods: Boolean = false,
    val rejectedAppleFrames: Int = 0,
    val nearbyDevices: List<NearbyAirPods> = emptyList(),
    val lastSeenAt: Long? = null,
    val monitorRunning: Boolean = false,
    val aclConnected: Boolean = false,
    val aclTransport: String? = null,
    val a2dpConnected: Boolean = false,
    val headsetConnected: Boolean = false,
    val discoveredUuids: String? = null,
    val overlayEnabled: Boolean = false,
    val message: String = ""
) {
    val effectivelyConnected: Boolean
        get() = aclConnected || a2dpConnected || headsetConnected
}
