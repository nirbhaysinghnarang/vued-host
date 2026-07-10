package com.nsn8.vued

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings

private fun deviceAdminComponent(context: Context): ComponentName =
    ComponentName(context, DeviceAdminReceiver::class.java)

fun isDeviceOwner(context: Context): Boolean {
    val dpm = context.getSystemService(DevicePolicyManager::class.java)
    return dpm.isDeviceOwnerApp(context.packageName)
}

fun isKioskLocked(context: Context): Boolean {
    val activityManager = context.getSystemService(ActivityManager::class.java)
    return activityManager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_LOCKED
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
        applyKioskPolicy(activity, dpm, admin)
        activity.startLockTask()
        "Kiosk lock task started — device pinned to Vued."
    } catch (error: Throwable) {
        "kioskError=${error.message ?: error.javaClass.simpleName}"
    }
}

private fun applyKioskPolicy(
    context: Context,
    dpm: DevicePolicyManager,
    admin: ComponentName,
) {
    dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
    dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
    dpm.addPersistentPreferredActivity(
        admin,
        IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        },
        ComponentName(context, MainActivity::class.java),
    )
    dpm.setStatusBarDisabled(admin, true)
    dpm.setKeyguardDisabled(admin, true)
    dpm.setGlobalSetting(
        admin,
        Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
        (
            BatteryManager.BATTERY_PLUGGED_AC or
                BatteryManager.BATTERY_PLUGGED_USB or
                BatteryManager.BATTERY_PLUGGED_WIRELESS
            ).toString(),
    )
}

fun stopKiosk(activity: Activity): String =
    try {
        val dpm = activity.getSystemService(DevicePolicyManager::class.java)
        val admin = deviceAdminComponent(activity)
        val lockTaskResult = runCatching {
            activity.stopLockTask()
            "Kiosk lock task stopped."
        }.getOrElse { error ->
            "unlockWarning=${error.message ?: error.javaClass.simpleName}"
        }
        if (dpm.isDeviceOwnerApp(activity.packageName)) {
            dpm.setStatusBarDisabled(admin, false)
            dpm.setKeyguardDisabled(admin, false)
            dpm.clearPackagePersistentPreferredActivities(admin, activity.packageName)
            dpm.setLockTaskPackages(admin, emptyArray<String>())
        }
        lockTaskResult
    } catch (error: Throwable) {
        "unlockError=${error.message ?: error.javaClass.simpleName}"
    }
