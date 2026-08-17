package com.avni.airpodscontrol.model

enum class ConnectionPhase { IDLE, SCANNING, NEARBY, CONNECTED, ERROR }

data class AirPodsState(
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val bluetoothEnabled: Boolean = false,
    val pairedAirPodsName: String? = null,
    val pairedAirPodsAddress: String? = null,
    val lastSeenName: String? = null,
    val lastSeenAddress: String? = null,
    val rssi: Int? = null,
    val leftBattery: Int? = null,
    val rightBattery: Int? = null,
    val caseBattery: Int? = null,
    val leftCharging: Boolean? = null,
    val rightCharging: Boolean? = null,
    val caseCharging: Boolean? = null,
    val rawManufacturerData: String? = null,
    val rawScanRecord: String? = null,
    val appleFrameType: Int? = null,
    val appleFrameLength: Int? = null,
    val appleFrameLikelyAirPods: Boolean = false,
    val rejectedAppleFrames: Int = 0,
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
