package com.nsn8.vued.net

import android.content.Context
import android.util.Log
import com.nsn8.vued.AmplitudeTracker
import com.nsn8.vued.DiagnosticsLogger
import com.nsn8.vued.audio.RollingBuffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Durable, offline-safe upload queue for the timeline flow — the Android mirror of the
 * iOS `AdaptiveOggStorage` cache-and-retry pattern, extended to also make the
 * `POST /meetings` create durable and ordered ahead of its audio.
 *
 *  - **Never miss audio / never lose a meeting:** both the exported m4a *and* the
 *    meeting-create are committed to a persisted index before any network call. The
 *    capture/flush cursor advances on enqueue, not on upload.
 *  - **Ordering:** a meeting's audio slice or terminal failure PATCH will not upload
 *    until that meeting's `MEETING_CREATE` item has succeeded — enforced on every
 *    drain, including retries. (The create is enqueued at meeting start, so it always
 *    precedes either stop-time outcome in the queue.)
 *  - **Offline-safe + idempotent:** the index survives restarts (SharedPreferences
 *    JSON, like iOS UserDefaults); the server is idempotent on `meetingId` /`sliceId`
 *    (`ON CONFLICT`), so at-least-once retries never duplicate.
 *  - **Resumable two-step:** `metadataDone` is persisted after the slice-metadata POST
 *    so a retry skips straight to the bytes PUT.
 *  - **Opportunistic drain:** no scheduler — [drain] runs at lifecycle points (service
 *    start, each ambient flush, meeting start/stop), guarded against concurrent runs.
 *  - **No eviction:** items are kept until they succeed (matches iOS). Success removes
 *    the entry (+ file); orphan audio entries (missing file) are pruned.
 */
object OutboundQueue {

    private const val TAG = "VuedOutboundQueue"
    private const val PREFS = "vued_outbound"
    private const val KEY = "queue"
    private const val KEY_COMPLETED_MEETING_AUDIO = "completed_meeting_audio"
    private const val MAX_COMPLETED_MEETING_MARKERS = 512

    enum class Kind { MEETING_CREATE, MEETING_FAILURE, AMBIENT, MEETING }

    private val lock = Any()
    private val draining = AtomicBoolean(false)
    private val drainRequested = AtomicBoolean(false)

    private fun dir(context: Context): File =
        File(context.filesDir, "outbound").apply { mkdirs() }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(context: Context): JSONArray =
        runCatching { JSONArray(prefs(context).getString(KEY, "[]")) }.getOrDefault(JSONArray())

    private fun save(context: Context, arr: JSONArray) {
        check(prefs(context).edit().putString(KEY, arr.toString()).commit()) {
            "Could not persist outbound queue."
        }
    }

    /** Pending item count (for status display). */
    fun size(context: Context): Int = synchronized(lock) { load(context).length() }

    /**
     * True once a meeting slice is durably queued or has completed upload. The
     * completion marker closes the crash window between upload success and removal of
     * MeetingController's pending-export record.
     */
    fun hasDurableMeetingAudio(context: Context, meetingId: String): Boolean = synchronized(lock) {
        val queued = load(context)
        val hasQueuedAudio = (0 until queued.length()).any { index ->
            val item = queued.getJSONObject(index)
            item.optString("kind") == Kind.MEETING.name &&
                item.optString("meetingId") == meetingId
        }
        hasQueuedAudio || loadCompletedMeetingAudio(context).contains(meetingId)
    }

    // ---- enqueue (the commit points) ----

