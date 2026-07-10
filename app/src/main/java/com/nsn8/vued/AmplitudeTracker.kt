package com.nsn8.vued

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.nsn8.vued.auth.VuedAuth
import com.nsn8.vued.net.RoomConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

object AmplitudeTracker {
    private const val TAG = "VuedAmplitude"
    private const val ENDPOINT = "https://api2.amplitude.com/2/httpapi"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient()

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        track("app_started")
    }

    fun track(event: String, properties: Map<String, Any?> = emptyMap()) {
        val context = appContext ?: return
        val apiKey = VuedConfig.AMPLITUDE_API_KEY.trim()
        if (apiKey.isEmpty()) return

        scope.launch {
            runCatching {
                val payload = JSONObject()
                    .put("api_key", apiKey)
                    .put(
                        "events",
                        JSONArray().put(
                            JSONObject()
                                .put("event_type", event)
                                .put("user_id", amplitudeUserId(context) ?: JSONObject.NULL)
                                .put("device_id", deviceId(context))
                                .put("time", System.currentTimeMillis())
                                .put("event_properties", JSONObject(commonProperties(context) + properties)),
                        ),
                    )
                val request = Request.Builder()
                    .url(ENDPOINT)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "track_failed event=$event http=${response.code}")
                    }
                }
            }.onFailure { error ->
                Log.w(TAG, "track_failed event=$event message=${error.message}")
            }
        }
    }

    fun trackDiagnosticsEvent(event: String, properties: Map<String, Any?>) {
        if (event.startsWith("self_update_")) {
            track(event, properties)
        }
    }

    private fun commonProperties(context: Context): Map<String, Any?> = mapOf(
        "versionName" to BuildConfig.VERSION_NAME,
        "versionCode" to BuildConfig.VERSION_CODE,
        "sdk" to Build.VERSION.SDK_INT,
        "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "email" to VuedAuth.currentEmail(),
        "roomName" to RoomConfig.roomName(context),
        "roomId" to RoomConfig.roomId(context),
        "microphoneId" to RoomConfig.microphoneId(context),
    )

    private fun amplitudeUserId(context: Context): String? {
        val email = VuedAuth.currentEmail()?.trim()?.takeIf { it.isNotBlank() }
        val roomName = RoomConfig.roomName(context)?.trim()?.takeIf { it.isNotBlank() }
        if (email == null || roomName == null) return null
        return "${normalizeIdentityPart(email)}_${normalizeIdentityPart(roomName)}"
    }

    private fun normalizeIdentityPart(value: String): String =
        value.trim().replace(Regex("\\s+"), "-")

    private fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "android-${Build.MANUFACTURER}-${Build.MODEL}"
}
