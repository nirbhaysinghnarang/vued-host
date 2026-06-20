package com.nsn8.vued.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Exports a wall-clock window [startMs, endMs] from the rolling segments into a single
 * `.m4a` by concatenating the overlapping AAC segments (no re-encode). Slight
 * over-inclusion at the edges is fine — the slice's precise `startedAt/endedAt` carry
 * the real bounds and the server transcribes the whole file.
 */
object SegmentExporter {

    data class Result(val file: File, val durationMs: Long, val segmentCount: Int)

    /** Pure selection: which segments overlap [startMs, endMs]. Exposed for tests. */
    fun overlappingSegments(
        segments: List<RollingBuffer.Segment>,
        startMs: Long,
        endMs: Long,
    ): List<RollingBuffer.Segment> =
        segments
            .filter { it.startMs < endMs && it.endMs > startMs }
            .sortedBy { it.startMs }

    /**
     * Concatenates the overlapping segments into [out]. Returns null if no segment
     * overlaps the window. Caller flushes the rolling buffer first so the tail segment
     * is finalized.
     */
    fun exportWindow(
        segments: List<RollingBuffer.Segment>,
        startMs: Long,
        endMs: Long,
        out: File,
    ): Result? {
        val selected = overlappingSegments(segments, startMs, endMs)
        if (selected.isEmpty()) return null

        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxTrack = -1
        var muxerStarted = false
        var ptsOffsetUs = 0L
        var maxPtsUs = 0L
        val buffer = ByteBuffer.allocate(MAX_SAMPLE_BYTES)
        val info = MediaCodec.BufferInfo()

        try {
            for (segment in selected) {
                if (!segment.file.exists()) continue
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(segment.file.absolutePath)
                    val track = (0 until extractor.trackCount).firstOrNull {
                        extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)
                            ?.startsWith("audio/") == true
                    } ?: continue
                    extractor.selectTrack(track)
                    val format = extractor.getTrackFormat(track)

                    if (!muxerStarted) {
                        muxTrack = muxer.addTrack(format)
                        muxer.start()
                        muxerStarted = true
                    }

                    var lastPtsUs = 0L
                    while (true) {
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        val ptsUs = extractor.sampleTime
                        info.offset = 0
                        info.size = size
                        info.presentationTimeUs = ptsOffsetUs + ptsUs
                        info.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME // every AAC frame is a sync frame
                        muxer.writeSampleData(muxTrack, buffer, info)
                        lastPtsUs = info.presentationTimeUs
                        maxPtsUs = maxOf(maxPtsUs, lastPtsUs)
                        extractor.advance()
                    }
                    // Advance the timeline by this segment so the next one doesn't overlap.
                    ptsOffsetUs = maxPtsUs + AAC_FRAME_US
                } finally {
                    extractor.release()
                }
            }
        } finally {
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }

        if (!muxerStarted) return null
        return Result(out, durationMs = maxPtsUs / 1000, segmentCount = selected.size)
    }

    // One AAC-LC frame = 1024 samples; at 16 kHz that's 64 ms.
    private const val AAC_FRAME_US = 1024L * 1_000_000L / 16_000L
    private const val MAX_SAMPLE_BYTES = 64 * 1024
}
