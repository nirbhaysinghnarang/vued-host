package com.nsn8.vued.net

import android.content.Context
import android.util.Base64
import android.util.Log
import com.nsn8.vued.crypto.HybridCryptoBox
import com.nsn8.vued.crypto.VuedCrypto
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * LAN-facing decrypt gateway. The device is the only holder of the private key,
 * so it's the only thing that can turn the operator's at-rest ciphertext back
 * into plaintext.
 *
 * Request path for every read:
 *
 *   LAN caller ──> this server ──> VuedApi (app → vued-api → supabase → back)
 *                       │
 *                       └─ decrypts the `*Enc` fields locally (Keystore key)
 *                          and returns plaintext JSON.
 *
 * The device never touches Supabase directly — it always goes through vued-api
 * (RLS-scoped by the logged-in user's JWT). Nothing here is authenticated:
 * v1 is open on the LAN by design. Anyone who can reach the port gets plaintext.
 *
 * Routes (all GET):
 *   /health                       liveness, no decryption
 *   /meetings                     meeting list (titles decrypted)
 *   /meetings/{id}                one meeting (title + notes + transcript) + its events
 *   /meetings/{id}/events         per-utterance transcript events (text decrypted)
 *   /ambient/events?from=&to=     ambient transcript events (text decrypted)
 *
 * AAD convention (must match how the server sealed): each field is bound to its
 * owning record's wire `id` — meeting fields → the meeting id (no-dash hex),
 * event `text`/segments → the event id (dashed uuid). For non-encrypted users
 * the `*Enc` fields are simply absent and the plaintext passes straight through.
 */
class LanDecryptServer(
    private val appContext: Context,
    port: Int = 0, // 0 = ephemeral; read listeningPort after start() and advertise it
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/').ifEmpty { "/" }
        return try {
            when {
                uri == "/" || uri == "/health" ->
                    ok(JSONObject().put("ok", true).put("service", "vued-decrypt-gateway"))
                uri == "/meetings" -> ok(meetingsList())
                MEETING_EVENTS.matches(uri) ->
                    ok(meetingEvents(MEETING_EVENTS.find(uri)!!.groupValues[1]))
                MEETING.matches(uri) -> ok(meetingDetail(MEETING.find(uri)!!.groupValues[1]))
                uri == "/ambient/events" -> ok(ambientEvents(session))
                else -> err(Response.Status.NOT_FOUND, "no such route: $uri")
            }
        } catch (e: NotProvisioned) {
            err(Response.Status.SERVICE_UNAVAILABLE, "device not provisioned; cannot decrypt")
        } catch (e: Exception) {
            Log.e(TAG, "serve $uri failed: ${e.message}", e)
            err(Response.Status.INTERNAL_ERROR, e.message ?: e.javaClass.simpleName)
        }
    }

    // ---- handlers ----

    private fun meetingsList(): JSONObject {
        val box = box()
        val items = runBlocking { VuedApi.listMeetings() }
        for (i in 0 until items.length()) decryptMeeting(items.getJSONObject(i), box)
        return JSONObject().put("items", items)
    }

    private fun meetingDetail(id: String): JSONObject {
        val box = box()
        val meeting = runBlocking { VuedApi.getMeeting(id) }
            ?: throw RuntimeException("meeting not found: $id")
        decryptMeeting(meeting, box)
        val events = runBlocking { VuedApi.meetingEvents(id) }
        for (i in 0 until events.length()) decryptEvent(events.getJSONObject(i), box)
        return meeting.put("events", events)
    }

    private fun meetingEvents(id: String): JSONObject {
        val box = box()
        val events = runBlocking { VuedApi.meetingEvents(id) }
        for (i in 0 until events.length()) decryptEvent(events.getJSONObject(i), box)
        return JSONObject().put("items", events)
    }

    private fun ambientEvents(session: IHTTPSession): JSONObject {
        val box = box()
        val from = session.parameters["from"]?.firstOrNull()?.toDoubleOrNull()
        val to = session.parameters["to"]?.firstOrNull()?.toDoubleOrNull()
        val events = runBlocking { VuedApi.ambientEvents(from, to) }
        for (i in 0 until events.length()) decryptEvent(events.getJSONObject(i), box)
        return JSONObject().put("items", newestFirst(events))
    }

    /** Sort transcript events by start time, most recent first. */
    private fun newestFirst(events: JSONArray): JSONArray {
        val sorted = (0 until events.length())
            .map { events.getJSONObject(it) }
            .sortedByDescending { it.optDouble("tsStart", 0.0) }
        return JSONArray(sorted)
    }

    // ---- decryption ----

    /** Meeting fields are sealed with AAD = the meeting's own id (no-dash hex). */
    private fun decryptMeeting(m: JSONObject, box: HybridCryptoBox) {
        val aad = m.optString("id")
        unseal(m, "titleEnc", "title", aad, box)
        unseal(m, "transcriptEnc", "transcript", aad, box)
        unseal(m, "notesMdEnc", "notesMd", aad, box)
    }

    /** Event text + each segment text are sealed with AAD = the event's own id. */
    private fun decryptEvent(e: JSONObject, box: HybridCryptoBox) {
        val aad = e.optString("id")
        val sealed = e.optString("textEnc", "")
        if (sealed.isNotEmpty() && sealed != "null") {
            e.put("text", open(sealed, aad, box))
            val segments = e.optJSONArray("segments")
            if (segments != null) {
                for (i in 0 until segments.length()) {
                    val seg = segments.optJSONObject(i) ?: continue
                    val segText = seg.optString("text", "")
                    if (segText.isNotEmpty() && segText != "null") {
                        seg.put("text", open(segText, aad, box))
                    }
                }
            }
        }
        e.remove("textEnc")
    }

    /** Replace `plainKey` with the decryption of `encKey` (if present), drop `encKey`. */
    private fun unseal(o: JSONObject, encKey: String, plainKey: String, aad: String, box: HybridCryptoBox) {
        val sealed = o.optString(encKey, "")
        if (sealed.isNotEmpty() && sealed != "null") {
            o.put(plainKey, open(sealed, aad, box))
        }
        o.remove(encKey)
    }

    private fun open(b64: String, aad: String, box: HybridCryptoBox): String =
        box.openString(Base64.decode(b64, Base64.DEFAULT), aad.toByteArray(Charsets.US_ASCII))

    /** The device's decrypt key, or [NotProvisioned] if the user hasn't provisioned. */
    private fun box(): HybridCryptoBox =
        try {
            VuedCrypto.box(appContext)
        } catch (e: IllegalStateException) {
            throw NotProvisioned()
        }

    private class NotProvisioned : Exception()

    // ---- responses ----

    private fun ok(body: JSONObject): Response =
        newFixedLengthResponse(Response.Status.OK, MIME_JSON, body.toString())

    private fun err(status: Response.Status, message: String): Response =
        newFixedLengthResponse(status, MIME_JSON, JSONObject().put("error", message).toString())

    companion object {
        private const val TAG = "VuedLanDecryptServer"
        private const val MIME_JSON = "application/json"
        private val MEETING = Regex("^/meetings/([^/]+)$")
        private val MEETING_EVENTS = Regex("^/meetings/([^/]+)/events$")
    }
}
