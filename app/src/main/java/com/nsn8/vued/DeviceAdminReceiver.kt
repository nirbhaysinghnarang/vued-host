package com.nsn8.vued

import android.content.Context
import android.content.Intent
import android.util.Log

// Named `DeviceAdminReceiver` so the device-owner component string is the simple
// `com.nsn8.vued/.DeviceAdminReceiver`. Extends the framework receiver of the same
// name via its fully-qualified path to avoid an import clash.
class DeviceAdminReceiver : android.app.admin.DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i("VuedAdmin", "Device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i("VuedAdmin", "Device admin disabled")
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        Log.i("VuedAdmin", "Lock task mode entering: $pkg")
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        Log.i("VuedAdmin", "Lock task mode exiting")
    }
}
