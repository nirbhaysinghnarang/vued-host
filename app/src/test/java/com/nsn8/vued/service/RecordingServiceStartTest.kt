package com.nsn8.vued.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingServiceStartTest {

    @Test
    fun productionCaptureRequiresConnectedAuthorizedUma() {
        assertFalse(canStartRecordingCapture(false, umaConnected = false, usbPermissionGranted = false))
        assertFalse(canStartRecordingCapture(false, umaConnected = true, usbPermissionGranted = false))
        assertTrue(canStartRecordingCapture(false, umaConnected = true, usbPermissionGranted = true))
    }

    @Test
    fun developmentFallbackDoesNotRequireUma() {
        assertTrue(canStartRecordingCapture(true, umaConnected = false, usbPermissionGranted = false))
    }
}
