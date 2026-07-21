package com.nsn8.vued

import com.nsn8.vued.net.OrgApi
import com.nsn8.vued.status.RoomMicStatusBroadcast
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
    fun snapshotHidesNullStatusesAndCurrentRoom() {
        val rooms = listOf(
            OrgApi.Room("room-1", "mic-1", "Current", "ambient_recording", 100.0),
            OrgApi.Room("room-2", "mic-2", "Not reported"),
            OrgApi.Room("room-3", "mic-3", "Visible", "ambient_muted", 100.0),
        )

        assertEquals(listOf("room-3"), visibleMicRooms(rooms, currentRoomId = "room-1").map { it.id })
    }
}
