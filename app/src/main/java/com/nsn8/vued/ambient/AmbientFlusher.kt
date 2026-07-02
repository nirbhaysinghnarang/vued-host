package com.nsn8.vued.ambient

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.nsn8.vued.DiagnosticsLogger
import com.nsn8.vued.audio.RollingBuffer
import com.nsn8.vued.audio.SegmentExporter
import com.nsn8.vued.meeting.MeetingController
import com.nsn8.vued.net.OutboundQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

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
    private var lastFlushMs: Long = 0

    @Volatile
    var lastUploadMs: Long = 0
        private set

    fun attach(buffer: RollingBuffer) {
        rolling = buffer
        lastFlushMs = System.currentTimeMillis()
        Log.i(TAG, "attached lastFlushMs=$lastFlushMs")
    }

    fun detach() {
        rolling = null
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
        // Durably enqueue BEFORE any network — this consumes `out` and is the commit point.
        OutboundQueue.enqueueAmbient(context, sliceId, sessionId, windowStart / 1000.0, windowEnd / 1000.0, durationSecs, out)
        lastFlushMs = windowEnd
        lastUploadMs = windowEnd
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
}
