package com.nsn8.vued.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * Byte layout of the compressed source object: a length-prefixed container of
 * per-segment WavPack blobs —
 *
 * ```
 * [MAGIC "VWV1"] then repeated: [u32 LE blob_len][wavpack blob]
 * ```
 *
 * Each blob is a standalone `.wv` file (one closed, trimmed 30s segment). The
 * layout is deterministic from the on-disk blob files, so [readRange] can serve
 * any offset and the streaming uploader can resume from the server's cursor —
 * exactly like [SourceWavStreamPlan] does for raw PCM. Modal splits on the
 * length prefixes and ffmpeg-decodes each blob.
 */
class SourceWavContainerPlan private constructor(
    private val entries: List<Entry>,
    override val totalBytes: Long,
) : SourceUploadPlan {

    /** One blob: a 4-byte LE length prefix at [prefixStart] then the file at [blobStart]. */
    private class Entry(val file: File, val blobLen: Long, val prefixStart: Long, val blobStart: Long)

    override fun readRange(fromOffset: Long, maxBytes: Int): ByteArray {
        if (fromOffset < 0 || fromOffset >= totalBytes || maxBytes <= 0) return ByteArray(0)
        val end = minOf(totalBytes, fromOffset + maxBytes)
        val out = ByteArray((end - fromOffset).toInt())
        var written = 0
        var pos = fromOffset

        if (pos < MAGIC.size) {
            val n = (minOf(MAGIC.size.toLong(), end) - pos).toInt()
            System.arraycopy(MAGIC, pos.toInt(), out, written, n)
            written += n
            pos += n
        }
        for (entry in entries) {
            if (pos >= end) break
            // length-prefix region
            val prefixEnd = entry.prefixStart + 4
            if (pos < prefixEnd && pos >= entry.prefixStart) {
                val prefix = leU32(entry.blobLen)
                val within = (pos - entry.prefixStart).toInt()
                val n = (minOf(prefixEnd, end) - pos).toInt()
                System.arraycopy(prefix, within, out, written, n)
                written += n
                pos += n
                if (pos >= end) break
            }
            // blob region
            val blobEnd = entry.blobStart + entry.blobLen
            if (pos in entry.blobStart until blobEnd) {
                val readFrom = pos - entry.blobStart
                val n = (minOf(blobEnd, end) - pos).toInt()
                RandomAccessFile(entry.file, "r").use { raf ->
                    raf.seek(readFrom)
                    raf.readFully(out, written, n)
                }
                written += n
                pos += n
            }
        }
        return if (written == out.size) out else out.copyOf(written)
    }

    companion object {
        val MAGIC = byteArrayOf('V'.code.toByte(), 'W'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())

        /** Builds the container over the ordered [blobFiles] (one `.wv` per segment). */
        fun build(blobFiles: List<File>): SourceWavContainerPlan {
            val entries = ArrayList<Entry>(blobFiles.size)
            var pos = MAGIC.size.toLong()
            for (file in blobFiles) {
                val len = file.length()
                if (len <= 0) continue
                val prefixStart = pos
                val blobStart = pos + 4
                entries.add(Entry(file, len, prefixStart, blobStart))
                pos = blobStart + len
            }
            return SourceWavContainerPlan(entries, pos)
        }

        private fun leU32(value: Long): ByteArray = byteArrayOf(
            (value and 0xff).toByte(),
            ((value ushr 8) and 0xff).toByte(),
            ((value ushr 16) and 0xff).toByte(),
            ((value ushr 24) and 0xff).toByte(),
        )
    }
}
