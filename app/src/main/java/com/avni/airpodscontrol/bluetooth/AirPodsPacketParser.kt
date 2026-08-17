package com.avni.airpodscontrol.bluetooth

/**
 * Conservative parser for Apple manufacturer BLE frames.
 *
 * v0.4 deliberately rejects short Apple frames such as 12 02 00 01. Those
 * frames are valid Apple advertisements but are not an AirPods proximity
 * battery frame. Unknown firmware layouts remain visible in diagnostics.
 */
object AirPodsPacketParser {
    data class Parsed(
        val isLikelyAirPods: Boolean,
        val frameType: Int?,
        val declaredLength: Int?,
        val leftBattery: Int? = null,
        val rightBattery: Int? = null,
        val caseBattery: Int? = null,
        val leftCharging: Boolean? = null,
        val rightCharging: Boolean? = null,
        val caseCharging: Boolean? = null,
        val rawHex: String
    )

    fun parse(data: ByteArray): Parsed {
        val raw = data.toHex()
        val type = data.getOrNull(0)?.u8()
        val length = data.getOrNull(1)?.u8()

        // AirPods proximity advertisements observed in the Apple manufacturer
        // namespace use type 0x07 and are much longer than generic 4-byte Apple
        // frames. Require both the type and a sane payload length.
        val structuralMatch = type == AIRPODS_PROXIMITY_TYPE && data.size >= MIN_PROXIMITY_BYTES
        val lengthMatch = length == null || length >= 0x10
        val likely = structuralMatch && lengthMatch
        if (!likely) {
            return Parsed(
                isLikelyAirPods = false,
                frameType = type,
                declaredLength = length,
                rawHex = raw
            )
        }

        // Keep the old conservative nibble mapping until we capture a verified
        // Pro 2 frame from this exact Samsung/firmware combination.
        val podByte = data.getOrNull(6)?.u8()
        val caseByte = data.getOrNull(7)?.u8()
        val a = podByte?.let { nibbleToPercent((it ushr 4) and 0x0F) }
        val b = podByte?.let { nibbleToPercent(it and 0x0F) }
        val case = caseByte?.let { nibbleToPercent(it and 0x0F) }

        return Parsed(
            isLikelyAirPods = true,
            frameType = type,
            declaredLength = length,
            leftBattery = a,
            rightBattery = b,
            caseBattery = case,
            rawHex = raw
        )
    }

    fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private fun Byte.u8() = toInt() and 0xFF
    private fun nibbleToPercent(value: Int): Int? = when (value) {
        0x0F -> null
        in 0..10 -> value * 10
        else -> null
    }

    private const val AIRPODS_PROXIMITY_TYPE = 0x07
    private const val MIN_PROXIMITY_BYTES = 18
}
