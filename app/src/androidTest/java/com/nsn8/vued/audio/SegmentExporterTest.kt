package com.nsn8.vued.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SegmentExporterTest {

    @Test
    fun exportWindow_preservesValidAudioWhenFinalSegmentIsCorrupt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "segment-export-${UUID.randomUUID()}").apply { mkdirs() }

        try {
            val valid = File(directory, "1.m4a")
            AacM4aSegmentWriter(valid).also { writer ->
                writer.write(ShortArray(8_192) { index -> if (index % 32 < 16) 4_000 else -4_000 }, 8_192)
                writer.finish()
            }
            val corrupt = File(directory, "2.m4a").apply {
                writeBytes("incomplete m4a segment".toByteArray())
            }
            val out = File(directory, "export.m4a")

            val result = SegmentExporter.exportWindow(
                segments = listOf(
                    RollingBuffer.Segment(valid, startMs = 1_000L, endMs = 2_000L),
                    RollingBuffer.Segment(corrupt, startMs = 2_000L, endMs = 3_000L),
                ),
                startMs = 1_000L,
                endMs = 3_000L,
                out = out,
            )

            assertNotNull(result)
            assertEquals(1, result!!.segmentCount)
            assertTrue("valid audio should remain in the export", result.durationMs > 0L)
            assertTrue("export should contain muxed audio", out.length() > 0L)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun exportWindow_returnsNullWhenEverySegmentIsCorrupt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "segment-export-empty-${UUID.randomUUID()}").apply { mkdirs() }

        try {
            val corrupt = File(directory, "1.m4a").apply {
                writeBytes("incomplete m4a segment".toByteArray())
            }
            val out = File(directory, "export.m4a")

            val result = SegmentExporter.exportWindow(
                segments = listOf(RollingBuffer.Segment(corrupt, 1_000L, 2_000L)),
                startMs = 1_000L,
                endMs = 2_000L,
                out = out,
            )

            assertNull(result)
        } finally {
            directory.deleteRecursively()
        }
    }
}
