package com.lightsync.android.audio

import android.media.audiofx.Visualizer
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Motor Audio Visualizer nativo do Android baseado na API android.media.audiofx.Visualizer.
 * Captura o mix global de saída de áudio do Android (audioSession = 0), incluindo músicas
 * transmitidas pelo celular via Bluetooth A2DP para a caixa de som Logitech G560!
 */
class AndroidAudioVisualizerEngine(
    private val onZonesComputed: (Array<IntArray>, FloatArray) -> Unit
) {
    private var visualizer: Visualizer? = null
    var gain: Float = 2.4f
    private val smoothedBands = FloatArray(10)

    fun start() {
        stop()
        try {
            // Session 0 captura o mix principal de saída do Android (inclusive Bluetooth A2DP)
            val vis = Visualizer(0)
            vis.captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
            vis.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(
                    visualizer: Visualizer?,
                    waveform: ByteArray?,
                    samplingRate: Int
                ) {}

                override fun onFftDataCapture(
                    visualizer: Visualizer?,
                    fft: ByteArray?,
                    samplingRate: Int
                ) {
                    if (fft != null) processFft(fft)
                }
            }, Visualizer.getMaxCaptureRate() / 2, false, true)
            vis.enabled = true
            visualizer = vis
        } catch (_: Exception) {}
    }

    private fun processFft(fft: ByteArray) {
        val binRanges = intArrayOf(1, 3, 6, 10, 16, 28, 48, 96, 180, 300, min(510, fft.size / 2 - 1))
        for (b in 0 until 10) {
            val k0 = binRanges[b]
            val k1 = max(k0 + 1, binRanges[b + 1])
            var sum = 0f
            for (k in k0..k1) {
                val idx = k * 2
                if (idx + 1 < fft.size) {
                    val re = fft[idx].toFloat()
                    val im = fft[idx + 1].toFloat()
                    sum += hypot(re, im) / 128f
                }
            }
            val avg = ((sum / (k1 - k0 + 1)) * gain * (1f + b * 0.25f)).coerceIn(0f, 1f)
            smoothedBands[b] = if (avg > smoothedBands[b]) avg else smoothedBands[b] * 0.75f
        }

        val bass = max(smoothedBands[0], max(smoothedBands[1], smoothedBands[2]))
        val mid = max(smoothedBands[3], max(smoothedBands[4], smoothedBands[5]))
        val high = max(smoothedBands[6], max(smoothedBands[7], smoothedBands[8]))

        // Zona 0 e 2 (Frontais Esq/Dir) = Médios/Agudos | Zona 1 e 3 (Traseiras Esq/Dir) = Graves/Sub
        val z0 = intArrayOf(0, (mid * 200).toInt().coerceIn(0, 255), (high * 255).toInt().coerceIn(0, 255))
        val z1 = intArrayOf((bass * 255).toInt().coerceIn(0, 255), 0, (bass * 220).toInt().coerceIn(0, 255))
        onZonesComputed(arrayOf(z0, z1, z0, z1), smoothedBands)
    }

    fun stop() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (_: Exception) {}
        visualizer = null
    }
}
