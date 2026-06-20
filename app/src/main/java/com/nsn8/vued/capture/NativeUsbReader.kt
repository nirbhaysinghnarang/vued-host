package com.nsn8.vued.capture

/**
 * JNI bridge to the native USB isochronous reader (libvuednative). The native layer
 * owns the URB ring and re-arms buffers so the stream never gaps; Kotlin just drains
 * deinterleaved PCM via [pollStream].
 */
internal object NativeUsbReader {
    init {
        System.loadLibrary("vuednative")
    }

    /** Submits the URB ring and returns an opaque handle (0 on failure). */
    external fun startStream(
        fd: Int,
        endpoint: Int,
        packetBytes: Int,
        packetsPerUrb: Int,
        urbCount: Int,
        frameBytes: Int,
        outChannels: Int,
    ): Long

    /**
     * Drains all completed URBs into [out] as interleaved [outChannels] int32 LE
     * samples (scaled to full 32-bit range). Returns bytes written, 0 if nothing was
     * ready, or -1 on a fatal error.
     */
    external fun pollStream(handle: Long, out: ByteArray): Int

    external fun stopStream(handle: Long)

    external fun streamError(handle: Long): String
}
