package com.msp1974.vacompanion.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The microphone's recent loudness, for wake word arbitration (house build, 2026-10-31).
 *
 * Upstream decides a contested wake word by the detector's score. In one open space both
 * satellites hear the phrase clearly and both scores sit at the ceiling, so the score says
 * nothing about who is nearer (his test: the kitchen answered him at the TSV, 0.94 vs a
 * hair more). The microphone level over the wake phrase does: the nearer microphone is the
 * louder one. It is measured here on exactly the samples the recognizer receives (after the
 * mic_gain trim), so his mic_gain setting per device is the calibration knob.
 *
 * A ring of per-frame mean squares covering roughly the last [WINDOW_MS]; [recentDbfs] is
 * the RMS of that window in dBFS (0 = full scale, silence well below -60).
 */
object MicLevel {
    private const val WINDOW_MS = 1500
    private const val SLOTS = 64
    private val meanSquares = DoubleArray(SLOTS)
    private val samples = IntArray(SLOTS)
    private var head = 0
    private var totalSamples = 0
    private var sampleRate = 16000
    private val lock = Any()

    fun feed(frame: ShortArray, length: Int, rate: Int) {
        if (length <= 0) return
        var acc = 0.0
        for (i in 0 until length) {
            val v = frame[i].toDouble()
            acc += v * v
        }
        synchronized(lock) {
            sampleRate = rate
            meanSquares[head] = acc
            samples[head] = length
            head = (head + 1) % SLOTS
        }
    }

    /** RMS over the last WINDOW_MS in dBFS, or -120 when nothing has been heard. */
    fun recentDbfs(): Float {
        synchronized(lock) {
            val want = sampleRate * WINDOW_MS / 1000
            var acc = 0.0
            var n = 0
            var i = (head - 1 + SLOTS) % SLOTS
            var seen = 0
            while (seen < SLOTS && n < want && samples[i] > 0) {
                acc += meanSquares[i]
                n += samples[i]
                i = (i - 1 + SLOTS) % SLOTS
                seen++
            }
            if (n == 0) return -120f
            val rms = sqrt(acc / n) / 32768.0
            return if (rms <= 1e-6) -120f else (20.0 * log10(rms)).toFloat()
        }
    }
}
