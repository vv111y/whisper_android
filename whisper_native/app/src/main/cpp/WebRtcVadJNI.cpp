#include <jni.h>
#include <cstdint>
#include <cmath>
#include <vector>
#include <android/log.h>

#define LOG_TAG "WebRtcVadJNI"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

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

static inline bool is_valid_frame_params(int sampleRate, int frameLen) {
    if (sampleRate != 16000) return false;
    return frameLen == 160 || frameLen == 320 || frameLen == 480; // 10/20/30ms at 16k
}

static inline int nearest_supported_len(int want) {
    const int opts[3] = {160, 320, 480};
    int best = opts[0];
    int bestd = std::abs(want - opts[0]);
    for (int i = 1; i < 3; ++i) {
        int d = std::abs(want - opts[i]);
        if (d < bestd) { bestd = d; best = opts[i]; }
    }
    return best;
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
    // Decide effective frame length to use (adapt if needed)
    static bool adaptWarned = false;
    int effLen = (int)n;
    if (!is_valid_frame_params((int)sampleRate, (int)frameLen) || frameLen != n) {
        int target = nearest_supported_len((int)std::min((jsize)frameLen, n));
        if (target <= n) {
            effLen = target;
            if (!adaptWarned) { adaptWarned = true; LOGW("Adapting VAD frame len from n=%d, frameLen=%d to %d", (int)n, (int)frameLen, effLen); }
        }
    }
#ifdef HAVE_WEBRTC_VAD
    if (!st->inst) return 0;
    // WebRTC VAD expects 10/20/30ms windows at 8/16/32k. We use 16k, 10/20/30ms (160/320/480).
    static bool warned = false;
    if (!is_valid_frame_params((int)sampleRate, effLen) && !warned) {
        warned = true;
        LOGW("Unexpected VAD params: sampleRate=%d, effLen=%d (expected 16000 Hz, 160/320/480)", (int)sampleRate, effLen);
    }
    int decision = WebRtcVad_Process(st->inst, (int)sampleRate, buf.data(), (size_t)effLen);
    // WebRtcVad_Process returns 1 (speech), 0 (non-speech), -1 (error)
    if (decision < 0) return 0;
    return decision == 1 ? 1 : 0;
#else
    // Optional: enforce typical VAD frame sizes (10/20/30ms). We accept any here.
    static bool warned = false;
    if (!is_valid_frame_params((int)sampleRate, effLen) && !warned) {
        warned = true;
        LOGW("Fallback VAD unexpected params: sampleRate=%d, effLen=%d", (int)sampleRate, effLen);
    }
    float rms = compute_rms(buf.data(), (int)effLen);
    float zcr = compute_zcr(buf.data(), (int)effLen);
    // Heuristic decision approximating a VAD: energy AND plausible ZCR window
    const float zcrMin = 0.01f;
    const float zcrMax = 0.25f;
    bool speech = (rms >= st->threshold) && (zcr >= zcrMin && zcr <= zcrMax);
    return speech ? 1 : 0;
#endif
}

JNIEXPORT jboolean JNICALL
Java_com_whispertflite_frontend_VadWebRtcNative_nativeSelfTest(JNIEnv*, jclass) {
#ifdef HAVE_WEBRTC_VAD
    WebRtcVadInst* inst = WebRtcVad_Create();
    if (!inst) return JNI_FALSE;
    bool ok = WebRtcVad_Init(inst) == 0 && WebRtcVad_set_mode(inst, 2) == 0;
    // prepare buffers
    const int fs = 16000;
    const int len = 320;
    std::vector<int16_t> silence(len, 0);
    std::vector<int16_t> tone(len);
    for (int i = 0; i < len; ++i) tone[i] = (int16_t)std::round(std::sin(2 * M_PI * i / 20.0) * 3276.7); // ~0.1
    if (ok) {
        int d0 = WebRtcVad_Process(inst, fs, silence.data(), len);
        int d1 = WebRtcVad_Process(inst, fs, tone.data(), len);
        ok = (d0 == 0 || d0 == 1) && (d1 == 0 || d1 == 1);
    }
    WebRtcVad_Free(inst);
    return ok ? JNI_TRUE : JNI_FALSE;
#else
    // Test fallback path: ensure computations run and return 0/1.
    const int len = 320;
    std::vector<int16_t> silence(len, 0);
    std::vector<int16_t> tone(len);
    for (int i = 0; i < len; ++i) tone[i] = (int16_t)std::round(std::sin(2 * M_PI * i / 20.0) * 3276.7);
    float rms0 = compute_rms(silence.data(), len);
    float zcr0 = compute_zcr(silence.data(), len);
    float rms1 = compute_rms(tone.data(), len);
    float zcr1 = compute_zcr(tone.data(), len);
    // Basic sanity: silence rms lower than tone; zcr values in [0,1]
    bool ok = (rms0 < rms1) && (zcr0 >= 0.f && zcr0 <= 1.f) && (zcr1 >= 0.f && zcr1 <= 1.f);
    return ok ? JNI_TRUE : JNI_FALSE;
#endif
}

}
