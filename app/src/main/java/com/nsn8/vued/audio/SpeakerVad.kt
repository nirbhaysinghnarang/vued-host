package com.nsn8.vued.audio

import kotlin.math.sqrt

/**
 * On-device speaker-enrollment quality gate — a port of the iOS
 * `SpeakerEnrollmentAudioQualityAnalyzer`. Grades a 16 kHz mono clip on speech
 * density (VAD) + confidence into four buckets, so the UI can nudge a re-record
 * unless the clip is HIGH. Uses TEN VAD (native) when `libten_vad.so` is bundled,
 * falling back to an energy/ZCR gate otherwise — same degradation as iOS.
 */
enum class EnrollmentQuality {
    UNACCEPTABLE, LOW, MEDIUM, HIGH;

    val shouldReEnroll: Boolean get() = this != HIGH
    val title: String get() = name.lowercase().replaceFirstChar { it.uppercase() }
}

data class EnrollmentQualityResult(
    val quality: EnrollmentQuality,
    val speechRatio: Double,
    val speechSecs: Double,
    val sourceSecs: Double,
    val averageProbability: Double,
)

/** Thin wrapper over the native TEN VAD (see ten_vad_jni.cpp). */
class TenVad private constructor(private var handle: Long) {
    /** Speech probability in [0,1], or -1 on error. */
    fun process(pcm16: ShortArray, len: Int): Float =
        if (handle != 0L) nativeProcess(handle, pcm16, len) else -1f

    fun close() {
        if (handle != 0L) { nativeDestroy(handle); handle = 0L }
    }

    private external fun nativeCreate(hopSize: Int, threshold: Float): Long
    private external fun nativeProcess(handle: Long, pcm: ShortArray, len: Int): Float
    private external fun nativeDestroy(handle: Long)

    companion object {
        init { runCatching { System.loadLibrary("vuednative") } }

        /** Returns null when the native lib isn't available (→ energy fallback). */
        fun create(hopSize: Int = 256, threshold: Float = 0.5f): TenVad? {
            val handle = runCatching { TenVad(0L).nativeCreate(hopSize, threshold) }.getOrDefault(0L)
            return if (handle != 0L) TenVad(handle) else null
        }
    }
}

class EnrollmentQualityAnalyzer {
    private val hopSize = 256
    private val sampleRate = 16_000
    private val threshold = 0.5f
    private val minSpeechRms = 0.0025

    private val tenVad: TenVad? = TenVad.create(hopSize, threshold)
    private var leftover = FloatArray(0)
    private var sourceSamples = 0L
    private var speechSamples = 0L
    private var speechFrames = 0L
    private var speechProbSum = 0.0

    /** Feed 16 kHz mono float frames as they arrive during enrollment capture. */
    @Synchronized
    fun consume(samples: FloatArray, count: Int) {
        if (count <= 0) return
        val buf = if (leftover.isEmpty()) samples.copyOf(count)
                  else leftover + samples.copyOf(count)
        var offset = 0
        val pcm = ShortArray(hopSize)
        while (buf.size - offset >= hopSize) {
            var rmsAcc = 0.0
            for (i in 0 until hopSize) {
                val s = buf[offset + i]
                rmsAcc += (s * s).toDouble()
                pcm[i] = (s.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }
            val rms = sqrt(rmsAcc / hopSize)
            var prob = tenVad?.process(pcm, hopSize) ?: -1f
            if (prob < 0f) prob = energyProbability(buf, offset, rms) // native error → fallback
            val isSpeech = prob >= threshold && rms >= minSpeechRms
            sourceSamples += hopSize
            if (isSpeech) {
                speechSamples += hopSize
                speechFrames += 1
                speechProbSum += prob.toDouble()
            }
            offset += hopSize
        }
        leftover = if (offset < buf.size) buf.copyOfRange(offset, buf.size) else FloatArray(0)
    }

    @Synchronized
    fun result(): EnrollmentQualityResult {
        val ratio = if (sourceSamples > 0) speechSamples.toDouble() / sourceSamples else 0.0
        val avgProb = if (speechFrames > 0) speechProbSum / speechFrames else 0.0
        val quality = when {
            ratio < 0.30 || avgProb < 0.25 -> EnrollmentQuality.UNACCEPTABLE
            ratio < 0.50 || avgProb < 0.55 -> EnrollmentQuality.LOW
            ratio >= 0.72 && avgProb >= 0.65 -> EnrollmentQuality.HIGH
            else -> EnrollmentQuality.MEDIUM
        }
        return EnrollmentQualityResult(
            quality = quality,
            speechRatio = ratio,
            speechSecs = speechSamples.toDouble() / sampleRate,
            sourceSecs = sourceSamples.toDouble() / sampleRate,
            averageProbability = avgProb,
        )
    }

    fun close() = tenVad?.close() ?: Unit

    // Energy + zero-crossing-rate fallback (port of iOS SpeakerEnrollmentEnergySpeechGate).
    private fun energyProbability(buf: FloatArray, offset: Int, rms: Double): Float {
        var crossings = 0
        for (i in 1 until hopSize) {
            if ((buf[offset + i - 1] >= 0f) != (buf[offset + i] >= 0f)) crossings++
        }
        val zcr = crossings.toDouble() / (hopSize - 1)
        val rmsScore = ((rms - 0.003) / 0.025).coerceIn(0.0, 1.0)
        val zcrPenalty = if (zcr > 0.32) 0.25 else 0.0
        return (rmsScore - zcrPenalty).coerceIn(0.0, 1.0).toFloat()
    }
}
