package com.nsn8.vued.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

class MultiChannelWavRecordingTest {

    @Test
    fun wavWriterPatchesPcm16Header() {
        val file = tempDir().resolve("source.wav")
        val writer = Pcm16WavSegmentWriter(file, sampleRate = 16_000, channels = 16)

        writer.writeInterleaved(ShortArray(32) { it.toShort() }, frames = 2)
        writer.finish()

        val info = Pcm16WavSegmentWriter.readInfo(file)
        assertNotNull(info)
        checkNotNull(info)
        assertEquals(16_000, info.sampleRate)
        assertEquals(16, info.channels)
        assertEquals(16, info.bitsPerSample)
        assertEquals(64L, info.dataBytes)
        assertEquals(2L, info.frameCount)

        val header = file.readBytes().copyOfRange(0, Pcm16WavSegmentWriter.HEADER_BYTES)
        assertEquals(32, le16(header, 32)) // block align: 16 channels * 2 bytes
        assertEquals(512_000, le32(header, 28)) // byte rate: 16000 * 32
    }

    @Test
    fun multiChannelResamplerPreservesInterleavedChannelOrder() {
        val channels = 16
        val inputFrames = 180
        val raw = ByteArray(inputFrames * channels * 4)
        var offset = 0
        for (frame in 0 until inputFrames) {
            for (channel in 0 until channels) {
                writeInt32Le(raw, offset, (channel + 1) * 1_000_000)
                offset += 4
            }
        }
        val resampler = MultiChannelResampler48to16(channels)

        val outFrames = resampler.process(raw, raw.size)

        assertTrue(outFrames > 40)
        val lastBase = (outFrames - 1) * channels
        val expected = ShortArray(channels) { channel ->
            (((channel + 1) * 1_000_000) /
                2_147_483_648.0 *
                MultiChannelResampler48to16.DEFAULT_MAKEUP_GAIN *
                32767.0).toInt().toShort()
        }
        val actual = resampler.output.copyOfRange(lastBase, lastBase + channels)
        assertArrayEquals(expected, actual)
    }

    @Test
    fun rollingBufferRotatesAndListsFinalizedWavSegments() {
        val dir = tempDir()
        var nowMs = 1_000L
        val buffer = MultiChannelWavRollingBuffer(
            directory = dir,
            segmentSeconds = 1,
            clockMs = { nowMs },
        )
        val oneSecond = ShortArray(16_000 * 16) { 100 }

        buffer.appendInterleavedPcm16(oneSecond, frames = 16_000)
        nowMs = 2_000L
        buffer.appendInterleavedPcm16(oneSecond, frames = 16_000)
        buffer.flush()

        val segments = buffer.listSegments()
        assertEquals(2, segments.size)
        assertEquals(1_000L, segments[0].startMs)
        assertEquals(2_000L, segments[0].endMs)
        assertEquals(16_000L, segments[0].frameCount)
        assertEquals(2_000L, segments[1].startMs)
        assertEquals(3_000L, segments[1].endMs)
    }

    @Test
    fun wavExporterStitchesAndTrimsMeetingWindow() {
        val dir = tempDir()
        var nowMs = 1_000L
        val buffer = MultiChannelWavRollingBuffer(
            directory = dir,
            segmentSeconds = 1,
            clockMs = { nowMs },
        )
        val first = ShortArray(16_000 * 16) { 1 }
        val second = ShortArray(16_000 * 16) { 2 }
        buffer.appendInterleavedPcm16(first, frames = 16_000)
        nowMs = 2_000L
        buffer.appendInterleavedPcm16(second, frames = 16_000)
        buffer.flush()

        val out = tempDir().resolve("meeting.wav")
        val result = WavSegmentExporter.exportWindow(
            buffer.listSegments(),
            startMs = 1_500L,
            endMs = 2_500L,
            out = out,
        )

        assertNotNull(result)
        checkNotNull(result)
        assertEquals(1_000L, result.durationMs)
        assertEquals(2, result.segmentCount)
        val info = checkNotNull(Pcm16WavSegmentWriter.readInfo(out))
        assertEquals(16_000L, info.frameCount)
        assertEquals(512_000L, info.dataBytes)
        RandomAccessFile(out, "r").use { file ->
            file.seek(Pcm16WavSegmentWriter.HEADER_BYTES.toLong())
            assertEquals(1, readLe16(file))
            file.seek(Pcm16WavSegmentWriter.HEADER_BYTES + 8_000L * 16 * 2)
            assertEquals(2, readLe16(file))
        }
    }

    private fun tempDir(): File =
        Files.createTempDirectory("vued-wav-test").toFile()

    private fun writeInt32Le(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xff).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xff).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }

    private fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun le32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun readLe16(file: RandomAccessFile): Int {
        val lo = file.read()
        val hi = file.read()
        return lo or (hi shl 8)
    }
}
