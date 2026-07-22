package com.nsn8.vued

import com.nsn8.vued.net.OrgApi
import com.nsn8.vued.status.RoomMicStatusBroadcast
import com.nsn8.vued.status.RoomMicCommand
import org.junit.Assert.assertEquals
import org.junit.Test

class MicStatusBroadcastUiTest {

    @Test
    fun broadcastReplacesExistingRoomStatus() {
        val existing = OrgApi.Room(
            id = "room-2",
            microphoneId = "mic-2",
            displayName = "Conference Room",
            status = "ambient_recording",
            statusUpdatedAt = 100.0,
        )

        val result = mergeMicStatusBroadcast(
            rooms = listOf(existing),
            update = RoomMicStatusBroadcast(
                roomId = "room-2",
                microphoneId = "mic-2",
                displayName = "Conference Room",
                status = "meeting_recording",
                statusUpdatedAt = 200.0,
            ),
            currentRoomId = "room-1",
        )

        assertEquals(1, result.size)
        assertEquals("meeting_recording", result.single().status)
        assertEquals(200.0, result.single().statusUpdatedAt)
    }

    @Test
    fun broadcastForCurrentRoomIsIgnored() {
        val result = mergeMicStatusBroadcast(
            rooms = emptyList(),
            update = RoomMicStatusBroadcast(
                roomId = "room-1",
                microphoneId = "mic-1",
                displayName = "Current Room",
                status = "ambient_muted",
                statusUpdatedAt = 200.0,
            ),
            currentRoomId = "room-1",
        )

        assertEquals(emptyList<OrgApi.Room>(), result)
    }

    @Test
    fun delayedBroadcastDoesNotReplaceNewerStatus() {
        val current = OrgApi.Room(
            id = "room-2",
            microphoneId = "mic-2",
            displayName = "Conference Room",
            status = "ambient_muted",
            statusUpdatedAt = 300.0,
        )
        val delayed = RoomMicStatusBroadcast(
            roomId = "room-2",
            microphoneId = "mic-2",
            displayName = "Conference Room",
            status = "mic_disconnected",
            statusUpdatedAt = 200.0,
        )

        assertEquals(
            listOf(current),
            mergeMicStatusBroadcast(listOf(current), delayed, currentRoomId = "room-1"),
        )
    }

    @Test
    fun staleSnapshotKeepsNewerRealtimeStatus() {
        val realtime = OrgApi.Room(
            id = "room-2",
            microphoneId = "mic-2",
            displayName = "Old room name",
            status = "mic_disconnected",
            statusUpdatedAt = 300.0,
        )
        val snapshot = OrgApi.Room(
            id = "room-2",
            microphoneId = "mic-2",
            displayName = "Updated room name",
            status = "ambient_muted",
            statusUpdatedAt = 200.0,
        )

        assertEquals(
            snapshot.copy(status = "mic_disconnected", statusUpdatedAt = 300.0),
            mergeMicStatusSnapshot(
                rooms = listOf(realtime),
                snapshot = listOf(snapshot),
                currentRoomId = "room-1",
            ).single(),
        )
    }

    @Test
    fun snapshotHidesNullStatusesAndCurrentRoom() {
        val rooms = listOf(
            OrgApi.Room("room-1", "mic-1", "Current", "ambient_recording", 100.0),
            OrgApi.Room("room-2", "mic-2", "Not reported"),
            OrgApi.Room("room-3", "mic-3", "Visible", "ambient_muted", 100.0),
        )

        assertEquals(listOf("room-3"), visibleMicRooms(rooms, currentRoomId = "room-1").map { it.id })
    }

    @Test
    fun micStatusPanelRequiresMultipleOrganizationMicrophones() {
        val oneMic = listOf(
            OrgApi.Room("room-1", "mic-1", "Current"),
        )
        val twoMics = oneMic + OrgApi.Room("room-2", "mic-2", "Conference Room")

        assertEquals(false, hasMultipleOrganizationMicrophones(emptyList()))
        assertEquals(false, hasMultipleOrganizationMicrophones(oneMic))
        assertEquals(true, hasMultipleOrganizationMicrophones(twoMics))
    }

