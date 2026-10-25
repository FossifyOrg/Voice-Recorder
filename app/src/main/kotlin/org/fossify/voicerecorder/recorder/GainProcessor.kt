package org.fossify.voicerecorder.recorder

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.tanh

/**
 * Applies a user-controlled gain to 16-bit PCM in place. Gain changes are ramped to avoid clicks and
 * a soft limiter keeps boosted peaks from hard clipping. Also tracks the post-gain peak for level meters.
 */
class GainProcessor(sampleRate: Int) {
    companion object {
        private const val DECADE = 10f
        private const val DB_PER_DECADE = 20f
        private const val RAMP_SECONDS = 0.02

        // soft limiting starts at about -1 dBFS
        private const val LIMIT_THRESHOLD = 0.89f
        private const val GAIN_EPSILON = 1e-4f

        fun dbToLinear(db: Float) = DECADE.pow(db / DB_PER_DECADE)
    }

    @Volatile
    private var targetGain = 1f
    private var currentGain = 1f
    private val rampCoefficient = (1.0 - exp(-1.0 / (RAMP_SECONDS * sampleRate))).toFloat()
    private val peak = AtomicInteger(0)

    fun setGainDb(gainDb: Float) {
        targetGain = dbToLinear(gainDb)
    }

    fun process(samples: ShortArray, count: Int) {
        val target = targetGain
        var maxAbs = 0
        if (currentGain == 1f && target == 1f) {
            for (i in 0 until count) {
                maxAbs = max(maxAbs, abs(samples[i].toInt()))
            }
        } else {
            for (i in 0 until count) {
                currentGain += (target - currentGain) * rampCoefficient
                val value = softLimit(samples[i] * currentGain / Short.MAX_VALUE)
                val output = (value * Short.MAX_VALUE).toInt()
                samples[i] = output.toShort()
                maxAbs = max(maxAbs, abs(output))
            }

            if (abs(currentGain - target) < GAIN_EPSILON) {
                currentGain = target
            }
        }

        peak.accumulateAndGet(maxAbs) { a, b -> max(a, b) }
    }

    /** Highest absolute sample since the previous call, in the same 0..32767 range as MediaRecorder.getMaxAmplitude */
    fun getAndResetPeak() = peak.getAndSet(0)

    private fun softLimit(value: Float): Float {
        val magnitude = abs(value)
        if (magnitude <= LIMIT_THRESHOLD) {
            return value
        }

        val headroom = 1f - LIMIT_THRESHOLD
        return sign(value) * (LIMIT_THRESHOLD + headroom * tanh((magnitude - LIMIT_THRESHOLD) / headroom))
    }
}
