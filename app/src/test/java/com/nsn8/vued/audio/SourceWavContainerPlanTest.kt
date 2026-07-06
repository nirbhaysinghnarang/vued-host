package com.nsn8.vued.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Verifies the WavPack upload container framing — `[VWV1][ (u32 LE len)(blob) ]*`
 * — and that [SourceWavContainerPlan.readRange] serves any offset (so the
 * streaming uploader can resume from the server cursor). Uses fake blob bytes;
 * real WavPack encode/decode is covered on-device and by the Modal decode test.
 */
class SourceWavContainerPlanTest {

    @Test
    fun layoutMatchesMagicAndLengthPrefixedBlobs() {
        val dir = tempDir()
        val b0 = "first-blob".toByteArray()
        val b1 = "second-blob-longer".toByteArray()
        val f0 = dir.resolve("s0.wv").apply { writeBytes(b0) }
        val f1 = dir.resolve("s1.wv").apply { writeBytes(b1) }

        val plan = SourceWavContainerPlan.build(listOf(f0, f1))

        val expected = "VWV1".toByteArray() +
            leU32(b0.size) + b0 +
            leU32(b1.size) + b1
        assertEquals(expected.size.toLong(), plan.totalBytes)
        assertArrayEquals(expected, plan.readRange(0, plan.totalBytes.toInt()))
    }

    @Test
    fun readRangeReassemblesAcrossBoundaries() {
        val dir = tempDir()
        val files = (0 until 4).map { i ->
            dir.resolve("s$i.wv").apply { writeBytes(ByteArray(1000 + i * 137) { (it + i).toByte() }) }
        }
        val plan = SourceWavContainerPlan.build(files)
        val whole = plan.readRange(0, plan.totalBytes.toInt())

        val reassembled = ByteArray(whole.size)
        var offset = 0L
        val chunk = 333 // crosses magic, prefix, and blob boundaries
        while (offset < plan.totalBytes) {
            val part = plan.readRange(offset, chunk)
            System.arraycopy(part, 0, reassembled, offset.toInt(), part.size)
            offset += part.size
        }
        assertArrayEquals(whole, reassembled)
    }

    @Test
    fun emptyBlobsAreSkipped() {
        val dir = tempDir()
        val good = dir.resolve("good.wv").apply { writeBytes("x".toByteArray()) }
        val empty = dir.resolve("empty.wv").apply { writeBytes(ByteArray(0)) }
        val plan = SourceWavContainerPlan.build(listOf(empty, good, empty))
        // Only the non-empty blob contributes: magic + [len][x].
        assertEquals((4 + 4 + 1).toLong(), plan.totalBytes)
    }

    private fun leU32(v: Int): ByteArray = byteArrayOf(
        (v and 0xff).toByte(),
        ((v ushr 8) and 0xff).toByte(),
        ((v ushr 16) and 0xff).toByte(),
        ((v ushr 24) and 0xff).toByte(),
    )

    private fun tempDir(): File = Files.createTempDirectory("vued-container-test").toFile()
}
