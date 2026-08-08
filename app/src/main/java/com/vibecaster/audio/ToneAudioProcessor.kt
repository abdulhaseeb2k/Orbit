package com.vibecaster.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Simple 2-band equalizer: bass (low-shelf @ 200 Hz) and treble
 * (high-shelf @ 4 kHz) using RBJ biquad filters. Gains in dB (-12..+12).
 */
@UnstableApi
class ToneAudioProcessor : BaseAudioProcessor() {

    @Volatile var bassDb: Float = 0f
        set(value) { field = value; dirty = true }

    @Volatile var trebleDb: Float = 0f
        set(value) { field = value; dirty = true }

    @Volatile private var dirty = true

    private var sampleRate = 44100
    private var channels = 2

    // Biquad coefficients: [b0, b1, b2, a1, a2] (normalized by a0)
    private val bass = FloatArray(5)
    private val treble = FloatArray(5)

    // Filter state per channel: [x1, x2, y1, y2] for each band
    private var bassState = Array(2) { FloatArray(4) }
    private var trebleState = Array(2) { FloatArray(4) }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (inputAudioFormat.channelCount !in 1..2) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        bassState = Array(channels) { FloatArray(4) }
        trebleState = Array(channels) { FloatArray(4) }
        dirty = true
        return inputAudioFormat
    }

    private fun shelfCoefficients(out: FloatArray, freq: Float, gainDb: Float, lowShelf: Boolean) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * Math.PI * freq / sampleRate
        val cosW = cos(w0)
        val sinW = sin(w0)
        val alpha = sinW / 2.0 * sqrt(2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha

        val b0: Double; val b1: Double; val b2: Double
        val a0: Double; val a1: Double; val a2: Double
        if (lowShelf) {
            b0 = a * ((a + 1) - (a - 1) * cosW + twoSqrtAAlpha)
            b1 = 2 * a * ((a - 1) - (a + 1) * cosW)
            b2 = a * ((a + 1) - (a - 1) * cosW - twoSqrtAAlpha)
            a0 = (a + 1) + (a - 1) * cosW + twoSqrtAAlpha
            a1 = -2 * ((a - 1) + (a + 1) * cosW)
            a2 = (a + 1) + (a - 1) * cosW - twoSqrtAAlpha
        } else {
            b0 = a * ((a + 1) + (a - 1) * cosW + twoSqrtAAlpha)
            b1 = -2 * a * ((a - 1) + (a + 1) * cosW)
            b2 = a * ((a + 1) + (a - 1) * cosW - twoSqrtAAlpha)
            a0 = (a + 1) - (a - 1) * cosW + twoSqrtAAlpha
            a1 = 2 * ((a - 1) - (a + 1) * cosW)
            a2 = (a + 1) - (a - 1) * cosW - twoSqrtAAlpha
        }
        out[0] = (b0 / a0).toFloat()
        out[1] = (b1 / a0).toFloat()
        out[2] = (b2 / a0).toFloat()
        out[3] = (a1 / a0).toFloat()
        out[4] = (a2 / a0).toFloat()
    }

    private fun biquad(c: FloatArray, s: FloatArray, x: Float): Float {
        val y = c[0] * x + c[1] * s[0] + c[2] * s[1] - c[3] * s[2] - c[4] * s[3]
        s[1] = s[0]; s[0] = x
        s[3] = s[2]; s[2] = y
        return y
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frames = inputBuffer.remaining() / (channels * 2)
        if (frames == 0) return
        val out = replaceOutputBuffer(frames * channels * 2)

        val bypass = bassDb == 0f && trebleDb == 0f
        if (bypass) {
            out.put(inputBuffer)
            out.flip()
            return
        }

        if (dirty) {
            shelfCoefficients(bass, 200f, bassDb, lowShelf = true)
            shelfCoefficients(treble, 4000f, trebleDb, lowShelf = false)
            dirty = false
        }

        repeat(frames) {
            for (ch in 0 until channels) {
                var v = inputBuffer.short.toFloat()
                v = biquad(bass, bassState[ch], v)
                v = biquad(treble, trebleState[ch], v)
                val clamped = v.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
                out.putShort(clamped.toInt().toShort())
            }
        }
        out.flip()
    }

    override fun onFlush() {
        bassState.forEach { it.fill(0f) }
        trebleState.forEach { it.fill(0f) }
    }

    override fun onReset() = onFlush()
}
