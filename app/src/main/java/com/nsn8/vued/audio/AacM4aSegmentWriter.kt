package com.nsn8.vued.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes a stream of mono 16-bit PCM into a single AAC-LC `.m4a` file. One instance
 * == one rolling segment; create a fresh one per segment and call [finish] to close.
 *
 * Synchronous MediaCodec usage: PCM is fed via input buffers, encoded frames are
 * drained to a MediaMuxer. Presentation timestamps are derived from the running
 * sample count so they are exact and monotonic.
 */
class AacM4aSegmentWriter(
    outputFile: File,
    private val sampleRate: Int = 16_000,
    bitRate: Int = 32_000,
) {
    private val encoder: MediaCodec
    private val muxer: MediaMuxer
    private val bufferInfo = MediaCodec.BufferInfo()
    private var trackIndex = -1
    private var muxerStarted = false
    private var totalSamples = 0L
    private var finished = false

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
        }
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    /** Encodes [length] mono samples from [pcm]. */
    fun write(pcm: ShortArray, length: Int) {
        if (finished) return
        var offset = 0
        while (offset < length) {
            val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (inIndex < 0) {
                drain(endOfStream = false)
                continue
            }
            val inBuffer = encoder.getInputBuffer(inIndex) ?: continue
            inBuffer.clear()
            val maxSamples = inBuffer.remaining() / 2
            val chunk = minOf(maxSamples, length - offset)
            val shorts = inBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            shorts.put(pcm, offset, chunk)
            val ptsUs = totalSamples * 1_000_000L / sampleRate
            encoder.queueInputBuffer(inIndex, 0, chunk * 2, ptsUs, 0)
            totalSamples += chunk
            offset += chunk
            drain(endOfStream = false)
        }
    }

    /** Flushes the encoder, finalizes the MP4 container, and releases resources. */
    fun finish() {
        if (finished) return
        finished = true
        try {
            val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US * 10)
            if (inIndex >= 0) {
                encoder.queueInputBuffer(
                    inIndex, 0, 0,
                    totalSamples * 1_000_000L / sampleRate,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            }
            drain(endOfStream = true)
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            if (muxerStarted) {
                runCatching { muxer.stop() }
            }
            runCatching { muxer.release() }
        }
    }

    private fun drain(endOfStream: Boolean) {
        while (true) {
            val outIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    // else keep polling until EOS surfaces
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "format changed twice" }
                    trackIndex = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outIndex >= 0 -> {
                    val encoded: ByteBuffer = encoder.getOutputBuffer(outIndex) ?: continue
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        // Codec-specific data is carried in the track format, not samples.
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && muxerStarted) {
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encoded, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return
                    }
                }
            }
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val MAX_INPUT_SIZE = 16 * 1024
    }
}
