package com.nsn8.vued.meeting

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.nsn8.vued.ambient.AmbientFlusher
import com.nsn8.vued.audio.RollingBuffer
import com.nsn8.vued.audio.SegmentExporter
import com.nsn8.vued.net.OutboundQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Coordinates meeting Start/Stop on top of the always-on ambient buffer.
 *
 *  - Start: mint a meeting id, POST /meetings, mark the window start.
 *  - Stop:  flush the rolling buffer, export the [start,end] window into one M4A,
 *           POST the audio-slice metadata, then PUT the bytes to kick the server's
 *           transcription/finalization pipeline.
 *
 * The active [RollingBuffer] is registered by the recording service via [attach].
 */
object MeetingController {

    data class ActiveMeeting(val meetingId: String, val startMs: Long)
    data class StopResult(val meetingId: String, val durationSecs: Double, val sizeBytes: Long)

    private const val TAG = "VuedMeeting"

    // One session id per app process (matches iOS session semantics).
    private val sessionId: String = UUID.randomUUID().toString()
    private val queueScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var rolling: RollingBuffer? = null

    @Volatile
    var active: ActiveMeeting? = null
        private set

    val isCapturing: Boolean get() = rolling != null

    fun attach(buffer: RollingBuffer) {
        rolling = buffer
    }

    fun detach() {
        rolling = null
    }

    /**
     * Begins a meeting; returns the meeting id. Requires ambient capture running.
     *
     * The `POST /meetings` create is **enqueued** (durable, offline-safe) rather than
     * sent inline, and the queue guarantees it lands before this meeting's audio slice
     * uploads. So a meeting started offline is never lost and never races its audio.
     */
    suspend fun start(context: Context, title: String): String {
        check(rolling != null) { "Start recording first — the ambient buffer isn't running." }
        check(active == null) { "A meeting is already in progress." }
        val meetingId = UUID.randomUUID().toString().replace("-", "")
        val startMs = System.currentTimeMillis()
        Log.i(TAG, "start meeting=$meetingId title=$title startMs=$startMs")
        OutboundQueue.enqueueMeetingCreate(context, meetingId, title, startMs / 1000.0)
        active = ActiveMeeting(meetingId, startMs)
        val appContext = context.applicationContext
        queueScope.launch {
            Log.i(TAG, "start drain begin meeting=$meetingId")
            runCatching { OutboundQueue.drain(appContext) }
                .onSuccess { Log.i(TAG, "start drain done meeting=$meetingId pending=${OutboundQueue.size(appContext)}") }
                .onFailure { Log.w(TAG, "start drain failed meeting=$meetingId: ${it.message}", it) }
        }
        return meetingId
    }

    /**
     * Ends the active meeting: export the window and upload the slice.
     *
     * Runs on [Dispatchers.IO] — the export concatenates the whole meeting window
     * (an hour of 30 s segments is ~60k AAC frames) through MediaExtractor/MediaMuxer,
     * which is seconds of CPU. Doing that here keeps it off whatever dispatcher the
     * caller launched on (Compose's Main, the LAN server, etc.).
     */
    suspend fun stop(context: Context): StopResult = withContext(Dispatchers.IO) {
        val meeting = active ?: error("No active meeting.")
        val buffer = rolling ?: error("Ambient buffer not running.")
        val totalStartMs = SystemClock.elapsedRealtime()
        val endMs = System.currentTimeMillis()
        active = null
        Log.i(TAG, "stop begin meeting=${meeting.meetingId} windowMs=${endMs - meeting.startMs}")
        // Resume ambient from here so it doesn't re-flush the meeting window as ambient.
        // Done before the export so it holds even if this meeting captured no audio.
        AmbientFlusher.resumeAfter(endMs)

        // Finalize the in-progress segment so the meeting tail is on disk, then export.
        val flushStartMs = SystemClock.elapsedRealtime()
        buffer.flush()
        Log.i(TAG, "stop flush done meeting=${meeting.meetingId} elapsedMs=${SystemClock.elapsedRealtime() - flushStartMs}")
        val segments = buffer.listSegments()
        Log.i(TAG, "stop segments meeting=${meeting.meetingId} count=${segments.size}")
        val out = File(context.cacheDir, "meeting_${meeting.meetingId}.m4a")
        val exportStartMs = SystemClock.elapsedRealtime()
        val export = SegmentExporter.exportWindow(segments, meeting.startMs, endMs, out)
        if (export == null) {
            Log.w(TAG, "stop export empty meeting=${meeting.meetingId}")
            error("No audio captured for this meeting window.")
        }
        Log.i(
            TAG,
            "stop export done meeting=${meeting.meetingId} segments=${export.segmentCount} " +
                "durationMs=${export.durationMs} bytes=${out.length()} elapsedMs=${SystemClock.elapsedRealtime() - exportStartMs}",
        )

        val sliceId = UUID.randomUUID().toString()
        val durationSecs = export.durationMs / 1000.0
        val sizeBytes = out.length()
        // Durably enqueue BEFORE any network — consumes `out` and is the commit point,
        // so stopping a meeting never loses audio even offline. Then drain to upload.
        val enqueueStartMs = SystemClock.elapsedRealtime()
        OutboundQueue.enqueueMeeting(
            context, sliceId, sessionId, meeting.meetingId,
            meeting.startMs / 1000.0, endMs / 1000.0, durationSecs, out,
        )
        Log.i(
            TAG,
            "stop enqueue done meeting=${meeting.meetingId} slice=$sliceId " +
                "elapsedMs=${SystemClock.elapsedRealtime() - enqueueStartMs}",
        )
        val drainStartMs = SystemClock.elapsedRealtime()
        OutboundQueue.drain(context) // upload this slice + any offline backlog
        Log.i(
            TAG,
            "stop drain done meeting=${meeting.meetingId} slice=$sliceId " +
                "pending=${OutboundQueue.size(context)} elapsedMs=${SystemClock.elapsedRealtime() - drainStartMs}",
        )
        Log.i(TAG, "stop done meeting=${meeting.meetingId} totalElapsedMs=${SystemClock.elapsedRealtime() - totalStartMs}")
        StopResult(meeting.meetingId, durationSecs, sizeBytes)
    }
}
