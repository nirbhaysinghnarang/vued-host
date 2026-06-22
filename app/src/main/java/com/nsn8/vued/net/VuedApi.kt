package com.nsn8.vued.net

import android.content.Context
import com.nsn8.vued.VuedConfig
import com.nsn8.vued.auth.VuedAuth
import com.nsn8.vued.crypto.EventCrypto
import com.nsn8.vued.crypto.WrappedKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Thin REST client for the Vued backend. Attaches the Supabase JWT and unwraps the
 * `{status, message, data, error}` envelope. Only the encrypted-client endpoints are
 * here for now (public-key registry + passphrase vault); the durable upload flow lands
 * next.
 */
object VuedApi {

    private val client = OkHttpClient()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    const val KEY_ID = "hpke-x25519-aesgcm-v1"

    class ApiException(message: String) : Exception(message)

    /**
     * Fetches the STT vendor credential so the device can transcribe directly
     * (device-orchestrated STT — transcript plaintext never hits our servers).
     * Interim: a shared key; becomes a scoped token later.
     */
    suspend fun fetchSttKey(): String {
        val data = request("/api/v1/tokens/assemblyai", method = "GET")
            ?: throw ApiException("STT key unavailable")
        return data.getString("api_key")
    }

    suspend fun uploadPublicKey(publicKeysetB64: String, keyId: String = KEY_ID) {
        val body = JSONObject()
            .put("public_keyset", publicKeysetB64)
            .put("key_id", keyId)
        post("/api/v1/keys", body)
    }

    suspend fun uploadVault(wrapped: WrappedKey) {
        val body = JSONObject()
            .put("salt", wrapped.saltB64)
            .put("nonce", wrapped.nonceB64)
            .put("wrapped_key", wrapped.ciphertextB64)
            .put("kdf_t_cost", wrapped.tCost)
            .put("kdf_m_cost_kib", wrapped.mCostKib)
            .put("kdf_parallelism", wrapped.parallelism)
        post("/api/v1/vault", body)
    }

    suspend fun fetchVault(): WrappedKey? {
        val data = request("/api/v1/vault", method = "GET") ?: return null
        return WrappedKey(
            saltB64 = data.getString("salt"),
            nonceB64 = data.getString("nonce"),
            ciphertextB64 = data.getString("wrapped_key"),
            tCost = data.getInt("kdf_t_cost"),
            mCostKib = data.getInt("kdf_m_cost_kib"),
            parallelism = data.getInt("kdf_parallelism"),
        )
    }

    // ---- meeting recording (timeline-slice path) ----

    /** Creates the meeting row (legacy `meetings`). [meetingId] is no-dash hex. */
    suspend fun createMeeting(meetingId: String, title: String, startedAtSec: Double) {
        post(
            "/api/v1/meetings",
            JSONObject()
                .put("id", meetingId)
                .put("title", title)
                .put("status", "recording")
                .put("started_at", startedAtSec),
        )
    }

    /** Registers the audio-slice metadata row (status -> pending). [roomId] tags
     *  the slice with the tablet's assigned room; the server resolves mic/org. */
    suspend fun createSlice(
        sliceId: String,
        sessionId: String,
        meetingId: String,
        startedAtSec: Double,
        endedAtSec: Double,
        durationSecs: Double,
        sizeBytes: Long,
        roomId: String? = null,
    ) {
        post(
            "/api/v1/transcript/audio-slices",
            JSONObject()
                .put("id", sliceId)
                .put("sessionId", sessionId)
                .put("modality", "meeting")
                .put("linkedRecordId", meetingId)
                .put("linkedRecordType", "meeting")
                .put("startedAt", startedAtSec)
                .put("endedAt", endedAtSec)
                .put("audioDurationSecs", durationSecs)
                .put("audioSizeBytes", sizeBytes)
                .putOpt("roomId", roomId),
        )
    }

    /** Registers an ambient slice (no linked record — just continuous capture). */
    suspend fun createAmbientSlice(
        sliceId: String,
        sessionId: String,
        startedAtSec: Double,
        endedAtSec: Double,
        durationSecs: Double,
        sizeBytes: Long,
        roomId: String? = null,
    ) {
        post(
            "/api/v1/transcript/audio-slices",
            JSONObject()
                .put("id", sliceId)
                .put("sessionId", sessionId)
                .put("modality", "ambient")
                .put("startedAt", startedAtSec)
                .put("endedAt", endedAtSec)
                .put("audioDurationSecs", durationSecs)
                .put("audioSizeBytes", sizeBytes)
                .putOpt("roomId", roomId),
        )
    }

