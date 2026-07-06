package com.nsn8.vued.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * Incremental PCM16 WAV writer. The RIFF and data sizes are patched on finish,
 * so callers can stream sample data as it arrives from capture.
 */
class Pcm16WavSegmentWriter(
    outputFile: File,
    val sampleRate: Int,
    val channels: Int,
) : Closeable {

    private val bytesPerSample = 2
    private val blockAlign = channels * bytesPerSample
    private val byteRate = sampleRate * blockAlign
    private val file = RandomAccessFile(outputFile.apply { parentFile?.mkdirs() }, "rw")
    private var dataBytes = 0L
    private var finished = false

    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channels > 0) { "channels must be positive" }
        file.setLength(0)
        writeHeader(dataSize = 0)
    }

    fun writeInterleaved(samples: ShortArray, frames: Int) {
        if (finished || frames <= 0) return
        val sampleCount = frames * channels
        require(sampleCount <= samples.size) { "not enough samples for $frames frames" }
        val bytes = ByteArray(sampleCount * bytesPerSample)
        var out = 0
        for (i in 0 until sampleCount) {
            val value = samples[i].toInt()
            bytes[out++] = (value and 0xff).toByte()
            bytes[out++] = ((value ushr 8) and 0xff).toByte()
        }
        writePcmBytes(bytes, bytes.size)
    }

    fun writePcmBytes(bytes: ByteArray, length: Int) {
        if (finished || length <= 0) return
        require(length <= bytes.size) { "length exceeds byte array size" }
        require(length % blockAlign == 0) { "PCM byte count must align to whole frames" }
        file.write(bytes, 0, length)
        dataBytes += length.toLong()
    }

    fun finish() {
        if (finished) return
        finished = true
        try {
            require(36L + dataBytes <= UINT32_MAX) { "WAV file exceeds 4 GiB RIFF limit" }
            file.seek(0)
            writeHeader(dataBytes)
        } finally {
            file.close()
        }
    }

    override fun close() = finish()

    data class Info(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataBytes: Long,
        val dataOffset: Long = 44L,
    ) {
        val blockAlign: Int get() = channels * bitsPerSample / 8
        val frameCount: Long get() = if (blockAlign > 0) dataBytes / blockAlign else 0
    }

    private fun writeHeader(dataSize: Long) {
        writeAscii("RIFF")
        writeLe32(36L + dataSize)
        writeAscii("WAVE")
        writeAscii("fmt ")
        writeLe32(16)
        writeLe16(1) // PCM
        writeLe16(channels)
        writeLe32(sampleRate.toLong())
        writeLe32(byteRate.toLong())
        writeLe16(blockAlign)
        writeLe16(16)
        writeAscii("data")
        writeLe32(dataSize)
    }

    private fun writeAscii(value: String) {
        file.write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun writeLe16(value: Int) {
        file.write(value and 0xff)
        file.write((value ushr 8) and 0xff)
    }

    private fun writeLe32(value: Long) {
        file.write((value and 0xff).toInt())
        file.write(((value ushr 8) and 0xff).toInt())
        file.write(((value ushr 16) and 0xff).toInt())
        file.write(((value ushr 24) and 0xff).toInt())
    }

    companion object {
        const val HEADER_BYTES = 44
        private const val UINT32_MAX = 0xffff_ffffL

        fun readInfo(file: File): Info? {
            if (!file.exists() || file.length() < HEADER_BYTES) return null
            val header = ByteArray(HEADER_BYTES)
            RandomAccessFile(file, "r").use { it.readFully(header) }
            if (ascii(header, 0, 4) != "RIFF") return null
            if (ascii(header, 8, 4) != "WAVE") return null
            if (ascii(header, 12, 4) != "fmt ") return null
            if (ascii(header, 36, 4) != "data") return null
            val audioFormat = le16(header, 20)
            if (audioFormat != 1) return null
            return Info(
                sampleRate = le32(header, 24).toInt(),
                channels = le16(header, 22),
                bitsPerSample = le16(header, 34),
                dataBytes = le32(header, 40),
            )
        }

        private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
            String(bytes, offset, length, Charsets.US_ASCII)

        private fun le16(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8)

        private fun le32(bytes: ByteArray, offset: Int): Long =
            (bytes[offset].toLong() and 0xff) or
                ((bytes[offset + 1].toLong() and 0xff) shl 8) or
                ((bytes[offset + 2].toLong() and 0xff) shl 16) or
                ((bytes[offset + 3].toLong() and 0xff) shl 24)
    }
}
