package com.nsn8.vued.ambient

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.nsn8.vued.DiagnosticsLogger
import com.nsn8.vued.VuedConfig
import com.nsn8.vued.audio.MultiChannelWavRollingBuffer
import com.nsn8.vued.audio.RollingBuffer
import com.nsn8.vued.audio.SegmentExporter
import com.nsn8.vued.audio.WavSegmentExporter
import com.nsn8.vued.meeting.MeetingController
import com.nsn8.vued.net.OutboundQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.log10

/**
 * Flushes a window of the rolling buffer and uploads it as a `modality=ambient`
 * slice — the always-on counterpart to explicit meetings. The server handles
 * transcription/finalization; nothing here knows about decryption.
 *
 * The recording service registers the live buffer via [attach] and drives [flushOnce]
 * on a 5-minute timer; the UI can also trigger [flushOnce] on demand.
 */
object AmbientFlusher {

    const val INTERVAL_MS = 5 * 60 * 1000L
    private const val TAG = "VuedAmbientFlusher"

    private val sessionId: String = UUID.randomUUID().toString()

    @Volatile
    private var rolling: RollingBuffer? = null

    @Volatile
    private var sourceBufferProvider: () -> MultiChannelWavRollingBuffer? = { null }

    @Volatile
    private var windowPeakProvider: () -> Float = { Float.MAX_VALUE }

    @Volatile
    private var lastFlushMs: Long = 0

    @Volatile
    var lastUploadMs: Long = 0
        private set

    fun attach(
        buffer: RollingBuffer,
        sourceBuffer: () -> MultiChannelWavRollingBuffer? = { null },
        windowPeak: () -> Float = { Float.MAX_VALUE },
    ) {
        rolling = buffer
        sourceBufferProvider = sourceBuffer
        windowPeakProvider = windowPeak
        lastFlushMs = System.currentTimeMillis()
        Log.i(TAG, "attached lastFlushMs=$lastFlushMs")
    }

    fun detach() {
        rolling = null
        sourceBufferProvider = { null }
        windowPeakProvider = { Float.MAX_VALUE }
        Log.i(TAG, "detached")
    }

    /**
     * Advance the ambient cursor past a finished meeting window so its audio (already
     * captured by the meeting slice) isn't re-flushed as ambient. Called by
     * [MeetingController.stop].
     */
    fun resumeAfter(endMs: Long) {
        if (endMs > lastFlushMs) lastFlushMs = endMs
        Log.i(TAG, "resumeAfter endMs=$endMs lastFlushMs=$lastFlushMs")
    }

