package com.nsn8.vued.meeting

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nsn8.vued.net.OutboundQueue
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MeetingControllerRecoveryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        // The instrumentation package has its own preferences and files directory, so
        // these tests cannot disturb a signed-in tablet's production recovery queue.
        context = InstrumentationRegistry.getInstrumentation().context.applicationContext
        clearState()
    }

    @After
    fun tearDown() {
        clearState()
    }

    @Test
    fun recovery_clampsHeartbeatAndIsIdempotent() {
        val meeting = activeRecord(heartbeatMs = 5_000L)
        MeetingController.persistActive(context, meeting)

        assertTrue(
            MeetingController.recoverStaleMeetings(
                context,
                nowMs = 4_000L,
                scheduleExport = false,
            ),
        )

        assertNull(MeetingController.persistedActive(context))
        val pending = MeetingController.pendingExports(context)
        assertEquals(1, pending.size)
        assertEquals(1_000L, pending.single().startMs)
        assertEquals(4_000L, pending.single().endMs)
        assertTrue(pending.single().recoveredAfterRestart)

        val queue = outboundQueue()
        assertEquals(1, queue.length())
        assertEquals("MEETING_CREATE", queue.getJSONObject(0).getString("kind"))
        assertEquals("room-at-start", queue.getJSONObject(0).getString("roomId"))
        assertEquals("mic-at-start", queue.getJSONObject(0).getString("microphoneId"))

        // A heartbeat already in flight must not recreate the cleared active record.
        MeetingController.touchActive(context, meeting.meetingId, 6_000L)
        assertNull(MeetingController.persistedActive(context))
        assertFalse(
            MeetingController.recoverStaleMeetings(
                context,
                nowMs = 7_000L,
                scheduleExport = false,
            ),
        )
        assertEquals(1, MeetingController.pendingExports(context).size)
        assertEquals(1, outboundQueue().length())
    }

    @Test
    fun recovery_handlesBackwardWallClockAndExistingCreate() {
        val meeting = activeRecord(heartbeatMs = 2_000L)
        OutboundQueue.enqueueMeetingCreate(
            context = context,
            meetingId = meeting.meetingId,
            title = meeting.title,
            startedAtSec = meeting.startMs / 1_000.0,
            roomId = meeting.roomId,
            microphoneId = meeting.microphoneId,
        )
        MeetingController.persistActive(context, meeting)

        assertTrue(
            MeetingController.recoverStaleMeetings(
                context,
                nowMs = 500L,
                scheduleExport = false,
            ),
        )

        assertEquals(1_000L, MeetingController.pendingExports(context).single().endMs)
        assertEquals(1, outboundQueue().length())
    }

    @Test
    fun heartbeatOnlyMovesForward() {
        val meeting = activeRecord(heartbeatMs = 2_000L)
        MeetingController.persistActive(context, meeting)

        MeetingController.touchActive(context, meeting.meetingId, 1_500L)
        assertEquals(2_000L, MeetingController.persistedActive(context)?.heartbeatMs)

        MeetingController.touchActive(context, meeting.meetingId, 3_000L)
        assertEquals(3_000L, MeetingController.persistedActive(context)?.heartbeatMs)
    }

    @Test
    fun recoveredMeetingWithNoAudioQueuesOrderedFailure() = runBlocking {
        val meeting = activeRecord(heartbeatMs = 2_000L)
        MeetingController.persistActive(context, meeting)
        MeetingController.recoverStaleMeetings(
            context,
            nowMs = 3_000L,
            scheduleExport = false,
        )

        MeetingController.drainPendingExportsForTest(context)

        assertTrue(MeetingController.pendingExports(context).isEmpty())
        val queue = outboundQueue()
        assertEquals(2, queue.length())
        assertEquals("MEETING_CREATE", queue.getJSONObject(0).getString("kind"))
        assertEquals("MEETING_FAILURE", queue.getJSONObject(1).getString("kind"))
        assertEquals(2.0, queue.getJSONObject(1).getDouble("endedAtSec"), 0.0)
        assertTrue(queue.getJSONObject(1).getString("failureReason").contains("restarted"))
    }

    @Test
    fun deterministicMeetingAudioEnqueueIsDeduplicated() {
        val sliceId = "22222222-2222-2222-2222-222222222222"
        val first = File(context.cacheDir, "first-$sliceId.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val retry = File(context.cacheDir, "retry-$sliceId.m4a").apply { writeBytes(byteArrayOf(4, 5, 6)) }

        repeat(2) { attempt ->
            OutboundQueue.enqueueMeeting(
                context = context,
                sliceId = sliceId,
                sessionId = "33333333-3333-3333-3333-333333333333",
                meetingId = "11111111111111111111111111111111",
                startedAtSec = 1.0,
                endedAtSec = 2.0,
                durationSecs = 1.0,
                source = if (attempt == 0) first else retry,
            )
        }

        assertEquals(1, outboundQueue().length())
        assertEquals(sliceId, outboundQueue().getJSONObject(0).getString("id"))
        assertFalse(retry.exists())
        assertTrue(File(context.filesDir, "outbound/$sliceId.m4a").isFile)
    }

    @Test
    fun pendingExportIsClearedWhenMeetingAudioIsAlreadyDurable() = runBlocking {
        val meeting = activeRecord(heartbeatMs = 2_000L)
        MeetingController.persistActive(context, meeting)
        MeetingController.recoverStaleMeetings(
            context,
            nowMs = 3_000L,
            scheduleExport = false,
        )
        val sliceId = "44444444-4444-4444-4444-444444444444"
        val staged = File(context.cacheDir, "$sliceId.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        OutboundQueue.enqueueMeeting(
            context = context,
            sliceId = sliceId,
            sessionId = "33333333-3333-3333-3333-333333333333",
            meetingId = meeting.meetingId,
            startedAtSec = 1.0,
            endedAtSec = 2.0,
            durationSecs = 1.0,
            source = staged,
        )

        MeetingController.drainPendingExportsForTest(context)

        assertTrue(MeetingController.pendingExports(context).isEmpty())
        val kinds = (0 until outboundQueue().length()).map { index ->
            outboundQueue().getJSONObject(index).getString("kind")
        }
        assertEquals(listOf("MEETING_CREATE", "MEETING"), kinds)
    }

    @Test
    fun completedAudioMarkerPreventsRecoveredMeetingFromBeingMarkedFailed() = runBlocking {
        val meeting = activeRecord(heartbeatMs = 2_000L)
        MeetingController.persistActive(context, meeting)
        MeetingController.recoverStaleMeetings(
            context,
            nowMs = 3_000L,
            scheduleExport = false,
        )
        // Model upload success after it committed the completion marker and removed
        // the outbound item/file, but before MeetingController removed its pending row.
        context.getSharedPreferences("vued_outbound", Context.MODE_PRIVATE)
            .edit()
            .putString("queue", "[]")
            .putString("completed_meeting_audio", JSONArray(listOf(meeting.meetingId)).toString())
            .commit()

        MeetingController.drainPendingExportsForTest(context)

        assertTrue(MeetingController.pendingExports(context).isEmpty())
        assertEquals(0, outboundQueue().length())
    }

    private fun activeRecord(heartbeatMs: Long) = MeetingController.PersistedActiveMeeting(
        meetingId = "11111111111111111111111111111111",
        title = "Recovered meeting",
        startMs = 1_000L,
        heartbeatMs = heartbeatMs,
        roomId = "room-at-start",
        microphoneId = "mic-at-start",
    )

    private fun outboundQueue(): JSONArray = JSONArray(
        context.getSharedPreferences("vued_outbound", Context.MODE_PRIVATE)
            .getString("queue", "[]"),
    )

    private fun clearState() {
        context.getSharedPreferences("vued_meeting_exports", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences("vued_outbound", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        File(context.filesDir, "outbound").deleteRecursively()
    }
}
