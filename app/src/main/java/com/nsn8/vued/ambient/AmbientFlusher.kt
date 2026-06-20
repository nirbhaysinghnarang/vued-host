package com.nsn8.vued.ambient

import android.content.Context
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
 * slice — the always-on counterpart to explicit meetings. The server transcribes it
 * and (for encrypted users) seals each event's text into `transcript_events.text_enc`
 * automatically; nothing here knows about encryption.
 *
 * The recording service registers the live buffer via [attach] and drives [flushOnce]
 * on a 5-minute timer; the UI can also trigger [flushOnce] on demand.
 */
object AmbientFlusher {

    const val INTERVAL_MS = 5 * 60 * 1000L

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
    }

    fun detach() {
        rolling = null
    }

    /**
     * Advance the ambient cursor past a finished meeting window so its audio (already
     * captured by the meeting slice) isn't re-flushed as ambient. Called by
     * [MeetingController.stop].
     */
    fun resumeAfter(endMs: Long) {
        if (endMs > lastFlushMs) lastFlushMs = endMs
    }

    /**
     * Exports everything since the last flush into one ambient slice, commits it to the
     * durable [OutboundQueue], then opportunistically drains the queue. The export is
     * the commit point — once it's on disk the cursor advances and the audio cannot be
     * lost, regardless of network. A failed/offline upload simply stays queued.
     */
    suspend fun flushOnce(context: Context): String = withContext(Dispatchers.IO) {
        val buffer = rolling ?: return@withContext "not capturing"
        val now = System.currentTimeMillis()
        val windowStart = lastFlushMs

        // Yield to an active meeting: only cover up to the meeting's start (the
        // pre-meeting tail), then pause. The meeting captures its own window, and
        // MeetingController.stop() advances our cursor past it via resumeAfter().
        val meetingStart = MeetingController.active?.startMs
        val windowEnd = if (meetingStart != null) minOf(now, meetingStart) else now
        if (windowEnd <= windowStart) {
            return@withContext if (meetingStart != null) "ambient paused (meeting active)" else "nothing to flush yet"
        }

        buffer.flush()
        val out = File(context.cacheDir, "ambient_$now.m4a")
        val export = SegmentExporter.exportWindow(buffer.listSegments(), windowStart, windowEnd, out)
        if (export == null) {
            out.delete()
            lastFlushMs = windowEnd
            return@withContext "no audio in window"
        }
        val sliceId = UUID.randomUUID().toString()
        val durationSecs = export.durationMs / 1000.0
        // Durably enqueue BEFORE any network — this consumes `out` and is the commit point.
        OutboundQueue.enqueueAmbient(context, sliceId, sessionId, windowStart / 1000.0, windowEnd / 1000.0, durationSecs, out)
        lastFlushMs = windowEnd
        lastUploadMs = windowEnd
        OutboundQueue.drain(context) // upload this slice + any offline backlog
        "queued ambient slice (${"%.1f".format(durationSecs)}s); ${OutboundQueue.size(context)} pending"
    }
}