    /** Durable `POST /meetings`. Enqueued at meeting start so it precedes the audio. */
    fun enqueueMeetingCreate(
        context: Context,
        meetingId: String,
        title: String,
        startedAtSec: Double,
        roomId: String? = RoomConfig.roomId(context),
        microphoneId: String? = RoomConfig.microphoneId(context),
    ) {
        val inserted = synchronized(lock) {
            val arr = load(context)
            val alreadyPending = (0 until arr.length()).any { index ->
                val item = arr.getJSONObject(index)
                item.optString("kind") == Kind.MEETING_CREATE.name &&
                    item.optString("meetingId") == meetingId
            }
            if (alreadyPending) {
                false
            } else {
                save(
                    context,
                    arr.put(
                        JSONObject()
                            .put("id", "meeting:$meetingId")
                            .put("kind", Kind.MEETING_CREATE.name)
                            .put("meetingId", meetingId)
                            .put("title", title)
                            .put("startedAtSec", startedAtSec)
                            // Callers recovering a persisted meeting pass the original
                            // snapshots here rather than reading today's room assignment.
                            .putOpt("roomId", roomId)
                            .putOpt("microphoneId", microphoneId),
                    ),
                )
                true
            }
        }
        if (!inserted) {
            Log.i(TAG, "MEETING_CREATE already pending $meetingId")
            DiagnosticsLogger.info("queue_meeting_create_deduplicated", mapOf(
                "meetingId" to meetingId,
                "pending" to size(context),
            ))
            return
        }
        Log.i(TAG, "enqueued MEETING_CREATE $meetingId")
        DiagnosticsLogger.info("queue_meeting_create_enqueued", mapOf(
            "meetingId" to meetingId,
            "pending" to size(context),
        ))
    }

    /**
     * Durable terminal PATCH for a meeting whose retained local audio cannot be
     * recovered. The fixed id makes repeated recovery attempts idempotent locally;
     * drain ordering holds the PATCH until the corresponding create has succeeded.
     */
    fun enqueueMeetingFailure(
        context: Context,
        meetingId: String,
        endedAtSec: Double,
        failureReason: String,
    ) {
        val inserted = synchronized(lock) {
            val arr = load(context)
            val alreadyPending = (0 until arr.length()).any { index ->
                val item = arr.getJSONObject(index)
                item.optString("kind") == Kind.MEETING_FAILURE.name &&
                    item.optString("meetingId") == meetingId
            }
            if (alreadyPending) {
                false
            } else {
                save(
                    context,
                    arr.put(
                        JSONObject()
                            .put("id", "meeting-failure:$meetingId")
                            .put("kind", Kind.MEETING_FAILURE.name)
                            .put("meetingId", meetingId)
                            .put("endedAtSec", endedAtSec)
                            .put("failureReason", failureReason),
                    ),
                )
                true
            }
        }
        val event = if (inserted) "queue_meeting_failure_enqueued" else "queue_meeting_failure_deduplicated"
        Log.i(TAG, if (inserted) "enqueued MEETING_FAILURE $meetingId" else "MEETING_FAILURE already pending $meetingId")
        DiagnosticsLogger.info(event, mapOf(
            "meetingId" to meetingId,
            "endedAtSec" to endedAtSec,
            "pending" to size(context),
        ))
    }

    fun enqueueAmbient(
        context: Context,
        sliceId: String,
        sessionId: String,
        startedAtSec: Double,
        endedAtSec: Double,
        durationSecs: Double,
        source: File,
    ) = enqueueAudio(context, Kind.AMBIENT, sliceId, sessionId, null, startedAtSec, endedAtSec, durationSecs, source)

    fun enqueueMeeting(
        context: Context,
        sliceId: String,
        sessionId: String,
        meetingId: String,
        startedAtSec: Double,
        endedAtSec: Double,
        durationSecs: Double,
        source: File,
    ) = enqueueAudio(context, Kind.MEETING, sliceId, sessionId, meetingId, startedAtSec, endedAtSec, durationSecs, source)

