package com.nsn8.vued.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Retention semantics for the uma16 ring: upload-driven deletion
 * (deleteSegmentsCoveredBy) and the free-space floor backstop. Pure JVM —
 * the free-bytes provider is injected, and DiskSpaceGuard's StatFs default
 * degrades to Long.MAX_VALUE (floor disabled) off-device.
 */
class RollingBufferRetentionTest {

    private val oneSecond = ShortArray(16_000 * 16) { 100 }

    private fun buffer(dir: File, clock: () -> Long) = MultiChannelWavRollingBuffer(
        directory = dir,
        channels = 16,
        segmentSeconds = 1,
        clockMs = clock,
    )

    @Test
    fun deleteCoveredSegments_removesOnlyFullyCoveredSegments() {
        val dir = tempDir()
        var nowMs = 1_000L
        val buffer = buffer(dir) { nowMs }
        buffer.appendInterleavedPcm16(oneSecond, frames = 16_000) // [1000, 2000)
        nowMs = 2_000L
        buffer.appendInterleavedPcm16(oneSecond, frames = 16_000) // [2000, 3000)
        nowMs = 3_000L
        buffer.appendInterleavedPcm16(oneSecond, frames = 16_000) // [3000, 4000)
        buffer.flush()

        val deleted = MultiChannelWavRollingBuffer.deleteSegmentsCoveredBy(dir, 2_000L, 3_000L, channels = 16)

        assertEquals(1, deleted)
        assertTrue(File(dir, "1.wav").exists()) // straddles the start bound
        assertFalse(File(dir, "2.wav").exists())
        assertTrue(File(dir, "3.wav").exists()) // straddles the end bound
    }

    @Test
    fun deleteCoveredSegments_usesParsedFrameCountForEndBoundary() {
        val dir = tempDir()
        var nowMs = 1_000L
        val buffer = buffer(dir) { nowMs }
        // 1.5s of frames in one segment: real endMs is 2500, not the 2000 the
        // nominal segment length would suggest.
        buffer.appendInterleavedPcm16(ShortArray(24_000 * 16) { 7 }, frames = 24_000)
        buffer.flush()

        assertEquals(0, MultiChannelWavRollingBuffer.deleteSegmentsCoveredBy(dir, 1_000L, 2_000L, channels = 16))
        assertTrue(File(dir, "1.wav").exists())
        assertEquals(1, MultiChannelWavRollingBuffer.deleteSegmentsCoveredBy(dir, 1_000L, 2_500L, channels = 16))
        assertFalse(File(dir, "1.wav").exists())
    }

    @Test
    fun deleteCoveredSegments_skipsInProgressZeroFrameFile() {
        val dir = tempDir()
        var nowMs = 1_000L
        val buffer = buffer(dir) { nowMs }
        // Half a segment, no flush: the writer is still open and the WAV
        // header still reports zero data bytes.
        buffer.appendInterleavedPcm16(ShortArray(8_000 * 16) { 3 }, frames = 8_000)

        val deletedWhileOpen =
            MultiChannelWavRollingBuffer.deleteSegmentsCoveredBy(dir, 0L, 10_000L, channels = 16)

        assertEquals(0, deletedWhileOpen)
        assertTrue(File(dir, "1.wav").exists())

        buffer.flush() // header patched; the same window now covers it

        assertEquals(1, MultiChannelWavRollingBuffer.deleteSegmentsCoveredBy(dir, 0L, 10_000L, channels = 16))
        assertFalse(File(dir, "1.wav").exists())
    }

    @Test
    fun deleteCoveredSegments_ignoresNonMatchingWavFiles() {
        val dir = tempDir()
        val monoFile = File(dir, "5.wav")
        val writer = Pcm16WavSegmentWriter(monoFile, sampleRate = 16_000, channels = 1)
        writer.writeInterleaved(ShortArray(16_000) { 9 }, frames = 16_000)
        writer.finish()

        val deleted = MultiChannelWavRollingBuffer.deleteSegmentsCoveredBy(dir, 0L, 60_000L, channels = 16)

        assertEquals(0, deleted)
        assertTrue(monoFile.exists())
    }

    @Test
    fun freeSpaceFloor_prunesOldestFirstUntilAboveFloor() {
        val dir = tempDir()
        var nowMs = 1_000L
        fun wavCount() = dir.listFiles { f -> f.name.endsWith(".wav") }?.size ?: 0
        val buffer = MultiChannelWavRollingBuffer(
            directory = dir,
            channels = 16,
            segmentSeconds = 1,
            clockMs = { nowMs },
            freeSpaceFloorBytes = 1L,
            // "Low disk" whenever more than two segments exist.
            freeBytesProvider = { if (wavCount() > 2) 0L else Long.MAX_VALUE },
        )
        for (second in 1..4) {
            nowMs = second * 1_000L
            buffer.appendInterleavedPcm16(oneSecond, frames = 16_000)
        }
        buffer.flush()

        // The rotate into second 4 saw three finalized segments and pruned the
        // oldest; recording continued uninterrupted.
        assertFalse(File(dir, "1.wav").exists())
        assertTrue(File(dir, "2.wav").exists())
        assertTrue(File(dir, "3.wav").exists())
        assertTrue(File(dir, "4.wav").exists())
    }

    @Test
    fun freeSpaceFloor_noopWhenAboveFloor() {
        val dir = tempDir()
        var nowMs = 1_000L
        val buffer = buffer(dir) { nowMs } // default provider: MAX_VALUE on JVM
        for (second in 1..3) {
            nowMs = second * 1_000L
            buffer.appendInterleavedPcm16(oneSecond, frames = 16_000)
        }
        buffer.flush()

        assertEquals(3, dir.listFiles { f -> f.name.endsWith(".wav") }?.size)
    }

    @Test
    fun diskSpaceGuard_enforceFloor_deletesOldestFirstAndRespectsProtect() {
        val dir = tempDir()
        for (second in 1..3) File(dir, "$second.m4a").writeBytes(ByteArray(10))
        fun m4aCount() = dir.listFiles { f -> f.name.endsWith(".m4a") }?.size ?: 0

        // Stops after one deletion: oldest goes first.
        val deletedFirst = DiskSpaceGuard.enforceFloor(
            dir, ".m4a", protect = null, floorBytes = 1L,
            freeBytes = { if (m4aCount() > 2) 0L else Long.MAX_VALUE },
        )
        assertEquals(1, deletedFirst)
        assertFalse(File(dir, "1.m4a").exists())

        // Drains to one file but never touches the protected (oldest) one.
        val protected = File(dir, "2.m4a")
        val deletedMore = DiskSpaceGuard.enforceFloor(
            dir, ".m4a", protect = protected, floorBytes = 1L,
            freeBytes = { if (m4aCount() > 1) 0L else Long.MAX_VALUE },
        )
        assertEquals(1, deletedMore)
        assertTrue(protected.exists())
        assertFalse(File(dir, "3.m4a").exists())
    }

    @Test
    fun diskSpaceGuard_enforceFloor_returnsZeroWhenAboveFloor() {
        val dir = tempDir()
        File(dir, "1.m4a").writeBytes(ByteArray(10))

        val deleted = DiskSpaceGuard.enforceFloor(
            dir, ".m4a", protect = null, floorBytes = 1L, freeBytes = { Long.MAX_VALUE },
        )

        assertEquals(0, deleted)
        assertTrue(File(dir, "1.m4a").exists())
    }

    private fun tempDir(): File =
        Files.createTempDirectory("vued-retention-test").toFile()
}
