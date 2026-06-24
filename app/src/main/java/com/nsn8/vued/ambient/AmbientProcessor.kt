package com.nsn8.vued.ambient

import android.content.Context
import android.util.Log
import com.nsn8.vued.crypto.AmbientDecryptor
import com.nsn8.vued.net.RoomConfig
import com.nsn8.vued.net.VuedApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Narrow tablet decrypt assist for ambient segmentation only. */
object AmbientProcessor {
    private const val TAG = "VuedAmbientProcessor"

    suspend fun processOnce(context: Context): String = withContext(Dispatchers.IO) {
        val roomId = RoomConfig.roomId(context) ?: return@withContext "ambient decrypt skipped (no room)"
        if (!AmbientDecryptor.isUnlocked(context)) {
            return@withContext "ambient decrypt skipped (locked)"
        }
        val window = VuedApi.ambientProcessWindow(roomId) ?: return@withContext "ambient decrypt skipped"
        if (!window.optBoolean("hasWork", false)) return@withContext "ambient decrypt idle"
        val fromTs = window.getDouble("from")
        val toTs = window.getDouble("to")
        val limit = window.optInt("limit", 5000)
        val events = VuedApi.ambientEvents(roomId, fromTs, toTs, limit)
        val decrypted = JSONArray()
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            val text = AmbientDecryptor.decryptEventText(context, event) ?: continue
            event.put("text", text)
            decryptSegments(context, event)
            decrypted.put(event)
        }
        if (decrypted.length() == 0) return@withContext "ambient decrypt found no readable events"
        val result = VuedApi.processAmbient(roomId, decrypted)
        val status = result?.optString("status", "processed") ?: "processed"
        Log.i(TAG, "processed ${decrypted.length()} ambient events: $status")
        "ambient decrypt processed ${decrypted.length()} events"
    }

    private fun decryptSegments(context: Context, event: JSONObject) {
        val segments = event.optJSONArray("segments") ?: return
        val aadId = event.getString("id")
        for (i in 0 until segments.length()) {
            val segment = segments.optJSONObject(i) ?: continue
            val sealed = segment.optString("text", "")
            if (sealed.isBlank()) continue
            AmbientDecryptor.decryptSealedText(context, aadId, sealed)?.let {
                segment.put("text", it)
            }
        }
    }
}
