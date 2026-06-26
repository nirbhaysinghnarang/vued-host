package com.nsn8.vued.meeting

import android.content.Context
import android.util.Log
import com.nsn8.vued.ambient.AmbientFlusher
import com.nsn8.vued.audio.RollingBuffer
import com.nsn8.vued.audio.SegmentExporter
import com.nsn8.vued.net.OutboundQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
    data class StopResult(val meetingId: String, val durationSecs: Double)
    private data class ClosedMeeting(val meetingId: String, val startMs: Long, val endMs: Long)

    private const val TAG = "VuedMeetingController"
    private const val PREFS = "vued_meeting_exports"
    private const val KEY_PENDING = "pending"

    // One session id per app process (matches iOS session semantics).
    private val sessionId: String = UUID.randomUUID().toString()
    private val queueScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val exportSignals = Channel<Context>(Channel.CONFLATED)
    private val lock = Any()

    init {
        queueScope.launch {
            for (context in exportSignals) drainPendingExports(context)
        }
    }

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
        val buffer = rolling ?: error("Start recording first — the ambient buffer isn't running.")
        check(active == null) { "A meeting is already in progress." }
        buffer.flush()
        val meetingId = UUID.randomUUID().toString().replace("-", "")
        val startMs = System.currentTimeMillis()
        OutboundQueue.enqueueMeetingCreate(context, meetingId, title, startMs / 1000.0)
        active = ActiveMeeting(meetingId, startMs)
        val appContext = context.applicationContext
        queueScope.launch { runCatching { OutboundQueue.drain(appContext) } }
        retryPendingExports(appContext)
        return meetingId
    }

    suspend fun stop(context: Context): StopResult = withContext(Dispatchers.IO) {
        val meeting = active ?: error("No active meeting.")
        val endMs = System.currentTimeMillis()
        active = null
        AmbientFlusher.resumeAfter(endMs)
        val appContext = context.applicationContext
        val durationSecs = exportAndEnqueue(appContext, ClosedMeeting(meeting.meetingId, meeting.startMs, endMs))
        OutboundQueue.drain(appContext)
        StopResult(meeting.meetingId, durationSecs)
    }

    fun stopAsync(context: Context) {
        val meeting = active ?: error("No active meeting.")
        val closed = ClosedMeeting(meeting.meetingId, meeting.startMs, System.currentTimeMillis())
        persistPending(context.applicationContext, closed)
        active = null
        AmbientFlusher.resumeAfter(closed.endMs)
        retryPendingExports(context.applicationContext)
    }

    fun retryPendingExports(context: Context) {
        exportSignals.trySend(context.applicationContext)
    }

    private suspend fun drainPendingExports(context: Context) {
        pendingExports(context).forEach { meeting ->
            runCatching {
                exportAndEnqueue(context, meeting)
                removePending(context, meeting.meetingId)
                OutboundQueue.drain(context)
            }.onFailure { error ->
                Log.w(TAG, "meeting ${meeting.meetingId} export still pending: ${error.message}")
            }
        }
    }

    private fun exportAndEnqueue(context: Context, meeting: ClosedMeeting): Double {
        val buffer = rolling ?: error("Ambient buffer not running.")
        buffer.flush()
        val out = File(context.cacheDir, "meeting_${meeting.meetingId}.m4a")
        val export = SegmentExporter.exportWindow(buffer.listSegments(), meeting.startMs, meeting.endMs, out)
            ?: error("No audio captured for this meeting window.")
        val sliceId = UUID.nameUUIDFromBytes("meeting:${meeting.meetingId}".toByteArray()).toString()
        val durationSecs = export.durationMs / 1000.0
        OutboundQueue.enqueueMeeting(
            context, sliceId, sessionId, meeting.meetingId,
            meeting.startMs / 1000.0, meeting.endMs / 1000.0, durationSecs, out,
        )
        return durationSecs
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(context: Context): JSONArray =
        runCatching { JSONArray(prefs(context).getString(KEY_PENDING, "[]")) }.getOrDefault(JSONArray())

    private fun save(context: Context, arr: JSONArray) {
        check(prefs(context).edit().putString(KEY_PENDING, arr.toString()).commit()) {
            "Could not persist closed meeting export."
        }
    }

    private fun persistPending(context: Context, meeting: ClosedMeeting) = synchronized(lock) {
        val arr = load(context)
        if ((0 until arr.length()).none { arr.getJSONObject(it).getString("meetingId") == meeting.meetingId }) {
            arr.put(
                JSONObject()
                    .put("meetingId", meeting.meetingId)
                    .put("startMs", meeting.startMs)
                    .put("endMs", meeting.endMs),
            )
            save(context, arr)
        }
    }

    private fun pendingExports(context: Context): List<ClosedMeeting> = synchronized(lock) {
        val arr = load(context)
        (0 until arr.length()).map { i ->
            val item = arr.getJSONObject(i)
            ClosedMeeting(item.getString("meetingId"), item.getLong("startMs"), item.getLong("endMs"))
        }
    }

    private fun removePending(context: Context, meetingId: String) = synchronized(lock) {
        val arr = load(context)
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            if (item.getString("meetingId") != meetingId) kept.put(item)
        }
        save(context, kept)
    }
}
