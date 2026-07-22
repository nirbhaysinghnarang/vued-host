package com.nsn8.vued.service

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSessionGateTest {

    @After
    fun resetRecorderState() {
        RecorderState.reset()
    }

    @Test
    fun invalidatedSessionCannotRestoreCaptureReady() {
        val sessions = CaptureSessionGate()
        val sessionId = sessions.begin()
        RecorderState.update { it.copy(running = true, captureReady = true) }

        sessions.invalidateCurrent()
        RecorderState.markCaptureStoppedByUser()

        val published = sessions.runIfActive(sessionId) {
            RecorderState.update { it.copy(running = true, captureReady = true) }
        }

        assertFalse(published)
        assertFalse(RecorderState.state.value.running)
        assertFalse(RecorderState.state.value.captureReady)
    }

    @Test
    fun oldSessionCannotPublishAfterNewSessionBegins() {
        val sessions = CaptureSessionGate()
        val oldSessionId = sessions.begin()
        val newSessionId = sessions.begin()

        assertFalse(sessions.runIfActive(oldSessionId) {})
        assertTrue(sessions.runIfActive(newSessionId) {})
    }

    @Test
    fun staleSessionCannotFinishNewSession() {
        val sessions = CaptureSessionGate()
        val oldSessionId = sessions.begin()
        val newSessionId = sessions.begin()

        assertFalse(sessions.finishIfActive(oldSessionId) {})
        assertTrue(sessions.isActive(newSessionId))
    }
}
