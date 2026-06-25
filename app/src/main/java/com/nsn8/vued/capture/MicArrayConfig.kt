package com.nsn8.vued.capture

import android.content.Context

/**
 * Persisted choice of mic array. [AUTO] lets [Uma8Capture] pick the profile from
 * the connected device's VID / product name; [UMA8] and [UMA16] force a profile.
 *
 * Takes effect on the next recording start (the service reads it once at startup).
 */
enum class MicArraySelection(val storageKey: String) {
    AUTO("auto"),
    UMA8("uma8"),
    UMA16("uma16");

    fun toProfile(): MicArrayProfile? = when (this) {
        AUTO -> null
        UMA8 -> PROFILE_UMA8
        UMA16 -> PROFILE_UMA16
    }

    companion object {
        fun fromStorageKey(key: String?): MicArraySelection =
            values().firstOrNull { it.storageKey == key } ?: AUTO
    }
}

object MicArrayConfig {
    private const val PREFS = "vued_mic_array"
    private const val KEY_SELECTION = "selection"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun selection(context: Context): MicArraySelection =
        MicArraySelection.fromStorageKey(prefs(context).getString(KEY_SELECTION, null))

    fun set(context: Context, selection: MicArraySelection) {
        prefs(context).edit().putString(KEY_SELECTION, selection.storageKey).apply()
    }
}
