package com.nsn8.vued.audio

import android.content.Context
import android.util.Log
import com.nsn8.vued.DiagnosticsLogger
import com.nsn8.vued.capture.Uma8Capture
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * Optional meeting-scoped diagnostic tap for inspecting the exact audio artifacts
 * produced before a meeting slice is uploaded.
 */
object AudioPipelineDebugCapture {

    private const val TAG = "VuedAudioDebug"
    private const val ROOT_DIR = "audio_pipeline_debug"
    private const val WAV_FORMAT_PCM = 1
    private const val WAV_FORMAT_IEEE_FLOAT = 3

    private val lock = Any()
    private var active: Session? = null
    private val completedDirs = LinkedHashMap<String, File>()

    val isActive: Boolean
        get() = active != null

    fun start(context: Context, meetingId: String, sliceId: String, startMs: Long) {
        val root = context.getExternalFilesDir(null)?.let { File(it, ROOT_DIR) }
            ?: File(context.filesDir, ROOT_DIR)
        val dir = File(root, "meeting_$meetingId").apply { mkdirs() }
        synchronized(lock) {
            active?.finish()
            active = Session(dir, meetingId, sliceId, startMs)
            completedDirs.remove(meetingId)
        }
        Log.i(TAG, "debug capture started meeting=$meetingId dir=${dir.absolutePath}")
        DiagnosticsLogger.info(
            "audio_debug_capture_started",
            mapOf("meetingId" to meetingId, "sliceId" to sliceId, "dir" to dir.absolutePath),
        )
    }

    fun finishCapture(meetingId: String, endMs: Long) {
        val session = synchronized(lock) {
            active?.takeIf { it.meetingId == meetingId }?.also {
                active = null
                completedDirs[meetingId] = it.dir
                trimCompletedDirs()
            }
        } ?: return
        session.finish(endMs)
        Log.i(TAG, "debug capture finished meeting=$meetingId dir=${session.dir.absolutePath}")
        DiagnosticsLogger.info(
            "audio_debug_capture_finished",
            mapOf("meetingId" to meetingId, "dir" to session.dir.absolutePath),
        )
    }

    fun copyCompressedMeeting(meetingId: String, source: File) {
        val dir = synchronized(lock) {
            active?.takeIf { it.meetingId == meetingId }?.dir ?: completedDirs[meetingId]
        } ?: return
        runCatching {
            source.copyTo(File(dir, "05_compressed_upload_m4a.m4a"), overwrite = true)
        }.onSuccess {
            Log.i(TAG, "debug compressed copy meeting=$meetingId file=${it.absolutePath}")
        }.onFailure { error ->
            Log.w(TAG, "debug compressed copy failed meeting=$meetingId: ${error.message}", error)
            DiagnosticsLogger.warn("audio_debug_compressed_copy_failed", mapOf("meetingId" to meetingId), error)
        }
    }

    fun recordRawInterleavedInt32(buffer: ByteArray, length: Int, channels: Int) {
        current()?.recordRawInterleavedInt32(buffer, length, channels)
    }

    fun recordAveraged48k(samples: FloatArray, count: Int) {
        current()?.recordAveraged48k(samples, count)
    }

    fun recordGained48k(samples: FloatArray, count: Int) {
        current()?.recordGained48k(samples, count)
    }

    fun recordDownsampled16k(samples: FloatArray, count: Int) {
        current()?.recordDownsampled16k(samples, count)
    }

    fun recordPcm16(samples: ShortArray, count: Int) {
        current()?.recordPcm16(samples, count)
    }

    private fun current(): Session? = active

    private fun trimCompletedDirs() {
        while (completedDirs.size > 8) {
            val firstKey = completedDirs.keys.firstOrNull() ?: return
            completedDirs.remove(firstKey)
        }
    }

