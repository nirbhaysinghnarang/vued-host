package com.nsn8.vued

import android.app.Application
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid

/**
 * Process entry point. The tablet is now an upload/provisioning appliance.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticsLogger.init(this)
        initSentry()
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
            options.isDebug = BuildConfig.SENTRY_TEST_ERROR
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
            "testError" to BuildConfig.SENTRY_TEST_ERROR,
        ))
        if (BuildConfig.SENTRY_TEST_ERROR) {
            val eventId = Sentry.captureException(RuntimeException("vued sentry test error: vued-host"))
            DiagnosticsLogger.info("sentry_test_error_captured", mapOf("eventId" to eventId.toString()))
            Sentry.flush(5_000)
        }
    }
}
