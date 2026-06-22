package com.nsn8.vued.net

import android.content.Context
import android.util.Base64
import android.util.Log
import com.nsn8.vued.crypto.EventCrypto
import com.nsn8.vued.crypto.HybridCryptoBox
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
 *   /search?q=&limit=&type=       semantic search; matched events (text decrypted)
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
        // CORS preflight: browsers send OPTIONS before a cross-origin fetch().
        // Answer it (incl. the Private-Network-Access bit Chrome wants for
        // public/secure-page -> private-IP requests) and stop.
        if (session.method == Method.OPTIONS) {
            return cors(newFixedLengthResponse(Response.Status.OK, "text/plain", ""))
        }
        val uri = session.uri.trimEnd('/').ifEmpty { "/" }
        return try {
            when {
                uri == "/" || uri == "/health" ->
                    ok(JSONObject().put("ok", true).put("service", "vued-decrypt-gateway"))
                uri == "/decrypt" && session.method == Method.POST -> ok(decryptBatch(session))
                uri == "/meetings" -> ok(meetingsList())
                MEETING_EVENTS.matches(uri) ->
                    ok(meetingEvents(MEETING_EVENTS.find(uri)!!.groupValues[1]))
                MEETING.matches(uri) -> ok(meetingDetail(MEETING.find(uri)!!.groupValues[1]))
                uri == "/ambient/events" -> ok(ambientEvents(session))
                uri == "/search" -> ok(search(session))
                else -> err(Response.Status.NOT_FOUND, "no such route: $uri")
            }
        } catch (e: IllegalArgumentException) {
            err(Response.Status.BAD_REQUEST, e.message ?: "bad request")
        } catch (e: EventCrypto.NotProvisioned) {
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

    /**
     * Semantic search. Matches on the server's embeddings-only index (no text
     * leaves the DB), then decrypts the hydrated events locally. Params:
     * `q` (required), `limit` (default 10), `type` ("meeting"|"idea", optional).
     */
    private fun search(session: IHTTPSession): JSONObject {
        val box = box()
        val q = (session.parameters["q"]?.firstOrNull()
            ?: session.parameters["query"]?.firstOrNull() ?: "").trim()
        require(q.isNotEmpty()) { "q is required" }
        val limit = session.parameters["limit"]?.firstOrNull()?.toIntOrNull() ?: 10
        val recordType = (session.parameters["type"]?.firstOrNull()
            ?: session.parameters["record_type"]?.firstOrNull())?.trim()?.ifEmpty { null }
        val hits = runBlocking {
            VuedApi.searchTranscripts(q, limit = limit, recordType = recordType, hydrate = true)
        }
        val items = JSONArray()
        for (h in hits) {
            EventCrypto.decryptEvents(h.events, box)
            items.put(
                JSONObject()
                    .put("recordId", h.recordId)
                    .put("recordType", h.recordType)
                    .put("sourceTable", h.sourceTable)
                    .put("score", h.score)
                    .put("tsStart", h.tsStart ?: JSONObject.NULL)
                    .put("tsEnd", h.tsEnd ?: JSONObject.NULL)
                    .put("eventIds", JSONArray(h.eventIds))
                    .put("events", h.events),
            )
        }
        return JSONObject().put("items", items)
    }

    /** Sort transcript events by start time, most recent first. */
    private fun newestFirst(events: JSONArray): JSONArray {
        val sorted = (0 until events.length())
            .map { events.getJSONObject(it) }
            .sortedByDescending { it.optDouble("tsStart", 0.0) }
        return JSONArray(sorted)
    }

    /**
     * Generic decrypt oracle: the browser collects `*Enc` ciphertext from any
     * backend payload and posts it here with each blob's AAD (the owning record's
     * wire id). We decrypt with the Keystore key and return plaintext, keyed by the
     * caller-chosen `id`. Stateless — no per-view route needed for new encrypted
     * fields. Per-item failures map to null so one bad blob can't fail the batch.
     *
     *   POST /decrypt  { "items": [ { "id", "ciphertext"(b64), "aad" }, ... ] }
     *   -> { "results": { "<id>": "<plaintext>" | null } }
     */
    private fun decryptBatch(session: IHTTPSession): JSONObject {
        val box = box() // throws NotProvisioned -> 503, handled in serve()
        val body = readBody(session)
        val items = (if (body.isNotBlank()) JSONObject(body) else JSONObject())
            .optJSONArray("items") ?: JSONArray()
        val results = JSONObject()
        // Time only the on-device decryption (Keystore + HPKE), so the caller can
        // split this from the LAN+TLS round-trip it measures on its end.
        val startNs = System.nanoTime()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isEmpty()) continue
            val ciphertext = item.optString("ciphertext", "")
            val aad = item.optString("aad", "")
            val plain: Any = try {
                if (ciphertext.isEmpty()) JSONObject.NULL
                else box.openString(Base64.decode(ciphertext, Base64.DEFAULT), aad.toByteArray(Charsets.US_ASCII))
            } catch (e: Exception) {
                Log.w(TAG, "decrypt item $id failed: ${e.message}")
                JSONObject.NULL
            }
            results.put(id, plain)
        }
        val decryptMs = (System.nanoTime() - startNs) / 1_000_000
        Log.i(TAG, "decrypt batch n=${items.length()} took ${decryptMs}ms")
        return JSONObject().put("results", results).put("serverMs", decryptMs)
    }

    /** Reads the raw request body (NanoHTTPD stashes it under "postData"). */
    private fun readBody(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        return try {
            session.parseBody(files)
            files["postData"] ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "parseBody failed: ${e.message}")
            ""
        }
    }

    // ---- decryption (delegated to the shared EventCrypto helper) ----

    private fun decryptMeeting(m: JSONObject, box: HybridCryptoBox) =
        EventCrypto.decryptMeeting(m, box)

    private fun decryptEvent(e: JSONObject, box: HybridCryptoBox) =
        EventCrypto.decryptEvent(e, box)

    /** The device's decrypt key, or [EventCrypto.NotProvisioned] if not provisioned. */
    private fun box(): HybridCryptoBox = EventCrypto.box(appContext)

    // ---- responses ----

    private fun ok(body: JSONObject): Response =
        cors(newFixedLengthResponse(Response.Status.OK, MIME_JSON, body.toString()))

    private fun err(status: Response.Status, message: String): Response =
        cors(newFixedLengthResponse(status, MIME_JSON, JSONObject().put("error", message).toString()))

    /**
     * Attach CORS + Private-Network-Access headers so a browser `fetch()` can
     * read this gateway. NOTE: `*` lets ANY web page the user visits read their
     * decrypted data (the gateway is unauthenticated). Fine for a local test;
     * before shipping, lock [ALLOWED_ORIGIN] to the app origin AND add a pairing
     * token — see the security note in the chat.
     */
    private fun cors(resp: Response): Response = resp.apply {
        addHeader("Access-Control-Allow-Origin", ALLOWED_ORIGIN)
        addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
        addHeader("Access-Control-Allow-Private-Network", "true")
        addHeader("Access-Control-Max-Age", "600")
    }

    companion object {
        private const val TAG = "VuedLanDecryptServer"
        private const val MIME_JSON = "application/json"
        // "*" for local testing only. Lock to the app origin before shipping.
        private const val ALLOWED_ORIGIN = "*"
        private val MEETING = Regex("^/meetings/([^/]+)$")
        private val MEETING_EVENTS = Regex("^/meetings/([^/]+)/events$")
    }
}
