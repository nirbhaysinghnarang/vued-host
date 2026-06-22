// JNI bridge to the TEN VAD native library, mirroring the iOS client's dlopen
// approach. We don't link ten_vad at build time — we dlopen "libten_vad.so" at
// runtime and resolve its C symbols. If the .so isn't bundled (jniLibs/<abi>/),
// create returns 0 and the Kotlin side falls back to the energy/ZCR gate — same
// graceful degradation as iOS.
//
// ten_vad C API (matches the iOS framework symbols):
//   int ten_vad_create(void** handle, size_t hop_size, float threshold);
//   int ten_vad_process(void* handle, const int16_t* audio, size_t len,
//                       float* out_probability, int32_t* out_flag);
//   int ten_vad_destroy(void** handle);

#include <jni.h>
#include <dlfcn.h>
#include <cstdint>
#include <cstddef>

namespace {

typedef int (*CreateFn)(void**, size_t, float);
typedef int (*ProcessFn)(void*, const int16_t*, size_t, float*, int32_t*);
typedef int (*DestroyFn)(void**);

struct TenVadApi {
    void* lib = nullptr;
    CreateFn create = nullptr;
    ProcessFn process = nullptr;
    DestroyFn destroy = nullptr;
    bool ok() const { return create && process && destroy; }
};

// Loaded once; ten_vad is a plain shared lib bundled under jniLibs.
TenVadApi& api() {
    static TenVadApi a = [] {
        TenVadApi t;
        t.lib = dlopen("libten_vad.so", RTLD_NOW | RTLD_LOCAL);
        if (!t.lib) return t;
        t.create = reinterpret_cast<CreateFn>(dlsym(t.lib, "ten_vad_create"));
        t.process = reinterpret_cast<ProcessFn>(dlsym(t.lib, "ten_vad_process"));
        t.destroy = reinterpret_cast<DestroyFn>(dlsym(t.lib, "ten_vad_destroy"));
        return t;
    }();
    return a;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_nsn8_vued_audio_TenVad_nativeCreate(JNIEnv*, jobject, jint hopSize, jfloat threshold) {
    TenVadApi& a = api();
    if (!a.ok()) return 0;
    void* handle = nullptr;
    if (a.create(&handle, static_cast<size_t>(hopSize), threshold) != 0 || handle == nullptr) {
        return 0;
    }
    return reinterpret_cast<jlong>(handle);
}

// Returns the speech probability in [0,1], or -1 on error.
JNIEXPORT jfloat JNICALL
Java_com_nsn8_vued_audio_TenVad_nativeProcess(JNIEnv* env, jobject, jlong handle,
                                              jshortArray pcm, jint len) {
    TenVadApi& a = api();
    if (!a.ok() || handle == 0) return -1.0f;
    jshort* samples = env->GetShortArrayElements(pcm, nullptr);
    if (!samples) return -1.0f;
    float probability = 0.0f;
    int32_t flag = 0;
    int rc = a.process(reinterpret_cast<void*>(handle),
                       reinterpret_cast<const int16_t*>(samples),
                       static_cast<size_t>(len), &probability, &flag);
    env->ReleaseShortArrayElements(pcm, samples, JNI_ABORT);
    return rc == 0 ? probability : -1.0f;
}

JNIEXPORT void JNICALL
Java_com_nsn8_vued_audio_TenVad_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    TenVadApi& a = api();
    if (!a.ok() || handle == 0) return;
    void* h = reinterpret_cast<void*>(handle);
    a.destroy(&h);
}

}  // extern "C"
