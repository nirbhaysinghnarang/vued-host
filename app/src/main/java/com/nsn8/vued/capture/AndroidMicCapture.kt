package com.nsn8.vued.capture

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.max

/**
 * Fallback capture path for the device's built-in Android microphone.
 *
 * The rolling-buffer side of the app is already normalized to mono 16 kHz float
 * samples, so this class records mono 16-bit PCM at 16 kHz and converts blocks to
 * floats before handing them to the caller.
 */
class AndroidMicCapture {

    class CaptureException(message: String) : Exception(message)

    private var scratch = FloatArray(0)

    fun streamPcm(
        onPcm: (samples: FloatArray, length: Int) -> Unit,
        shouldContinue: () -> Boolean,
    ) {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            CHANNEL_CONFIG,
            ENCODING,
        )
        if (minBufferBytes <= 0) {
            throw CaptureException("Android mic does not support 16 kHz mono PCM")
        }

        val bufferSamples = max(minBufferBytes / BYTES_PER_SAMPLE, SAMPLE_RATE_HZ / 10)
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE_HZ)
                    .setChannelMask(CHANNEL_CONFIG)
                    .setEncoding(ENCODING)
                    .build()
            )
            .setBufferSizeInBytes(bufferSamples * BYTES_PER_SAMPLE)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw CaptureException("Android mic failed to initialize")
        }

        val pcm = ShortArray(bufferSamples)
        if (scratch.size < bufferSamples) {
            scratch = FloatArray(bufferSamples)
        }

        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw CaptureException("Android mic failed to start")
            }

            Log.i(TAG, "Android mic fallback started sampleRate=$SAMPLE_RATE_HZ encoding=PCM_16BIT")
            while (shouldContinue()) {
                when (val count = record.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)) {
                    AudioRecord.ERROR_INVALID_OPERATION ->
                        throw CaptureException("Android mic read invalid operation")
                    AudioRecord.ERROR_BAD_VALUE ->
                        throw CaptureException("Android mic read bad value")
                    AudioRecord.ERROR_DEAD_OBJECT ->
                        throw CaptureException("Android mic died")
                    AudioRecord.ERROR ->
                        throw CaptureException("Android mic read failed")
                    0 -> Thread.sleep(2)
                    else -> {
                        for (i in 0 until count) {
                            scratch[i] = pcm[i] / PCM16_FULL_SCALE
                        }
                        onPcm(scratch, count)
                    }
                }
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000

        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2
        private const val PCM16_FULL_SCALE = 32768f
        private const val TAG = "VuedAndroidMic"
    }
}
