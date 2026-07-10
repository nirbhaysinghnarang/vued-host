package com.nsn8.vued.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The source-WAV (GSS) buffer must exist exactly for the supported mic-array
 * channel counts — UMA-8 (7) and UMA-16 (16) — and must be recreated with the
 * new count when the attached array changes mid-service.
 */
class CapturePipelineSourceGateTest {

    @Test
    fun supportedChannelCountsCreateSourceBuffer() {
        for (channels in CapturePipeline.SUPPORTED_SOURCE_CHANNELS) {
            val pipeline = CapturePipeline(tempDir(), channels, tempDir())
            assertEquals(channels, checkNotNull(pipeline.sourceRollingBuffer).channels)
        }
    }

    @Test
    fun unsupportedChannelCountsCreateNoSourceBuffer() {
        for (channels in intArrayOf(0, 1, 2, 8)) {
            val pipeline = CapturePipeline(tempDir(), channels, tempDir())
            assertNull(pipeline.sourceRollingBuffer)
        }
    }

    @Test
    fun noSourceBufferWithoutSourceDir() {
        val pipeline = CapturePipeline(tempDir(), 16, sourceSegmentsDir = null)
        assertNull(pipeline.sourceRollingBuffer)
    }

    @Test
    fun reconfigureAcrossArraysRecreatesBufferWithNewChannelCount() {
        val pipeline = CapturePipeline(tempDir(), 16, tempDir())
        assertEquals(16, checkNotNull(pipeline.sourceRollingBuffer).channels)

        pipeline.configureInputChannels(7) // UMA-16 -> UMA-8 hot-swap
        assertEquals(7, checkNotNull(pipeline.sourceRollingBuffer).channels)

        pipeline.configureInputChannels(1) // Android mic fallback
        assertNull(pipeline.sourceRollingBuffer)

        pipeline.configureInputChannels(16) // array returns
        assertEquals(16, checkNotNull(pipeline.sourceRollingBuffer).channels)
    }

    private fun tempDir(): File =
        Files.createTempDirectory("vued-capture-gate-test").toFile()
}
