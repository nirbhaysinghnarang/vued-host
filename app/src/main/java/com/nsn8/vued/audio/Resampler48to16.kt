package com.nsn8.vued.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Streaming 48 kHz → 16 kHz downsampler (exact ÷3). A windowed-sinc low-pass FIR
 * (cutoff ~7 kHz, below the 8 kHz output Nyquist) runs before decimation to prevent
 * aliasing — the difference between clean speech and a tinny, aliased upload.
 *
 * Filter state ([tail], [phase]) is carried across calls so block boundaries are
 * seamless. [output] holds the result of the most recent [process] call.
 */
class Resampler48to16 {

    private val taps: FloatArray = buildLowPass(NUM_TAPS, CUTOFF_NORM)
    private val tail = FloatArray(NUM_TAPS - 1)   // history from the previous block
    private var phase = 0                          // input-sample phase mod 3

    var output: FloatArray = FloatArray(0)
        private set

    /**
     * Filters and decimates [length] input samples from [input].
     * @return the number of valid output (16 kHz) samples now in [output].
     */
    fun process(input: FloatArray, length: Int): Int {
        val ext = FloatArray(tail.size + length)
        System.arraycopy(tail, 0, ext, 0, tail.size)
        System.arraycopy(input, 0, ext, tail.size, length)

        val maxOut = length / DECIMATION + 1
        if (output.size < maxOut) {
            output = FloatArray(maxOut)
        }

        var outCount = 0
        for (i in 0 until length) {
            if (phase == 0) {
                val j = tail.size + i           // most-recent sample for this output
                var acc = 0f
                for (k in taps.indices) {
                    acc += taps[k] * ext[j - k]
                }
                output[outCount++] = acc
            }
            phase = (phase + 1) % DECIMATION
        }

        System.arraycopy(ext, ext.size - tail.size, tail, 0, tail.size)
        return outCount
    }

    private companion object {
        const val DECIMATION = 3
        const val NUM_TAPS = 49                  // odd → symmetric / linear phase
        const val CUTOFF_NORM = 7_000.0 / 48_000.0

        /** Hamming-windowed sinc low-pass, normalized to unity DC gain. */
        fun buildLowPass(numTaps: Int, cutoffNorm: Double): FloatArray {
            val h = DoubleArray(numTaps)
            val m = (numTaps - 1) / 2.0
            var sum = 0.0
            for (n in 0 until numTaps) {
                val x = n - m
                val sinc = if (x == 0.0) {
                    2.0 * cutoffNorm
                } else {
                    sin(2.0 * PI * cutoffNorm * x) / (PI * x)
                }
                val hamming = 0.54 - 0.46 * cos(2.0 * PI * n / (numTaps - 1))
                h[n] = sinc * hamming
                sum += h[n]
            }
            return FloatArray(numTaps) { (h[it] / sum).toFloat() }
        }
    }
}
