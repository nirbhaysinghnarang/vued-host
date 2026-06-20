package com.nsn8.vued.auth

import android.content.Context
import com.nsn8.vued.VuedConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.createSupabaseClient
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin wrapper over the Supabase Auth (GoTrue) client: email/password sign-in,
 * persisted sessions, automatic token refresh, and a [currentAccessToken] accessor
 * that the upload layer attaches as `Authorization: Bearer <jwt>`.
 *
 * The SDK auto-refreshes the 1-hour access token in the background (no hand-rolled
 * timer needed) and reloads the persisted session on init.
 */
object VuedAuth {

    @Volatile
    private var clientRef: SupabaseClient? = null

    private val client: SupabaseClient
        get() = clientRef ?: error("VuedAuth.init(context) must be called first")

    fun init(context: Context) {
        if (clientRef != null) return
        synchronized(this) {
            if (clientRef != null) return
            clientRef = createSupabaseClient(
                supabaseUrl = VuedConfig.SUPABASE_URL,
                supabaseKey = VuedConfig.SUPABASE_ANON_KEY,
            ) {
                install(Auth) {
                    sessionManager = SharedPrefsSessionManager(context)
                    alwaysAutoRefresh = true
                    autoLoadFromStorage = true
                }
            }
        }
    }

    val sessionStatus: StateFlow<SessionStatus>
        get() = client.auth.sessionStatus

    suspend fun signIn(email: String, password: String) {
        client.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    suspend fun signOut() {
        client.auth.signOut()
    }

    fun currentUserId(): String? = client.auth.currentUserOrNull()?.id

    fun currentEmail(): String? = client.auth.currentUserOrNull()?.email

    /** Current (auto-refreshed) access token, or null if not signed in. */
    fun currentAccessToken(): String? = client.auth.currentSessionOrNull()?.accessToken
}