    /** Moves [source] into the durable queue dir and records its metadata. Consumes [source]. */
    private fun enqueueAudio(
        context: Context,
        kind: Kind,
        sliceId: String,
        sessionId: String,
        meetingId: String?,
        startedAtSec: Double,
        endedAtSec: Double,
        durationSecs: Double,
        source: File,
    ) {
        synchronized(lock) {
            val dest = File(dir(context), "$sliceId.m4a")
            val current = load(context)
            val alreadyPending = (0 until current.length()).any { index ->
                current.getJSONObject(index).optString("id") == sliceId
            }
            if (alreadyPending && dest.isFile) {
                source.delete()
                Log.i(TAG, "$kind slice $sliceId already pending")
                DiagnosticsLogger.info("queue_audio_deduplicated", mapOf(
                    "kind" to kind.name,
                    "sliceId" to sliceId,
                    "meetingId" to meetingId,
                    "pending" to current.length(),
                ))
                return
            }
            val retained = JSONArray()
            for (index in 0 until current.length()) {
                val item = current.getJSONObject(index)
                if (item.optString("id") != sliceId) retained.put(item)
            }
            if (!source.renameTo(dest)) {
                source.copyTo(dest, overwrite = true)
                source.delete()
            }
            save(
                context,
                retained.put(
                    JSONObject()
                        .put("id", sliceId)
                        .put("kind", kind.name)
                        .put("sessionId", sessionId)
                        .putOpt("meetingId", meetingId)
                        .put("startedAtSec", startedAtSec)
                        .put("endedAtSec", endedAtSec)
                        .put("durationSecs", durationSecs)
                        .put("sizeBytes", dest.length())
                        .put("metadataDone", false)
                        // Snapshot the assigned room at record time (future-only,
                        // decision #7): changing rooms later won't retag queued slices.
                        .putOpt("roomId", RoomConfig.roomId(context)),
                ),
            )
            Log.i(TAG, "enqueued $kind slice $sliceId (${dest.length()} bytes)")
            DiagnosticsLogger.info("queue_audio_enqueued", mapOf(
                "kind" to kind.name,
                "sliceId" to sliceId,
                "meetingId" to meetingId,
                "bytes" to dest.length(),
                "pending" to load(context).length(),
            ))
            AmplitudeTracker.track(
                "audio_push_enqueued",
                mapOf(
                    "kind" to kind.name,
                    "sliceId" to sliceId,
                    "meetingId" to meetingId,
                    "bytes" to dest.length(),
                    "durationSecs" to durationSecs,
                    "pending" to load(context).length(),
                ),
            )
        }
    }

    // ---- drain (opportunistic, ordered, idempotent) ----

    /**
     * Attempts every pending item once, in insertion order. A meeting's audio slice or
     * terminal failure PATCH is skipped while its `MEETING_CREATE` is still pending, so
     * the meeting always exists server-side before either outcome is delivered.
     */
    suspend fun drain(context: Context) {
        if (!draining.compareAndSet(false, true)) {
            drainRequested.set(true)
            return
        }
        try {
            do {
                drainRequested.set(false)
                drainOnce(context)
            } while (drainRequested.get())
        } finally {
            draining.set(false)
        }
        // Close the small race where another caller requested a drain after the loop's
        // final condition check but before [draining] was cleared.
        if (drainRequested.getAndSet(false)) drain(context)
    }

    private suspend fun drainOnce(context: Context) {
        val startedAt = System.currentTimeMillis()
        var attempted = 0
        val items = synchronized(lock) { load(context) }
        for (i in 0 until items.length()) {
            attempted += 1
            val item = items.getJSONObject(i)
            when (Kind.valueOf(item.getString("kind"))) {
                Kind.MEETING_CREATE -> drainMeetingCreate(context, item)
                Kind.MEETING_FAILURE -> drainMeetingFailure(context, item)
                Kind.AMBIENT, Kind.MEETING -> drainAudio(context, item)
            }
        }
        DiagnosticsLogger.info("queue_drain_completed", mapOf(
            "attempted" to attempted,
            "pending" to size(context),
            "elapsedMs" to (System.currentTimeMillis() - startedAt),
        ))
    }

