package com.nsn8.vued.status

import android.content.Context
import com.nsn8.vued.DiagnosticsLogger
import com.nsn8.vued.auth.VuedAuth
import com.nsn8.vued.meeting.MeetingController
import com.nsn8.vued.net.RoomConfig
import com.nsn8.vued.service.RecordingService
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class RoomMicCommand(val apiValue: String) {
    @SerialName("mute")
    MUTE("mute"),

    @SerialName("unmute")
    UNMUTE("unmute"),
}

@Serializable
data class RoomMicCommandBroadcast(
    @SerialName("command_id") val commandId: String,
    @SerialName("room_id") val roomId: String,
    val command: RoomMicCommand,
    @SerialName("issued_at") val issuedAt: Double,
)

internal fun isMicCommandConfirmed(command: RoomMicCommand, status: String?): Boolean =
    when (command) {
        RoomMicCommand.MUTE -> status == "ambient_muted"
        RoomMicCommand.UNMUTE -> status in setOf("ambient_recording", "meeting_recording")
    }

/** Maintains this tablet's private, app-scoped command subscription. */
object RoomMicCommandBroadcasts {
    private const val EVENT = "mic_command"
    private const val RETRY_DELAY_MS = 5_000L
    private const val MAX_SEEN_COMMANDS = 128
    private val seenCommandIds = LinkedHashSet<String>()

    fun topic(orgId: String, roomId: String): String =
        "org:$orgId:room:$roomId:mic-commands"

    suspend fun run(context: Context) {
        val appContext = context.applicationContext
        RoomConfig.changes
            .onStart { emit(Unit) }
            .map {
                Assignment(
                    orgId = RoomConfig.orgId(appContext)?.trim().orEmpty(),
                    roomId = RoomConfig.roomId(appContext)?.trim().orEmpty(),
                )
            }
            .distinctUntilChanged()
            .collectLatest { assignment ->
                if (assignment.orgId.isEmpty() || assignment.roomId.isEmpty()) {
                    return@collectLatest
                }
                while (currentCoroutineContext().isActive) {
                    try {
                        listen(appContext, assignment)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        DiagnosticsLogger.warn(
                            "mic_command_broadcast_failed",
                            mapOf("roomId" to assignment.roomId),
                            error,
                        )
                        delay(RETRY_DELAY_MS)
                    }
                }
            }
    }

    private suspend fun listen(context: Context, assignment: Assignment): Nothing = coroutineScope {
        val supabase = VuedAuth.supabaseClient()
        val channel = supabase.channel(topic(assignment.orgId, assignment.roomId)) {
            isPrivate = true
        }
        val commands = channel.broadcastFlow<RoomMicCommandBroadcast>(EVENT)
        val commandJob = launch {
            commands.collect { message ->
                runCatching { apply(context, assignment, message) }
                    .onFailure { error ->
                        DiagnosticsLogger.warn(
                            "mic_command_apply_failed",
                            mapOf(
                                "roomId" to assignment.roomId,
                                "commandId" to message.commandId,
                                "command" to message.command.apiValue,
                            ),
                            error,
                        )
                    }
            }
        }
        val connectionJob = launch {
            channel.status.collect { status ->
                if (status == RealtimeChannel.Status.SUBSCRIBED) {
                    DiagnosticsLogger.info(
                        "mic_command_broadcast_subscribed",
                        mapOf("roomId" to assignment.roomId),
                    )
                }
            }
        }

        try {
            channel.subscribe(blockUntilSubscribed = true)
            awaitCancellation()
        } finally {
            commandJob.cancel()
            connectionJob.cancel()
            withContext(NonCancellable) {
                runCatching { supabase.realtime.removeChannel(channel) }
            }
            DiagnosticsLogger.info(
                "mic_command_broadcast_unsubscribed",
                mapOf("roomId" to assignment.roomId),
            )
        }
    }

    private fun apply(
        context: Context,
        assignment: Assignment,
        message: RoomMicCommandBroadcast,
    ) {
        if (message.roomId != assignment.roomId || !markUnseen(message.commandId)) return

        val applied = when (message.command) {
            RoomMicCommand.MUTE -> MeetingController.runIfNoActiveMeeting {
                RecordingService.stop(context)
            }
            RoomMicCommand.UNMUTE -> {
                RecordingService.start(context)
                true
            }
        }

        DiagnosticsLogger.info(
            if (applied) "mic_command_applied" else "mic_command_rejected",
            mapOf(
                "roomId" to assignment.roomId,
                "commandId" to message.commandId,
                "command" to message.command.apiValue,
                "reason" to if (applied) "" else "local_state_changed",
            ),
        )
        RoomMicStatusReporter.requestImmediateUpdate()
    }

    private fun markUnseen(commandId: String): Boolean = synchronized(seenCommandIds) {
        if (!seenCommandIds.add(commandId)) return@synchronized false
        while (seenCommandIds.size > MAX_SEEN_COMMANDS) {
            seenCommandIds.remove(seenCommandIds.first())
        }
        true
    }

    private data class Assignment(val orgId: String, val roomId: String)
}
