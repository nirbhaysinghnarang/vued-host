package com.nsn8.vued.audio

/**
 * Lossless WavPack encoder backed by native libwavpack (built into
 * `libvuednative.so`; see `app/src/main/cpp/wavpack_jni.cpp`). Mirrors the JNI
 * bridge pattern used by [com.nsn8.vued.capture.NativeUsbReader] /
 * [SpeakerVad].
 *
 * Encodes one segment's interleaved little-endian 16-bit PCM into a standalone
 * `.wv` byte stream (a complete WavPack file), which the server stores inside
 * the upload container and Modal decodes with ffmpeg.
 */
object WavPackEncoder {
    init {
        System.loadLibrary("vuednative")
    }

    /**
     * @param pcm interleaved 16-bit LE PCM, [channels] samples per frame
     * @return a complete WavPack (.wv) byte stream, or empty on failure
     */
    external fun encode(pcm: ByteArray, channels: Int, sampleRate: Int): ByteArray
}
