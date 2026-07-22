package com.nsn8.vued.status

import android.content.Context
import com.nsn8.vued.DiagnosticsLogger
import com.nsn8.vued.meeting.MeetingController
import com.nsn8.vued.net.OrgApi
import com.nsn8.vued.net.RoomConfig
import com.nsn8.vued.service.RecorderState
import com.nsn8.vued.service.RecordingService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class RoomMicStatus(val apiValue: String) {
    AMBIENT_RECORDING("ambient_recording"),
    AMBIENT_MUTED("ambient_muted"),
    MEETING_RECORDING("meeting_recording"),
    MEETING_MUTED("meeting_muted"),
    MIC_DISCONNECTED("mic_disconnected"),
}

internal fun deriveRoomMicStatus(
    recorder: RecorderState.Status,
    manualMeetingActive: Boolean,
    nowMs: Long = System.currentTimeMillis(),
): RoomMicStatus {
    val captureActive = recorder.running && recorder.hasFreshAudio(nowMs)
    return when {
        recorder.micDisconnected -> RoomMicStatus.MIC_DISCONNECTED
        manualMeetingActive && captureActive -> RoomMicStatus.MEETING_RECORDING
        manualMeetingActive -> RoomMicStatus.MEETING_MUTED
        captureActive -> RoomMicStatus.AMBIENT_RECORDING
        else -> RoomMicStatus.AMBIENT_MUTED
    }
}

/**
 * Reports this tablet's current room microphone state while its account is signed in.
 * Status changes and room assignment changes enqueue an immediate update; the periodic
 * signal refreshes `status_updated_at` even when the state has not changed.
 *
 * A conflated channel and a single network writer guarantee that an older heartbeat
 * cannot arrive after and overwrite a newer state transition from this process.
 */
object RoomMicStatusReporter {
    const val HEARTBEAT_INTERVAL_MS = 30_000L
    private val signals = Channel<Unit>(Channel.CONFLATED)

    fun requestImmediateUpdate() {
        signals.trySend(Unit)
    }

    suspend fun run(context: Context) = coroutineScope {
        val appContext = context.applicationContext

        launch {
            combine(RecorderState.state, MeetingController.activeState) { recorder, meeting ->
                deriveRoomMicStatus(recorder, manualMeetingActive = meeting != null)
            }
                .distinctUntilChanged()
                .collect { signals.trySend(Unit) }
        }

        launch {
            RoomConfig.changes.collect { signals.trySend(Unit) }
        }

        launch {
            signals.trySend(Unit)
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                signals.trySend(Unit)
            }
        }

        for (signal in signals) {
            publishCurrentStatus(appContext)
        }
    }

    private suspend fun publishCurrentStatus(context: Context) {
        if (!RecordingService.isCaptureStartEligible(context)) {
            RecorderState.markMicDisconnected()
        }
        val orgId = RoomConfig.orgId(context)?.trim().orEmpty()
        val roomId = RoomConfig.roomId(context)?.trim().orEmpty()
        if (orgId.isEmpty() || roomId.isEmpty()) return

        val status = deriveRoomMicStatus(
            recorder = RecorderState.state.value,
            manualMeetingActive = MeetingController.activeState.value != null,
        )
        runCatching {
            OrgApi.updateRoomMicStatus(orgId, roomId, status.apiValue)
        }.onSuccess {
            DiagnosticsLogger.info(
                "room_mic_status_updated",
                mapOf("roomId" to roomId, "status" to status.apiValue),
            )
        }.onFailure { error ->
            DiagnosticsLogger.warn(
                "room_mic_status_update_failed",
                mapOf("roomId" to roomId, "status" to status.apiValue),
                error,
            )
        }
    }
}
