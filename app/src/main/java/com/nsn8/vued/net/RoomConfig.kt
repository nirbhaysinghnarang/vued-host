package com.nsn8.vued.net

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * The room this tablet is assigned to. Tagged onto every uploaded slice so the
 * server can stamp `room_id`/`microphone_id`/`org_id` on the transcript events.
 * One room per tablet, changeable in settings (decision #3). Persisted locally;
 * no server write — "assigning" is purely which room_id we send.
 */
object RoomConfig {
    private const val PREFS = "vued_room"
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val changes: SharedFlow<Unit> = _changes

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun roomId(context: Context): String? = prefs(context).getString("roomId", null)
    fun roomName(context: Context): String? = prefs(context).getString("roomName", null)
    fun orgId(context: Context): String? = prefs(context).getString("orgId", null)

    /** The room's microphone id, sent with uploaded audio for provenance. */
    fun microphoneId(context: Context): String? = prefs(context).getString("microphoneId", null)

    fun set(context: Context, roomId: String, roomName: String, orgId: String, microphoneId: String) {
        prefs(context).edit()
            .putString("roomId", roomId)
            .putString("roomName", roomName)
            .putString("orgId", orgId)
            .putString("microphoneId", microphoneId)
            .apply()
        _changes.tryEmit(Unit)
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        _changes.tryEmit(Unit)
    }
}