    /**
     * Ships the raw M4A bytes. Normally this kicks the server's transcribe pipeline;
     * with [clientTranscribed] the server stores the audio (for speaker-ID + retention)
     * but skips server STT — the device transcribed it and posts sealed events itself.
     */
    suspend fun uploadSliceAudio(
        sliceId: String,
        audio: ByteArray,
        durationSecs: Double,
        sizeBytes: Long,
        clientTranscribed: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        val token = VuedAuth.currentAccessToken() ?: throw ApiException("not signed in")
        val sttParam = if (clientTranscribed) "?stt=skip" else ""
        val request = Request.Builder()
            .url("${VuedConfig.API_BASE_URL}/api/v1/transcript/audio-slices/$sliceId/audio$sttParam")
            .header("Authorization", "Bearer $token")
            .header("x-audio-duration-secs", durationSecs.toString())
            .header("x-audio-size-bytes", sizeBytes.toString())
            .put(audio.toRequestBody("audio/mp4".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val envelope = if (text.isNotBlank()) JSONObject(text) else JSONObject()
            val status = envelope.optInt("status", response.code)
            if (status !in 200..299) {
                throw ApiException(envelope.optString("message", "HTTP $status"))
            }
        }
    }

    /** Fetches a meeting row (camelCase), including `contentEnc` for decryption. */
    suspend fun getMeeting(meetingId: String): JSONObject? =
        request("/api/v1/meetings/$meetingId", method = "GET")

    // ---- decrypt-gateway reads (ciphertext in; the device decrypts locally) ----
    // These are the device's two-hop fetches: app -> vued-api -> supabase -> back.
    // Every row carries the `*Enc` fields; nothing here is plaintext content.

    /** Most-recent-first meeting rows (each may carry `titleEnc`). */
    suspend fun listMeetings(limit: Int = 100): JSONArray =
        request("/api/v1/meetings?limit=$limit", method = "GET")
            ?.optJSONArray("items") ?: JSONArray()

    /** Transcript events linked to a meeting (each may carry `textEnc`). */
    suspend fun meetingEvents(meetingId: String, limit: Int = 2000): JSONArray =
        request(
            "/api/v1/transcript/events?linked_record_id=$meetingId" +
                "&linked_record_type=meeting&limit=$limit",
            method = "GET",
        )?.optJSONArray("items") ?: JSONArray()

    /** Ambient transcript events in [fromSec, toSec) (each may carry `textEnc`). */
    suspend fun ambientEvents(fromSec: Double?, toSec: Double?, limit: Int = 2000): JSONArray {
        val q = StringBuilder("/api/v1/transcript/events?modality=ambient&limit=$limit")
        if (fromSec != null) q.append("&from=$fromSec")
        if (toSec != null) q.append("&to=$toSec")
        return request(q.toString(), method = "GET")?.optJSONArray("items") ?: JSONArray()
    }

    // ---- semantic search (embeddings-only index; results carry ciphertext events) ----

    /**
     * One semantic-search hit. The vector store holds no transcript text — the
     * server matches on embeddings, then hydrates the matched transcript events.
     * [events] rows may carry `textEnc`; the device decrypts them locally (same
     * model as the decrypt-gateway reads above). [score] is cosine similarity in
     * [0,1]; [tsStart]/[tsEnd] bound the matched span.
     */
    data class SearchHit(
        val recordId: String,
        val recordType: String,
        val sourceTable: String,
        val score: Double,
        val tsStart: Double?,
        val tsEnd: Double?,
        val eventIds: List<String>,
        val events: JSONArray,
    )

    /**
     * Semantic search over the user's transcripts. [recordType] optionally scopes
     * to "meeting" or "idea". With [hydrate] (default true) each hit includes the
     * matched transcript-event rows; pass false for ids-only (lighter).
     *
     * Pass [context] to decrypt the hits in place before returning (events get
     * plaintext `text`); omit it to receive ciphertext and decrypt later via
     * [decryptHits]. Decryption uses the device Keystore key and throws
     * [EventCrypto.NotProvisioned] if the device isn't provisioned.
     */
    suspend fun searchTranscripts(
        query: String,
        limit: Int = 10,
        recordType: String? = null,
        hydrate: Boolean = true,
        context: Context? = null,
    ): List<SearchHit> {
        val body = JSONObject()
            .put("query", query)
            .put("limit", limit)
            .put("hydrate", hydrate)
        if (recordType != null) body.put("record_type", recordType)
        val data = post("/api/v1/transcript/search", body) ?: return emptyList()
        val results = data.optJSONArray("results") ?: return emptyList()
        val hits = (0 until results.length()).map { i ->
            val r = results.getJSONObject(i)
            val idsArr = r.optJSONArray("eventIds") ?: JSONArray()
            SearchHit(
                recordId = r.optString("recordId"),
                recordType = r.optString("recordType"),
                sourceTable = r.optString("sourceTable"),
                score = r.optDouble("score", 0.0),
                tsStart = if (r.isNull("tsStart")) null else r.optDouble("tsStart"),
                tsEnd = if (r.isNull("tsEnd")) null else r.optDouble("tsEnd"),
                eventIds = (0 until idsArr.length()).map { idsArr.getString(it) },
                events = r.optJSONArray("events") ?: JSONArray(),
            )
        }
        if (context != null && hits.isNotEmpty()) decryptHits(hits, context)
        return hits
    }

    /**
     * Decrypts each hit's `events` in place — `textEnc` becomes plaintext `text`.
     * The device's private key never leaves the Keystore. Throws
     * [EventCrypto.NotProvisioned] if the device has no key yet. No-op for
     * non-encrypted users (events have no `textEnc`).
     */
    fun decryptHits(hits: List<SearchHit>, context: Context) {
        val box = EventCrypto.box(context)
        hits.forEach { EventCrypto.decryptEvents(it.events, box) }
    }

    // ---- speaker enrollment ----

    data class SpeakerProfile(
        val id: String,
        val displayName: String,
        val sampleCount: Int,
        val model: String,
    )

    /** Thrown on 409 when a speaker with the same name exists — carries the
     *  existing profiles so the UI can offer "add a sample to ⟨X⟩". */
    class DuplicateSpeakerException(val profiles: List<SpeakerProfile>) :
        Exception("A speaker with that name already exists.")

    private fun parseProfile(o: JSONObject?): SpeakerProfile? {
        if (o == null) return null
        val id = o.optString("id")
        if (id.isEmpty()) return null
        return SpeakerProfile(
            id = id,
            displayName = o.optString("displayName", o.optString("display_name")),
            sampleCount = o.optInt("sampleCount", o.optInt("sample_count", 0)),
            model = o.optString("model"),
        )
    }

    private fun parseProfiles(arr: JSONArray?): List<SpeakerProfile> =
        if (arr == null) emptyList()
        else (0 until arr.length()).mapNotNull { parseProfile(arr.optJSONObject(it)) }

    /** Existing speaker profiles (for the dedup pre-check + picker). */
    suspend fun listSpeakers(): List<SpeakerProfile> {
        val data = request("/api/v1/speaker-profiles", method = "GET")
        return parseProfiles(data?.optJSONArray("items"))
    }

    /**
     * Enroll a speaker from a recorded clip (WAV/M4A bytes). New profile when
     * [profileId] is null; otherwise adds a sample to that profile. Throws
     * [DuplicateSpeakerException] on a name clash (new-profile case).
     */
    suspend fun enrollSpeaker(
        displayName: String,
        audio: ByteArray,
        profileId: String? = null,
        durationSecs: Double? = null,
        isOrgUser: Boolean = false,
    ): SpeakerProfile = withContext(Dispatchers.IO) {
        val token = VuedAuth.currentAccessToken() ?: throw ApiException("not signed in")
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("display_name", displayName)
            .addFormDataPart("is_org_user", isOrgUser.toString())
            .addFormDataPart("audio", "enroll.wav", audio.toRequestBody("audio/wav".toMediaType()))
        if (profileId != null) body.addFormDataPart("profile_id", profileId)
        if (durationSecs != null) body.addFormDataPart("duration_secs", durationSecs.toString())
        val request = Request.Builder()
            .url("${VuedConfig.API_BASE_URL}/api/v1/speaker-profiles/enroll")
            .header("Authorization", "Bearer $token")
            .post(body.build())
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val envelope = if (text.isNotBlank()) JSONObject(text) else JSONObject()
            val status = envelope.optInt("status", response.code)
            if (status == 409) {
                val err = envelope.optJSONObject("error")
                throw DuplicateSpeakerException(parseProfiles(err?.optJSONArray("profiles")))
            }
            if (status !in 200..299) {
                throw ApiException(envelope.optString("message", "HTTP $status"))
            }
            parseProfile(envelope.optJSONObject("data")?.optJSONObject("profile"))
                ?: throw ApiException("enroll returned no profile")
        }
    }

