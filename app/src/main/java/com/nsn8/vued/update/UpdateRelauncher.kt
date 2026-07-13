package com.nsn8.vued.update

import android.content.Context
import android.content.Intent
import android.util.Log
import com.nsn8.vued.DiagnosticsLogger
import com.nsn8.vued.MainActivity

object UpdateRelauncher {
    private const val TAG = "VuedUpdateRelaunch"

    fun launch(context: Context, reason: String) {
        Log.i(TAG, "launching_main_activity reason=$reason")
        DiagnosticsLogger.info("self_update_relaunch", mapOf("reason" to reason))
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        context.startActivity(launchIntent)
    }
}
