package com.nsn8.vued.meeting

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.nsn8.vued.DiagnosticsLogger
import com.nsn8.vued.VuedConfig
import com.nsn8.vued.ambient.AmbientFlusher
import com.nsn8.vued.audio.MultiChannelWavRollingBuffer
import com.nsn8.vued.audio.RollingBuffer
import com.nsn8.vued.audio.SegmentExporter
import com.nsn8.vued.audio.WavSegmentExporter
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
    data class StopResult(
        val meetingId: String,
        val durationSecs: Double,
        val sizeBytes: Long,
        val sourceWavPath: String? = null,
    )
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
    private var sourceRollingProvider: () -> MultiChannelWavRollingBuffer? = { null }

    // Application context captured at meeting start so segment-close callbacks
    // can drive incremental source-WAV upload drains.
    @Volatile
    private var meetingAppContext: Context? = null

    @Volatile
    var active: ActiveMeeting? = null
        private set

    val isCapturing: Boolean get() = rolling != null

    fun attach(
        buffer: RollingBuffer,
        sourceBufferProvider: () -> MultiChannelWavRollingBuffer? = { null },
    ) {
        rolling = buffer
        sourceRollingProvider = sourceBufferProvider
    }

    fun detach() {
        rolling = null
        sourceRollingProvider = { null }
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
        flushSourceForMeetingStart()
        val meetingId = UUID.randomUUID().toString().replace("-", "")
        val startMs = System.currentTimeMillis()
        Log.i(TAG, "start meeting=$meetingId title=$title startMs=$startMs")
        DiagnosticsLogger.info("meeting_started", mapOf("meetingId" to meetingId, "startMs" to startMs))
        OutboundQueue.enqueueMeetingCreate(context, meetingId, title, startMs / 1000.0)
        active = ActiveMeeting(meetingId, startMs)
        val appContext = context.applicationContext
        meetingAppContext = appContext
        if (VuedConfig.INCREMENTAL_SOURCE_WAV_UPLOAD) {
            startStreamingSourceWav(appContext, meetingId, startMs)
        }
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
        stopStreamingSourceWavTrigger()
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
            "sourceWavPath" to (result.sourceWavPath ?: ""),
            "drainElapsedMs" to (SystemClock.elapsedRealtime() - drainStartMs),
        ))
        StopResult(meeting.meetingId, result.durationSecs, result.sizeBytes, result.sourceWavPath)
    }

    fun stopAsync(context: Context) {
        val meeting = active ?: error("No active meeting.")
        val closed = ClosedMeeting(meeting.meetingId, meeting.startMs, System.currentTimeMillis())
        stopStreamingSourceWavTrigger()
        // Bound + mark the streaming source-WAV ended now (synchronous), so any
        // drain before the deferred export never streams past the meeting window.
        finalizeStreamingSourceWav(context.applicationContext, closed)
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

    private data class ExportResult(
        val durationSecs: Double,
        val sizeBytes: Long,
        val sourceWavPath: String?,
    )

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
        val sourceWavPath: String? = if (VuedConfig.INCREMENTAL_SOURCE_WAV_UPLOAD) {
            // Incremental path: segments have been streaming during the meeting.
            // Flush the tail and mark the queued item ended so the drain uploads
            // the remainder and completes; no whole-file export needed.
            finalizeStreamingSourceWav(context, meeting)
            null
        } else {
            exportSourceMeeting(context, meeting)?.let { sourcePath ->
                OutboundQueue.enqueueMeetingSourceWav(
                    context = context,
                    sliceId = sliceId,
                    sessionId = sessionId,
                    meetingId = meeting.meetingId,
                    startedAtSec = meeting.startMs / 1000.0,
                    endedAtSec = meeting.endMs / 1000.0,
                    durationSecs = durationSecs,
                    monoSizeBytes = sizeBytes,
                    source = File(sourcePath),
                ).absolutePath
            }
        }
        return ExportResult(durationSecs, sizeBytes, sourceWavPath)
    }

    private fun flushSourceForMeetingStart() {
        runCatching { sourceRollingProvider()?.flush() }
            .onFailure { error ->
                Log.w(TAG, "source wav start flush failed: ${error.message}", error)
                DiagnosticsLogger.warn("meeting_source_wav_start_flush_failed", throwable = error)
            }
    }

    private fun sliceIdFor(meetingId: String): String =
        UUID.nameUUIDFromBytes("meeting:$meetingId".toByteArray()).toString()

    /** Opens the durable streaming source-WAV item and subscribes to segment
     *  closes so each closed 30s segment is uploaded during the meeting. */
    private fun startStreamingSourceWav(context: Context, meetingId: String, startMs: Long) {
        val buffer = sourceRollingProvider() ?: return
        OutboundQueue.enqueueMeetingSourceWavStreaming(
            context = context,
            sliceId = sliceIdFor(meetingId),
            sessionId = sessionId,
            meetingId = meetingId,
            startedAtSec = startMs / 1000.0,
            segmentsDir = buffer.directory.absolutePath,
            codec = VuedConfig.SOURCE_WAV_CODEC,
        )
        buffer.onSegmentClosed = { _, _ -> onSourceSegmentClosed() }
    }

    private fun onSourceSegmentClosed() {
        val context = meetingAppContext ?: return
        if (active == null) return
        queueScope.launch {
            runCatching { OutboundQueue.drain(context) }
                .onFailure { Log.w(TAG, "source segment drain failed: ${it.message}", it) }
        }
    }

    private fun stopStreamingSourceWavTrigger() {
        runCatching { sourceRollingProvider()?.onSegmentClosed = { _, _ -> } }
        meetingAppContext = null
    }

    /** Flushes the source tail and marks the queued streaming item ended so the
     *  drain uploads the remainder and completes. No-op (returns false) when
     *  there is no streaming item (feature off, or non-UMA16 capture). */
    private fun finalizeStreamingSourceWav(context: Context, meeting: ClosedMeeting): Boolean {
        val sliceId = sliceIdFor(meeting.meetingId)
        if (!OutboundQueue.hasStreamingSourceWav(context, sliceId)) return false
        runCatching { sourceRollingProvider()?.flush() }
            .onFailure { error ->
                Log.w(TAG, "source wav finalize flush failed: ${error.message}", error)
                DiagnosticsLogger.warn(
                    "meeting_source_wav_finalize_flush_failed",
                    mapOf("meetingId" to meeting.meetingId),
                    error,
                )
            }
        OutboundQueue.markMeetingSourceWavEnded(
            context, sliceId, meeting.endMs / 1000.0, durationSecs = 0.0,
        )
        return true
    }

    private fun exportSourceMeeting(context: Context, meeting: ClosedMeeting): String? {
        val source = sourceRollingProvider() ?: return null
        return runCatching {
            val flushStartMs = SystemClock.elapsedRealtime()
            source.flush()
            Log.i(
                TAG,
                "source wav flush done meeting=${meeting.meetingId} " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - flushStartMs}",
            )
            val segments = source.listSegments()
            if (segments.isEmpty()) return@runCatching null
            val directory = File(
                context.getExternalFilesDir(null) ?: context.filesDir,
                "uma16_meetings",
            ).apply { mkdirs() }
            val out = File(directory, "meeting_${meeting.meetingId}_16ch_16k.wav")
            val exportStartMs = SystemClock.elapsedRealtime()
            val export = WavSegmentExporter.exportWindow(segments, meeting.startMs, meeting.endMs, out)
                ?: return@runCatching null
            Log.i(
                TAG,
                "source wav export done meeting=${meeting.meetingId} " +
                    "segments=${export.segmentCount} durationMs=${export.durationMs} " +
                    "bytes=${out.length()} elapsedMs=${SystemClock.elapsedRealtime() - exportStartMs}",
            )
            DiagnosticsLogger.info("meeting_source_wav_export_completed", mapOf(
                "meetingId" to meeting.meetingId,
                "segments" to export.segmentCount,
                "durationMs" to export.durationMs,
                "bytes" to out.length(),
                "path" to out.absolutePath,
                "elapsedMs" to (SystemClock.elapsedRealtime() - exportStartMs),
            ))
            out.absolutePath
        }.onFailure { error ->
            Log.w(TAG, "source wav export failed meeting=${meeting.meetingId}: ${error.message}", error)
            DiagnosticsLogger.warn(
                "meeting_source_wav_export_failed",
                mapOf("meetingId" to meeting.meetingId),
                error,
            )
        }.getOrNull()
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
