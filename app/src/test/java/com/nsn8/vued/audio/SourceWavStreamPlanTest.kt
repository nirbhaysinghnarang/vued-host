package com.nsn8.vued.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The incremental uploader streams [SourceWavStreamPlan] bytes; correctness of
 * resume-from-server-offset rests on two invariants verified here:
 *   1. the plan is byte-identical to [WavSegmentExporter.exportWindow] (the
 *      whole-file export) apart from the two header size fields the server
 *      patches on /complete, and
 *   2. what a live (in-progress) drain streams is an exact prefix of the final
 *      ended file, so appending the remainder at stop reconstructs it exactly.
 */
class SourceWavStreamPlanTest {

    private val headerBytes = Pcm16WavSegmentWriter.HEADER_BYTES

    @Test
    fun planMatchesWholeFileExportApartFromPatchedSizes() {
        val dir = threeSegments()
        val segments = MultiChannelWavRollingBuffer.listSegmentsIn(dir)
        val startMs = 1_500L
        val endMs = 2_500L

        val out = tempDir().resolve("meeting.wav")
        WavSegmentExporter.exportWindow(segments, startMs, endMs, out)
        val exported = out.readBytes()

        val selected = WavSegmentExporter.overlappingSegments(segments, startMs, endMs)
        val plan = SourceWavStreamPlan.build(selected, startMs, endMs)
        val streamed = plan.readRange(0, plan.totalBytes.toInt())

        // Same total size.
        assertEquals(exported.size.toLong(), plan.totalBytes)
        // Identical PCM payload (everything after the 44-byte header).
        assertArrayEquals(
            exported.copyOfRange(headerBytes, exported.size),
            streamed.copyOfRange(headerBytes, streamed.size),
        )
        // Identical fmt chunk (channels/rate/blockAlign/byteRate) and structure;
        // only the RIFF size (4..8) and data size (40..44) differ (patched later).
        assertArrayEquals(exported.copyOfRange(0, 4), streamed.copyOfRange(0, 4))    // "RIFF"
        assertArrayEquals(exported.copyOfRange(8, 40), streamed.copyOfRange(8, 40))  // WAVE..fmt..data id
    }

    @Test
    fun liveStreamPrefixMatchesEndedFilePrefix() {
        val dir = threeSegments()
        val segments = MultiChannelWavRollingBuffer.listSegmentsIn(dir)
        val startMs = 1_000L
        val endMs = 3_500L  // ends partway through the 3rd segment

        // Live drain: window open-ended, withhold the most recent segment.
        val liveSelected = WavSegmentExporter
            .overlappingSegments(segments, startMs, Long.MAX_VALUE)
            .dropLast(1)
        val livePlan = SourceWavStreamPlan.build(liveSelected, startMs, null)

        // Ended drain: full window, tail-trims the last segment to endMs.
        val endedSelected = WavSegmentExporter.overlappingSegments(segments, startMs, endMs)
        val endedPlan = SourceWavStreamPlan.build(endedSelected, startMs, endMs)

        assertTrue("live must be shorter than ended", livePlan.totalBytes < endedPlan.totalBytes)
        val liveBytes = livePlan.readRange(0, livePlan.totalBytes.toInt())
        val endedPrefix = endedPlan.readRange(0, livePlan.totalBytes.toInt())
        assertArrayEquals(liveBytes, endedPrefix)
    }

    @Test
    fun readRangeSpansHeaderAndSegmentBoundaries() {
        val dir = threeSegments()
        val segments = WavSegmentExporter.overlappingSegments(
            MultiChannelWavRollingBuffer.listSegmentsIn(dir), 1_000L, 3_500L,
        )
        val plan = SourceWavStreamPlan.build(segments, 1_000L, 3_500L)
        val whole = plan.readRange(0, plan.totalBytes.toInt())

        // Reassemble from arbitrary, boundary-crossing chunk sizes.
        val reassembled = ByteArray(whole.size)
        var offset = 0L
        val chunk = 7_000  // deliberately not a frame/header multiple
        while (offset < plan.totalBytes) {
            val part = plan.readRange(offset, chunk)
            System.arraycopy(part, 0, reassembled, offset.toInt(), part.size)
            offset += part.size
        }
        assertArrayEquals(whole, reassembled)
    }

    /** Three finalized 1-second, 16ch/16kHz segments starting at 1000/2000/3000 ms. */
    private fun threeSegments(): File {
        val dir = tempDir()
        var nowMs = 1_000L
        val buffer = MultiChannelWavRollingBuffer(
            directory = dir,
            segmentSeconds = 1,
            clockMs = { nowMs },
        )
        val frame = 16_000
        buffer.appendInterleavedPcm16(ShortArray(frame * 16) { 1 }, frames = frame)
        nowMs = 2_000L
        buffer.appendInterleavedPcm16(ShortArray(frame * 16) { 2 }, frames = frame)
        nowMs = 3_000L
        buffer.appendInterleavedPcm16(ShortArray(frame * 16) { 3 }, frames = frame)
        buffer.flush()
        return dir
    }

    private fun tempDir(): File =
        Files.createTempDirectory("vued-stream-plan-test").toFile()
}
