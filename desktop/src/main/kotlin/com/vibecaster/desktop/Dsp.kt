package com.vibecaster.desktop

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Desktop ports of the Android audio processors. The DSP math is identical to
 * app/src/main/java/com/vibecaster/audio/{EightDAudioProcessor,ToneAudioProcessor}.kt —
 * only the wrapper differs: Android runs inside Media3's AudioProcessor chain,
 * here we process interleaved 16-bit stereo frames from the ffmpeg PCM pipe.
 * If you tune the effect, change BOTH files the same way.
 */
class EightD {
    @Volatile var enabled: Boolean = true
    @Volatile var rotationSpeed: Float = 0.12f   // rotations per second
    @Volatile var intensity: Float = 0.9f        // pan depth 0..1
    @Volatile var crossfeed: Float = 0.22f       // opposite-ear leak
    @Volatile var reverse: Boolean = false

    private var phase = 0.0

    /** In-place processing of interleaved stereo 16-bit samples. */
    fun process(samples: ShortArray, validSamples: Int, sampleRate: Int) {
        if (!enabled) return
        val inc = 2.0 * Math.PI * rotationSpeed / sampleRate
        val depth = intensity.toDouble().coerceIn(0.0, 1.0)
        val cf = crossfeed.toDouble().coerceIn(0.0, 0.5)

        var i = 0
        while (i + 1 < validSamples) {
            val l = samples[i].toDouble()
            val r = samples[i + 1].toDouble()

            val bl = l * (1 - cf) + r * cf
            val br = r * (1 - cf) + l * cf

            val pan = depth * sin(phase)
            val angle = (pan + 1.0) * (Math.PI / 4.0)
            val gainL = cos(angle)
            val gainR = sin(angle)
            val dist = 0.88 + 0.12 * cos(phase)

            // Makeup gain: 1.1 peaked at 1.022 and clipped loud masters.
            // Must stay identical to EightDAudioProcessor.MAKEUP_GAIN.
            samples[i] = clamp(bl * gainL * dist * 1.075)
            samples[i + 1] = clamp(br * gainR * dist * 1.075)

            phase += if (reverse) -inc else inc
            if (phase >= 2.0 * Math.PI) phase -= 2.0 * Math.PI
            if (phase < 0.0) phase += 2.0 * Math.PI
            i += 2
        }
    }

    fun resetPhase() { phase = 0.0 }

    private fun clamp(v: Double): Short =
        v.coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt().toShort()
}

/**
 * 2-band shelf EQ (bass @200Hz, treble @4kHz), RBJ biquads — same math as
 * the Android ToneAudioProcessor.
 */
class Tone {
    @Volatile var bassDb: Float = 0f
        set(value) { field = value; dirty = true }
    @Volatile var trebleDb: Float = 0f
        set(value) { field = value; dirty = true }

    @Volatile private var dirty = true
    private var sampleRate = 48000

    private val bass = FloatArray(5)
    private val treble = FloatArray(5)
    private val bassState = Array(2) { FloatArray(4) }
    private val trebleState = Array(2) { FloatArray(4) }

    fun process(samples: ShortArray, validSamples: Int, rate: Int) {
        if (bassDb == 0f && trebleDb == 0f) return
        if (dirty || rate != sampleRate) {
            sampleRate = rate
            shelfCoefficients(bass, 200f, bassDb, lowShelf = true)
            shelfCoefficients(treble, 4000f, trebleDb, lowShelf = false)
            dirty = false
        }
        var i = 0
        while (i + 1 < validSamples) {
            for (ch in 0..1) {
                var v = samples[i + ch].toFloat()
                v = biquad(bass, bassState[ch], v)
                v = biquad(treble, trebleState[ch], v)
                samples[i + ch] = v
                    .coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
                    .toInt().toShort()
            }
            i += 2
        }
    }

    fun reset() {
        bassState.forEach { it.fill(0f) }
        trebleState.forEach { it.fill(0f) }
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
}
