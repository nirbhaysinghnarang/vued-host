package com.nsn8.vued.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nsn8.vued.DiagnosticsLogger

class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Log.i(TAG, "my_package_replaced")
        DiagnosticsLogger.info("self_update_my_package_replaced")
        runCatching {
            UpdateRelauncher.launch(context, reason = "my_package_replaced")
        }.onFailure { error ->
            Log.e(TAG, "my_package_replaced_relaunch_failed message=${error.message}", error)
            DiagnosticsLogger.error("self_update_relaunch_failed", mapOf("reason" to "my_package_replaced"), error, sentry = false)
        }
    }

    private companion object {
        private const val TAG = "VuedPackageReplaced"
    }
}
