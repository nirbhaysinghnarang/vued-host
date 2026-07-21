package com.nsn8.vued.status

import com.nsn8.vued.service.RecorderState
import org.junit.Assert.assertEquals
import org.junit.Test

class RoomMicStatusReporterTest {
    private val nowMs = 10_000L

    @Test
    fun ambientRecordingWhenCaptureIsFreshWithoutMeeting() {
        assertEquals(
            RoomMicStatus.AMBIENT_RECORDING,
            deriveRoomMicStatus(freshCapture(), manualMeetingActive = false, nowMs = nowMs),
        )
    }

    @Test
    fun ambientMutedWhenCaptureIsStopped() {
        assertEquals(
            RoomMicStatus.AMBIENT_MUTED,
            deriveRoomMicStatus(RecorderState.Status(), manualMeetingActive = false, nowMs = nowMs),
        )
    }

    @Test
    fun meetingRecordingWhenCaptureIsFresh() {
        assertEquals(
            RoomMicStatus.MEETING_RECORDING,
            deriveRoomMicStatus(freshCapture(), manualMeetingActive = true, nowMs = nowMs),
        )
    }

    @Test
    fun meetingMutedWhenCaptureIsNotFresh() {
        assertEquals(
            RoomMicStatus.MEETING_MUTED,
            deriveRoomMicStatus(
                RecorderState.Status(running = true, captureReady = true, lastAudioMs = 1_000L),
                manualMeetingActive = true,
                nowMs = nowMs,
            ),
        )
    }

    private fun freshCapture() = RecorderState.Status(
        running = true,
        captureReady = true,
        lastAudioMs = nowMs - 100L,
    )
}
