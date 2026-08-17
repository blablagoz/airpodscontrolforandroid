package com.avni.airpodscontrol.bluetooth

/**
 * Clean-room parser based on the publicly documented Apple Proximity Pairing
 * message layout. Apple company id (0x004C) is stripped by Android before this
 * parser receives the byte array.
 */
object AirPodsPacketParser {
    data class Parsed(
        val isLikelyAirPods: Boolean,
        val frameType: Int?,
        val declaredLength: Int?,
        val pairedBroadcast: Boolean = false,
        val modelId: Int? = null,
        val modelName: String? = null,
        val leftBattery: Int? = null,
        val rightBattery: Int? = null,
        val caseBattery: Int? = null,
        val leftCharging: Boolean = false,
        val rightCharging: Boolean = false,
        val caseCharging: Boolean = false,
        val lidOpen: Boolean? = null,
        val connectionState: String = "Unknown",
        val rawHex: String
    )

    fun parse(data: ByteArray): Parsed {
        val raw = data.toHex()
        val type = data.getOrNull(0)?.u8()
        val declaredLength = data.getOrNull(1)?.u8()

        if (type != PROXIMITY_TYPE || data.size < MIN_BYTES) {
            return Parsed(false, type, declaredLength, rawHex = raw)
        }

        val modelId = (data[3].u8() shl 8) or data[4].u8()
        val modelName = MODELS[modelId] ?: "Apple Audio 0x%04X".format(modelId)
        // Reject unrelated 0x07 Apple payloads unless the model family looks
        // like a known AirPods/Beats proximity model.
        val modelKnown = MODELS.containsKey(modelId)
        if (!modelKnown) {
            return Parsed(false, type, declaredLength, modelId = modelId, modelName = modelName, rawHex = raw)
        }

        val status = data[5].u8()
        val podBattery = data[6].u8()
        val caseAndFlags = data[7].u8()
        val lid = data[8].u8()
        val connection = data[10].u8()

        val upperPod = batteryNibble((podBattery ushr 4) and 0x0F)
        val lowerPod = batteryNibble(podBattery and 0x0F)
        val primaryLeft = (status and 0x20) != 0

        // Public protocol docs identify byte 6 as the two pod battery nibbles,
        // with primary-pod status determining their orientation.
        val left = if (primaryLeft) upperPod else lowerPod
        val right = if (primaryLeft) lowerPod else upperPod

        // Byte 7: upper nibble = case battery; lower bits = charge flags.
        val caseBattery = batteryNibble((caseAndFlags ushr 4) and 0x0F)
        val flags = caseAndFlags and 0x0F
        val rawRightCharging = (flags and 0x01) != 0
        val rawLeftCharging = (flags and 0x02) != 0
        val leftCharging = if (primaryLeft) rawLeftCharging else rawRightCharging
        val rightCharging = if (primaryLeft) rawRightCharging else rawLeftCharging
        val caseCharging = (flags and 0x04) != 0

        return Parsed(
            isLikelyAirPods = true,
            frameType = type,
            declaredLength = declaredLength,
            pairedBroadcast = data[2].u8() == 0x01,
            modelId = modelId,
            modelName = modelName,
            leftBattery = left,
            rightBattery = right,
            caseBattery = caseBattery,
            leftCharging = leftCharging,
            rightCharging = rightCharging,
            caseCharging = caseCharging,
            lidOpen = (lid and 0x08) == 0,
            connectionState = CONNECTION_STATES[connection] ?: "0x%02X".format(connection),
            rawHex = raw
        )
    }

    fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
    private fun Byte.u8() = toInt() and 0xFF

    private fun batteryNibble(value: Int): Int? = when (value) {
        0x0F -> null
        in 0x00..0x09 -> value * 10
        in 0x0A..0x0E -> 100
        else -> null
    }

    private const val PROXIMITY_TYPE = 0x07
    private const val MIN_BYTES = 11

    private val CONNECTION_STATES = mapOf(
        0x00 to "Disconnected",
        0x04 to "Idle",
        0x05 to "Music",
        0x06 to "Call",
        0x07 to "Ringing",
        0x09 to "Hanging up"
    )

    private val MODELS = mapOf(
        0x0220 to "AirPods (1st gen)",
        0x0F20 to "AirPods (2nd gen)",
        0x1320 to "AirPods (3rd gen)",
        0x1920 to "AirPods (4th gen)",
        0x1B20 to "AirPods 4 (ANC)",
        0x0A20 to "AirPods Max",
        0x1F20 to "AirPods Max (USB-C)",
        0x0E20 to "AirPods Pro",
        0x1420 to "AirPods Pro 2",
        0x2420 to "AirPods Pro 2 (USB-C)",
        0x0520 to "Beats X",
        0x1020 to "Beats Flex",
        0x0920 to "Beats Studio 3",
        0x1720 to "Beats Studio Pro",
        0x0B20 to "Powerbeats Pro",
        0x1220 to "Beats Fit Pro",
        0x1120 to "Beats Studio Buds",
        0x1620 to "Beats Studio Buds+"
    )
}
