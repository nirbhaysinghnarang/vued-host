package com.nsn8.vued.audio

/**
 * Converts interleaved 48 kHz int32 little-endian UMA PCM into interleaved
 * 16 kHz PCM16 while preserving channel order.
 */
class MultiChannelResampler48to16(
    private val channels: Int,
    private val makeupGain: Double = DEFAULT_MAKEUP_GAIN,
) {
    private val resamplers = Array(channels) { Resampler48to16() }
    private var channelScratch = Array(channels) { FloatArray(0) }

    var output: ShortArray = ShortArray(0)
        private set

    init {
        require(channels > 0) { "channels must be positive" }
    }

    fun process(buffer: ByteArray, length: Int): Int {
        val bytesPerFrame = channels * 4
        val frames = length / bytesPerFrame
        if (frames <= 0) return 0
        ensureChannelScratch(frames)

        var offset = 0
        for (frame in 0 until frames) {
            for (channel in 0 until channels) {
                channelScratch[channel][frame] =
                    (int32Le(buffer, offset) / INT32_FULL_SCALE * makeupGain).toFloat()
                offset += 4
            }
        }

        var outFrames = -1
        for (channel in 0 until channels) {
            val count = resamplers[channel].process(channelScratch[channel], frames)
            if (outFrames == -1) {
                outFrames = count
            } else {
                check(outFrames == count) { "per-channel resampler output drifted" }
            }
        }
        if (outFrames <= 0) return 0
        ensureOutput(outFrames * channels)

        for (frame in 0 until outFrames) {
            val base = frame * channels
            for (channel in 0 until channels) {
                output[base + channel] = floatToPcm16(resamplers[channel].output[frame])
            }
        }
        return outFrames
    }

    private fun ensureChannelScratch(frames: Int) {
        for (channel in 0 until channels) {
            if (channelScratch[channel].size < frames) {
                channelScratch[channel] = FloatArray(frames)
            }
        }
    }

    private fun ensureOutput(samples: Int) {
        if (output.size < samples) {
            output = ShortArray(samples)
        }
    }

    private fun floatToPcm16(value: Float): Short {
        val scaled = value * 32767f
        return when {
            scaled >= 32767f -> Short.MAX_VALUE
            scaled <= -32768f -> Short.MIN_VALUE
            else -> scaled.toInt().toShort()
        }
    }

    private fun int32Le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            (bytes[offset + 3].toInt() shl 24)

    companion object {
        const val DEFAULT_MAKEUP_GAIN = 128.0
        private const val INT32_FULL_SCALE = 2_147_483_648.0
    }
}
