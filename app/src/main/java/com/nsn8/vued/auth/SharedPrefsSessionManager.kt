package com.nsn8.vued.auth

import android.content.Context
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists the Supabase session (JWT + refresh token) to app-private storage so
 * login survives restarts. Plain SharedPreferences for now; harden to Android
 * Keystore / EncryptedSharedPreferences before shipping managed deployments.
 */
class SharedPrefsSessionManager(context: Context) : SessionManager {

    private val prefs = context.applicationContext
        .getSharedPreferences("vued_auth", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun saveSession(session: UserSession) {
        prefs.edit().putString(KEY, json.encodeToString(session)).apply()
    }

    override suspend fun loadSession(): UserSession? {
        val raw = prefs.getString(KEY, null) ?: return null
        return runCatching { json.decodeFromString<UserSession>(raw) }.getOrNull()
    }

    override suspend fun deleteSession() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "session"
    }
}
