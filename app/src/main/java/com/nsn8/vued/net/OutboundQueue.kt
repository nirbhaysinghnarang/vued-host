package com.nsn8.vued.net

import android.content.Context
import android.util.Log
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
 *  - **Ordering:** a meeting's audio slice will not upload until that meeting's
 *    `MEETING_CREATE` item has succeeded — enforced on every drain, including retries.
 *    (The create is enqueued at meeting start, so it always precedes the stop-time
 *    audio item in the queue.)
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

    enum class Kind { MEETING_CREATE, AMBIENT, MEETING }

    private val lock = Any()
    private val draining = AtomicBoolean(false)

    private fun dir(context: Context): File =
        File(context.filesDir, "outbound").apply { mkdirs() }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(context: Context): JSONArray =
        runCatching { JSONArray(prefs(context).getString(KEY, "[]")) }.getOrDefault(JSONArray())

    private fun save(context: Context, arr: JSONArray) {
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    private fun append(context: Context, item: JSONObject) {
        synchronized(lock) { save(context, load(context).put(item)) }
    }

    /** Pending item count (for status display). */
    fun size(context: Context): Int = synchronized(lock) { load(context).length() }

    // ---- enqueue (the commit points) ----

    /** Durable `POST /meetings`. Enqueued at meeting start so it precedes the audio. */
    fun enqueueMeetingCreate(
        context: Context,
        meetingId: String,
        title: String,
        startedAtSec: Double,
    ) {
        append(
            context,
            JSONObject()
                .put("id", "meeting:$meetingId")
                .put("kind", Kind.MEETING_CREATE.name)
                .put("meetingId", meetingId)
                .put("title", title)
                .put("startedAtSec", startedAtSec)
                // Snapshot the assigned room at meeting start so the in-progress
                // placeholder is filed under the same room as the eventual audio.
                .putOpt("roomId", RoomConfig.roomId(context))
                .putOpt("microphoneId", RoomConfig.microphoneId(context)),
        )
        Log.i(TAG, "enqueued MEETING_CREATE $meetingId")
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
            if (!source.renameTo(dest)) {
                source.copyTo(dest, overwrite = true)
                source.delete()
            }
            save(
                context,
                load(context).put(
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
        }
    }

    // ---- drain (opportunistic, ordered, idempotent) ----

    /**
     * Attempts every pending item once, in insertion order. A meeting's audio slice is
     * skipped while its `MEETING_CREATE` is still pending, so the meeting always exists
     * server-side before its audio is uploaded — on first try and on every retry.
     */
    suspend fun drain(context: Context) {
        if (!draining.compareAndSet(false, true)) return
        try {
            val items = synchronized(lock) { load(context) }
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                when (Kind.valueOf(item.getString("kind"))) {
                    Kind.MEETING_CREATE -> drainMeetingCreate(context, item)
                    Kind.AMBIENT, Kind.MEETING -> drainAudio(context, item)
                }
            }
        } finally {
            draining.set(false)
        }
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
            return
        }
        try {
            val durationSecs = item.getDouble("durationSecs")
            val sizeBytes = item.getLong("sizeBytes")
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
            VuedApi.uploadSliceAudio(id, file.readBytes(), durationSecs, sizeBytes)
            file.delete()
            remove(context, id)
            Log.i(TAG, "uploaded queued slice $id")
        } catch (e: Exception) {
            Log.w(TAG, "slice $id still pending: ${e.message}")
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
