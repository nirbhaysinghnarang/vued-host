package com.nsn8.vued.net

import android.content.Context
import android.util.Log
import com.nsn8.vued.DiagnosticsLogger
import com.nsn8.vued.audio.MultiChannelWavRollingBuffer
import com.nsn8.vued.audio.RollingBuffer
import com.nsn8.vued.audio.SourceUploadPlan
import com.nsn8.vued.audio.SourceWavContainerPlan
import com.nsn8.vued.audio.SourceWavSegmentEncoder
import com.nsn8.vued.audio.SourceWavStreamPlan
import com.nsn8.vued.audio.WavSegmentExporter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Durable, offline-safe upload queue for the timeline flow — the Android mirror of the
 * iOS `AdaptiveOggStorage` cache-and-retry pattern, extended to also make the
 * `POST /meetings` create durable and ordered ahead of its audio.
 *
 *  - **Never miss audio / never lose a meeting:** exported audio artifacts and the
 *    meeting-create are committed to a persisted index before any network call. The
 *    capture/flush cursor advances on enqueue, not on upload.
 *  - **Ordering:** a meeting's audio slice will not upload until that meeting's
 *    `MEETING_CREATE` item has succeeded — enforced on every drain, including retries.
 *    (The create is enqueued at meeting start, so it always precedes the stop-time
 *    audio item in the queue.) The drain services items in priority tiers —
 *    `MEETING_CREATE → MEETING → MEETING_SOURCE_WAV → AMBIENT → AMBIENT_SOURCE_WAV`,
 *    FIFO within a tier — and reloads the index between items, so meeting work
 *    enqueued mid-drain preempts a queued ambient backlog instead of waiting behind
 *    it. Ambient source sidecars additionally yield between 4 MB chunks to pending
 *    higher-tier work and later resume from the server offset.
 *  - **Offline-safe + idempotent:** the index survives restarts (SharedPreferences
 *    JSON, like iOS UserDefaults); the server is idempotent on `meetingId` /`sliceId`
 *    (`ON CONFLICT`), so at-least-once retries never duplicate.
 *  - **Resumable two-step:** `metadataDone` is persisted after the slice-metadata POST
 *    so a retry skips straight to the bytes PUT.
 *  - **Opportunistic drain:** no scheduler — [drain] runs at lifecycle points (service
 *    start, each ambient flush, meeting start/stop), guarded against concurrent runs.
 *    A drain requested while one is running sets a rerun flag so the in-flight drain
 *    runs another pass instead of the request being dropped.
 *  - **No eviction:** items are kept until they succeed (matches iOS). Success removes
 *    the entry (+ file); orphan audio entries (missing file) are pruned.
 */
object OutboundQueue {

    private const val TAG = "VuedOutboundQueue"
    private const val PREFS = "vued_outbound"
    private const val KEY = "queue"

    enum class Kind { MEETING_CREATE, AMBIENT, MEETING, MEETING_SOURCE_WAV, AMBIENT_SOURCE_WAV }

    /** Drain priority: lower value first; FIFO (insertion order) within a tier.
     *  Explicit `when` — not [Kind.ordinal] — because kind names are persisted. */
    private fun priority(kind: Kind): Int = when (kind) {
        Kind.MEETING_CREATE -> 0
        Kind.MEETING -> 1
        Kind.MEETING_SOURCE_WAV -> 2 // includes streaming items
        Kind.AMBIENT -> 3
        Kind.AMBIENT_SOURCE_WAV -> 4
    }

