package com.nsn8.vued.net

import com.nsn8.vued.VuedConfig
import com.nsn8.vued.auth.VuedAuth
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
 * `{status, message, data, error}` envelope.
 */
object VuedApi {

    private val client = OkHttpClient()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    class ApiException(message: String) : Exception(message)

    // ---- meeting recording (timeline-slice path) ----

    /** Creates the meeting row (legacy `meetings`). [meetingId] is no-dash hex. */
    suspend fun createMeeting(
        meetingId: String,
        title: String,
        startedAtSec: Double,
        roomId: String? = null,
        microphoneId: String? = null,
    ) {
        post(
            "/api/v1/meetings",
            JSONObject()
                .put("id", meetingId)
                .put("title", title)
                .put("status", "recording")
                .put("started_at", startedAtSec)
                .putOpt("roomId", roomId)
                .putOpt("microphoneId", microphoneId),
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

    /** Ships raw M4A bytes to the server transcription/finalization pipeline. */
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
