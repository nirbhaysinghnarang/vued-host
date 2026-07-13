package com.nsn8.vued.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderStateTest {

    @After
    fun resetState() {
        RecorderState.reset()
    }

    @Test
    fun firstDisconnectTimestampAndResumeIntentArePreserved() {
        RecorderState.update { it.copy(running = true, captureReady = true) }

        RecorderState.markMicDisconnected(disconnectedAtMs = 1_000L)
        RecorderState.markMicDisconnected(disconnectedAtMs = 2_000L, captureWasRunning = false)

        val status = RecorderState.state.value
        assertTrue(status.micDisconnected)
        assertEquals(1_000L, status.disconnectedAtMs)
        assertTrue(status.resumeOnReconnect)
        assertFalse(status.captureReady)
    }

    @Test
    fun mutedDisconnectDoesNotRequestAutoResume() {
        RecorderState.markCaptureStoppedByUser()

        RecorderState.markMicDisconnected(disconnectedAtMs = 1_000L)

        // An unmute attempt while the device is still absent must not restart the
        // physical-disconnect clock or turn a previously muted recorder into an
        // auto-resume candidate.
        RecorderState.markMicDisconnected(disconnectedAtMs = 2_000L, captureWasRunning = false)

        val status = RecorderState.state.value
        assertTrue(status.micDisconnected)
        assertEquals(1_000L, status.disconnectedAtMs)
        assertFalse(status.resumeOnReconnect)
    }

    @Test
    fun manualMuteKeepsPhysicalDisconnectAndDisablesAutoResume() {
        RecorderState.update {
            it.copy(
                running = true,
                captureReady = true,
                micDisconnected = true,
                disconnectedAtMs = 1_000L,
                resumeOnReconnect = true,
            )
        }

        RecorderState.markCaptureStoppedByUser()

        val status = RecorderState.state.value
        assertFalse(status.running)
        assertFalse(status.captureReady)
        assertTrue(status.micDisconnected)
        assertEquals(1_000L, status.disconnectedAtMs)
        assertFalse(status.resumeOnReconnect)
    }

    @Test
    fun reconnectCancelsPhysicalDisconnectButKeepsPendingAutoResumeMetadata() {
        RecorderState.update {
            it.copy(
                micDisconnected = true,
                disconnectedAtMs = 1_000L,
                resumeOnReconnect = true,
            )
        }

        RecorderState.markMicReconnected()

        val status = RecorderState.state.value
        assertFalse(status.micDisconnected)
        assertEquals(1_000L, status.disconnectedAtMs)
        assertTrue(status.resumeOnReconnect)
    }
}