    @Test
    fun duplicateAndBlankMicrophoneIdsDoNotEnablePanel() {
        val rooms = listOf(
            OrgApi.Room("room-1", "mic-1", "Current"),
            OrgApi.Room("room-2", "mic-1", "Duplicate Assignment"),
            OrgApi.Room("room-3", "  ", "Unassigned"),
        )

        assertEquals(false, hasMultipleOrganizationMicrophones(rooms))
    }

    @Test
    fun staleHeartbeatUsesUnavailableIndicator() {
        val nowMs = 200_000L
        val room = OrgApi.Room(
            id = "room-2",
            microphoneId = "mic-2",
            displayName = "Conference Room",
            status = "ambient_recording",
            statusUpdatedAt = (nowMs - 60_001L) / 1_000.0,
        )

        assertEquals(false, isMicOnline(room, nowMs))
        assertEquals(true, shouldShowMicUnavailableIndicator(room, nowMs))
    }

    @Test
    fun freshDisconnectedMicUsesUnavailableIndicator() {
        val nowMs = 200_000L
        val room = OrgApi.Room(
            id = "room-2",
            microphoneId = "mic-2",
            displayName = "Conference Room",
            status = "mic_disconnected",
            statusUpdatedAt = nowMs / 1_000.0,
        )

        assertEquals(true, isMicOnline(room, nowMs))
        assertEquals(true, shouldShowMicUnavailableIndicator(room, nowMs))
    }

    @Test
    fun freshConnectedMicKeepsItsStatusControl() {
        val nowMs = 200_000L
        val room = OrgApi.Room(
            id = "room-2",
            microphoneId = "mic-2",
            displayName = "Conference Room",
            status = "ambient_recording",
            statusUpdatedAt = nowMs / 1_000.0,
        )

        assertEquals(true, isMicOnline(room, nowMs))
        assertEquals(false, shouldShowMicUnavailableIndicator(room, nowMs))
    }

    @Test
    fun pendingCommandResolvesOnlyAfterStatusValueChanges() {
        assertEquals(
            false,
            hasMicStatusChangedSinceDispatch("ambient_muted", "ambient_muted"),
        )
        assertEquals(
            true,
            hasMicStatusChangedSinceDispatch("ambient_muted", "ambient_recording"),
        )
        assertEquals(
            true,
            hasMicStatusChangedSinceDispatch("ambient_muted", "meeting_muted"),
        )
    }

    @Test
    fun disconnectedStatusReturnsFriendlyUnmuteFailure() {
        assertEquals(true, isMicDisconnectedStatus("mic_disconnected"))
        assertEquals(false, isMicDisconnectedStatus("ambient_muted"))
        assertEquals(
            "Conference Room's microphone is disconnected.",
            micCommandFailureMessage(
                RoomMicCommand.UNMUTE,
                "mic_disconnected",
                "Conference Room",
            ),
        )
        assertEquals(
            null,
            micCommandFailureMessage(
                RoomMicCommand.MUTE,
                "mic_disconnected",
                "Conference Room",
            ),
        )
        assertEquals(
            "Conference Room's microphone is disconnected.",
            messageForDisconnectedApiFailure(
                RoomMicCommand.UNMUTE,
                "microphone is disconnected",
                "Conference Room",
            ),
        )
    }

    @Test
    fun disconnectedCommandErrorClearsAfterThatRoomReconnects() {
        val error = MicCommandError(
            roomId = "room-2",
            message = "Conference Room's microphone is disconnected.",
            clearsWhenMicReconnects = true,
        )

        assertEquals(
            false,
            shouldClearMicCommandError(
                error,
                OrgApi.Room("room-2", "mic-2", "Conference Room", "mic_disconnected", 100.0),
            ),
        )
        assertEquals(
            true,
            shouldClearMicCommandError(
                error,
                OrgApi.Room("room-2", "mic-2", "Conference Room", "ambient_muted", 200.0),
            ),
        )
        assertEquals(
            false,
            shouldClearMicCommandError(
                error,
                OrgApi.Room("room-3", "mic-3", "Other Room", "ambient_muted", 200.0),
            ),
        )
    }
}
