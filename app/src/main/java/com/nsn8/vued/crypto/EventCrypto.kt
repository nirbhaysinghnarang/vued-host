package com.nsn8.vued.crypto

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared decryption for the operator's at-rest ciphertext. The device holds the
 * only private key, so it's the only thing that can turn `*Enc` fields back into
 * plaintext. Used by both the LAN decrypt gateway and in-app reads (e.g. semantic
 * search results).
 *
 * AAD convention (must match how the server sealed): each field is bound to its
 * owning record's wire `id` — meeting fields → the meeting id (no-dash hex),
 * event `text`/segments → the event id (dashed uuid). For non-encrypted users
 * the `*Enc` fields are simply absent and plaintext passes straight through.
 */
object EventCrypto {

    /** Thrown when the device hasn't provisioned a key, so it cannot decrypt. */
    class NotProvisioned : Exception()

    /** The device's decrypt key, or [NotProvisioned] if the user hasn't provisioned. */
    fun box(context: Context): HybridCryptoBox =
        try {
            VuedCrypto.box(context)
        } catch (e: IllegalStateException) {
            throw NotProvisioned()
        }

    /** Meeting fields are sealed with AAD = the meeting's own id (no-dash hex). */
    fun decryptMeeting(m: JSONObject, box: HybridCryptoBox) {
        val aad = m.optString("id")
        unseal(m, "titleEnc", "title", aad, box)
        unseal(m, "transcriptEnc", "transcript", aad, box)
        unseal(m, "notesMdEnc", "notesMd", aad, box)
    }

    /** Decrypt every event in [events] in place (drops `textEnc`). */
    fun decryptEvents(events: JSONArray, box: HybridCryptoBox) {
        for (i in 0 until events.length()) {
            val e = events.optJSONObject(i) ?: continue
            decryptEvent(e, box)
        }
    }

    /** Event text + each segment text are sealed with AAD = the event's own id. */
    fun decryptEvent(e: JSONObject, box: HybridCryptoBox) {
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
    fun unseal(o: JSONObject, encKey: String, plainKey: String, aad: String, box: HybridCryptoBox) {
        val sealed = o.optString(encKey, "")
        if (sealed.isNotEmpty() && sealed != "null") {
            o.put(plainKey, open(sealed, aad, box))
        }
        o.remove(encKey)
    }

    fun open(b64: String, aad: String, box: HybridCryptoBox): String =
        box.openString(Base64.decode(b64, Base64.DEFAULT), aad.toByteArray(Charsets.US_ASCII))
}
