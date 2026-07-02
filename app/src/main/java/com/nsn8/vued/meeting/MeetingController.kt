package com.nsn8.vued.meeting

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.nsn8.vued.DiagnosticsLogger
import com.nsn8.vued.ambient.AmbientFlusher
import com.nsn8.vued.audio.RollingBuffer
import com.nsn8.vued.audio.SegmentExporter
import com.nsn8.vued.net.OutboundQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
    data class StopResult(val meetingId: String, val durationSecs: Double, val sizeBytes: Long)
    private data class ClosedMeeting(val meetingId: String, val startMs: Long, val endMs: Long)

    private const val TAG = "VuedMeeting"
    private const val PREFS = "vued_meeting_exports"
    private const val KEY_PENDING = "pending"
    private const val ACTIVE_CREATE_RETRY_MS = 5_000L

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
        Log.i(TAG, "start meeting=$meetingId title=$title startMs=$startMs")
        DiagnosticsLogger.info("meeting_started", mapOf("meetingId" to meetingId, "startMs" to startMs))
        OutboundQueue.enqueueMeetingCreate(context, meetingId, title, startMs / 1000.0)
        active = ActiveMeeting(meetingId, startMs)
        val appContext = context.applicationContext
        queueScope.launch {
            Log.i(TAG, "start drain begin meeting=$meetingId")
            runCatching { OutboundQueue.drain(appContext) }
                .onSuccess { Log.i(TAG, "start drain done meeting=$meetingId pending=${OutboundQueue.size(appContext)}") }
                .onFailure {
                    Log.w(TAG, "start drain failed meeting=$meetingId: ${it.message}", it)
                    DiagnosticsLogger.warn("meeting_start_drain_failed", mapOf("meetingId" to meetingId), it)
                }
        }
        retryActiveMeetingCreate(appContext, meetingId)
        retryPendingExports(appContext)
        return meetingId
    }

    suspend fun stop(context: Context): StopResult = withContext(Dispatchers.IO) {
        val meeting = active ?: error("No active meeting.")
        val endMs = System.currentTimeMillis()
        active = null
        Log.i(TAG, "stop begin meeting=${meeting.meetingId} windowMs=${endMs - meeting.startMs}")
        DiagnosticsLogger.info("meeting_stop_started", mapOf("meetingId" to meeting.meetingId, "windowMs" to (endMs - meeting.startMs)))
        AmbientFlusher.resumeAfter(endMs)
        val appContext = context.applicationContext
        val result = exportAndEnqueue(appContext, ClosedMeeting(meeting.meetingId, meeting.startMs, endMs))
        val drainStartMs = SystemClock.elapsedRealtime()
        OutboundQueue.drain(appContext)
        Log.i(
            TAG,
            "stop drain done meeting=${meeting.meetingId} pending=${OutboundQueue.size(appContext)} " +
                "elapsedMs=${SystemClock.elapsedRealtime() - drainStartMs}",
        )
        DiagnosticsLogger.info("meeting_stop_completed", mapOf(
            "meetingId" to meeting.meetingId,
            "pending" to OutboundQueue.size(appContext),
            "durationSecs" to result.durationSecs,
            "sizeBytes" to result.sizeBytes,
            "drainElapsedMs" to (SystemClock.elapsedRealtime() - drainStartMs),
        ))
        StopResult(meeting.meetingId, result.durationSecs, result.sizeBytes)
    }

    fun stopAsync(context: Context) {
        val meeting = active ?: error("No active meeting.")
        val closed = ClosedMeeting(meeting.meetingId, meeting.startMs, System.currentTimeMillis())
        persistPending(context.applicationContext, closed)
        active = null
        Log.i(TAG, "stop async queued meeting=${meeting.meetingId} windowMs=${closed.endMs - meeting.startMs}")
        DiagnosticsLogger.info("meeting_stop_async_queued", mapOf("meetingId" to meeting.meetingId, "windowMs" to (closed.endMs - meeting.startMs)))
        AmbientFlusher.resumeAfter(closed.endMs)
        retryPendingExports(context.applicationContext)
    }

    fun retryPendingExports(context: Context) {
        exportSignals.trySend(context.applicationContext)
    }

    private fun retryActiveMeetingCreate(context: Context, meetingId: String) {
        queueScope.launch {
            while (active?.meetingId == meetingId) {
                delay(ACTIVE_CREATE_RETRY_MS)
                if (active?.meetingId != meetingId) break
                val created = runCatching { OutboundQueue.drainMeetingCreate(context, meetingId) }
                    .onFailure { Log.w(TAG, "active create retry failed meeting=$meetingId: ${it.message}", it) }
                    .getOrDefault(false)
                if (created) {
                    Log.i(TAG, "active create retry done meeting=$meetingId")
                    break
                }
            }
        }
    }

    private suspend fun drainPendingExports(context: Context) {
        pendingExports(context).forEach { meeting ->
            runCatching {
                val result = exportAndEnqueue(context, meeting)
                removePending(context, meeting.meetingId)
                val drainStartMs = SystemClock.elapsedRealtime()
                OutboundQueue.drain(context)
                Log.i(
                    TAG,
                    "pending export done meeting=${meeting.meetingId} durationSecs=${result.durationSecs} " +
                        "pending=${OutboundQueue.size(context)} elapsedMs=${SystemClock.elapsedRealtime() - drainStartMs}",
                )
            }.onFailure { error ->
                Log.w(TAG, "meeting ${meeting.meetingId} export still pending: ${error.message}", error)
                DiagnosticsLogger.warn("meeting_export_pending", mapOf("meetingId" to meeting.meetingId), error)
            }
        }
    }

    private data class ExportResult(val durationSecs: Double, val sizeBytes: Long)

    private fun exportAndEnqueue(context: Context, meeting: ClosedMeeting): ExportResult {
        val buffer = rolling ?: error("Ambient buffer not running.")
        val totalStartMs = SystemClock.elapsedRealtime()
        val flushStartMs = SystemClock.elapsedRealtime()
        buffer.flush()
        Log.i(TAG, "export flush done meeting=${meeting.meetingId} elapsedMs=${SystemClock.elapsedRealtime() - flushStartMs}")
        val segments = buffer.listSegments()
        Log.i(TAG, "export segments meeting=${meeting.meetingId} count=${segments.size}")
        val out = File(context.cacheDir, "meeting_${meeting.meetingId}.m4a")
        val exportStartMs = SystemClock.elapsedRealtime()
        val export = SegmentExporter.exportWindow(segments, meeting.startMs, meeting.endMs, out)
        if (export == null) {
            out.delete()
            Log.w(TAG, "export empty meeting=${meeting.meetingId}")
            DiagnosticsLogger.warn("meeting_export_empty", mapOf("meetingId" to meeting.meetingId))
            error("No audio captured for this meeting window.")
        }
        val durationSecs = export.durationMs / 1000.0
        val sizeBytes = out.length()
        Log.i(
            TAG,
            "export done meeting=${meeting.meetingId} segments=${export.segmentCount} " +
                "durationMs=${export.durationMs} bytes=$sizeBytes elapsedMs=${SystemClock.elapsedRealtime() - exportStartMs}",
        )
        DiagnosticsLogger.info("meeting_export_completed", mapOf(
            "meetingId" to meeting.meetingId,
            "segments" to export.segmentCount,
            "durationMs" to export.durationMs,
            "bytes" to sizeBytes,
            "elapsedMs" to (SystemClock.elapsedRealtime() - exportStartMs),
        ))

        val sliceId = UUID.nameUUIDFromBytes("meeting:${meeting.meetingId}".toByteArray()).toString()
        val enqueueStartMs = SystemClock.elapsedRealtime()
        OutboundQueue.enqueueMeeting(
            context, sliceId, sessionId, meeting.meetingId,
            meeting.startMs / 1000.0, meeting.endMs / 1000.0, durationSecs, out,
        )
        Log.i(
            TAG,
            "enqueue done meeting=${meeting.meetingId} slice=$sliceId " +
                "elapsedMs=${SystemClock.elapsedRealtime() - enqueueStartMs} totalElapsedMs=${SystemClock.elapsedRealtime() - totalStartMs}",
        )
        DiagnosticsLogger.info("meeting_enqueue_completed", mapOf(
            "meetingId" to meeting.meetingId,
            "sliceId" to sliceId,
            "elapsedMs" to (SystemClock.elapsedRealtime() - enqueueStartMs),
            "totalElapsedMs" to (SystemClock.elapsedRealtime() - totalStartMs),
        ))
        return ExportResult(durationSecs, sizeBytes)
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
