package com.nsn8.vued.status

import com.nsn8.vued.DiagnosticsLogger
import com.nsn8.vued.auth.VuedAuth
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RoomMicStatusBroadcast(
    @SerialName("room_id") val roomId: String,
    @SerialName("microphone_id") val microphoneId: String,
    @SerialName("display_name") val displayName: String,
    val status: String,
    @SerialName("status_updated_at") val statusUpdatedAt: Double,
)

/**
 * Listens for the organization's private microphone-status Broadcast channel.
 * Supabase owns reconnection; [onConnected] runs after every successful channel
 * subscription so callers can refresh their snapshot after a network gap.
 */
object RoomMicStatusBroadcasts {
    private const val EVENT = "mic_status_changed"

    fun topic(orgId: String): String = "org:$orgId:mic-status"

    suspend fun listen(
        orgId: String,
        onConnected: suspend () -> Unit,
        onStatus: (RoomMicStatusBroadcast) -> Unit,
    ): Nothing = coroutineScope {
        val supabase = VuedAuth.supabaseClient()
        val channel = supabase.channel(topic(orgId)) {
            isPrivate = true
        }
        val statusUpdates = channel.broadcastFlow<RoomMicStatusBroadcast>(EVENT)
        val updateJob = launch {
            statusUpdates.collect { update -> onStatus(update) }
        }
        val connectionJob = launch {
            channel.status
                .collect { status ->
                    if (status == RealtimeChannel.Status.SUBSCRIBED) {
                        DiagnosticsLogger.info(
                            "mic_status_broadcast_subscribed",
                            mapOf("orgId" to orgId),
                        )
                        onConnected()
                    }
                }
        }

        try {
            channel.subscribe(blockUntilSubscribed = true)
            awaitCancellation()
        } finally {
            updateJob.cancel()
            connectionJob.cancel()
            withContext(NonCancellable) {
                runCatching { supabase.realtime.removeChannel(channel) }
            }
            DiagnosticsLogger.info(
                "mic_status_broadcast_unsubscribed",
                mapOf("orgId" to orgId),
            )
        }
    }
}
