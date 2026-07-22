package com.nsn8.vued.net

import com.nsn8.vued.VuedConfig
import com.nsn8.vued.auth.VuedAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Client for the org-management API (aircaps-api-mcp). Separate host from
 * [VuedApi] (the recording backend), same Supabase JWT + `{status,data,...}`
 * envelope. Used by the room picker to list the org's rooms.
 */
object OrgApi {

    private val client = OkHttpClient()

    data class Org(val id: String, val name: String, val role: String)
    data class Room(
        val id: String,
        val microphoneId: String,
        val displayName: String,
        val status: String? = null,
        val statusUpdatedAt: Double? = null,
    )
    data class Member(val userId: String, val email: String, val displayName: String, val role: String)

    /** Orgs this account belongs to (a tablet account typically has one). */
    suspend fun getOrgs(): List<Org> {
        val arr = getArray("/api/v1/orgs")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Org(o.optString("id"), o.optString("name"), o.optString("role"))
        }
    }

    /** Rooms configured for [orgId] (fields are snake_case from the org API). */
    suspend fun getRooms(orgId: String): List<Room> {
        val arr = getArray("/api/v1/orgs/$orgId/rooms")
        return (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            val microphoneId = r.optString("microphone_id")
            Room(
                id = r.optString("id"),
                microphoneId = microphoneId,
                displayName = r.optRoomName(microphoneId),
                status = r.optNullableString("status"),
                statusUpdatedAt = r.optFiniteDouble("status_updated_at"),
            )
        }
    }

    /** Create a room for [orgId]. The org API accepts camelCase fields. */
    suspend fun createRoom(orgId: String, displayName: String, microphoneId: String): Room {
        val r = postJson(
            "/api/v1/orgs/$orgId/rooms",
            JSONObject()
                .put("displayName", displayName)
                .put("microphoneId", microphoneId),
        )
        val returnedMicrophoneId = r.optString("microphone_id")
            .ifBlank { r.optString("microphoneId") }
            .ifBlank { microphoneId }
        return Room(
            id = r.optString("id"),
            microphoneId = returnedMicrophoneId,
            displayName = r.optRoomName(returnedMicrophoneId),
        )
    }

    /** Org members (snake_case from the org API) — for the "existing user" picker. */
    suspend fun getMembers(orgId: String): List<Member> {
        val arr = getArray("/api/v1/orgs/$orgId/members")
        return (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i)
            Member(
                userId = m.optString("user_id"),
                email = m.optString("email"),
                displayName = m.optString("display_name"),
                role = m.optString("role"),
            )
        }
    }

    /** Link an enrolled speaker profile to an org member (so the voiceprint maps
     *  to a real user). Omit for a temp/name-only speaker. */
    suspend fun linkSpeakerProfile(orgId: String, speakerProfileId: String, userId: String) {
        putJson(
            "/api/v1/orgs/$orgId/speaker-profile-links/$speakerProfileId",
            JSONObject().put("userId", userId),
        )
    }

    /** Updates the assigned room's current microphone state. The backend stamps
     *  `status_updated_at` using server time, so the tablet sends only the state. */
    suspend fun updateRoomMicStatus(orgId: String, roomId: String, status: String) {
        putJson(
            "/api/v1/orgs/$orgId/rooms/$roomId/mic-status",
            JSONObject().put("status", status),
        )
    }

    /** Ask the API to transiently broadcast a command to the assigned tablet. */
    suspend fun sendRoomMicCommand(orgId: String, roomId: String, command: String) {
        postJson(
            "/api/v1/orgs/$orgId/rooms/$roomId/mic-command",
            JSONObject().put("command", command),
        )
    }

    private suspend fun postJson(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val token = VuedAuth.currentAccessTokenReady() ?: throw VuedApi.ApiException("not signed in")
        val request = Request.Builder()
            .url(VuedConfig.ORG_API_BASE_URL + path)
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val envelope = if (text.isNotBlank()) JSONObject(text) else JSONObject()
            val status = envelope.optInt("status", response.code)
            if (status !in 200..299) {
                throw VuedApi.ApiException(envelope.optString("message", "HTTP $status"))
            }
            envelope.optJSONObject("data") ?: JSONObject()
        }
    }

    private suspend fun putJson(path: String, body: JSONObject) = withContext(Dispatchers.IO) {
        val token = VuedAuth.currentAccessTokenReady() ?: throw VuedApi.ApiException("not signed in")
        val request = Request.Builder()
            .url(VuedConfig.ORG_API_BASE_URL + path)
            .header("Authorization", "Bearer $token")
            .put(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val envelope = if (text.isNotBlank()) JSONObject(text) else JSONObject()
            val status = envelope.optInt("status", response.code)
            if (status !in 200..299) {
                throw VuedApi.ApiException(envelope.optString("message", "HTTP $status"))
            }
        }
    }

    private suspend fun getArray(path: String): JSONArray = withContext(Dispatchers.IO) {
        val token = VuedAuth.currentAccessTokenReady() ?: throw VuedApi.ApiException("not signed in")
        val request = Request.Builder()
            .url(VuedConfig.ORG_API_BASE_URL + path)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val envelope = if (text.isNotBlank()) org.json.JSONObject(text) else org.json.JSONObject()
            val status = envelope.optInt("status", response.code)
            if (status !in 200..299) {
                throw VuedApi.ApiException(envelope.optString("message", "HTTP $status"))
            }
            envelope.optJSONArray("data") ?: JSONArray()
        }
    }

    private fun JSONObject.optRoomName(microphoneId: String): String {
        listOf("room_name", "roomName", "name", "display_name", "displayName").forEach { key ->
            val value = optString(key).trim()
            if (value.isNotEmpty() && value != microphoneId) return value
        }
        return optString("display_name").trim().ifEmpty { microphoneId }
    }

    private fun JSONObject.optFiniteDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return optDouble(key).takeIf { it.isFinite() }
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key)
            .trim()
            .takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    }
}
