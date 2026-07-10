package com.nsn8.vued

import android.app.Application
import android.content.Context
import com.nsn8.vued.auth.VuedAuth
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
        bindSentryUserToAuth()
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

    private fun bindSentryUserToAuth() {
        appScope.launch {
            VuedAuth.sessionStatus.collect {
                updateSentryContext(this@App)
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
