package com.nsn8.vued.audio

import java.io.File

/**
 * Disk-backed rolling WAV buffer for mic-array (UMA-8/UMA-16) source audio.
 *
 * Accepts 48 kHz interleaved int32 PCM from the UMA native reader and stores
 * 16 kHz interleaved PCM16 WAV segments.
 */
class MultiChannelWavRollingBuffer(
    val directory: File,
    val channels: Int,
    private val sampleRate: Int = SAMPLE_RATE_16K,
    private val segmentSeconds: Int = DEFAULT_SEGMENT_SECONDS,
    private val retentionSeconds: Long = 72 * 60 * 60,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val freeSpaceFloorBytes: Long = DiskSpaceGuard.DEFAULT_FLOOR_BYTES,
    private val freeBytesProvider: () -> Long = { DiskSpaceGuard.freeBytes(directory) },
    // Settable so the meeting controller can subscribe to segment closes (to
    // drive incremental upload) only while a meeting is active.
    @Volatile var onSegmentClosed: (file: File, count: Int) -> Unit = { _, _ -> },
) {
    data class Segment(
        val file: File,
        val startMs: Long,
        val endMs: Long,
        val frameCount: Long,
    )

    private val lock = Any()
    private val resampler = MultiChannelResampler48to16(channels)
    private val framesPerSegment = sampleRate.toLong() * segmentSeconds

    private var writer: Pcm16WavSegmentWriter? = null
    private var currentFile: File? = null
    private var framesInSegment = 0L

    var segmentCount = 0
        private set
    var lastSegmentPath: String? = null
        private set

    init {
        require(channels > 0) { "channels must be positive" }
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(segmentSeconds > 0) { "segmentSeconds must be positive" }
        directory.mkdirs()
    }

    fun append48kInt32Le(buffer: ByteArray, length: Int): Int {
        val frames = resampler.process(buffer, length)
        if (frames <= 0) return 0
        appendInterleavedPcm16(resampler.output, frames)
        return frames
    }

    fun appendInterleavedPcm16(samples: ShortArray, frames: Int) {
        if (frames <= 0) return
        synchronized(lock) {
            if (writer == null || framesInSegment >= framesPerSegment) {
                rotate()
            }
            writer?.writeInterleaved(samples, frames)
            framesInSegment += frames
        }
    }

    fun flush() = synchronized(lock) { finalizeCurrent() }

    fun close() = synchronized(lock) { finalizeCurrent() }

    fun listSegments(): List<Segment> = synchronized(lock) {
        val inProgress = if (writer != null) currentFile else null
        directory.listFiles { file -> file.name.endsWith(".wav") }
            ?.filter { it != inProgress }
            ?.mapNotNull { segmentFor(it, sampleRate, channels, segmentSeconds) }
            ?.sortedBy { it.startMs }
            ?: emptyList()
    }

    private fun finalizeCurrent() {
        writer?.let { active ->
            runCatching { active.finish() }
            currentFile?.let { onSegmentClosed(it, segmentCount) }
        }
        writer = null
        currentFile = null
        framesInSegment = 0
    }

    private fun rotate() {
        finalizeCurrent()
        pruneOld()

        val startSec = clockMs() / 1000
        val file = File(directory, "$startSec.wav")
        currentFile = file
        writer = runCatching {
            Pcm16WavSegmentWriter(file, sampleRate = sampleRate, channels = channels)
        }.getOrNull()
        framesInSegment = 0
        segmentCount += 1
        lastSegmentPath = file.absolutePath
    }

    private fun pruneOld() {
        val cutoff = clockMs() / 1000 - retentionSeconds
        directory.listFiles { file -> file.name.endsWith(".wav") }?.forEach { file ->
            val startSec = file.nameWithoutExtension.toLongOrNull() ?: return@forEach
            if (startSec < cutoff) {
                runCatching { file.delete() }
            }
        }
        DiskSpaceGuard.enforceFloor(
            directory,
            ".wav",
            protect = currentFile,
            floorBytes = freeSpaceFloorBytes,
            freeBytes = freeBytesProvider,
        )
    }

    companion object {
        const val CHANNELS_UMA8 = 7
        const val CHANNELS_UMA16 = 16
        const val SAMPLE_RATE_16K = 16_000
        const val DEFAULT_SEGMENT_SECONDS = 30

        /**
         * Lists finalized segments in [directory], sorted by start time, for
         * callers without the buffer instance (e.g. the upload drain). Unlike
         * [listSegments] it cannot know which file is still being written, so
         * the caller must withhold the most recent segment during a live
         * meeting (its data is still growing). An in-progress file parses with a
         * zero data size and therefore contributes no bytes regardless.
         */
        fun listSegmentsIn(
            directory: File,
            sampleRate: Int = SAMPLE_RATE_16K,
            channels: Int,
            segmentSeconds: Int = DEFAULT_SEGMENT_SECONDS,
        ): List<Segment> =
            directory.listFiles { file -> file.name.endsWith(".wav") }
                ?.mapNotNull { segmentFor(it, sampleRate, channels, segmentSeconds) }
                ?.sortedBy { it.startMs }
                ?: emptyList()

        /**
         * Deletes finalized segments fully inside [startMs, endMs] — the
         * upload-completion reaper. Zero-frame files are skipped: an
         * in-progress writer patches its WAV header only on finish(), so a
         * live file parses as zero frames and would otherwise look "fully
         * covered" via the fallback duration. Boundary-straddling segments
         * survive to the age/floor prunes. Returns the number deleted.
         */
        fun deleteSegmentsCoveredBy(
            directory: File,
            startMs: Long,
            endMs: Long,
            sampleRate: Int = SAMPLE_RATE_16K,
            channels: Int,
            segmentSeconds: Int = DEFAULT_SEGMENT_SECONDS,
        ): Int {
            var deleted = 0
            directory.listFiles { file -> file.name.endsWith(".wav") }?.forEach { file ->
                val segment = segmentFor(file, sampleRate, channels, segmentSeconds) ?: return@forEach
                if (segment.frameCount <= 0L) return@forEach
                if (segment.startMs >= startMs && segment.endMs <= endMs &&
                    runCatching { file.delete() }.getOrDefault(false)
                ) {
                    deleted += 1
                }
            }
            return deleted
        }

        fun segmentFor(
            file: File,
            sampleRate: Int = SAMPLE_RATE_16K,
            channels: Int,
            segmentSeconds: Int = DEFAULT_SEGMENT_SECONDS,
        ): Segment? {
            val startMs = (file.nameWithoutExtension.toLongOrNull() ?: return null) * 1000
            val info = Pcm16WavSegmentWriter.readInfo(file) ?: return null
            if (info.sampleRate != sampleRate || info.channels != channels || info.bitsPerSample != 16) {
                return null
            }
            val durationMs = info.frameCount * 1000L / sampleRate
            val fallbackDurationMs = segmentSeconds * 1000L
            val endMs = startMs + if (durationMs > 0) durationMs else fallbackDurationMs
            return Segment(file, startMs, endMs, info.frameCount)
        }
    }
}
