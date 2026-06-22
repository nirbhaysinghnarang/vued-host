package com.nsn8.vued.audio

import com.nsn8.vued.service.RecorderState
import java.io.ByteArrayOutputStream

/**
 * Process-wide hook the [CapturePipeline] feeds with 16 kHz UMA-8 frames when an
 * enrollment is armed. A global (rather than a pipeline reference) because the
 * pipeline instance lives privately inside the recording service.
 */
object EnrollmentTap {
    @Volatile
    var sink: ((FloatArray, Int) -> Unit)? = null
}

/**
 * Records a speaker-enrollment clip from the UMA-8 array (the same source as
 * recording — NOT the phone mic). Requires the recording service to be running
 * (the UMA-8 stream must be live). Feeds the [EnrollmentQualityAnalyzer] and
 * accumulates 16 kHz mono PCM16, which [stop] wraps as a WAV for upload.
 */
class EnrollmentRecorder {
    private val sampleRate = 16_000
    private var analyzer = EnrollmentQualityAnalyzer()
    private val pcm = ByteArrayOutputStream()
    @Volatile private var samplesWritten = 0L
    @Volatile private var armed = false

    /** Peak amplitude (0..1) of the most recent frame — for the live meter. */
    @Volatile var liveLevel: Float = 0f
        private set

    val isRecording: Boolean get() = armed
    val secondsCaptured: Double get() = samplesWritten.toDouble() / sampleRate

    class NotCapturing : Exception("Start recording (UMA-8) before enrolling.")

    data class Result(val wav: ByteArray, val quality: EnrollmentQualityResult)

    /** Arms the tap, resetting all state so a re-record starts fresh from 0.
     *  Throws [NotCapturing] if the UMA-8 isn't streaming. */
    fun start() {
        if (!RecorderState.state.value.running) throw NotCapturing()
        if (armed) return
        // Reset for a clean (re-)recording.
        analyzer.close()
        analyzer = EnrollmentQualityAnalyzer()
        synchronized(pcm) { pcm.reset() }
        samplesWritten = 0L
        liveLevel = 0f
        armed = true
        EnrollmentTap.sink = { samples, count ->
            if (count > 0) {
                analyzer.consume(samples, count)
                var peak = 0f
                val bytes = ByteArray(count * 2)
                for (i in 0 until count) {
                    val f = samples[i].coerceIn(-1f, 1f)
                    val a = if (f < 0f) -f else f
                    if (a > peak) peak = a
                    val s = (f * 32767f).toInt()
                    bytes[i * 2] = (s and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                }
                liveLevel = peak
                synchronized(pcm) { pcm.write(bytes) }
                samplesWritten += count
            }
        }
    }

    /** Disarms and returns the WAV + quality grade. */
    fun stop(): Result {
        armed = false
        EnrollmentTap.sink = null
        val raw = synchronized(pcm) { pcm.toByteArray() }
        val quality = analyzer.result()
        analyzer.close()
        return Result(wav = wrapWav(raw, sampleRate), quality = quality)
    }

    fun cancel() {
        armed = false
        EnrollmentTap.sink = null
        analyzer.close()
    }

    /** Minimal 16-bit mono PCM → WAV (the server decodes any ffmpeg format). */
    private fun wrapWav(pcmData: ByteArray, rate: Int): ByteArray {
        val out = ByteArrayOutputStream(44 + pcmData.size)
        val byteRate = rate * 2 // mono, 16-bit
        fun le32(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        out.write("RIFF".toByteArray()); le32(36 + pcmData.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(1); le32(rate); le32(byteRate); le16(2); le16(16)
        out.write("data".toByteArray()); le32(pcmData.size); out.write(pcmData)
        return out.toByteArray()
    }
}