    // ---- device-orchestrated STT (device transcribed + sealed; server stores ciphertext) ----

    /** Upload device-sealed transcript events (text_enc only; server never reads them).
     *  [sliceId] correlates the events to the uploaded audio so the server can run
     *  speaker-ID on that slice's audio by time window. */
    suspend fun postSealedEvents(events: JSONArray, sliceId: String) {
        post(
            "/api/v1/transcript/events/sealed",
            JSONObject().put("slice_id", sliceId).put("events", events),
        )
    }

    private suspend fun post(path: String, body: JSONObject): JSONObject? =
        request(path, method = "POST", body = body)

    /** Returns the envelope's `data` object, or null when data is null/absent. */
    private suspend fun request(
        path: String,
        method: String,
        body: JSONObject? = null,
    ): JSONObject? = withContext(Dispatchers.IO) {
        val token = VuedAuth.currentAccessToken() ?: throw ApiException("not signed in")
        val builder = Request.Builder()
            .url(VuedConfig.API_BASE_URL + path)
            .header("Authorization", "Bearer $token")
        when (method) {
            "POST" -> builder.post((body ?: JSONObject()).toString().toRequestBody(jsonMedia))
            "PATCH" -> builder.patch((body ?: JSONObject()).toString().toRequestBody(jsonMedia))
            else -> builder.get()
        }
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val envelope = if (text.isNotBlank()) JSONObject(text) else JSONObject()
            val status = envelope.optInt("status", response.code)
            if (status !in 200..299) {
                throw ApiException(envelope.optString("message", "HTTP $status"))
            }
            envelope.optJSONObject("data")
        }
    }
}