    private class Session(
        val dir: File,
        val meetingId: String,
        private val sliceId: String,
        private val startMs: Long,
    ) {
        private var rawChannels = 0
        private var rawWriter: WavWriter? = null
        private val averagedWriter = WavWriter(
            File(dir, "01_averaged_48k_mono_f32.wav"),
            sampleRate = Uma8Capture.SAMPLE_RATE_HZ,
            channels = 1,
            bitsPerSample = 32,
            audioFormat = WAV_FORMAT_IEEE_FLOAT,
        )
        private val gainedWriter = WavWriter(
            File(dir, "02_gained_48k_mono_f32.wav"),
            sampleRate = Uma8Capture.SAMPLE_RATE_HZ,
            channels = 1,
            bitsPerSample = 32,
            audioFormat = WAV_FORMAT_IEEE_FLOAT,
        )
        private val downsampledWriter = WavWriter(
            File(dir, "03_downsampled_gained_16k_mono_f32.wav"),
            sampleRate = 16_000,
            channels = 1,
            bitsPerSample = 32,
            audioFormat = WAV_FORMAT_IEEE_FLOAT,
        )
        private val pcm16Writer = WavWriter(
            File(dir, "04_pcm16_encoder_input_16k_mono.wav"),
            sampleRate = 16_000,
            channels = 1,
            bitsPerSample = 16,
            audioFormat = WAV_FORMAT_PCM,
        )

        init {
            writeManifest(endMs = null)
        }

        fun recordRawInterleavedInt32(buffer: ByteArray, length: Int, channels: Int) = synchronized(this) {
            if (channels <= 0 || length <= 0) return@synchronized
            if (rawWriter == null || rawChannels != channels) {
                rawWriter?.finish()
                rawChannels = channels
                rawWriter = WavWriter(
                    File(dir, "00_uma_raw_48k_${channels}ch_s32le.wav"),
                    sampleRate = Uma8Capture.SAMPLE_RATE_HZ,
                    channels = channels,
                    bitsPerSample = 32,
                    audioFormat = WAV_FORMAT_PCM,
                )
                writeManifest(endMs = null)
            }
            val bytesPerFrame = channels * 4
            val alignedLength = length - (length % bytesPerFrame)
            rawWriter?.writeBytes(buffer, alignedLength)
        }

        fun recordAveraged48k(samples: FloatArray, count: Int) = synchronized(this) {
            averagedWriter.writeFloat32(samples, count)
        }

        fun recordGained48k(samples: FloatArray, count: Int) = synchronized(this) {
            gainedWriter.writeFloat32(samples, count)
        }

        fun recordDownsampled16k(samples: FloatArray, count: Int) = synchronized(this) {
            downsampledWriter.writeFloat32(samples, count)
        }

        fun recordPcm16(samples: ShortArray, count: Int) = synchronized(this) {
            pcm16Writer.writeInt16(samples, count)
        }

        fun finish(endMs: Long? = null) = synchronized(this) {
            rawWriter?.finish()
            averagedWriter.finish()
            gainedWriter.finish()
            downsampledWriter.finish()
            pcm16Writer.finish()
            writeManifest(endMs)
        }

        private fun writeManifest(endMs: Long?) {
            val lines = buildString {
                appendLine("meetingId=$meetingId")
                appendLine("sliceId=$sliceId")
                appendLine("startMs=$startMs")
                if (endMs != null) appendLine("endMs=$endMs")
                appendLine("inputSampleRateHz=${Uma8Capture.SAMPLE_RATE_HZ}")
                if (rawChannels > 0) appendLine("rawChannels=$rawChannels")
                appendLine("makeupGain=${String.format(Locale.US, "%.1f", Downmixer.MAKEUP_GAIN)}")
                appendLine()
                appendLine("00_uma_raw_48k_${if (rawChannels > 0) rawChannels else "N"}ch_s32le.wav")
                appendLine("01_averaged_48k_mono_f32.wav")
                appendLine("02_gained_48k_mono_f32.wav")
                appendLine("03_downsampled_gained_16k_mono_f32.wav")
                appendLine("04_pcm16_encoder_input_16k_mono.wav")
                appendLine("05_compressed_upload_m4a.m4a")
            }
            File(dir, "manifest.txt").writeText(lines)
        }
    }

    private class WavWriter(
        private val file: File,
        private val sampleRate: Int,
        private val channels: Int,
        private val bitsPerSample: Int,
        private val audioFormat: Int,
    ) {
        private val out = RandomAccessFile(file, "rw")
        private var dataBytes = 0L
        private var finished = false

        init {
            file.parentFile?.mkdirs()
            out.setLength(0)
            writeHeader(dataBytes = 0)
        }

        fun writeBytes(buffer: ByteArray, length: Int) {
            if (finished || length <= 0) return
            out.write(buffer, 0, length)
            dataBytes += length
        }

        fun writeFloat32(samples: FloatArray, count: Int) {
            if (finished || count <= 0) return
            for (i in 0 until count) writeLe32(java.lang.Float.floatToRawIntBits(samples[i]))
            dataBytes += count * 4L
        }

        fun writeInt16(samples: ShortArray, count: Int) {
            if (finished || count <= 0) return
            for (i in 0 until count) writeLe16(samples[i].toInt())
            dataBytes += count * 2L
        }

        fun finish() {
            if (finished) return
            finished = true
            out.seek(0)
            writeHeader(dataBytes)
            out.close()
        }

        private fun writeHeader(dataBytes: Long) {
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8
            out.writeBytes("RIFF")
            writeLe32((36L + dataBytes).coerceAtMost(UINT32_MAX).toInt())
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            writeLe32(16)
            writeLe16(audioFormat)
            writeLe16(channels)
            writeLe32(sampleRate)
            writeLe32(byteRate)
            writeLe16(blockAlign)
            writeLe16(bitsPerSample)
            out.writeBytes("data")
            writeLe32(dataBytes.coerceAtMost(UINT32_MAX).toInt())
        }

        private fun writeLe16(value: Int) {
            out.write(value and 0xff)
            out.write((value ushr 8) and 0xff)
        }

        private fun writeLe32(value: Int) {
            out.write(value and 0xff)
            out.write((value ushr 8) and 0xff)
            out.write((value ushr 16) and 0xff)
            out.write((value ushr 24) and 0xff)
        }

        private companion object {
            const val UINT32_MAX = 0xffff_ffffL
        }
    }
}
