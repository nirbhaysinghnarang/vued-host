package com.nsn8.vued.stt

import com.nsn8.vued.net.VuedApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Device-orchestrated speech-to-text via AssemblyAI.
 *
 * The tablet uploads the audio, submits a transcription job, and **polls** for the
 * result — all directly to AssemblyAI. So the transcript plaintext never touches
 * our servers; the device is the only Vued-side party that sees it. The API key is
 * fetched from our server ([VuedApi.fetchSttKey], interim → scoped token later).
 *
 * Webhook-free by design: a tablet has no public URL and sleeps, so a callback
 * can't reach it — we poll instead. The poll fits the durable queue: a long job
 * can be resumed by polling its [transcriptId] rather than re-uploading.
 *
 * Output is the repo transcript shape — `events: [{text, tsStart, tsEnd, speakerId}]`
 * — matching the server's `normalize_async_transcript` (ms→s, A/B→speaker_N), so the
 * downstream seal/store path is identical to the server-produced transcript.
 */
object AssemblyAiStt {

    private const val BASE = "https://api.assemblyai.com/v2"
    private const val SEGMENT_GAP_S = 2.0

    private val client = OkHttpClient.Builder()
        .callTimeout(120, TimeUnit.SECONDS)
        .build()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val octetMedia = "application/octet-stream".toMediaType()

    data class Event(val text: String, val tsStart: Double, val tsEnd: Double, val speakerId: String)
    data class Transcript(val text: String, val events: List<Event>)

    class SttException(message: String) : Exception(message)

    /** Full flow: upload → submit → poll → normalize. Call from a coroutine. */
    suspend fun transcribe(audio: ByteArray, languageCode: String = "en"): Transcript =
        withContext(Dispatchers.IO) {
            val key = VuedApi.fetchSttKey()
            val uploadUrl = upload(key, audio)
            val id = submit(key, uploadUrl, languageCode)
            normalize(poll(key, id))
        }

    // ---- AssemblyAI calls (direct, not through our server) ----

    private fun upload(key: String, audio: ByteArray): String {
        val req = Request.Builder()
            .url("$BASE/upload")
            .header("authorization", key)
            .post(audio.toRequestBody(octetMedia))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw SttException("upload failed: HTTP ${resp.code}")
            return JSONObject(body).getString("upload_url")
        }
    }

    private fun submit(key: String, audioUrl: String, languageCode: String): String {
        val body = JSONObject()
            .put("audio_url", audioUrl)
            .put("speaker_labels", true)
            .put("language_code", languageCode)
        val req = Request.Builder()
            .url("$BASE/transcript")
            .header("authorization", key)
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw SttException("submit failed: HTTP ${resp.code}")
            return JSONObject(text).getString("id")
        }
    }

    /** Polls one job to completion. Public so the queue can resume a known id. */
    suspend fun poll(
        key: String,
        transcriptId: String,
        intervalMs: Long = 3_000,
        timeoutMs: Long = 600_000,
    ): JSONObject = withContext(Dispatchers.IO) {
        var waited = 0L
        while (waited < timeoutMs) {
            val payload = fetch(key, transcriptId)
            when (payload.optString("status")) {
                "completed" -> return@withContext payload
                "error" -> throw SttException("assemblyai error: ${payload.optString("error")}")
                else -> {
                    delay(intervalMs)
                    waited += intervalMs
                }
            }
        }
        throw SttException("transcription $transcriptId timed out after ${timeoutMs}ms")
    }

    private fun fetch(key: String, transcriptId: String): JSONObject {
        val req = Request.Builder()
            .url("$BASE/transcript/$transcriptId")
            .header("authorization", key)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw SttException("poll failed: HTTP ${resp.code}")
            return JSONObject(text)
        }
    }

    // ---- normalize: AssemblyAI payload → repo transcript shape ----

    fun normalize(payload: JSONObject): Transcript {
        val utterances = payload.optJSONArray("utterances")
        val raw = if (utterances != null && utterances.length() > 0) {
            (0 until utterances.length()).mapNotNull { i ->
                val u = utterances.optJSONObject(i) ?: return@mapNotNull null
                val t = u.optString("text").trim()
                if (t.isEmpty()) null
                else Event(
                    text = t,
                    tsStart = u.optDouble("start", 0.0) / 1000.0,
                    tsEnd = u.optDouble("end", 0.0) / 1000.0,
                    speakerId = speakerId(u.optString("speaker")) ?: "speaker_1",
                )
            }
        } else {
            coalesceWords(payload.optJSONArray("words") ?: JSONArray())
        }.sortedBy { it.tsStart }

        val flat = payload.optString("text").trim()
            .ifEmpty { raw.joinToString(" ") { it.text }.trim() }
        return Transcript(flat, raw)
    }

    /** AssemblyAI 'A'/'B'/… → repo 'speaker_1'/'speaker_2'/… so diarization is consistent. */
    private fun speakerId(label: String?): String? {
        val s = label?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (s.length == 1 && s[0].isLetter()) return "speaker_${s.uppercase()[0] - 'A' + 1}"
        return if (s.startsWith("speaker_")) s else "speaker_$s"
    }

    private fun coalesceWords(words: JSONArray): List<Event> {
        val out = mutableListOf<Event>()
        var cur: Event? = null
        for (i in 0 until words.length()) {
            val w = words.optJSONObject(i) ?: continue
            val t = w.optString("text").trim()
            if (t.isEmpty()) continue
            val start = w.optDouble("start", 0.0) / 1000.0
            val end = w.optDouble("end", 0.0) / 1000.0
            val spk = speakerId(w.optString("speaker"))
            val c = cur
            if (c != null && spk == c.speakerId && start - c.tsEnd <= SEGMENT_GAP_S) {
                cur = c.copy(text = "${c.text} $t".trim(), tsEnd = end)
            } else {
                if (c != null) out.add(c)
                cur = Event(t, start, end, spk ?: "speaker_1")
            }
        }
        cur?.let { out.add(it) }
        return out
    }
}