    suspend fun drainMeetingCreate(context: Context, meetingId: String): Boolean {
        val item = synchronized(lock) {
            val arr = load(context)
            (0 until arr.length())
                .map { arr.getJSONObject(it) }
                .firstOrNull {
                    it.getString("kind") == Kind.MEETING_CREATE.name &&
                        it.optString("meetingId") == meetingId
                }
        } ?: return true
        drainMeetingCreate(context, item)
        return !hasPendingMeetingCreate(context, meetingId)
    }

    private suspend fun drainMeetingCreate(context: Context, item: JSONObject) {
        val meetingId = item.getString("meetingId")
        try {
            val roomId = item.optString("roomId", "").ifEmpty { null }
            val microphoneId = item.optString("microphoneId", "").ifEmpty { null }
            VuedApi.createMeeting(
                meetingId,
                item.getString("title"),
                item.getDouble("startedAtSec"),
                roomId = roomId,
                microphoneId = microphoneId,
            )
            remove(context, item.getString("id"))
            Log.i(TAG, "created queued meeting $meetingId")
        } catch (e: Exception) {
            Log.w(TAG, "meeting $meetingId create still pending: ${e.message}")
            DiagnosticsLogger.warn("queue_meeting_create_pending", mapOf("meetingId" to meetingId), e)
        }
    }

    private suspend fun drainMeetingFailure(context: Context, item: JSONObject) {
        val meetingId = item.getString("meetingId")
        if (hasPendingMeetingCreate(context, meetingId)) {
            Log.i(TAG, "meeting failure $meetingId waiting on meeting-create")
            return
        }
        try {
            VuedApi.markMeetingFailed(
                meetingId = meetingId,
                endedAtSec = item.getDouble("endedAtSec"),
                failureReason = item.getString("failureReason"),
            )
            remove(context, item.getString("id"))
            Log.i(TAG, "marked queued meeting failed $meetingId")
            DiagnosticsLogger.info("queue_meeting_failure_delivered", mapOf(
                "meetingId" to meetingId,
                "pending" to size(context),
            ))
        } catch (e: Exception) {
            Log.w(TAG, "meeting $meetingId failure patch still pending: ${e.message}")
            DiagnosticsLogger.warn("queue_meeting_failure_pending", mapOf("meetingId" to meetingId), e)
        }
    }

