package com.nsn8.vued.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * Byte layout of the single 16-channel source WAV as it will be stored:
 * `[44-byte canonical header][per-segment trimmed PCM ...]`. Lets the
 * incremental uploader read any byte range on demand so it can resume from the
 * server's offset.
 *
 * The header carries placeholder RIFF/data sizes (the total is unknown while a
 * meeting is in progress); the server patches those two fields at `/complete`.
 * Per-segment trim uses [WavSegmentExporter.frameStart]/[frameEnd], so the
 * streamed prefix is byte-identical to [WavSegmentExporter.exportWindow]'s
 * output for the same window — which is what makes resume-from-offset correct.
 */
class SourceWavStreamPlan private constructor(
    private val header: ByteArray,
    private val parts: List<Part>,
    override val totalBytes: Long,
) : SourceUploadPlan {
    /** A contiguous PCM range within one segment file, placed at [streamStart] in the virtual stream. */
    private class Part(val file: File, val fileOffset: Long, val byteLen: Long, val streamStart: Long)

    /** Reads up to [maxBytes] of the virtual stream starting at [fromOffset]. Returns fewer at EOF. */
    override fun readRange(fromOffset: Long, maxBytes: Int): ByteArray {
        if (fromOffset < 0 || fromOffset >= totalBytes || maxBytes <= 0) return ByteArray(0)
        val end = minOf(totalBytes, fromOffset + maxBytes)
        val out = ByteArray((end - fromOffset).toInt())
        var written = 0
        var pos = fromOffset

        if (pos < header.size) {
            val n = (minOf(header.size.toLong(), end) - pos).toInt()
            System.arraycopy(header, pos.toInt(), out, written, n)
            written += n
            pos += n
        }
        for (part in parts) {
            if (pos >= end) break
            val partEnd = part.streamStart + part.byteLen
            if (pos >= partEnd) continue
            val readFrom = part.fileOffset + (pos - part.streamStart)
            val n = (minOf(partEnd, end) - pos).toInt()
            RandomAccessFile(part.file, "r").use { raf ->
                raf.seek(readFrom)
                raf.readFully(out, written, n)
            }
            written += n
            pos += n
        }
        return if (written == out.size) out else out.copyOf(written)
    }

    companion object {
        const val HEADER_BYTES = Pcm16WavSegmentWriter.HEADER_BYTES

        /**
         * Builds the plan over [segments] (already selected + ordered by the
         * caller) for the window `[startMs, endMs]`. [endMs] null means "to the
         * end of each segment" (no tail-trim) — used while streaming segments
         * that cannot be the meeting's last.
         */
        fun build(
            segments: List<MultiChannelWavRollingBuffer.Segment>,
            startMs: Long,
            endMs: Long?,
            fallbackSampleRate: Int = MultiChannelWavRollingBuffer.SAMPLE_RATE_16K,
            fallbackChannels: Int = MultiChannelWavRollingBuffer.CHANNELS_UMA16,
        ): SourceWavStreamPlan {
            var header: ByteArray? = null
            val parts = ArrayList<Part>()
            var stream = HEADER_BYTES.toLong()
            for (segment in segments) {
                val info = Pcm16WavSegmentWriter.readInfo(segment.file) ?: continue
                if (header == null) header = canonicalHeader(info.sampleRate, info.channels)
                val startFrame = WavSegmentExporter.frameStart(startMs, segment.startMs, info.sampleRate, info.frameCount)
                val endFrame = WavSegmentExporter.frameEnd(endMs, segment.startMs, info.sampleRate, info.frameCount)
                if (endFrame <= startFrame) continue
                val fileOffset = info.dataOffset + startFrame * info.blockAlign
                val byteLen = (endFrame - startFrame) * info.blockAlign
                parts.add(Part(segment.file, fileOffset, byteLen, stream))
                stream += byteLen
            }
            return SourceWavStreamPlan(
                header ?: canonicalHeader(fallbackSampleRate, fallbackChannels),
                parts,
                stream,
            )
        }

        /** Canonical 44-byte PCM16 WAV header with placeholder (zero) RIFF/data sizes. */
        fun canonicalHeader(sampleRate: Int, channels: Int): ByteArray {
            val bitsPerSample = 16
            val blockAlign = channels * bitsPerSample / 8
            val byteRate = sampleRate.toLong() * blockAlign
            val out = ByteArray(HEADER_BYTES)
            var i = 0
            fun ascii(s: String) { for (c in s) out[i++] = c.code.toByte() }
            fun le16(v: Int) { out[i++] = (v and 0xff).toByte(); out[i++] = ((v ushr 8) and 0xff).toByte() }
            fun le32(v: Long) {
                out[i++] = (v and 0xff).toByte()
                out[i++] = ((v ushr 8) and 0xff).toByte()
                out[i++] = ((v ushr 16) and 0xff).toByte()
                out[i++] = ((v ushr 24) and 0xff).toByte()
            }
            ascii("RIFF"); le32(0)            // RIFF size — patched server-side at /complete
            ascii("WAVE")
            ascii("fmt "); le32(16)
            le16(1)                            // PCM
            le16(channels)
            le32(sampleRate.toLong())
            le32(byteRate)
            le16(blockAlign)
            le16(bitsPerSample)
            ascii("data"); le32(0)             // data size — patched server-side at /complete
            return out
        }
    }
}
