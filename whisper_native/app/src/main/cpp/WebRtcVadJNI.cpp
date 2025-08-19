#include <jni.h>
#include <cstdint>
#include <cmath>
#include <vector>

#ifdef HAVE_WEBRTC_VAD
// When vendored, the standalone WebRTC VAD exposes this header
// Typical API (BSD 3‑Clause):
//   WebRtcVadInst* WebRtcVad_Create();
//   void WebRtcVad_Free(WebRtcVadInst*);
//   int WebRtcVad_Init(WebRtcVadInst*);
//   int WebRtcVad_set_mode(WebRtcVadInst*, int mode); // 0..3
//   int WebRtcVad_Process(WebRtcVadInst*, int fs, const int16_t* data, size_t len);
extern "C" {
#include "webrtc_vad.h"
}
#endif

// VAD state: real WebRTC VAD when available, else heuristic fallback
struct JniVadState {
#ifdef HAVE_WEBRTC_VAD
    WebRtcVadInst* inst = nullptr;
    int mode = 2;
#else
    int mode = 2;            // 0..3 (aggressiveness)
    float threshold = 0.035f; // RMS threshold fallback (maps to mode)
#endif
};

static inline float compute_rms(const int16_t* data, int len) {
    double acc = 0.0;
    for (int i = 0; i < len; ++i) {
        double v = data[i] / 32768.0;
        acc += v * v;
    }
    return (float)std::sqrt(acc / (double)(len > 0 ? len : 1));
}

static inline float compute_zcr(const int16_t* data, int len) {
    if (len <= 1) return 0.0f;
    int zc = 0;
    int16_t prev = data[0];
    for (int i = 1; i < len; ++i) {
        int16_t v = data[i];
        if ((v >= 0 && prev < 0) || (v < 0 && prev >= 0)) ++zc;
        prev = v;
    }
    return (float)zc / (float)(len - 1);
}

extern "C" {

// Methods for class com.whispertflite.frontend.VadWebRtcNative
JNIEXPORT jlong JNICALL
Java_com_whispertflite_frontend_VadWebRtcNative_nativeCreate(JNIEnv*, jclass, jint mode) {
    auto* st = new JniVadState();
    st->mode = (mode < 0 ? 0 : (mode > 3 ? 3 : mode));
#ifdef HAVE_WEBRTC_VAD
    st->inst = WebRtcVad_Create();
    if (st->inst) {
        if (WebRtcVad_Init(st->inst) != 0) {
            WebRtcVad_Free(st->inst); st->inst = nullptr;
        } else {
            (void)WebRtcVad_set_mode(st->inst, st->mode);
        }
    }
#else
    // Map mode to a heuristic RMS threshold in fallback
    static const float map[4] = {0.025f, 0.030f, 0.035f, 0.045f};
    st->threshold = map[st->mode];
#endif
    return reinterpret_cast<jlong>(st);
}

JNIEXPORT void JNICALL
Java_com_whispertflite_frontend_VadWebRtcNative_nativeRelease(JNIEnv*, jclass, jlong handle) {
    auto* st = reinterpret_cast<JniVadState*>(handle);
    if (!st) return;
#ifdef HAVE_WEBRTC_VAD
    if (st->inst) { WebRtcVad_Free(st->inst); st->inst = nullptr; }
#endif
    delete st;
}

JNIEXPORT void JNICALL
Java_com_whispertflite_frontend_VadWebRtcNative_nativeSetMode(JNIEnv*, jclass, jlong handle, jint mode) {
    auto* st = reinterpret_cast<JniVadState*>(handle);
    if (!st) return;
    st->mode = (mode < 0 ? 0 : (mode > 3 ? 3 : mode));
#ifdef HAVE_WEBRTC_VAD
    if (st->inst) (void)WebRtcVad_set_mode(st->inst, st->mode);
#else
    static const float map[4] = {0.025f, 0.030f, 0.035f, 0.045f};
    st->threshold = map[st->mode];
#endif
}

JNIEXPORT void JNICALL
Java_com_whispertflite_frontend_VadWebRtcNative_nativeSetThreshold(JNIEnv*, jclass, jlong handle, jfloat thr) {
    auto* st = reinterpret_cast<JniVadState*>(handle);
    if (!st) return;
#ifdef HAVE_WEBRTC_VAD
    (void)handle; (void)thr; // not applicable to WebRTC VAD; ignore
#else
    if (thr < 0.001f) thr = 0.001f;
    st->threshold = thr;
#endif
}

JNIEXPORT jint JNICALL
Java_com_whispertflite_frontend_VadWebRtcNative_nativeProcess(JNIEnv* env, jclass, jlong handle,
                                                              jshortArray frame,
                                                              jint sampleRate,
                                                              jint frameLen) {
    auto* st = reinterpret_cast<JniVadState*>(handle);
    if (!st || !frame) return 0;
    jsize n = env->GetArrayLength(frame);
    if (n <= 0) return 0;
    std::vector<int16_t> buf((size_t)n);
    env->GetShortArrayRegion(frame, 0, n, buf.data());
#ifdef HAVE_WEBRTC_VAD
    if (!st->inst) return 0;
    // WebRTC VAD expects 10/20/30ms windows; here frameLen is passed from Java (e.g., 320 @16k = 20ms)
    int decision = WebRtcVad_Process(st->inst, (int)sampleRate, buf.data(), (size_t)frameLen);
    // WebRtcVad_Process returns 1 (speech), 0 (non-speech), -1 (error)
    if (decision < 0) return 0;
    return decision == 1 ? 1 : 0;
#else
    // Optional: enforce typical VAD frame sizes (10/20/30ms). We accept any here.
    (void)sampleRate; (void)frameLen;
    float rms = compute_rms(buf.data(), (int)n);
    float zcr = compute_zcr(buf.data(), (int)n);
    // Heuristic decision approximating a VAD: energy AND plausible ZCR window
    const float zcrMin = 0.01f;
    const float zcrMax = 0.25f;
    bool speech = (rms >= st->threshold) && (zcr >= zcrMin && zcr <= zcrMax);
    return speech ? 1 : 0;
#endif
}

}
