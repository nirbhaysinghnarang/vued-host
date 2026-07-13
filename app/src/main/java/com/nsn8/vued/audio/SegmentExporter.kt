package com.nsn8.vued.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.nsn8.vued.DiagnosticsLogger
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
    private data class EncodedSample(
        val bytes: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int,
    )
    private data class ReadableSegment(
        val format: MediaFormat,
        val samples: List<EncodedSample>,
    )

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
     * Concatenates the usable overlapping segments into [out], skipping corrupt,
     * incomplete, missing, or empty segments independently. Returns null if no usable
     * audio remains. Caller flushes the rolling buffer first so the tail segment is
     * finalized.
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
        var successfulSegmentCount = 0
        val info = MediaCodec.BufferInfo()

        try {
            for (segment in selected) {
                // Fully stage one segment before mutating the destination muxer. If a
                // corrupt tail fails halfway through extraction, none of its partial
                // samples or timestamps can poison the following valid segment.
                val readable = readSegment(segment) ?: continue
                if (!muxerStarted) {
                    muxTrack = muxer.addTrack(readable.format)
                    muxer.start()
                    muxerStarted = true
                }
                readable.samples.forEach { sample ->
                    info.offset = 0
                    info.size = sample.bytes.size
                    info.presentationTimeUs = ptsOffsetUs + sample.presentationTimeUs
                    info.flags = sample.flags
                    muxer.writeSampleData(muxTrack, ByteBuffer.wrap(sample.bytes), info)
                    maxPtsUs = maxOf(maxPtsUs, info.presentationTimeUs)
                }
                successfulSegmentCount += 1
                ptsOffsetUs = maxPtsUs + AAC_FRAME_US
            }
        } finally {
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }

        if (!muxerStarted || successfulSegmentCount == 0) return null
        return Result(out, durationMs = maxPtsUs / 1000, segmentCount = successfulSegmentCount)
    }

    private fun readSegment(segment: RollingBuffer.Segment): ReadableSegment? {
        if (!segment.file.isFile) {
            diagnoseSkippedSegment(segment, "missing", null)
            return null
        }
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(segment.file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            }
            if (track == null) {
                diagnoseSkippedSegment(segment, "no_audio_track", null)
                return null
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val scratch = ByteBuffer.allocate(MAX_SAMPLE_BYTES)
            val samples = mutableListOf<EncodedSample>()
            while (true) {
                scratch.clear()
                val size = extractor.readSampleData(scratch, 0)
                if (size < 0) break
                val presentationTimeUs = extractor.sampleTime
                check(presentationTimeUs >= 0L) { "Audio sample has no presentation timestamp." }
                val bytes = ByteArray(size)
                scratch.position(0)
                scratch.limit(size)
                scratch.get(bytes)
                samples += EncodedSample(
                    bytes = bytes,
                    presentationTimeUs = presentationTimeUs,
                    flags = extractor.sampleFlags,
                )
                extractor.advance()
            }
            if (samples.isEmpty()) {
                diagnoseSkippedSegment(segment, "no_audio_samples", null)
                null
            } else {
                ReadableSegment(format, samples)
            }
        } catch (error: Exception) {
            diagnoseSkippedSegment(segment, "unreadable_or_corrupt", error)
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun diagnoseSkippedSegment(
        segment: RollingBuffer.Segment,
        reason: String,
        error: Throwable?,
    ) {
        val data = mapOf(
            "fileName" to segment.file.name,
            "sizeBytes" to segment.file.length(),
            "startMs" to segment.startMs,
            "endMs" to segment.endMs,
            "reason" to reason,
        )
        Log.w(
            TAG,
            "Skipping segment ${segment.file.name} reason=$reason sizeBytes=${segment.file.length()}: ${error?.message}",
            error,
        )
        DiagnosticsLogger.warn("segment_export_skipped", data, error)
    }

    // One AAC-LC frame = 1024 samples; at 16 kHz that's 64 ms.
    private const val AAC_FRAME_US = 1024L * 1_000_000L / 16_000L
    private const val MAX_SAMPLE_BYTES = 64 * 1024
    private const val TAG = "VuedSegmentExporter"
}
