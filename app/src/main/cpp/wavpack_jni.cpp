// JNI bridge: encode one segment's interleaved 16-bit PCM to a standalone
// WavPack (.wv) byte stream using the vendored libwavpack (portable C, no SIMD).
// Backs com.nsn8.vued.audio.WavPackEncoder.encode(...).
#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <vector>

extern "C" {
#include "wavpack.h"
}

#define LOG_TAG "WavPackEncoder"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct OutBuf {
    std::vector<uint8_t> data;
};

// libwavpack block-output callback: append encoded bytes to the growing buffer.
int block_out(void *id, void *data, int32_t bcount) {
    auto *ob = reinterpret_cast<OutBuf *>(id);
    if (bcount > 0 && data != nullptr) {
        auto *p = reinterpret_cast<uint8_t *>(data);
        ob->data.insert(ob->data.end(), p, p + bcount);
    }
    return 1;  // non-zero = success
}

}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_nsn8_vued_audio_WavPackEncoder_encode(
    JNIEnv *env, jobject /*thiz*/, jbyteArray pcm, jint channels, jint sampleRate) {
    const jbyteArray empty = env->NewByteArray(0);
    if (channels <= 0 || sampleRate <= 0 || pcm == nullptr) return empty;

    const jsize pcm_len = env->GetArrayLength(pcm);
    const int frame_bytes = channels * 2;  // 16-bit
    if (pcm_len <= 0 || (pcm_len % frame_bytes) != 0) return empty;
    const int64_t frames = pcm_len / frame_bytes;
    const size_t sample_count = static_cast<size_t>(frames) * channels;

    jbyte *pcm_bytes = env->GetByteArrayElements(pcm, nullptr);
    if (pcm_bytes == nullptr) return empty;

    // libwavpack packs int32 samples; sign-extend the 16-bit LE input.
    std::vector<int32_t> samples(sample_count);
    const auto *src = reinterpret_cast<const uint8_t *>(pcm_bytes);
    for (size_t i = 0; i < sample_count; ++i) {
        samples[i] = static_cast<int16_t>(src[2 * i] | (src[2 * i + 1] << 8));
    }
    env->ReleaseByteArrayElements(pcm, pcm_bytes, JNI_ABORT);

    OutBuf ob;
    WavpackContext *wpc = WavpackOpenFileOutput(block_out, &ob, nullptr);
    if (wpc == nullptr) {
        LOGE("WavpackOpenFileOutput failed");
        return empty;
    }

    WavpackConfig config;
    memset(&config, 0, sizeof(config));
    config.bytes_per_sample = 2;
    config.bits_per_sample = 16;
    config.num_channels = channels;
    // Non-standard 16-mic array: a full mask of `channels` bits keeps
    // num_channels consistent (speaker positions are irrelevant — decode is
    // interleaved PCM regardless).
    config.channel_mask = (channels >= 32) ? 0 : static_cast<int>((1u << channels) - 1);
    config.sample_rate = sampleRate;
    config.flags = CONFIG_FAST_FLAG;  // fast, still fully lossless

    jbyteArray result = nullptr;
    // total_samples is known, so the first-block header is written correctly and
    // no output seek (which the memory sink can't do) is needed.
    if (WavpackSetConfiguration64(wpc, &config, frames, nullptr) &&
        WavpackPackInit(wpc) &&
        WavpackPackSamples(wpc, samples.data(), static_cast<uint32_t>(frames)) &&
        WavpackFlushSamples(wpc)) {
        result = env->NewByteArray(static_cast<jsize>(ob.data.size()));
        if (result != nullptr && !ob.data.empty()) {
            env->SetByteArrayRegion(result, 0, static_cast<jsize>(ob.data.size()),
                                    reinterpret_cast<const jbyte *>(ob.data.data()));
        }
    } else {
        LOGE("wavpack encode failed: %s", WavpackGetErrorMessage(wpc));
    }

    WavpackCloseFile(wpc);
    return result != nullptr ? result : empty;
}