    /**
     * Exports everything since the last flush into one ambient slice, commits it to the
     * durable [OutboundQueue], then opportunistically drains the queue. The export is
     * the commit point — once it's on disk the cursor advances and the audio cannot be
     * lost, regardless of network. A failed/offline upload simply stays queued.
     */
    suspend fun flushOnce(context: Context): String = withContext(Dispatchers.IO) {
        val totalStartMs = SystemClock.elapsedRealtime()
        val buffer = rolling ?: run {
            Log.i(TAG, "flush skipped: not capturing")
            return@withContext "not capturing"
        }
        val now = System.currentTimeMillis()
        val windowStart = lastFlushMs

        // Yield to an active meeting: only cover up to the meeting's start (the
        // pre-meeting tail), then pause. The meeting captures its own window, and
        // MeetingController.stop() advances our cursor past it via resumeAfter().
        val meetingStart = MeetingController.active?.startMs
        val windowEnd = if (meetingStart != null) minOf(now, meetingStart) else now
        if (windowEnd <= windowStart) {
            val reason = if (meetingStart != null) "ambient paused (meeting active)" else "nothing to flush yet"
            Log.i(TAG, "flush skipped: $reason windowMs=${windowEnd - windowStart}")
            return@withContext reason
        }
        Log.i(TAG, "flush begin windowMs=${windowEnd - windowStart} meetingActive=${meetingStart != null}")

        val flushStartMs = SystemClock.elapsedRealtime()
        buffer.flush()
        Log.i(TAG, "flush buffer done elapsedMs=${SystemClock.elapsedRealtime() - flushStartMs}")
        val segments = buffer.listSegments()
        Log.i(TAG, "flush segments count=${segments.size}")
        val out = File(context.cacheDir, "ambient_$now.m4a")
        val exportStartMs = SystemClock.elapsedRealtime()
        val export = SegmentExporter.exportWindow(segments, windowStart, windowEnd, out)
        if (export == null) {
            out.delete()
            lastFlushMs = windowEnd
            Log.i(TAG, "flush export empty windowMs=${windowEnd - windowStart}")
            DiagnosticsLogger.info("ambient_export_empty", mapOf("windowMs" to (windowEnd - windowStart)))
            return@withContext "no audio in window"
        }
        Log.i(
            TAG,
            "flush export done segments=${export.segmentCount} durationMs=${export.durationMs} " +
                "bytes=${out.length()} elapsedMs=${SystemClock.elapsedRealtime() - exportStartMs}",
        )
        DiagnosticsLogger.info("ambient_export_completed", mapOf(
            "segments" to export.segmentCount,
            "durationMs" to export.durationMs,
            "bytes" to out.length(),
            "elapsedMs" to (SystemClock.elapsedRealtime() - exportStartMs),
        ))
        val sliceId = UUID.randomUUID().toString()
        val durationSecs = export.durationMs / 1000.0
        val monoSizeBytes = out.length() // enqueueAmbient consumes `out`
        // Durably enqueue BEFORE any network — this consumes `out` and is the commit point.
        OutboundQueue.enqueueAmbient(context, sliceId, sessionId, windowStart / 1000.0, windowEnd / 1000.0, durationSecs, out)
        lastFlushMs = windowEnd
        lastUploadMs = windowEnd
        // Mic-array source sidecar for the same window/slice; never allowed to
        // break the canonical ambient flush.
        val sidecarOutcome = runCatching {
            enqueueSourceSidecar(context, sliceId, windowStart, windowEnd, durationSecs, monoSizeBytes)
        }.getOrElse { error ->
            Log.w(TAG, "ambient source sidecar failed: ${error.message}")
            DiagnosticsLogger.warn("ambient_source_wav_failed", mapOf("sliceId" to sliceId), error)
            null // failed: keep the window's raw segments for the 72h fallback
        }
        // The window is never revisited (cursor already advanced), so once its
        // bytes are durably enqueued — or the skip decision is final — the raw
        // uma16 segments it covers are dead weight (~1.8GB/hour if retained).
        if (sidecarOutcome?.windowConsumed == true) {
            sourceBufferProvider()?.let { sourceBuffer ->
                runCatching {
                    val deleted = MultiChannelWavRollingBuffer.deleteSegmentsCoveredBy(
                        sourceBuffer.directory, windowStart, windowEnd, channels = sourceBuffer.channels,
                    )
                    if (deleted > 0) {
                        DiagnosticsLogger.info("ambient_source_segments_deleted", mapOf(
                            "sliceId" to sliceId,
                            "deleted" to deleted,
                            "outcome" to sidecarOutcome.name,
                            "windowMs" to (windowEnd - windowStart),
                        ))
                    }
                }
            }
        }
        val drainStartMs = SystemClock.elapsedRealtime()
        OutboundQueue.drain(context) // upload this slice + any offline backlog
        Log.i(
            TAG,
            "flush drain done slice=$sliceId pending=${OutboundQueue.size(context)} " +
                "elapsedMs=${SystemClock.elapsedRealtime() - drainStartMs} totalElapsedMs=${SystemClock.elapsedRealtime() - totalStartMs}",
        )
        DiagnosticsLogger.info("ambient_flush_completed", mapOf(
            "sliceId" to sliceId,
            "pending" to OutboundQueue.size(context),
            "durationSecs" to durationSecs,
            "drainElapsedMs" to (SystemClock.elapsedRealtime() - drainStartMs),
            "totalElapsedMs" to (SystemClock.elapsedRealtime() - totalStartMs),
        ))
        "queued ambient slice (${"%.1f".format(durationSecs)}s); ${OutboundQueue.size(context)} pending"
    }

