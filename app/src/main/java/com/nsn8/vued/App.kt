package com.nsn8.vued

import android.app.Application
import android.content.Context
import com.nsn8.vued.auth.VuedAuth
import com.nsn8.vued.meeting.MeetingController
import com.nsn8.vued.net.OutboundQueue
import io.github.jan.supabase.auth.status.SessionStatus
import io.sentry.IScope
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Process entry point. The tablet is now an upload/provisioning appliance.
 */
class App : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        DiagnosticsLogger.init(this)
        VuedAuth.init(this)
        AmplitudeTracker.init(this)
        initSentry()
        runCatching { MeetingController.recoverStaleMeetings(this) }
            .onFailure { error ->
                DiagnosticsLogger.error("meeting_startup_recovery_failed", throwable = error)
            }
        bindAppStateToAuth()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            DiagnosticsLogger.fatal("uncaught_exception", mapOf("thread" to thread.name), throwable)
            Sentry.captureException(throwable)
            previousHandler?.uncaughtException(thread, throwable) ?: Runtime.getRuntime().exit(1)
        }
    }

    private fun initSentry() {
        val dsn = BuildConfig.SENTRY_DSN.trim()
        if (dsn.isEmpty()) {
            DiagnosticsLogger.info("sentry_disabled", mapOf("reason" to "empty_dsn"))
            return
        }
        SentryAndroid.init(this) { options ->
            options.dsn = dsn
            options.environment = BuildConfig.SENTRY_ENVIRONMENT
            options.release = BuildConfig.SENTRY_RELEASE
            options.isSendDefaultPii = false
            options.tracesSampleRate = 0.0
            options.setBeforeSend { event, _ ->
                event.setTag("component", "vued-host")
                event.setTag("runtime", "android-tablet")
                event
            }
        }
        DiagnosticsLogger.info("sentry_initialized", mapOf(
            "environment" to BuildConfig.SENTRY_ENVIRONMENT,
            "release" to BuildConfig.SENTRY_RELEASE,
        ))
    }

    private fun bindAppStateToAuth() {
        appScope.launch {
            VuedAuth.sessionStatus.collect { status ->
                updateSentryContext(this@App)
                if (status is SessionStatus.Authenticated) {
                    MeetingController.retryPendingExports(this@App)
                    runCatching { OutboundQueue.drain(this@App) }
                        .onFailure { error ->
                            DiagnosticsLogger.warn("auth_queue_drain_failed", throwable = error)
                        }
                }
            }
        }
    }

    companion object {
        fun updateSentryContext(context: Context) {
            val email = VuedAuth.currentEmail()?.takeIf { it.isNotBlank() }
            val userId = VuedAuth.currentUserId()?.takeIf { it.isNotBlank() }
            Sentry.configureScope { scope ->
                if (email == null && userId == null) {
                    scope.user = null
                    scope.removeTag("user.email")
                } else {
                    scope.user = User().apply {
                        id = userId
                        this.email = email
                    }
                    scope.setOrRemoveTag("user.email", email)
                }
            }
            DiagnosticsLogger.info(
                "sentry_context_updated",
                mapOf(
                    "hasEmail" to (email != null),
                    "hasUserId" to (userId != null),
                ),
            )
        }
    }
}

private fun IScope.setOrRemoveTag(key: String, value: String?) {
    if (value == null) {
        removeTag(key)
    } else {
        setTag(key, value)
    }
}
