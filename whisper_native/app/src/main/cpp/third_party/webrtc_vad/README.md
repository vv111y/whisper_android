# WebRTC VAD (optional vendored C implementation)

This app can use the real WebRTC Voice Activity Detector when the C sources are present here.

Expected files in this folder:
- `webrtc_vad.c`
- `webrtc_vad.h`

These are the standalone C sources for the WebRTC VAD (BSD 3‑Clause). You can obtain a minimal, C‑only copy derived from the Chromium WebRTC project. Place the two files here and rebuild.

Notes
- License: BSD 3‑Clause; ensure the upstream license is included (see `LICENSE` in this folder).
- API used by JNI:
  - `WebRtcVadInst* WebRtcVad_Create();`
  - `void WebRtcVad_Free(WebRtcVadInst*);`
  - `int WebRtcVad_Init(WebRtcVadInst*);`
  - `int WebRtcVad_set_mode(WebRtcVadInst*, int mode); // 0..3`
  - `int WebRtcVad_Process(WebRtcVadInst*, int fs, const int16_t* data, size_t len);`
- Supported sample rates: 8000, 16000, 32000, 48000 Hz. This app uses 16000 Hz with 20 ms frames (320 samples).

Build behavior
- If `webrtc_vad.c` exists here, the build defines `HAVE_WEBRTC_VAD` and links the native VAD (`webrtcvad` static lib) into `audioEngine`.
- If not present, the app falls back to the heuristic VAD inside `WebRtcVadJNI.cpp`.

Troubleshooting
- If you see unresolved symbols for WebRtcVad_*: verify the headers match the functions above and that `webrtc_vad.c/h` are actually here.
- If `WebRtcVad_Process` returns -1: ensure frame len is 10/20/30 ms at a supported sample rate. This app passes 320 samples @ 16k (20 ms).
