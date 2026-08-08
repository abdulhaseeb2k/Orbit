package com.vibecaster.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.sin

/**
 * Real-time 8D audio effect.
 *
 * Rotates the sound around the listener's head by sweeping the stereo pan
 * with a slow LFO (equal-power pan law) plus a subtle distance modulation,
 * which creates the classic "8D" circular motion in headphones.
 */
@UnstableApi
class EightDAudioProcessor : BaseAudioProcessor() {

    private companion object {
        /**
         * Output makeup gain. The peak of gainL*dist at full depth is 0.9291,
         * so the old 1.1 reached 1.022 and hard-clipped loud masters on every
         * rotation. Keep in step with desktop's Dsp.kt.
         */
        const val MAKEUP_GAIN = 1.075
    }

    /** Master on/off. When off, audio passes through untouched. */
    @Volatile var effectEnabled: Boolean = true

    /** Rotations per second (0.05 = very slow, 0.5 = fast). */
    @Volatile var rotationSpeed: Float = 0.12f

    /** Pan depth 0..1 (how far the sound swings left/right). */
    @Volatile var intensity: Float = 0.9f

    /** How much of the opposite channel leaks in so sound never fully disappears from one ear. */
    @Volatile var crossfeed: Float = 0.22f

    /** Reverses the rotation direction (counter-clockwise). */
    @Volatile var reverse: Boolean = false

    @Volatile private var phase: Double = 0.0

    /** Current LFO phase (radians) — read by the UI orbit visualizer. */
    val currentPhase: Double get() = phase
    private var inputChannels = 2
    private var sampleRate = 44100

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        inputChannels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        if (inputChannels != 1 && inputChannels != 2) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        // Always output stereo.
        return AudioProcessor.AudioFormat(sampleRate, 2, C.ENCODING_PCM_16BIT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytesPerInputFrame = inputChannels * 2
        val frames = inputBuffer.remaining() / bytesPerInputFrame
        if (frames == 0) return

        val out = replaceOutputBuffer(frames * 4)
        val inc = 2.0 * Math.PI * rotationSpeed / sampleRate
        val enabled = effectEnabled
        val depth = intensity.toDouble().coerceIn(0.0, 1.0)
        val cf = crossfeed.toDouble().coerceIn(0.0, 0.5)

        repeat(frames) {
            val l: Double
            val r: Double
            if (inputChannels == 2) {
                l = inputBuffer.short.toDouble()
                r = inputBuffer.short.toDouble()
            } else {
                val m = inputBuffer.short.toDouble()
                l = m
                r = m
            }

            if (!enabled) {
                out.putShort(clamp(l))
                out.putShort(clamp(r))
            } else {
                // Blend a bit of the opposite ear (crossfeed).
                val bl = l * (1 - cf) + r * cf
                val br = r * (1 - cf) + l * cf

                // Equal-power panning driven by a slow sine LFO.
                val pan = depth * sin(phase)               // -1..1
                val angle = (pan + 1.0) * (Math.PI / 4.0)  // 0..PI/2
                val gainL = cos(angle)
                val gainR = sin(angle)

                // Subtle "distance" modulation for a circular (not just L-R) feel.
                val dist = 0.88 + 0.12 * cos(phase)

                // Makeup gain. 1.1 pushed the peak of gainL*dist to ~1.022 at
                // full depth, so loud masters clipped on every rotation cycle;
                // 1.075 keeps the same loudness lift while staying under 1.0.
                out.putShort(clamp(bl * gainL * dist * MAKEUP_GAIN))
                out.putShort(clamp(br * gainR * dist * MAKEUP_GAIN))

                phase += if (reverse) -inc else inc
                if (phase >= 2.0 * Math.PI) phase -= 2.0 * Math.PI
                if (phase < 0.0) phase += 2.0 * Math.PI
            }
        }
        out.flip()
    }

    private fun clamp(v: Double): Short =
        v.coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt().toShort()

    override fun onFlush() {
        phase = 0.0
    }

    override fun onReset() {
        phase = 0.0
    }
}
