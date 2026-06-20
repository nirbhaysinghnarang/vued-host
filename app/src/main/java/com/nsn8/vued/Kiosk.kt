package com.nsn8.vued

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

private fun deviceAdminComponent(context: Context): ComponentName =
    ComponentName(context, DeviceAdminReceiver::class.java)

fun isDeviceOwner(context: Context): Boolean {
    val dpm = context.getSystemService(DevicePolicyManager::class.java)
    return dpm.isDeviceOwnerApp(context.packageName)
}

/**
 * Enters single-app kiosk mode: whitelists this package for lock task, locks down
 * all system affordances, and pins the app to the screen. Requires device owner
 * (set via `adb shell dpm set-device-owner com.nsn8.vued/.DeviceAdminReceiver`).
 * Returns a short human-readable status string.
 */
fun startKiosk(activity: Activity): String {
    val dpm = activity.getSystemService(DevicePolicyManager::class.java)
    val admin = deviceAdminComponent(activity)
    if (!dpm.isDeviceOwnerApp(activity.packageName)) {
        return "Not device owner. Run:\n" +
            "adb shell dpm set-device-owner ${activity.packageName}/.DeviceAdminReceiver"
    }
    return try {
        dpm.setLockTaskPackages(admin, arrayOf(activity.packageName))
        dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
        activity.startLockTask()
        "Kiosk lock task started — device pinned to Vued."
    } catch (error: Throwable) {
        "kioskError=${error.message ?: error.javaClass.simpleName}"
    }
}

fun stopKiosk(activity: Activity): String =
    try {
        activity.stopLockTask()
        "Kiosk lock task stopped."
    } catch (error: Throwable) {
        "unlockError=${error.message ?: error.javaClass.simpleName}"
    }
