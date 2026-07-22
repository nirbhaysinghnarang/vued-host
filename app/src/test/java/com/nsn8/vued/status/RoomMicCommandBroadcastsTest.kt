package com.nsn8.vued.status

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomMicCommandBroadcastsTest {

    @Test
    fun supportsOnlyMuteAndUnmute() {
        assertEquals(
            listOf(RoomMicCommand.MUTE, RoomMicCommand.UNMUTE),
            RoomMicCommand.entries,
        )
    }

    @Test
    fun topicScopesCommandsToOrganizationAndRoom() {
        assertTrue(
            RoomMicCommandBroadcasts.topic("org-1", "room-2") ==
                "org:org-1:room:room-2:mic-commands"
        )
    }

    @Test
    fun muteConfirmsOnlyAfterAmbientCaptureStops() {
        assertTrue(isMicCommandConfirmed(RoomMicCommand.MUTE, "ambient_muted"))
        assertFalse(isMicCommandConfirmed(RoomMicCommand.MUTE, "ambient_recording"))
        assertFalse(isMicCommandConfirmed(RoomMicCommand.MUTE, "meeting_muted"))
    }

    @Test
    fun unmuteConfirmsForAmbientOrMeetingRecording() {
        assertTrue(isMicCommandConfirmed(RoomMicCommand.UNMUTE, "ambient_recording"))
        assertTrue(isMicCommandConfirmed(RoomMicCommand.UNMUTE, "meeting_recording"))
        assertFalse(isMicCommandConfirmed(RoomMicCommand.UNMUTE, "ambient_muted"))
    }
}
