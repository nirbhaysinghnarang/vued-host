package com.nsn8.vued.meeting

import android.content.Context
import com.nsn8.vued.audio.RollingBuffer
import com.nsn8.vued.audio.SegmentExporter
import com.nsn8.vued.net.OutboundQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Coordinates meeting Start/Stop on top of the always-on ambient buffer.
 *
 *  - Start: mint a meeting id, POST /meetings, mark the window start.
 *  - Stop:  flush the rolling buffer, export the [start,end] window into one M4A,
 *           POST the audio-slice metadata, then PUT the bytes (which kicks the
 *           server's transcribe → encrypt pipeline). The server writes ciphertext.
 *
 * The active [RollingBuffer] is registered by the recording service via [attach].
 */
object MeetingController {

    data class ActiveMeeting(val meetingId: String, val startMs: Long)
    data class StopResult(val meetingId: String, val durationSecs: Double, val sizeBytes: Long)

    // One session id per app process (matches iOS session semantics).
    private val sessionId: String = UUID.randomUUID().toString()

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
        OutboundQueue.enqueueMeetingCreate(context, meetingId, title, startMs / 1000.0)
        active = ActiveMeeting(meetingId, startMs)
        OutboundQueue.drain(context) // attempt the create now (online); leaves it queued offline
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
        val endMs = System.currentTimeMillis()

        // Finalize the in-progress segment so the meeting tail is on disk, then export.
        buffer.flush()
        val out = File(context.cacheDir, "meeting_${meeting.meetingId}.m4a")
        val export = SegmentExporter.exportWindow(buffer.listSegments(), meeting.startMs, endMs, out)
            ?: error("No audio captured for this meeting window.")

        val sliceId = UUID.randomUUID().toString()
        val durationSecs = export.durationMs / 1000.0
        val sizeBytes = out.length()
        // Durably enqueue BEFORE any network — consumes `out` and is the commit point,
        // so stopping a meeting never loses audio even offline. Then drain to upload.
        OutboundQueue.enqueueMeeting(
            context, sliceId, sessionId, meeting.meetingId,
            meeting.startMs / 1000.0, endMs / 1000.0, durationSecs, out,
        )
        active = null
        OutboundQueue.drain(context) // upload this slice + any offline backlog
        StopResult(meeting.meetingId, durationSecs, sizeBytes)
    }
}
