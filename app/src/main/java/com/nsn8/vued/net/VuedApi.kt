package com.nsn8.vued.net

import com.nsn8.vued.VuedConfig
import com.nsn8.vued.auth.VuedAuth
import com.nsn8.vued.crypto.WrappedKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
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

    /** Registers the audio-slice metadata row (status -> pending). */
    suspend fun createSlice(
        sliceId: String,
        sessionId: String,
        meetingId: String,
        startedAtSec: Double,
        endedAtSec: Double,
        durationSecs: Double,
        sizeBytes: Long,
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
                .put("audioSizeBytes", sizeBytes),
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
                .put("audioSizeBytes", sizeBytes),
        )
    }

    /** Ships the raw M4A bytes — this kicks the server's transcribe+encrypt pipeline. */
    suspend fun uploadSliceAudio(
        sliceId: String,
        audio: ByteArray,
        durationSecs: Double,
        sizeBytes: Long,
    ) = withContext(Dispatchers.IO) {
        val token = VuedAuth.currentAccessToken() ?: throw ApiException("not signed in")
        val request = Request.Builder()
            .url("${VuedConfig.API_BASE_URL}/api/v1/transcript/audio-slices/$sliceId/audio")
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
