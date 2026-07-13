package com.nsn8.vued.audio

import com.nsn8.vued.capture.PROFILE_UMA16
import com.nsn8.vued.capture.PROFILE_UMA8
import com.nsn8.vued.capture.Uma8Capture
import java.io.File
import kotlin.math.abs

/**
 * The Phase-1 audio chain: mic-array interleaved PCM -> first-channel selection -> anti-aliased
 * 48->16 kHz -> 30 s AAC/M4A rolling segments. [process] is the `onPcm` callback fed
 * by [Uma8Capture]; it must run synchronously on the capture thread.
 *
 * [inputChannels] is the array's real-mic channel count (7 for UMA-8, 16 for
 * UMA-16); channel 0 is used as the mono source.
 */
class CapturePipeline(
    segmentsDir: File,
    initialInputChannels: Int,
    private val sourceSegmentsDir: File? = null,
) {

    private var inputChannels = initialInputChannels
    private var downmixer = Downmixer(initialInputChannels)
    private var resampler = Resampler48to16()
    private val rolling = RollingBuffer(segmentsDir)
    @Volatile
    private var sourceRolling: MultiChannelWavRollingBuffer? = createSourceRolling(initialInputChannels)
    @Volatile
    private var lastSourceWriteMs: Long = 0
    private var closedSourceSegmentCount = 0
    private var closedLastSourceSegmentPath: String? = null

    /** The live rolling buffer, so the meeting flow can flush + export windows. */
    val rollingBuffer: RollingBuffer get() = rolling

    /** Optional mic-array source WAV buffer. Null for Android mic capture and
     *  unsupported channel counts. */
    val sourceRollingBuffer: MultiChannelWavRollingBuffer? get() = sourceRolling

    /** Peak amplitude (0..1) of the most recently processed 16 kHz block. */
    @Volatile
    var peak: Float = 0f
        private set

    /** Max block peak since the last [takeAmbientWindowPeak] call. */
    @Volatile
    private var windowPeakMax: Float = 0f

    /**
     * Peak amplitude (0..1) accumulated over the current ambient window;
     * reading resets the accumulator. The per-block [peak] is instantaneous,
     * so the ambient flusher needs this to judge a whole 5-minute window.
     */
    fun takeAmbientWindowPeak(): Float {
        val value = windowPeakMax
        windowPeakMax = 0f
        return value
    }

    fun configureInputChannels(channels: Int) {
        if (channels == inputChannels) return
        inputChannels = channels
        downmixer = Downmixer(channels)
        resampler = Resampler48to16()
        configureSourceRolling(channels)
    }

    fun process(buffer: ByteArray, length: Int) {
        val frames = downmixer.frameCount(length)
        if (frames == 0) return
        val sourceFrames = sourceRolling?.append48kInt32Le(buffer, length) ?: 0
        if (sourceFrames > 0) {
            lastSourceWriteMs = System.currentTimeMillis()
        }
        val mono = downmixer.toMonoFloat(buffer, length)
        val outCount = resampler.process(mono, frames)
        process16kMono(resampler.output, outCount)
    }

    fun process16kMono(samples: FloatArray, count: Int) {
        if (count <= 0) return
        var p = 0f
        for (i in 0 until count) {
            val a = abs(samples[i])
            if (a > p) p = a
        }
        peak = p
        if (p > windowPeakMax) windowPeakMax = p

        // Speaker-enrollment tap: when armed, the recorder receives the same
        // 16 kHz frames the rolling buffer gets. Output is reused, so the sink
        // must copy what it keeps. Runs on the capture thread; keep it light.
        EnrollmentTap.sink?.invoke(samples, count)

        rolling.append(samples, count)
    }

    val segmentCount: Int get() = rolling.segmentCount
    val lastSegmentPath: String? get() = rolling.lastSegmentPath
    val sourceWavRecording: Boolean
        get() {
            val rolling = sourceRolling ?: return false
            return rolling.segmentCount > 0 &&
                System.currentTimeMillis() - lastSourceWriteMs <= SOURCE_WRITE_ACTIVE_WINDOW_MS
        }
    val sourceSegmentCount: Int get() = closedSourceSegmentCount + (sourceRolling?.segmentCount ?: 0)
    val lastSourceSegmentPath: String?
        get() = sourceRolling?.lastSegmentPath ?: closedLastSourceSegmentPath
    val lastAudioMs: Long get() = rolling.lastAppendMs

    fun close() {
        closeSourceRolling()
        rolling.close()
    }

    private fun configureSourceRolling(channels: Int) {
        val shouldRecordSource = channels in SUPPORTED_SOURCE_CHANNELS &&
            sourceSegmentsDir != null
        if (shouldRecordSource) {
            // A UMA-16 <-> UMA-8 hot-swap changes the channel count; the old
            // buffer's segments stay on disk for their own upload item.
            val existing = sourceRolling
            if (existing != null && existing.channels != channels) {
                closeSourceRolling()
            }
            if (sourceRolling == null) {
                sourceRolling = createSourceRolling(channels)
            }
        } else {
            closeSourceRolling()
        }
    }

    private fun createSourceRolling(channels: Int): MultiChannelWavRollingBuffer? {
        val directory = sourceSegmentsDir ?: return null
        if (channels !in SUPPORTED_SOURCE_CHANNELS) return null
        return MultiChannelWavRollingBuffer(directory, channels = channels)
    }

    private fun closeSourceRolling() {
        val rolling = sourceRolling ?: return
        closedSourceSegmentCount += rolling.segmentCount
        closedLastSourceSegmentPath = rolling.lastSegmentPath ?: closedLastSourceSegmentPath
        rolling.close()
        sourceRolling = null
        lastSourceWriteMs = 0
    }

    companion object {
        private const val SOURCE_WRITE_ACTIVE_WINDOW_MS = 2_000L

        /** Mic-array channel counts eligible for source-WAV (GSS) recording.
         *  Keep in sync with ALLOWED_SOURCE_CHANNELS on the server and Modal. */
        val SUPPORTED_SOURCE_CHANNELS = setOf(PROFILE_UMA8.outChannels, PROFILE_UMA16.outChannels)
    }
}
