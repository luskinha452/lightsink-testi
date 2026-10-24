package com.lightsync.android.protocol

/**
 * Codificador nativo do protocolo Logitech HID++ 2.0 (Relatórios Longos 0x11 de 20 bytes)
 * Reconstruído via engenharia reversa de lghub_agent.exe (VA 0x14216af30 - feature_8070_lighting_effects).
 */
object HidppProtocol {
    const val LOGITECH_VID = 0x046D
    const val REPORT_ID_LONG: Byte = 0x11
    const val SW_ID: Byte = 0x0E

    // HID++ 2.0 Feature IDs
    const val FEAT_ROOT = 0x0000
    const val FEAT_FEATURE_SET = 0x0001
    const val FEAT_DEVICE_NAME = 0x0005
    const val FEAT_BRIGHTNESS = 0x8040
    const val FEAT_COLOR_LED_EFFECTS = 0x8070
    const val FEAT_RGB_EFFECTS = 0x8071

    // 0x8070 Firmware Effect IDs (get_raw_effect_params)
    const val FX_OFF: Byte = 0x00
    const val FX_FIXED: Byte = 0x01
    const val FX_CYCLE: Byte = 0x03
    const val FX_WAVE: Byte = 0x04
    const val FX_BREATHING: Byte = 0x0A
    const val FX_RIPPLE: Byte = 0x0B

    fun buildPacket(
        deviceIndex: Byte = 0xFF.toByte(),
        featureIndex: Byte,
        functionId: Int,
        vararg params: Byte
    ): ByteArray {
        val pkt = ByteArray(20)
        pkt[0] = REPORT_ID_LONG
        pkt[1] = deviceIndex
        pkt[2] = featureIndex
        pkt[3] = (((functionId and 0x0F) shl 4) or (SW_ID.toInt() and 0x0F)).toByte()
        for (i in params.indices) {
            if (i < 16) pkt[4 + i] = params[i]
        }
        return pkt
    }

    /**
     * Consulta o índice de uma Feature HID++ 2.0 via IRoot (0x0000, Function 0: getFeature).
     */
    fun buildRootGetFeature(featureId: Int, deviceIndex: Byte = 0xFF.toByte()): ByteArray {
        val hi = ((featureId shr 8) and 0xFF).toByte()
        val lo = (featureId and 0xFF).toByte()
        return buildPacket(deviceIndex, 0x00, 0x00, hi, lo)
    }

    /**
     * Constrói o pacote 0x8070 Function 3 (setZoneEffect) para Cor Fixa (0x01).
     * Layout exato (VA 0x14216affd):
     * pkt[4] = zoneIndex, pkt[5] = 0x01 (FIXED), pkt[6] = R, pkt[7] = G, pkt[8] = B, pkt[9..15] = 0
     */
    fun buildSetZoneFixedColor(
        feature8070Index: Byte = 0x04,
        zoneIndex: Int,
        r: Int,
        g: Int,
        b: Int,
        deviceIndex: Byte = 0xFF.toByte()
    ): ByteArray {
        return buildPacket(
            deviceIndex,
            feature8070Index,
            0x03,
            zoneIndex.toByte(),
            FX_FIXED,
            (r and 0xFF).toByte(),
            (g and 0xFF).toByte(),
            (b and 0xFF).toByte(),
            0x00
        )
    }

    /**
     * Constrói o pacote 0x8070 Function 3 (setZoneEffect) para Efeito de Ciclo RGB (0x03).
     * Layout exato (VA 0x14216b0a2):
     * pkt[4] = zoneIndex, pkt[5] = 0x03, pkt[11] = periodHi, pkt[12] = periodLo, pkt[13] = intensity (1..100)
     */
    fun buildSetZoneCycleEffect(
        feature8070Index: Byte = 0x04,
        zoneIndex: Int,
        periodMs: Int = 4000,
        intensity: Int = 100,
        deviceIndex: Byte = 0xFF.toByte()
    ): ByteArray {
        val p = periodMs.coerceIn(500, 30000)
        val pHi = ((p shr 8) and 0xFF).toByte()
        val pLo = (p and 0xFF).toByte()
        val inten = intensity.coerceIn(1, 100).toByte()
        return buildPacket(
            deviceIndex,
            feature8070Index,
            0x03,
            zoneIndex.toByte(),
            FX_CYCLE,
            0x00, 0x00, 0x00, 0x00, 0x00,
            pHi,
            pLo,
            inten
        )
    }

    /**
     * Constrói o pacote 0x8070 Function 3 (setZoneEffect) para Efeito de Respiração (0x0A).
     * Layout exato (VA 0x14216b1ea):
     * pkt[4] = zoneIndex, pkt[5] = 0x0A, pkt[6..8] = R,G,B, pkt[9..10] = periodHi,periodLo, pkt[11] = 0, pkt[12] = intensity
     */
    fun buildSetZoneBreathingEffect(
        feature8070Index: Byte = 0x04,
        zoneIndex: Int,
        r: Int,
        g: Int,
        b: Int,
        periodMs: Int = 4000,
        intensity: Int = 100,
        deviceIndex: Byte = 0xFF.toByte()
    ): ByteArray {
        val p = periodMs.coerceIn(500, 30000)
        val pHi = ((p shr 8) and 0xFF).toByte()
        val pLo = (p and 0xFF).toByte()
        val inten = intensity.coerceIn(1, 100).toByte()
        return buildPacket(
            deviceIndex,
            feature8070Index,
            0x03,
            zoneIndex.toByte(),
            FX_BREATHING,
            (r and 0xFF).toByte(),
            (g and 0xFF).toByte(),
            (b and 0xFF).toByte(),
            pHi,
            pLo,
            0x00,
            inten
        )
    }

    /**
     * Constrói o pacote 0x8040 Function 2 (setBrightness) para controle de brilho em hardware.
     */
    fun buildSetBrightness(
        feature8040Index: Byte = 0x08,
        brightnessPercent: Int,
        deviceIndex: Byte = 0xFF.toByte()
    ): ByteArray {
        val b = brightnessPercent.coerceIn(0, 100)
        val hi = ((b shr 8) and 0xFF).toByte()
        val lo = (b and 0xFF).toByte()
        return buildPacket(deviceIndex, feature8040Index, 0x02, hi, lo)
    }

    fun toHex(bytes: ByteArray): String =
        bytes.joinToString("-") { "%02X".format(it) }
}
