package com.nsn8.vued.audio

/**
 * Selects the first channel from the interleaved [channels]-channel int32 LE PCM
 * produced by the UMA native stream, returning mono float audio in roughly [-1, 1].
 *
 * Keeping the other channels out of this stage avoids phase cancellation from a
 * plain microphone average and leaves a clear replacement point for WPE later.
 */
class Downmixer(private val channels: Int) {

    private var scratch = FloatArray(0)

    /**
     * @param buffer interleaved int32 LE samples, [length] bytes valid
     * @return a mono FloatArray of length `frames`; valid only until the next call
     *         (the backing array is reused).
     */
    fun toMonoFloat(buffer: ByteArray, length: Int, gain: Double = MAKEUP_GAIN): FloatArray {
        val bytesPerFrame = channels * 4
        val frames = length / bytesPerFrame
        if (scratch.size < frames) {
            scratch = FloatArray(frames)
        }
        for (frame in 0 until frames) {
            val firstChannelOffset = frame * bytesPerFrame
            scratch[frame] = (int32Le(buffer, firstChannelOffset) / INT32_FULL_SCALE * gain).toFloat()
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

    companion object {
        const val INT32_FULL_SCALE = 2_147_483_648.0 // 2^31

        // RAW-mode makeup gain. The UMA-8 applies NO gain in RAW mode (per the
        // manual — gain/AGC only happens in DSP mode), so the MEMS signal is very
        // low level and needs digital makeup gain. Applied here in float so the
        // RollingBuffer's clamp limits peaks cleanly. Tune via the on-screen peak
        // meter so normal speech peaks land around -12 dBFS (≈0.25); raise if too
        // quiet, lower if it clips. ~+36 dB; sane starting point for far-field RAW.
        const val MAKEUP_GAIN = 128.0
    }
}
