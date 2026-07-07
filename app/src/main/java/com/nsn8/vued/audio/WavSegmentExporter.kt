package com.nsn8.vued.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * Exports a wall-clock window from finalized PCM WAV source segments.
 */
object WavSegmentExporter {

    data class Result(val file: File, val durationMs: Long, val segmentCount: Int)

    fun overlappingSegments(
        segments: List<MultiChannelWavRollingBuffer.Segment>,
        startMs: Long,
        endMs: Long,
    ): List<MultiChannelWavRollingBuffer.Segment> =
        segments
            .filter { it.startMs < endMs && it.endMs > startMs }
            .sortedBy { it.startMs }

    fun exportWindow(
        segments: List<MultiChannelWavRollingBuffer.Segment>,
        startMs: Long,
        endMs: Long,
        out: File,
    ): Result? {
        val selected = overlappingSegments(segments, startMs, endMs)
        if (selected.isEmpty()) return null
        val firstInfo = selected.firstNotNullOfOrNull { Pcm16WavSegmentWriter.readInfo(it.file) }
            ?: return null
        val writer = Pcm16WavSegmentWriter(
            out,
            sampleRate = firstInfo.sampleRate,
            channels = firstInfo.channels,
        )
        var totalFrames = 0L
        var copiedSegments = 0
        try {
            for (segment in selected) {
                val info = Pcm16WavSegmentWriter.readInfo(segment.file) ?: continue
                if (
                    info.sampleRate != firstInfo.sampleRate ||
                    info.channels != firstInfo.channels ||
                    info.bitsPerSample != firstInfo.bitsPerSample
                ) {
                    continue
                }
                val startFrame = frameStart(startMs, segment.startMs, info.sampleRate, info.frameCount)
                val endFrame = frameEnd(endMs, segment.startMs, info.sampleRate, info.frameCount)
                if (endFrame <= startFrame) continue
                val copied = copyFrames(segment.file, info, startFrame, endFrame, writer)
                if (copied > 0) {
                    totalFrames += copied
                    copiedSegments += 1
                }
            }
        } finally {
            writer.finish()
        }

        if (totalFrames <= 0) {
            runCatching { out.delete() }
            return null
        }
        return Result(
            file = out,
            durationMs = totalFrames * 1000L / firstInfo.sampleRate,
            segmentCount = copiedSegments,
        )
    }

    /**
     * Exports the window as the WavPack source container
     * ([SourceWavContainerPlan.MAGIC] then length-prefixed per-segment blobs) —
     * the same byte layout the streaming meeting uploader produces, so the
     * server and Modal treat both identically. Blob files are per-window
     * scratch (ambient windows never repeat), so they are deleted after the
     * container is written.
     */
    fun exportWindowWv(
        segments: List<MultiChannelWavRollingBuffer.Segment>,
        startMs: Long,
        endMs: Long,
        out: File,
        blobCacheDir: File,
    ): Result? {
        val selected = overlappingSegments(segments, startMs, endMs)
        if (selected.isEmpty()) return null
        val blobs = ArrayList<File>(selected.size)
        var totalFrames = 0L
        var sampleRate = MultiChannelWavRollingBuffer.SAMPLE_RATE_16K
        try {
            for (segment in selected) {
                val info = Pcm16WavSegmentWriter.readInfo(segment.file) ?: continue
                sampleRate = info.sampleRate
                val startFrame = frameStart(startMs, segment.startMs, info.sampleRate, info.frameCount)
                val endFrame = frameEnd(endMs, segment.startMs, info.sampleRate, info.frameCount)
                if (endFrame <= startFrame) continue
                val blob = SourceWavSegmentEncoder.encodeSegment(segment, startMs, endMs, blobCacheDir)
                    ?: continue
                blobs.add(blob)
                totalFrames += endFrame - startFrame
            }
            if (blobs.isEmpty() || totalFrames <= 0) {
                runCatching { out.delete() }
                return null
            }
            out.outputStream().buffered().use { sink ->
                sink.write(SourceWavContainerPlan.MAGIC)
                for (blob in blobs) {
                    val len = blob.length()
                    sink.write(
                        byteArrayOf(
                            (len and 0xff).toByte(),
                            ((len ushr 8) and 0xff).toByte(),
                            ((len ushr 16) and 0xff).toByte(),
                            ((len ushr 24) and 0xff).toByte(),
                        ),
                    )
                    blob.inputStream().use { it.copyTo(sink) }
                }
            }
        } finally {
            blobs.forEach { runCatching { it.delete() } }
        }
        return Result(
            file = out,
            durationMs = totalFrames * 1000L / sampleRate,
            segmentCount = blobs.size,
        )
    }

    private fun copyFrames(
        file: File,
        info: Pcm16WavSegmentWriter.Info,
        startFrame: Long,
        endFrame: Long,
        writer: Pcm16WavSegmentWriter,
    ): Long {
        val blockAlign = info.blockAlign
        val frames = endFrame - startFrame
        var remainingBytes = frames * blockAlign
        if (remainingBytes <= 0) return 0

        val buffer = ByteArray(COPY_BUFFER_BYTES)
        RandomAccessFile(file, "r").use { input ->
            input.seek(info.dataOffset + startFrame * blockAlign)
            while (remainingBytes > 0) {
                val readSize = minOf(buffer.size.toLong(), remainingBytes).toInt()
                input.readFully(buffer, 0, readSize)
                writer.writePcmBytes(buffer, readSize)
                remainingBytes -= readSize
            }
        }
        return frames
    }

    /**
     * First frame of [segment] included when the window starts at [startMs]
     * (head-trim). Shared with the incremental uploader so a streamed segment's
     * bytes match this exporter's output exactly.
     */
    fun frameStart(startMs: Long, segmentStartMs: Long, sampleRate: Int, frameCount: Long): Long =
        frameCeil(startMs - segmentStartMs, sampleRate).coerceIn(0, frameCount)

    /**
     * One past the last frame included when the window ends at [endMs]
     * (tail-trim). [endMs] null means "to end of segment" — used while streaming
     * segments that cannot be the meeting's last.
     */
    fun frameEnd(endMs: Long?, segmentStartMs: Long, sampleRate: Int, frameCount: Long): Long =
        if (endMs == null) frameCount
        else frameFloor(endMs - segmentStartMs, sampleRate).coerceIn(0, frameCount)

    private fun frameCeil(deltaMs: Long, sampleRate: Int): Long =
        if (deltaMs <= 0) 0 else (deltaMs * sampleRate + 999) / 1000

    private fun frameFloor(deltaMs: Long, sampleRate: Int): Long =
        if (deltaMs <= 0) 0 else deltaMs * sampleRate / 1000

    private const val COPY_BUFFER_BYTES = 64 * 1024
}
