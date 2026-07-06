package com.nsn8.vued.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * Encodes one closed source segment's *trimmed* PCM to a cached WavPack `.wv`
 * blob. Uses the same trim math as [WavSegmentExporter] (head-trim the first
 * segment to `startMs`, tail-trim the last to `endMs`) so the compressed
 * container matches the intended meeting window. Blobs are cached by segment +
 * frame range, so repeated drains (and resume-from-offset) reuse identical
 * bytes and never re-encode.
 */
object SourceWavSegmentEncoder {

    fun encodeSegment(
        segment: MultiChannelWavRollingBuffer.Segment,
        startMs: Long,
        endMs: Long?,
        cacheDir: File,
    ): File? {
        val info = Pcm16WavSegmentWriter.readInfo(segment.file) ?: return null
        val startFrame = WavSegmentExporter.frameStart(startMs, segment.startMs, info.sampleRate, info.frameCount)
        val endFrame = WavSegmentExporter.frameEnd(endMs, segment.startMs, info.sampleRate, info.frameCount)
        if (endFrame <= startFrame) return null

        cacheDir.mkdirs()
        val out = File(cacheDir, "${segment.file.nameWithoutExtension}_${startFrame}_${endFrame}.wv")
        if (out.exists() && out.length() > 0) return out

        val blockAlign = info.blockAlign
        val pcm = ByteArray(((endFrame - startFrame) * blockAlign).toInt())
        RandomAccessFile(segment.file, "r").use { raf ->
            raf.seek(info.dataOffset + startFrame * blockAlign)
            raf.readFully(pcm)
        }
        val wv = WavPackEncoder.encode(pcm, info.channels, info.sampleRate)
        if (wv.isEmpty()) return null

        val tmp = File(cacheDir, out.name + ".tmp")
        tmp.writeBytes(wv)
        if (!tmp.renameTo(out)) {
            tmp.copyTo(out, overwrite = true)
            tmp.delete()
        }
        return out
    }
}