    private suspend fun drainAudio(context: Context, item: JSONObject) {
        val id = item.getString("id")
        val kind = Kind.valueOf(item.getString("kind"))
        // A meeting's audio must not upload before its meeting row exists.
        if (kind == Kind.MEETING && hasPendingMeetingCreate(context, item.getString("meetingId"))) {
            Log.i(TAG, "slice $id waiting on meeting-create ${item.getString("meetingId")}")
            return
        }
        val file = File(dir(context), "$id.m4a")
        if (!file.exists()) {
            remove(context, id) // orphan record
            DiagnosticsLogger.warn("queue_audio_orphan_removed", mapOf("sliceId" to id, "kind" to kind.name))
            return
        }
        try {
            val durationSecs = item.getDouble("durationSecs")
            val sizeBytes = item.getLong("sizeBytes")
            AmplitudeTracker.track(
                "audio_push_started",
                mapOf(
                    "sliceId" to id,
                    "kind" to kind.name,
                    "meetingId" to item.optString("meetingId", ""),
                    "bytes" to sizeBytes,
                    "durationSecs" to durationSecs,
                    "metadataDone" to item.optBoolean("metadataDone", false),
                ),
            )
            if (!item.optBoolean("metadataDone", false)) {
                val roomId = item.optString("roomId", "").ifEmpty { null }
                when (kind) {
                    Kind.AMBIENT -> VuedApi.createAmbientSlice(
                        id, item.getString("sessionId"),
                        item.getDouble("startedAtSec"), item.getDouble("endedAtSec"),
                        durationSecs, sizeBytes, roomId = roomId,
                    )
                    Kind.MEETING -> VuedApi.createSlice(
                        id, item.getString("sessionId"), item.getString("meetingId"),
                        item.getDouble("startedAtSec"), item.getDouble("endedAtSec"),
                        durationSecs, sizeBytes, roomId = roomId,
                    )
                    else -> {}
                }
                setMetadataDone(context, id) // persist so a retry skips re-create
            }
            VuedApi.uploadSliceAudio(id, file, durationSecs, sizeBytes)
            if (kind == Kind.MEETING) {
                markMeetingAudioCompleted(context, item.getString("meetingId"))
            }
            runCatching { deleteUploadedSourceSegments(context, item) }
            file.delete()
            remove(context, id)
            Log.i(TAG, "uploaded queued slice $id")
            AmplitudeTracker.track(
                "audio_push_succeeded",
                mapOf(
                    "sliceId" to id,
                    "kind" to kind.name,
                    "meetingId" to item.optString("meetingId", ""),
                    "bytes" to sizeBytes,
                    "durationSecs" to durationSecs,
                    "pending" to size(context),
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "slice $id still pending: ${e.message}")
            AmplitudeTracker.track(
                "audio_push_pending",
                mapOf(
                    "sliceId" to id,
                    "kind" to kind.name,
                    "meetingId" to item.optString("meetingId", ""),
                    "message" to (e.message ?: e.javaClass.simpleName),
                ),
            )
            DiagnosticsLogger.warn("queue_audio_pending", mapOf(
                "sliceId" to id,
                "kind" to kind.name,
                "meetingId" to item.optString("meetingId", ""),
            ), e)
        }
    }

    private fun hasPendingMeetingCreate(context: Context, meetingId: String): Boolean =
        synchronized(lock) {
            val arr = load(context)
            (0 until arr.length()).any { idx ->
                val o = arr.getJSONObject(idx)
                o.getString("kind") == Kind.MEETING_CREATE.name && o.optString("meetingId") == meetingId
            }
        }

    private fun deleteUploadedSourceSegments(context: Context, item: JSONObject) {
        val segmentsDir = context.getExternalFilesDir(null)?.let { File(it, "segments") } ?: return
        val startMs = (item.getDouble("startedAtSec") * 1000).toLong()
        val endMs = (item.getDouble("endedAtSec") * 1000).toLong()
        RollingBuffer.deleteSegmentsCoveredBy(segmentsDir, startMs, endMs)
    }

    private fun loadCompletedMeetingAudio(context: Context): List<String> =
        runCatching {
            val arr = JSONArray(prefs(context).getString(KEY_COMPLETED_MEETING_AUDIO, "[]"))
            (0 until arr.length()).mapNotNull { index ->
                arr.optString(index).takeIf { it.isNotEmpty() }
            }
        }.getOrDefault(emptyList())

    private fun markMeetingAudioCompleted(context: Context, meetingId: String) = synchronized(lock) {
        val current = loadCompletedMeetingAudio(context)
        if (meetingId in current) return@synchronized
        val retained = (current + meetingId).takeLast(MAX_COMPLETED_MEETING_MARKERS)
        check(
            prefs(context).edit()
                .putString(KEY_COMPLETED_MEETING_AUDIO, JSONArray(retained).toString())
                .commit(),
        ) { "Could not persist completed meeting audio marker." }
    }

    // ---- index mutations (whole-array rewrite under lock, like iOS UserDefaults) ----

    private fun remove(context: Context, id: String) {
        synchronized(lock) {
            val arr = load(context)
            val kept = JSONArray()
            for (i in 0 until arr.length()) {
                val it = arr.getJSONObject(i)
                if (it.getString("id") != id) kept.put(it)
            }
            save(context, kept)
        }
    }

    private fun setMetadataDone(context: Context, id: String) {
        synchronized(lock) {
            val arr = load(context)
            for (i in 0 until arr.length()) {
                val it = arr.getJSONObject(i)
                if (it.getString("id") == id) it.put("metadataDone", true)
            }
            save(context, arr)
        }
    }
}
