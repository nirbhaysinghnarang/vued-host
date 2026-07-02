package com.nsn8.vued.audio

import android.util.Log
import com.nsn8.vued.DiagnosticsLogger
import java.io.File

/**
 * Continuous on-disk ring of fixed-length AAC/M4A segments — the local store meeting
 * windows are exported from (mirrors the iOS RollingAudioBuffer).
 *
 * Accepts mono 16 kHz float samples, converts to 16-bit PCM, and rotates to a new
 * segment every [segmentSeconds]. Filenames encode the segment start unix second
 * (`<startSec>.m4a`); files older than [retentionSeconds] are pruned.
 *
 * Thread-safe: [append] runs on the capture thread while [flush]/[listSegments] may
 * be called from the meeting flow on another thread.
 */
class RollingBuffer(
    private val directory: File,
    private val sampleRate: Int = 16_000,
    private val segmentSeconds: Int = 30,
    private val retentionSeconds: Long = 72 * 60 * 60,
    private val onSegmentClosed: (file: File, count: Int) -> Unit = { _, _ -> },
) {
    /** A finalized, readable segment and its approximate wall-clock span. */
    data class Segment(val file: File, val startMs: Long, val endMs: Long)

    private val lock = Any()
    private val samplesPerSegment = sampleRate.toLong() * segmentSeconds

    private var writer: AacM4aSegmentWriter? = null
    private var currentFile: File? = null
    private var samplesInSegment = 0L
    private var pcmScratch = ShortArray(0)

    var segmentCount = 0
        private set
    var lastSegmentPath: String? = null
        private set

    init {
        directory.mkdirs()
    }

    /** Appends [count] mono float samples (clamped to 16-bit), rotating as needed. */
    fun append(samples: FloatArray, count: Int) {
        if (count <= 0) return
        synchronized(lock) {
            if (writer == null || samplesInSegment >= samplesPerSegment) {
                rotate()
            }
            if (pcmScratch.size < count) {
                pcmScratch = ShortArray(count)
            }
            for (i in 0 until count) {
                val scaled = samples[i] * 32767f
                pcmScratch[i] = when {
                    scaled >= 32767f -> Short.MAX_VALUE
                    scaled <= -32768f -> Short.MIN_VALUE
                    else -> scaled.toInt().toShort()
                }
            }
            writer?.write(pcmScratch, count)
            samplesInSegment += count
        }
    }

    /**
     * Finalizes the in-progress segment so it's readable on disk, then keeps
     * recording (the next [append] starts a fresh segment). Call this before
     * exporting a meeting window so its tail isn't stuck in an open file.
     */
    fun flush() = synchronized(lock) { finalizeCurrent() }

    /** Finalizes and stops. Safe to call repeatedly. */
    fun close() = synchronized(lock) { finalizeCurrent() }

    /** All finalized segments (the open one is excluded), sorted by start time. */
    fun listSegments(): List<Segment> = synchronized(lock) {
        val inProgress = if (writer != null) currentFile else null
        directory.listFiles { f -> f.name.endsWith(".m4a") }
            ?.filter { it != inProgress }
            ?.mapNotNull { segmentFor(it, segmentSeconds) }
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
        samplesInSegment = 0
    }

    private fun rotate() {
        finalizeCurrent()
        pruneOld()

        val startSec = System.currentTimeMillis() / 1000
        val file = File(directory, "$startSec.m4a")
        currentFile = file
        writer = try {
            AacM4aSegmentWriter(file, sampleRate = sampleRate)
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to open segment ${file.name}: ${error.message}")
            DiagnosticsLogger.error("rolling_buffer_segment_open_failed", mapOf("fileName" to file.name), error)
            null
        }
        samplesInSegment = 0
        segmentCount += 1
        lastSegmentPath = file.absolutePath
    }

    private fun pruneOld() {
        val cutoff = System.currentTimeMillis() / 1000 - retentionSeconds
        directory.listFiles { f -> f.name.endsWith(".m4a") }?.forEach { f ->
            val startSec = f.nameWithoutExtension.toLongOrNull() ?: return@forEach
            if (startSec < cutoff) {
                runCatching { f.delete() }
            }
        }
    }

    companion object {
        private const val TAG = "VuedRollingBuffer"
        const val DEFAULT_SEGMENT_SECONDS = 30

        fun deleteSegmentsCoveredBy(directory: File, startMs: Long, endMs: Long) {
            directory.listFiles { f -> f.name.endsWith(".m4a") }?.forEach { file ->
                val segment = segmentFor(file, DEFAULT_SEGMENT_SECONDS) ?: return@forEach
                if (segment.startMs >= startMs && segment.endMs <= endMs) {
                    runCatching { file.delete() }
                }
            }
        }

        private fun segmentFor(file: File, segmentSeconds: Int): Segment? {
            val startMs = (file.nameWithoutExtension.toLongOrNull() ?: return null) * 1000
            val fallbackEndMs = startMs + segmentSeconds * 1000L
            val modifiedMs = file.lastModified()
            val endMs = if (modifiedMs in (startMs + 1) until fallbackEndMs) modifiedMs else fallbackEndMs
            return Segment(file, startMs, endMs)
        }
    }
}
