package com.nsn8.vued.audio

import com.nsn8.vued.capture.Uma8Capture
import java.io.File
import kotlin.math.abs

/**
 * The Phase-1 audio chain: mic-array interleaved PCM -> mono downmix -> anti-aliased
 * 48->16 kHz -> 30 s AAC/M4A rolling segments. [process] is the `onPcm` callback fed
 * by [Uma8Capture]; it must run synchronously on the capture thread.
 *
 * [inputChannels] is the array's real-mic channel count (7 for UMA-8, 16 for
 * UMA-16); the downmix is a plain mean, so any value works.
 */
class CapturePipeline(segmentsDir: File, initialInputChannels: Int) {

    private var inputChannels = initialInputChannels
    private var downmixer = Downmixer(initialInputChannels)
    private var resampler = Resampler48to16()
    private val rolling = RollingBuffer(segmentsDir)

    /** The live rolling buffer, so the meeting flow can flush + export windows. */
    val rollingBuffer: RollingBuffer get() = rolling

    /** Peak amplitude (0..1) of the most recently processed 16 kHz block. */
    @Volatile
    var peak: Float = 0f
        private set

    fun configureInputChannels(channels: Int) {
        if (channels == inputChannels) return
        inputChannels = channels
        downmixer = Downmixer(channels)
        resampler = Resampler48to16()
    }

    fun process(buffer: ByteArray, length: Int) {
        val frames = downmixer.frameCount(length)
        if (frames == 0) return
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

        // Speaker-enrollment tap: when armed, the recorder receives the same
        // 16 kHz frames the rolling buffer gets. Output is reused, so the sink
        // must copy what it keeps. Runs on the capture thread; keep it light.
        EnrollmentTap.sink?.invoke(samples, count)

        rolling.append(samples, count)
    }

    val segmentCount: Int get() = rolling.segmentCount
    val lastSegmentPath: String? get() = rolling.lastSegmentPath

    fun close() = rolling.close()
}
