package com.nsn8.vued.meeting

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.nsn8.vued.DiagnosticsLogger
import com.nsn8.vued.ambient.AmbientFlusher
import com.nsn8.vued.audio.RollingBuffer
import com.nsn8.vued.audio.SegmentExporter
import com.nsn8.vued.net.OutboundQueue
import com.nsn8.vued.net.RoomConfig
import com.nsn8.vued.service.RecorderState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    internal data class PersistedActiveMeeting(
        val meetingId: String,
        val title: String,
        val startMs: Long,
        val heartbeatMs: Long,
        val roomId: String?,
        val microphoneId: String?,
    )
    internal data class ClosedMeeting(
        val meetingId: String,
        val startMs: Long,
        val endMs: Long,
        val recoveredAfterRestart: Boolean = false,
    )

    private class NoUsableMeetingAudio(message: String) : IllegalStateException(message)

    private const val TAG = "VuedMeeting"
    private const val PREFS = "vued_meeting_exports"
    private const val KEY_ACTIVE = "active_v1"
    private const val KEY_PENDING = "pending"
    private const val ACTIVE_RECORD_VERSION = 1
    private const val ACTIVE_CREATE_RETRY_MS = 5_000L
    private const val ACTIVE_HEARTBEAT_MS = 10_000L
    private const val RECOVERY_FAILURE_REASON =
        "Tablet restarted before the meeting could be finalized; no usable local audio remained."

    // One session id per app process (matches iOS session semantics).
    private val sessionId: String = UUID.randomUUID().toString()
    private val queueScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val exportSignals = Channel<Context>(Channel.CONFLATED)
    private val lock = Any()
    private val exportMutex = Mutex()

    init {
        queueScope.launch {
            for (context in exportSignals) drainPendingExports(context)
        }
    }

    @Volatile
    private var rolling: RollingBuffer? = null

    private val _activeState = MutableStateFlow<ActiveMeeting?>(null)
    val activeState: StateFlow<ActiveMeeting?> = _activeState

    val active: ActiveMeeting?
        get() = _activeState.value

    val isCapturing: Boolean
        get() = rolling?.hasRecentAudio(RecorderState.CAPTURE_STALE_MS) == true &&
            RecorderState.state.value.hasFreshAudio()

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
        val appContext = context.applicationContext
        val nowMs = System.currentTimeMillis()
        check(RecorderState.state.value.hasFreshAudio(nowMs) && buffer.hasRecentAudio(RecorderState.CAPTURE_STALE_MS, nowMs)) {
            "Start recording first — the microphone is not ready."
        }
        check(active == null) { "A meeting is already in progress." }
        buffer.flush()
        val meetingId = UUID.randomUUID().toString().replace("-", "")
        val startMs = System.currentTimeMillis()
        val persisted = PersistedActiveMeeting(
            meetingId = meetingId,
            title = title,
            startMs = startMs,
            heartbeatMs = startMs,
            roomId = RoomConfig.roomId(appContext),
            microphoneId = RoomConfig.microphoneId(appContext),
        )
        persistActive(appContext, persisted)
        _activeState.value = ActiveMeeting(meetingId, startMs)
        Log.i(TAG, "start meeting=$meetingId title=$title startMs=$startMs")
        DiagnosticsLogger.info("meeting_started", mapOf("meetingId" to meetingId, "startMs" to startMs))
        runCatching {
            enqueueMeetingCreate(appContext, persisted)
        }.onFailure { error ->
            Log.w(TAG, "meeting create enqueue deferred meeting=$meetingId: ${error.message}", error)
            DiagnosticsLogger.warn("meeting_create_enqueue_deferred", mapOf("meetingId" to meetingId), error)
        }
        startActiveHeartbeat(appContext, meetingId)
        queueScope.launch {
            Log.i(TAG, "start drain begin meeting=$meetingId")
            runCatching { OutboundQueue.drain(appContext) }
                .onSuccess { Log.i(TAG, "start drain done meeting=$meetingId pending=${OutboundQueue.size(appContext)}") }
                .onFailure {
                    Log.w(TAG, "start drain failed meeting=$meetingId: ${it.message}", it)
                    DiagnosticsLogger.warn("meeting_start_drain_failed", mapOf("meetingId" to meetingId), it)
                }
        }
        retryActiveMeetingCreate(appContext, persisted)
        retryPendingExports(appContext)
        return meetingId
    }

    suspend fun stop(context: Context): StopResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val requestedEndMs = System.currentTimeMillis()
        val (closed, result) = try {
            exportMutex.withLock {
                val claimed = closeActiveMeeting(appContext, requestedEndMs)
                Log.i(TAG, "stop begin meeting=${claimed.meetingId} windowMs=${claimed.endMs - claimed.startMs}")
                DiagnosticsLogger.info(
                    "meeting_stop_started",
                    mapOf("meetingId" to claimed.meetingId, "windowMs" to (claimed.endMs - claimed.startMs)),
                )
                AmbientFlusher.resumeAfter(claimed.endMs)
                val exported = try {
                    exportAndEnqueue(appContext, claimed).also { removePending(appContext, claimed.meetingId) }
                } catch (error: NoUsableMeetingAudio) {
                    enqueueFailureAndRemovePending(appContext, claimed)
                    null
                }
                claimed to exported
            }
        } catch (error: Throwable) {
            retryPendingExports(appContext)
            throw error
        }
        val meeting = ActiveMeeting(closed.meetingId, closed.startMs)
        val drainStartMs = SystemClock.elapsedRealtime()
        OutboundQueue.drain(appContext)
        if (result == null) {
            throw NoUsableMeetingAudio("No usable audio captured for this meeting window.")
        }
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

    fun stopAsync(context: Context, endMs: Long = System.currentTimeMillis()) {
        val closed = closeActiveMeeting(context.applicationContext, endMs)
        val meeting = ActiveMeeting(closed.meetingId, closed.startMs)
        Log.i(TAG, "stop async queued meeting=${meeting.meetingId} windowMs=${closed.endMs - meeting.startMs}")
        DiagnosticsLogger.info("meeting_stop_async_queued", mapOf("meetingId" to meeting.meetingId, "windowMs" to (closed.endMs - meeting.startMs)))
        AmbientFlusher.resumeAfter(closed.endMs)
        retryPendingExports(context.applicationContext)
    }

    fun retryPendingExports(context: Context) {
        exportSignals.trySend(context.applicationContext)
    }

    /**
     * Converts the active record left by a previous process into the normal durable
     * closed-meeting export path. A fresh process never resumes an interrupted meeting.
     */
    internal fun recoverStaleMeetings(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
        scheduleExport: Boolean = true,
    ): Boolean {
        val appContext = context.applicationContext
        val persisted = persistedActive(appContext) ?: run {
            if (scheduleExport) retryPendingExports(appContext)
            return false
        }
        check(active == null) {
            "Cannot recover a stale meeting while another meeting is active."
        }
        enqueueMeetingCreate(appContext, persisted)
        val endMs = persisted.heartbeatMs.coerceIn(persisted.startMs, nowMs.coerceAtLeast(persisted.startMs))
        val closed = ClosedMeeting(
            persisted.meetingId,
            persisted.startMs,
            endMs,
            recoveredAfterRestart = true,
        )
        transitionActiveToPending(appContext, closed)
        Log.i(
            TAG,
            "recovered stale meeting=${persisted.meetingId} startMs=${persisted.startMs} " +
                "endMs=$endMs heartbeatAgeMs=${(nowMs - persisted.heartbeatMs).coerceAtLeast(0L)}",
        )
        DiagnosticsLogger.warn("meeting_stale_recovered", mapOf(
            "meetingId" to persisted.meetingId,
            "startMs" to persisted.startMs,
            "endMs" to endMs,
            "heartbeatAgeMs" to (nowMs - persisted.heartbeatMs).coerceAtLeast(0L),
        ))
        if (scheduleExport) retryPendingExports(appContext)
        return true
    }

    private fun retryActiveMeetingCreate(context: Context, meeting: PersistedActiveMeeting) {
        queueScope.launch {
            while (active?.meetingId == meeting.meetingId) {
                delay(ACTIVE_CREATE_RETRY_MS)
                if (active?.meetingId != meeting.meetingId) break
                val created = runCatching {
                    enqueueMeetingCreate(context, meeting)
                    OutboundQueue.drainMeetingCreate(context, meeting.meetingId)
                }
                    .onFailure { Log.w(TAG, "active create retry failed meeting=${meeting.meetingId}: ${it.message}", it) }
                    .getOrDefault(false)
                if (created) {
                    Log.i(TAG, "active create retry done meeting=${meeting.meetingId}")
                    break
                }
            }
        }
    }

    private fun startActiveHeartbeat(context: Context, meetingId: String) {
        queueScope.launch {
            while (active?.meetingId == meetingId) {
                delay(ACTIVE_HEARTBEAT_MS)
                if (active?.meetingId != meetingId) break
                runCatching { touchActive(context, meetingId, System.currentTimeMillis()) }
                    .onFailure { error ->
                        Log.w(TAG, "active heartbeat failed meeting=$meetingId: ${error.message}", error)
                        DiagnosticsLogger.warn("meeting_active_heartbeat_failed", mapOf("meetingId" to meetingId), error)
                    }
            }
        }
    }

    private suspend fun drainPendingExports(context: Context, drainOutbound: Boolean = true) {
        pendingExports(context).forEach { meeting ->
            try {
                val result = exportMutex.withLock {
                    if (!hasPendingExport(context, meeting.meetingId)) return@withLock null
                    if (OutboundQueue.hasDurableMeetingAudio(context, meeting.meetingId)) {
                        removePending(context, meeting.meetingId)
                        DiagnosticsLogger.info(
                            "meeting_pending_export_already_durable",
                            mapOf("meetingId" to meeting.meetingId),
                        )
                        return@withLock null
                    }
                    try {
                        exportAndEnqueue(context, meeting).also { removePending(context, meeting.meetingId) }
                    } catch (error: NoUsableMeetingAudio) {
                        enqueueFailureAndRemovePending(context, meeting)
                        null
                    }
                }
                if (result == null) {
                    if (drainOutbound) OutboundQueue.drain(context)
                    return@forEach
                }
                val drainStartMs = SystemClock.elapsedRealtime()
                if (drainOutbound) OutboundQueue.drain(context)
                Log.i(
                    TAG,
                    "pending export done meeting=${meeting.meetingId} durationSecs=${result.durationSecs} " +
                        "pending=${OutboundQueue.size(context)} elapsedMs=${SystemClock.elapsedRealtime() - drainStartMs}",
                )
            } catch (error: Throwable) {
                Log.w(TAG, "meeting ${meeting.meetingId} export still pending: ${error.message}", error)
                DiagnosticsLogger.warn("meeting_export_pending", mapOf("meetingId" to meeting.meetingId), error)
            }
        }
    }

    internal suspend fun drainPendingExportsForTest(context: Context) {
        drainPendingExports(context.applicationContext, drainOutbound = false)
    }

    private data class ExportResult(val durationSecs: Double, val sizeBytes: Long)

    private fun exportAndEnqueue(context: Context, meeting: ClosedMeeting): ExportResult {
        val totalStartMs = SystemClock.elapsedRealtime()
        val segments = rolling?.let { buffer ->
            val flushStartMs = SystemClock.elapsedRealtime()
            buffer.flush()
            Log.i(TAG, "export flush done meeting=${meeting.meetingId} elapsedMs=${SystemClock.elapsedRealtime() - flushStartMs}")
            buffer.listSegments()
        } ?: finalizedSegments(context, meeting.meetingId)
        Log.i(TAG, "export segments meeting=${meeting.meetingId} count=${segments.size}")
        val out = File(context.cacheDir, "meeting_${meeting.meetingId}.m4a")
        val exportStartMs = SystemClock.elapsedRealtime()
        val export = SegmentExporter.exportWindow(segments, meeting.startMs, meeting.endMs, out)
        if (export == null) {
            out.delete()
            Log.w(TAG, "export empty meeting=${meeting.meetingId}")
            DiagnosticsLogger.warn("meeting_export_empty", mapOf("meetingId" to meeting.meetingId))
            throw NoUsableMeetingAudio("No usable audio captured for this meeting window.")
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

        val sliceId = meetingSliceId(meeting.meetingId)
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

    private fun finalizedSegments(context: Context, meetingId: String): List<RollingBuffer.Segment> {
        val segmentsDir = context.getExternalFilesDir(null)?.let { File(it, "segments") }
            ?: error("Ambient segment directory unavailable.")
        val segments = RollingBuffer(segmentsDir).listSegments()
        Log.i(TAG, "export using finalized segments meeting=$meetingId count=${segments.size}")
        return segments
    }

    private fun meetingSliceId(meetingId: String): String =
        UUID.nameUUIDFromBytes("meeting:$meetingId".toByteArray()).toString()

    private fun enqueueMeetingCreate(context: Context, meeting: PersistedActiveMeeting) {
        OutboundQueue.enqueueMeetingCreate(
            context = context,
            meetingId = meeting.meetingId,
            title = meeting.title,
            startedAtSec = meeting.startMs / 1000.0,
            roomId = meeting.roomId,
            microphoneId = meeting.microphoneId,
        )
    }

    private fun enqueueFailureAndRemovePending(context: Context, meeting: ClosedMeeting) {
        val reason = if (meeting.recoveredAfterRestart) {
            RECOVERY_FAILURE_REASON
        } else {
            "Meeting ended without any usable local audio."
        }
        OutboundQueue.enqueueMeetingFailure(
            context = context,
            meetingId = meeting.meetingId,
            endedAtSec = meeting.endMs / 1000.0,
            failureReason = reason,
        )
        removePending(context, meeting.meetingId)
        DiagnosticsLogger.warn("meeting_recovery_failed_no_audio", mapOf(
            "meetingId" to meeting.meetingId,
            "endedAtMs" to meeting.endMs,
        ))
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadPending(context: Context): JSONArray =
        runCatching { JSONArray(prefs(context).getString(KEY_PENDING, "[]")) }.getOrDefault(JSONArray())

    private fun savePending(context: Context, arr: JSONArray) {
        check(prefs(context).edit().putString(KEY_PENDING, arr.toString()).commit()) {
            "Could not persist closed meeting export."
        }
    }

    private fun transitionActiveToPending(context: Context, meeting: ClosedMeeting) = synchronized(lock) {
        val stored = loadActiveUnlocked(context)
        check(stored == null || stored.meetingId == meeting.meetingId) {
            "Persisted active meeting does not match ${meeting.meetingId}."
        }
        val arr = loadPending(context)
        if ((0 until arr.length()).none { arr.getJSONObject(it).getString("meetingId") == meeting.meetingId }) {
            arr.put(meeting.toJson())
        }
        check(
            prefs(context).edit()
                .putString(KEY_PENDING, arr.toString())
                .remove(KEY_ACTIVE)
                .commit(),
        ) { "Could not persist meeting close handoff." }
    }

    private fun closeActiveMeeting(context: Context, requestedEndMs: Long): ClosedMeeting = synchronized(lock) {
        val meeting = active ?: error("No active meeting.")
        val closed = ClosedMeeting(
            meetingId = meeting.meetingId,
            startMs = meeting.startMs,
            endMs = requestedEndMs.coerceAtLeast(meeting.startMs),
        )
        transitionActiveToPending(context, closed)
        _activeState.value = null
        closed
    }

    internal fun pendingExports(context: Context): List<ClosedMeeting> = synchronized(lock) {
        val arr = loadPending(context)
        (0 until arr.length()).map { i ->
            val item = arr.getJSONObject(i)
            ClosedMeeting(
                meetingId = item.getString("meetingId"),
                startMs = item.getLong("startMs"),
                endMs = item.getLong("endMs"),
                recoveredAfterRestart = item.optBoolean("recoveredAfterRestart", false),
            )
        }
    }

    private fun hasPendingExport(context: Context, meetingId: String): Boolean = synchronized(lock) {
        val arr = loadPending(context)
        (0 until arr.length()).any { index ->
            arr.getJSONObject(index).optString("meetingId") == meetingId
        }
    }

    private fun removePending(context: Context, meetingId: String) = synchronized(lock) {
        val arr = loadPending(context)
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            if (item.getString("meetingId") != meetingId) kept.put(item)
        }
        savePending(context, kept)
    }

    internal fun persistedActive(context: Context): PersistedActiveMeeting? = synchronized(lock) {
        loadActiveUnlocked(context)
    }

    private fun loadActiveUnlocked(context: Context): PersistedActiveMeeting? {
        val raw = prefs(context).getString(KEY_ACTIVE, null) ?: return null
        return runCatching {
            val item = JSONObject(raw)
            check(item.getInt("version") == ACTIVE_RECORD_VERSION) { "Unsupported active meeting version." }
            PersistedActiveMeeting(
                meetingId = item.getString("meetingId"),
                title = item.getString("title"),
                startMs = item.getLong("startMs"),
                heartbeatMs = item.getLong("heartbeatMs"),
                roomId = item.optString("roomId", "").ifEmpty { null },
                microphoneId = item.optString("microphoneId", "").ifEmpty { null },
            )
        }.onFailure { error ->
            Log.w(TAG, "discarding invalid persisted active meeting: ${error.message}", error)
            DiagnosticsLogger.warn("meeting_active_record_invalid", throwable = error)
            prefs(context).edit().remove(KEY_ACTIVE).commit()
        }.getOrNull()
    }

    internal fun persistActive(context: Context, meeting: PersistedActiveMeeting) = synchronized(lock) {
        check(persistedActive(context) == null) { "A previous meeting is still being recovered." }
        check(prefs(context).edit().putString(KEY_ACTIVE, meeting.toJson().toString()).commit()) {
            "Could not persist active meeting."
        }
    }

    internal fun touchActive(context: Context, meetingId: String, heartbeatMs: Long) = synchronized(lock) {
        val stored = loadActiveUnlocked(context) ?: return@synchronized
        if (stored.meetingId != meetingId) return@synchronized
        val updated = stored.copy(heartbeatMs = maxOf(stored.heartbeatMs, stored.startMs, heartbeatMs))
        check(prefs(context).edit().putString(KEY_ACTIVE, updated.toJson().toString()).commit()) {
            "Could not persist active meeting heartbeat."
        }
    }

    private fun PersistedActiveMeeting.toJson(): JSONObject =
        JSONObject()
            .put("version", ACTIVE_RECORD_VERSION)
            .put("meetingId", meetingId)
            .put("title", title)
            .put("startMs", startMs)
            .put("heartbeatMs", heartbeatMs)
            .putOpt("roomId", roomId)
            .putOpt("microphoneId", microphoneId)

    private fun ClosedMeeting.toJson(): JSONObject =
        JSONObject()
            .put("meetingId", meetingId)
            .put("startMs", startMs)
            .put("endMs", endMs)
            .put("recoveredAfterRestart", recoveredAfterRestart)
}