    // Ambient source sidecars are best-effort: cap their pending bytes so an
    // offline stretch can't fill the disk with ~150 MB windows. Meetings keep
    // the no-eviction guarantee.
    private const val AMBIENT_SOURCE_WAV_MAX_PENDING_BYTES = 2_000_000_000L

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
        DiagnosticsLogger.info("queue_meeting_create_enqueued", mapOf(
            "meetingId" to meetingId,
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

    fun enqueueMeetingSourceWav(
        context: Context,
        sliceId: String,
        sessionId: String,
        meetingId: String,
        startedAtSec: Double,
        endedAtSec: Double,
        durationSecs: Double,
        monoSizeBytes: Long,
        channels: Int,
        source: File,
    ): File =
        synchronized(lock) {
            val dest = File(dir(context), "$sliceId.source.wav")
            if (!source.renameTo(dest)) {
                source.copyTo(dest, overwrite = true)
                source.delete()
            }
            save(
                context,
                load(context).put(
                    JSONObject()
                        .put("id", sourceItemId(sliceId))
                        .put("kind", Kind.MEETING_SOURCE_WAV.name)
                        .put("sliceId", sliceId)
                        .put("sessionId", sessionId)
                        .put("meetingId", meetingId)
                        .put("startedAtSec", startedAtSec)
                        .put("endedAtSec", endedAtSec)
                        .put("durationSecs", durationSecs)
                        .put("monoSizeBytes", monoSizeBytes)
                        .put("sourceSizeBytes", dest.length())
                        .put("sourceChannels", channels)
                        .put("sourceSampleRateHz", SOURCE_WAV_SAMPLE_RATE_HZ)
                        .put("metadataDone", false)
                        .putOpt("roomId", RoomConfig.roomId(context)),
                ),
            )
            Log.i(TAG, "enqueued MEETING_SOURCE_WAV slice $sliceId (${dest.length()} bytes)")
            DiagnosticsLogger.info("queue_source_wav_enqueued", mapOf(
                "sliceId" to sliceId,
                "meetingId" to meetingId,
                "bytes" to dest.length(),
                "pending" to load(context).length(),
            ))
            dest
        }

    fun enqueueAmbientSourceWav(
        context: Context,
        sliceId: String,
        sessionId: String,
        startedAtSec: Double,
        endedAtSec: Double,
        durationSecs: Double,
        monoSizeBytes: Long,
        channels: Int,
        source: File,
        codec: String = "pcm",
    ): File? =
        synchronized(lock) {
            val items = load(context)
            var pendingSourceBytes = 0L
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                if (item.optString("kind") == Kind.AMBIENT_SOURCE_WAV.name) {
                    pendingSourceBytes += item.optLong("sourceSizeBytes", 0L)
                }
            }
            if (pendingSourceBytes + source.length() > AMBIENT_SOURCE_WAV_MAX_PENDING_BYTES) {
                source.delete()
                Log.w(TAG, "ambient source wav dropped: backlog ${pendingSourceBytes / 1_000_000}MB over cap")
                DiagnosticsLogger.warn("ambient_source_wav_backlog_dropped", mapOf(
                    "sliceId" to sliceId,
                    "pendingBytes" to pendingSourceBytes,
                ))
                return@synchronized null
            }
            val dest = File(dir(context), "$sliceId.source.wav")
            if (!source.renameTo(dest)) {
                source.copyTo(dest, overwrite = true)
                source.delete()
            }
            save(
                context,
                items.put(
                    JSONObject()
                        .put("id", sourceItemId(sliceId))
                        .put("kind", Kind.AMBIENT_SOURCE_WAV.name)
                        .put("sliceId", sliceId)
                        .put("sessionId", sessionId)
                        .put("startedAtSec", startedAtSec)
                        .put("endedAtSec", endedAtSec)
                        .put("durationSecs", durationSecs)
                        .put("monoSizeBytes", monoSizeBytes)
                        .put("sourceSizeBytes", dest.length())
                        .put("sourceChannels", channels)
                        .put("sourceSampleRateHz", SOURCE_WAV_SAMPLE_RATE_HZ)
                        .put("codec", codec)
                        .put("metadataDone", false)
                        .putOpt("roomId", RoomConfig.roomId(context)),
                ),
            )
            Log.i(TAG, "enqueued AMBIENT_SOURCE_WAV slice $sliceId (${dest.length()} bytes, $codec)")
            DiagnosticsLogger.info("queue_ambient_source_wav_enqueued", mapOf(
                "sliceId" to sliceId,
                "bytes" to dest.length(),
                "codec" to codec,
                "pending" to load(context).length(),
            ))
            dest
        }

    /**
     * Enqueues a durable *streaming* source-WAV item at meeting start. Unlike
     * [enqueueMeetingSourceWav] there is no file yet — the durable chunks are
     * the 30s segments already on disk under [segmentsDir]. Each [drain] (driven
     * by segment closes) pushes more bytes; [markMeetingSourceWavEnded] at stop
     * lets the drain finalize.
     */
    fun enqueueMeetingSourceWavStreaming(
        context: Context,
        sliceId: String,
        sessionId: String,
        meetingId: String,
        startedAtSec: Double,
        segmentsDir: String,
        channels: Int,
        sampleRateHz: Int = SOURCE_WAV_SAMPLE_RATE_HZ,
        codec: String = "pcm",
    ) = synchronized(lock) {
        val id = sourceItemId(sliceId)
        val arr = load(context)
        if ((0 until arr.length()).any { arr.getJSONObject(it).getString("id") == id }) {
            return@synchronized  // already enqueued for this slice
        }
        arr.put(
            JSONObject()
                .put("id", id)
                .put("kind", Kind.MEETING_SOURCE_WAV.name)
                .put("streaming", true)
                .put("ended", false)
                .put("sliceId", sliceId)
                .put("sessionId", sessionId)
                .put("meetingId", meetingId)
                .put("startedAtSec", startedAtSec)
                .put("endedAtSec", 0.0)
                .put("durationSecs", 0.0)
                .put("segmentsDir", segmentsDir)
                .put("sourceChannels", channels)
                .put("sourceSampleRateHz", sampleRateHz)
                .put("codec", codec)
                .put("createdAtMs", System.currentTimeMillis())
                .put("metadataDone", false)
                .putOpt("roomId", RoomConfig.roomId(context)),
        )
        save(context, arr)
        Log.i(TAG, "enqueued streaming MEETING_SOURCE_WAV slice $sliceId")
        DiagnosticsLogger.info("queue_source_wav_stream_enqueued", mapOf(
            "sliceId" to sliceId,
            "meetingId" to meetingId,
            "pending" to arr.length(),
        ))
    }

    /** True if a streaming source-WAV item exists for [sliceId]. */
    fun hasStreamingSourceWav(context: Context, sliceId: String): Boolean = synchronized(lock) {
        val arr = load(context)
        val id = sourceItemId(sliceId)
        (0 until arr.length()).any {
            val o = arr.getJSONObject(it)
            o.getString("id") == id && o.optBoolean("streaming", false)
        }
    }

    /** Marks the streaming source-WAV item ended so the next drain finalizes it. */
    fun markMeetingSourceWavEnded(
        context: Context,
        sliceId: String,
        endedAtSec: Double,
        durationSecs: Double,
    ) = synchronized(lock) {
        val arr = load(context)
        val id = sourceItemId(sliceId)
        for (i in 0 until arr.length()) {
            val it = arr.getJSONObject(i)
            if (it.getString("id") == id && it.optBoolean("streaming", false)) {
                it.put("ended", true)
                it.put("endedAtSec", endedAtSec)
                it.put("durationSecs", durationSecs)
            }
        }
        save(context, arr)
        Log.i(TAG, "marked streaming MEETING_SOURCE_WAV ended slice $sliceId")
    }

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
            DiagnosticsLogger.info("queue_audio_enqueued", mapOf(
                "kind" to kind.name,
                "sliceId" to sliceId,
                "meetingId" to meetingId,
                "bytes" to dest.length(),
                "pending" to load(context).length(),
            ))
        }
    }

    // ---- drain (opportunistic, ordered, idempotent) ----

    /**
     * Attempts every pending item at most once per pass, highest-priority tier first
     * ([priority]; insertion order within a tier), reloading the index between items so
     * meeting work enqueued mid-drain is picked up ahead of a queued ambient backlog.
     * A meeting's audio slice is skipped while its `MEETING_CREATE` is still pending,
     * so the meeting always exists server-side before its audio is uploaded — on first
     * try and on every retry. Reruns while further drains were requested; a yielded
     * ambient sidecar is re-pickable within the pass and is not counted as a failure.
     */
    suspend fun drain(context: Context) {
        drainRequested.set(true) // set AFTER the caller's enqueue, so a request is never lost
        var pass = 0
        while (true) {
            if (!draining.compareAndSet(false, true)) return // running drain will see the flag
            try {
                while (drainRequested.compareAndSet(true, false)) {
                    if (pass > 0) DiagnosticsLogger.info("queue_drain_rerun", mapOf("pass" to pass))
                    drainPass(context)
                    pass += 1
                }
            } finally {
                draining.set(false)
            }
            // A request may have landed between the last flag check and releasing
            // `draining` (that caller's CAS failed, so nobody owns it) — re-claim.
            if (!drainRequested.get()) return
        }
    }

    private suspend fun drainPass(context: Context) {
        val startedAt = System.currentTimeMillis()
        // Every iteration either grows `attempted` permanently (including failures — a
        // failing item is tried at most once per pass) or is a yield, which requires an
        // unattempted higher-tier item that the very next pick consumes. So the pass
        // terminates for any finite enqueue rate, and nothing can starve or hot-loop.
        val attempted = mutableSetOf<String>()
        var attempts = 0
        var yields = 0
        while (true) {
            val item = nextPending(context, attempted) ?: break
            val id = item.getString("id")
            attempted += id
            attempts += 1
            val yielded = when (Kind.valueOf(item.getString("kind"))) {
                Kind.MEETING_CREATE -> { drainMeetingCreate(context, item); false }
                Kind.AMBIENT, Kind.MEETING -> { drainAudio(context, item); false }
                Kind.MEETING_SOURCE_WAV -> { drainMeetingSourceWav(context, item); false }
                Kind.AMBIENT_SOURCE_WAV -> drainAmbientSourceWav(context, item) {
                    hasHigherPriorityPending(context, priority(Kind.AMBIENT_SOURCE_WAV), attempted)
                }
            }
            if (yielded) {
                attempted -= id
                yields += 1
            }
        }
        DiagnosticsLogger.info("queue_drain_completed", mapOf(
            "attempted" to attempts,
            "yields" to yields,
            "pending" to size(context),
            "elapsedMs" to (System.currentTimeMillis() - startedAt),
        ))
    }

    /** First (insertion-order) unattempted item of the highest-priority tier,
     *  reloaded under lock so items enqueued or removed mid-drain are seen. */
    private fun nextPending(context: Context, attempted: Set<String>): JSONObject? =
        synchronized(lock) {
            val items = load(context)
            (0 until items.length())
                .map { items.getJSONObject(it) }
                .filter { it.getString("id") !in attempted }
                .minByOrNull { priority(Kind.valueOf(it.getString("kind"))) }
        }

    /** True when an unattempted item of a strictly higher tier than [tier] is pending. */
    private fun hasHigherPriorityPending(context: Context, tier: Int, attempted: Set<String>): Boolean =
        synchronized(lock) {
            val items = load(context)
            (0 until items.length()).any { idx ->
                val o = items.getJSONObject(idx)
                o.getString("id") !in attempted &&
                    priority(Kind.valueOf(o.getString("kind"))) < tier
            }
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
            runCatching { deleteUploadedSourceSegments(context, item) }
            file.delete()
            remove(context, id)
            Log.i(TAG, "uploaded queued slice $id (${kind.name})")
        } catch (e: Exception) {
            Log.w(TAG, "slice $id still pending: ${e.message}")
            DiagnosticsLogger.warn("queue_audio_pending", mapOf(
                "sliceId" to id,
                "kind" to kind.name,
                "meetingId" to item.optString("meetingId", ""),
            ), e)
        }
    }

    private suspend fun drainMeetingSourceWav(context: Context, item: JSONObject) {
        if (item.optBoolean("streaming", false)) {
            drainStreamingSourceWav(context, item)
            return
        }
        val itemId = item.getString("id")
        val sliceId = item.getString("sliceId")
        val meetingId = item.getString("meetingId")
        if (hasPendingMeetingCreate(context, meetingId)) {
            Log.i(TAG, "source wav $sliceId waiting on meeting-create $meetingId")
            return
        }
        val file = File(dir(context), "$sliceId.source.wav")
        if (!file.exists()) {
            remove(context, itemId)
            DiagnosticsLogger.warn("queue_source_wav_orphan_removed", mapOf(
                "sliceId" to sliceId,
                "meetingId" to meetingId,
            ))
            return
        }
        try {
            val durationSecs = item.getDouble("durationSecs")
            val monoSizeBytes = item.getLong("monoSizeBytes")
            if (!item.optBoolean("metadataDone", false)) {
                val roomId = item.optString("roomId", "").ifEmpty { null }
                VuedApi.createSlice(
                    sliceId,
                    item.getString("sessionId"),
                    meetingId,
                    item.getDouble("startedAtSec"),
                    item.getDouble("endedAtSec"),
                    durationSecs,
                    monoSizeBytes,
                    roomId = roomId,
                )
                setMetadataDone(context, itemId)
            }

            val sourceSizeBytes = file.length()
            // TODO: if long meeting sidecars ever delay ambient mono too much, pass a
            // shouldYield (higher-tier threshold) here and handle Yielded like the
            // ambient variant does.
            VuedApi.uploadSliceSourceWav(
                sliceId = sliceId,
                source = file,
                durationSecs = durationSecs,
                sizeBytes = sourceSizeBytes,
                channels = item.optInt("sourceChannels", SOURCE_WAV_CHANNELS),
                sampleRateHz = item.optInt("sourceSampleRateHz", SOURCE_WAV_SAMPLE_RATE_HZ),
                codec = item.optString("codec", "pcm"),
            )
            file.delete()
            remove(context, itemId)
            runCatching { deleteUploadedUma16Segments(context, item) }
            Log.i(TAG, "uploaded queued source wav $sliceId")
        } catch (e: Exception) {
            Log.w(TAG, "source wav $sliceId still pending: ${e.message}")
            DiagnosticsLogger.warn("queue_source_wav_pending", mapOf(
                "sliceId" to sliceId,
                "meetingId" to meetingId,
            ), e)
        }
    }

    /**
     * Ambient variant of [drainMeetingSourceWav]: no meeting to wait for, and
     * the slice row is ensured via the ambient creator (idempotent server-side —
     * the mono AMBIENT item usually created it already).
     *
     * Returns true when the upload yielded to higher-priority work ([shouldYield],
     * polled between chunks) — not a failure: the item stays pending and re-pickable,
     * and the next attempt resumes from the server offset. All other exits return false.
     */
    private suspend fun drainAmbientSourceWav(
        context: Context,
        item: JSONObject,
        shouldYield: () -> Boolean,
    ): Boolean {
        val itemId = item.getString("id")
        val sliceId = item.getString("sliceId")
        val file = File(dir(context), "$sliceId.source.wav")
        if (!file.exists()) {
            remove(context, itemId)
            DiagnosticsLogger.warn("queue_ambient_source_wav_orphan_removed", mapOf(
                "sliceId" to sliceId,
            ))
            return false
        }
        try {
            val durationSecs = item.getDouble("durationSecs")
            val monoSizeBytes = item.getLong("monoSizeBytes")
            if (!item.optBoolean("metadataDone", false)) {
                val roomId = item.optString("roomId", "").ifEmpty { null }
                VuedApi.createAmbientSlice(
                    sliceId,
                    item.getString("sessionId"),
                    item.getDouble("startedAtSec"),
                    item.getDouble("endedAtSec"),
                    durationSecs,
                    monoSizeBytes,
                    roomId = roomId,
                )
                setMetadataDone(context, itemId)
            }

            val sourceSizeBytes = file.length()
            val result = VuedApi.uploadSliceSourceWav(
                sliceId = sliceId,
                source = file,
                durationSecs = durationSecs,
                sizeBytes = sourceSizeBytes,
                channels = item.optInt("sourceChannels", SOURCE_WAV_CHANNELS),
                sampleRateHz = item.optInt("sourceSampleRateHz", SOURCE_WAV_SAMPLE_RATE_HZ),
                codec = item.optString("codec", "pcm"),
                shouldYield = shouldYield,
            )
            if (result is VuedApi.SourceWavUploadResult.Yielded) {
                Log.i(TAG, "ambient source wav $sliceId yielded at ${result.offset}/${result.totalBytes}")
                DiagnosticsLogger.info("queue_ambient_source_wav_yielded", mapOf(
                    "sliceId" to sliceId,
                    "offset" to result.offset,
                    "totalBytes" to result.totalBytes,
                ))
                return true
            }
            file.delete()
            remove(context, itemId)
            Log.i(TAG, "uploaded queued ambient source wav $sliceId")
        } catch (e: Exception) {
            Log.w(TAG, "ambient source wav $sliceId still pending: ${e.message}")
            DiagnosticsLogger.warn("queue_ambient_source_wav_pending", mapOf(
                "sliceId" to sliceId,
            ), e)
        }
        return false
    }

    private suspend fun drainStreamingSourceWav(context: Context, item: JSONObject) {
        val itemId = item.getString("id")
        val sliceId = item.getString("sliceId")
        val meetingId = item.getString("meetingId")
        val ended = item.optBoolean("ended", false)

        // Orphan reclaim: an un-ended item far past creation means the meeting was
        // abandoned (e.g. an app crash mid-meeting never reached stop). The server
        // sweeps its temp session separately.
        if (!ended) {
            val ageMs = System.currentTimeMillis() - item.optLong("createdAtMs", 0L)
            if (ageMs > STREAMING_SOURCE_WAV_TTL_MS) {
                remove(context, itemId)
                runCatching { wvBlobDir(context, sliceId).deleteRecursively() }
                DiagnosticsLogger.warn("queue_source_wav_stream_orphaned", mapOf(
                    "sliceId" to sliceId, "meetingId" to meetingId, "ageMs" to ageMs,
                ))
                return
            }
        }

        if (hasPendingMeetingCreate(context, meetingId)) {
            Log.i(TAG, "source wav stream $sliceId waiting on meeting-create $meetingId")
            return
        }

        val segmentsPath = item.optString("segmentsDir", "")
        val segmentsDir = File(segmentsPath)
        if (segmentsPath.isEmpty() || !segmentsDir.isDirectory) {
            return  // segment dir not available yet; retry on the next drain
        }
        val startMs = (item.getDouble("startedAtSec") * 1000).toLong()
        val channels = item.optInt("sourceChannels", SOURCE_WAV_CHANNELS)
        val sampleRate = item.optInt("sourceSampleRateHz", SOURCE_WAV_SAMPLE_RATE_HZ)
        val codec = item.optString("codec", "pcm")

        try {
            val endMs: Long? = if (ended) (item.getDouble("endedAtSec") * 1000).toLong() else null
            val overlapping = WavSegmentExporter.overlappingSegments(
                MultiChannelWavRollingBuffer.listSegmentsIn(segmentsDir, sampleRate, channels),
                startMs,
                endMs ?: Long.MAX_VALUE,
            )
            // While recording, withhold the most recent segment — it may still be
            // growing or turn out to be the meeting's last (tail-trimmed) segment.
            val selected = if (ended) overlapping else overlapping.dropLast(1)
            if (ended && selected.isEmpty()) {
                // The meeting window contains no source audio at all (e.g. a
                // false-start stop, or the rolling buffer never produced mic-array
                // segments). The server will only ever reject the empty payload,
                // so retrying poisons the queue forever — abandon instead.
                remove(context, itemId)
                runCatching { wvBlobDir(context, sliceId).deleteRecursively() }
                DiagnosticsLogger.warn("queue_source_wav_stream_abandoned_empty", mapOf(
                    "sliceId" to sliceId, "meetingId" to meetingId,
                ))
                return
            }
            val plan: SourceUploadPlan = if (codec == "wavpack") {
                // Encode each safe, trimmed segment to a cached .wv blob, then lay
                // them out as the length-prefixed container the server stores and
                // Modal decodes.
                val blobs = selected.mapNotNull {
                    SourceWavSegmentEncoder.encodeSegment(it, startMs, endMs, wvBlobDir(context, sliceId))
                }
                if (ended && blobs.isEmpty()) {
                    remove(context, itemId)
                    runCatching { wvBlobDir(context, sliceId).deleteRecursively() }
                    DiagnosticsLogger.warn("queue_source_wav_stream_abandoned_empty", mapOf(
                        "sliceId" to sliceId, "meetingId" to meetingId, "codec" to codec,
                    ))
                    return
                }
                SourceWavContainerPlan.build(blobs)
            } else {
                SourceWavStreamPlan.build(selected, startMs, endMs, sampleRate, channels)
            }
            val durationSecs = if (ended) {
                (item.getDouble("endedAtSec") - item.getDouble("startedAtSec")).coerceAtLeast(0.0)
            } else 0.0

            if (ended && !item.optBoolean("metadataDone", false)) {
                // /complete needs the slice row. The mono MEETING item drains
                // ahead of source-wav (so it usually wins the ON CONFLICT DO
                // NOTHING race with its own metadata); this is the safety net
                // that guarantees the row exists before we finalize.
                val roomId = item.optString("roomId", "").ifEmpty { null }
                VuedApi.createSlice(
                    sliceId,
                    item.getString("sessionId"),
                    meetingId,
                    item.getDouble("startedAtSec"),
                    item.getDouble("endedAtSec"),
                    durationSecs,
                    plan.totalBytes,
                    roomId = roomId,
                )
                setMetadataDone(context, itemId)
            }

            VuedApi.uploadSourceWavStream(
                sliceId, plan, ended, channels, sampleRate, codec = codec, durationSecs = durationSecs,
            )

            if (ended) {
                remove(context, itemId)
                runCatching { wvBlobDir(context, sliceId).deleteRecursively() }
                // The upload is durable server-side; the raw ring segments this
                // window covered are no longer needed by anyone (ambient windows
                // never overlap meeting ranges). Boundary straddlers survive to
                // the age/floor prunes.
                runCatching {
                    val deleted = MultiChannelWavRollingBuffer.deleteSegmentsCoveredBy(
                        segmentsDir, startMs, requireNotNull(endMs), channels = channels,
                    )
                    if (deleted > 0) {
                        DiagnosticsLogger.info("queue_source_wav_segments_deleted", mapOf(
                            "sliceId" to sliceId, "meetingId" to meetingId, "deleted" to deleted,
                            "startMs" to startMs, "endMs" to endMs,
                        ))
                    }
                }
                Log.i(TAG, "completed streaming source wav $sliceId (${plan.totalBytes} bytes, codec=$codec)")
                DiagnosticsLogger.info("queue_source_wav_stream_completed", mapOf(
                    "sliceId" to sliceId, "meetingId" to meetingId, "bytes" to plan.totalBytes, "codec" to codec,
                ))
            }
        } catch (e: Exception) {
            if (ended && e is VuedApi.ApiException && e.isPermanentRejection) {
                // The server has definitively rejected the finalized payload
                // (e.g. 400 "wav data chunk is empty"); no retry can change the
                // bytes, so drop the item instead of blocking the queue forever.
                remove(context, itemId)
                runCatching { wvBlobDir(context, sliceId).deleteRecursively() }
                Log.w(TAG, "streaming source wav $sliceId abandoned: ${e.message}")
                DiagnosticsLogger.warn("queue_source_wav_stream_abandoned_rejected", mapOf(
                    "sliceId" to sliceId, "meetingId" to meetingId, "status" to e.statusCode,
                ), e)
                return
            }
            Log.w(TAG, "streaming source wav $sliceId still pending: ${e.message}")
            DiagnosticsLogger.warn("queue_source_wav_stream_pending", mapOf(
                "sliceId" to sliceId, "meetingId" to meetingId, "ended" to ended,
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

    /** Mic-array counterpart of [deleteUploadedSourceSegments] for the
     *  non-streaming meeting source upload (self-contained outbound copy). */
    private fun deleteUploadedUma16Segments(context: Context, item: JSONObject) {
        val dir = context.getExternalFilesDir(null)?.let { File(it, SOURCE_SEGMENTS_DIR_NAME) } ?: return
        val deleted = MultiChannelWavRollingBuffer.deleteSegmentsCoveredBy(
            dir,
            (item.getDouble("startedAtSec") * 1000).toLong(),
            (item.getDouble("endedAtSec") * 1000).toLong(),
            channels = item.optInt("sourceChannels", SOURCE_WAV_CHANNELS),
        )
        if (deleted > 0) {
            DiagnosticsLogger.info("queue_source_wav_segments_deleted", mapOf(
                "sliceId" to item.optString("sliceId"), "deleted" to deleted,
            ))
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

    private fun sourceItemId(sliceId: String): String = "source:$sliceId"

    /** Per-slice cache of encoded WavPack segment blobs (durable across restarts). */
    private fun wvBlobDir(context: Context, sliceId: String): File =
        File(context.filesDir, "wv_blobs/$sliceId")

    /** On-device mic-array segment dir. Historical name — kept for UMA-8 too,
     *  since persisted queue items reference it by absolute path. */
    const val SOURCE_SEGMENTS_DIR_NAME = "uma16_segments"

    /** Fallback channel count for queue items persisted by pre-UMA-8 builds,
     *  which only ever recorded 16-channel source WAVs. Not a default for new
     *  items — enqueue callers must pass the buffer's real channel count. */
    private const val SOURCE_WAV_CHANNELS = 16
    private const val SOURCE_WAV_SAMPLE_RATE_HZ = 16_000
    // Longer than any plausible single meeting, so a still-recording meeting is
    // never reclaimed — only a streaming item whose meeting was truly abandoned.
    private const val STREAMING_SOURCE_WAV_TTL_MS = 12L * 60 * 60 * 1000
}