    /**
     * How a window's 16-ch sidecar concluded. [windowConsumed] marks outcomes
     * where the decision is final and the window's raw uma16 segments can be
     * deleted: bytes durably enqueued, or an intentional skip that will never
     * be revisited. Failures (exception, backlog drop) keep the segments for
     * the 72h fallback.
     */
    private enum class SidecarOutcome(val windowConsumed: Boolean) {
        ENQUEUED(true),
        SKIPPED_DISABLED(true),
        SKIPPED_SILENT(true),
        EXPORT_EMPTY(true),
        NO_SOURCE_BUFFER(false),
        DROPPED_BACKLOG(false),
    }

    /**
     * Exports the window from the mic-array source buffer and enqueues it as a
     * sidecar on the same slice as the mono item. Skipped when disabled, when
     * no mic-array buffer exists, or when the window never rose above the silence
     * gate (the peak accumulator is read unconditionally so it resets per window).
     */
    private fun enqueueSourceSidecar(
        context: Context,
        sliceId: String,
        windowStart: Long,
        windowEnd: Long,
        durationSecs: Double,
        monoSizeBytes: Long,
    ): SidecarOutcome {
        val windowPeak = windowPeakProvider()
        if (!VuedConfig.AMBIENT_SOURCE_WAV_UPLOAD) return SidecarOutcome.SKIPPED_DISABLED
        val source = sourceBufferProvider() ?: return SidecarOutcome.NO_SOURCE_BUFFER
        val peakDb = if (windowPeak > 0f) 20f * log10(windowPeak) else Float.NEGATIVE_INFINITY
        if (peakDb < VuedConfig.AMBIENT_SOURCE_WAV_MIN_PEAK_DB) {
            Log.i(TAG, "ambient source sidecar skipped: silent window peakDb=$peakDb")
            DiagnosticsLogger.info("ambient_source_wav_skipped_silent", mapOf(
                "sliceId" to sliceId,
                "peakDb" to peakDb,
            ))
            return SidecarOutcome.SKIPPED_SILENT
        }
        source.flush()
        // WavPack halves-or-better the ~150 MB raw window; same container the
        // streaming meeting path ships, so server/Modal need nothing new.
        val compress = VuedConfig.SOURCE_WAV_CODEC == "wavpack"
        val codec = if (compress) "wavpack" else "pcm"
        val out = File(
            context.cacheDir,
            if (compress) {
                "ambient_${windowEnd}_${source.channels}ch_16k.wv"
            } else {
                "ambient_${windowEnd}_${source.channels}ch_16k.wav"
            },
        )
        val export = if (compress) {
            WavSegmentExporter.exportWindowWv(
                source.listSegments(),
                windowStart,
                windowEnd,
                out,
                blobCacheDir = File(context.cacheDir, "ambient_wv_blobs"),
            )
        } else {
            WavSegmentExporter.exportWindow(source.listSegments(), windowStart, windowEnd, out)
        }
        if (export == null) {
            out.delete()
            Log.i(TAG, "ambient source sidecar export empty windowMs=${windowEnd - windowStart}")
            DiagnosticsLogger.info("ambient_source_wav_export_empty", mapOf(
                "sliceId" to sliceId,
                "windowMs" to (windowEnd - windowStart),
            ))
            return SidecarOutcome.EXPORT_EMPTY
        }
        DiagnosticsLogger.info("ambient_source_wav_export_completed", mapOf(
            "sliceId" to sliceId,
            "segments" to export.segmentCount,
            "durationMs" to export.durationMs,
            "bytes" to out.length(),
            "codec" to codec,
        ))
        val enqueued = OutboundQueue.enqueueAmbientSourceWav(
            context,
            sliceId = sliceId,
            sessionId = sessionId,
            startedAtSec = windowStart / 1000.0,
            endedAtSec = windowEnd / 1000.0,
            durationSecs = durationSecs,
            monoSizeBytes = monoSizeBytes,
            channels = source.channels,
            source = out,
            codec = codec,
        )
        return if (enqueued != null) SidecarOutcome.ENQUEUED else SidecarOutcome.DROPPED_BACKLOG
    }
}
