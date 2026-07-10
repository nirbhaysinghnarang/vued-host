package com.nsn8.vued.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import com.nsn8.vued.DiagnosticsLogger

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SelfUpdateManager.ACTION_INSTALL_RESULT) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val versionName = intent.getStringExtra("versionName")
        val versionCode = intent.getLongExtra("versionCode", -1L)
        val sessionId = intent.getIntExtra("sessionId", -1)
        val extras = intent.extras?.keySet()?.sorted().orEmpty()
        val data = mapOf(
            "status" to status,
            "statusLabel" to installStatusLabel(status),
            "message" to message,
            "versionName" to versionName,
            "versionCode" to versionCode.takeIf { it > 0L },
            "sessionId" to sessionId.takeIf { it >= 0 },
            "extras" to extras,
        )
        val label = installStatusLabel(status)
        Log.i(
            TAG,
            "install_result status=$label($status) message=$message sessionId=$sessionId " +
                "versionName=$versionName versionCode=$versionCode extras=$extras",
        )
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "self_update_install_succeeded status=$label versionName=$versionName versionCode=$versionCode")
                DiagnosticsLogger.info("self_update_install_succeeded", data)
                runCatching {
                    UpdateRelauncher.launch(context, reason = "install_success")
                }.onFailure { error ->
                    Log.e(TAG, "self_update_install_success_relaunch_failed message=${error.message}", error)
                    DiagnosticsLogger.error("self_update_relaunch_failed", mapOf("reason" to "install_success"), error, sentry = false)
                }
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Log.i(TAG, "self_update_install_pending_user_action status=$label message=$message")
                DiagnosticsLogger.info("self_update_install_pending_user_action", data)
                val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)

                if (confirmation == null) {
                    Log.e(TAG, "self_update_install_missing_confirmation_intent status=$label extras=$extras")
                    DiagnosticsLogger.error("self_update_install_missing_confirmation_intent", data, sentry = false)
                    return
                }
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                Log.i(TAG, "self_update_launching_confirmation_intent action=${confirmation.action}")
                context.startActivity(confirmation)
            }
            else -> {
                Log.e(
                    TAG,
                    "self_update_install_failed status=$label($status) message=$message versionName=$versionName versionCode=$versionCode",
                )
                DiagnosticsLogger.error("self_update_install_failed", data, sentry = false)
            }
        }
    }

    private fun installStatusLabel(status: Int): String = when (status) {
        PackageInstaller.STATUS_SUCCESS -> "success"
        PackageInstaller.STATUS_PENDING_USER_ACTION -> "pending_user_action"
        PackageInstaller.STATUS_FAILURE -> "failure"
        PackageInstaller.STATUS_FAILURE_ABORTED -> "failure_aborted"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "failure_blocked"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "failure_conflict"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "failure_incompatible"
        PackageInstaller.STATUS_FAILURE_INVALID -> "failure_invalid"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "failure_storage"
        else -> "unknown"
    }

    private companion object {
        private const val TAG = "VuedUpdateInstall"
    }
}
