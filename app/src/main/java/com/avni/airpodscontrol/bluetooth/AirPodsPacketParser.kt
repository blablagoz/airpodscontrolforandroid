package com.avni.airpodscontrol.bluetooth

/**
 * Conservative parser for Apple manufacturer BLE frames.
 *
 * We intentionally only expose battery values when the frame resembles the
 * AirPods proximity layout and every nibble passes sanity checks. Unknown
 * firmware variants are kept as raw hex instead of returning invented values.
 */
object AirPodsPacketParser {
    data class Parsed(
        val isLikelyAirPods: Boolean,
        val leftBattery: Int? = null,
        val rightBattery: Int? = null,
        val caseBattery: Int? = null,
        val leftCharging: Boolean? = null,
        val rightCharging: Boolean? = null,
        val caseCharging: Boolean? = null,
        val rawHex: String
    )

    fun parse(data: ByteArray): Parsed {
        val raw = data.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        val type = data.getOrNull(0)?.u8()
        val length = data.getOrNull(1)?.u8()
        val likely = type == 0x07 && (length == 0x19 || data.size >= 20)
        if (!likely || data.size < 8) return Parsed(false, rawHex = raw)

        // Apple proximity frames encode battery as 0..10 nibbles (10% steps),
        // with 0xF meaning not available. Exact left/right orientation can vary
        // with frame status; until AACP confirms it we keep the conservative map.
        val podByte = data[6].u8()
        val caseByte = data[7].u8()
        val a = nibbleToPercent((podByte ushr 4) and 0x0F)
        val b = nibbleToPercent(podByte and 0x0F)
        val case = nibbleToPercent(caseByte and 0x0F)

        // Charging flags vary between generations/firmware. Do not guess.
        return Parsed(
            isLikelyAirPods = true,
            leftBattery = a,
            rightBattery = b,
            caseBattery = case,
            rawHex = raw
        )
    }

    private fun Byte.u8() = toInt() and 0xFF
    private fun nibbleToPercent(value: Int): Int? = when (value) {
        0x0F -> null
        in 0..10 -> value * 10
        else -> null
    }
}
