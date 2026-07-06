package com.nsn8.vued.audio

/**
 * A deterministic, seekable byte layout of the source-audio object to upload.
 * The uploader reads arbitrary ranges so it can resume from the server's offset.
 * Implemented by [SourceWavStreamPlan] (raw PCM WAV) and [SourceWavContainerPlan]
 * (length-prefixed WavPack container).
 */
interface SourceUploadPlan {
    /** Total size of the object in bytes. */
    val totalBytes: Long

    /** Reads up to [maxBytes] starting at [fromOffset]; returns fewer at EOF. */
    fun readRange(fromOffset: Long, maxBytes: Int): ByteArray
}
