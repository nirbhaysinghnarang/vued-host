package com.nsn8.vued.audio

/**
 * Collapses the interleaved [channels]-channel int32 LE PCM produced by the UMA-8
 * native stream into a single mono float channel in roughly [-1, 1].
 *
 * v1 strategy is a plain average of the mics (safe against clipping since the mean
 * magnitude never exceeds the max). Delay-and-sum beamforming is a later upgrade.
 */
class Downmixer(private val channels: Int) {

    private var scratch = FloatArray(0)

    /**
     * @param buffer interleaved int32 LE samples, [length] bytes valid
     * @return a mono FloatArray of length `frames`; valid only until the next call
     *         (the backing array is reused).
     */
    fun toMonoFloat(buffer: ByteArray, length: Int): FloatArray {
        val bytesPerFrame = channels * 4
        val frames = length / bytesPerFrame
        if (scratch.size < frames) {
            scratch = FloatArray(frames)
        }
        var offset = 0
        for (frame in 0 until frames) {
            var sum = 0.0
            for (channel in 0 until channels) {
                sum += int32Le(buffer, offset)
                offset += 4
            }
            scratch[frame] = (sum / channels / INT32_FULL_SCALE).toFloat()
        }
        return scratch
    }

    /** Number of mono frames a [length]-byte interleaved buffer yields. */
    fun frameCount(length: Int): Int = length / (channels * 4)

    private fun int32Le(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xff) or
            ((b[i + 1].toInt() and 0xff) shl 8) or
            ((b[i + 2].toInt() and 0xff) shl 16) or
            (b[i + 3].toInt() shl 24)

    private companion object {
        const val INT32_FULL_SCALE = 2_147_483_648.0 // 2^31
    }
}
