Got it. I recommend a two-tier optimization strategy: full ASR-in-the-loop offline tuning, plus a lightweight on-device calibration for user-specific adjustments.

Proposed approach
- Offline, comprehensive (CI/local workstation):
  - Dataset: short 16 kHz mono clips + ground-truth text in a JSONL manifest (audio, text, speaker/env tags).
  - Search space: VAD engine (energy/webrtc/silero) + VAD tunables + ASR model choice + Whisper decode params (beam size, temperature, patience, prompt bias, etc.).
  - Metrics: primary WER/CER; secondary latency/RTF, false triggers, missed onsets, utterance completeness, CPU/battery budget.
  - Scoring: weighted multi-objective with constraints (e.g., cap RTF/latency and penalize violations).
  - Output: best_config_asr.json with selected VAD engine, ASR model, and params; same import path as best_config.json plus ASR fields.
  - Where to run: connected tests (androidTest) for real engines; keep a unit-test mock for fast local iteration.

- On-device calibration (user-facing):
  - UX: show 3–6 prompt sentences; user reads them; we compare ASR to ground truth.
  - Search scope: local neighborhood around your current preset (few VAD tunables, mic gain, maybe decoding temperature/beam width). Keep runs sub-30s total.
  - Output: “User Profile” preset stored locally (SharedPreferences), applied like the “Auto” preset. No audio leaves device.
  - Model selection: fixed per build here (don’t swap large models in-app), but you can toggle VAD engine and a couple ASR decode params safely.

Contracts to make this clean
- Manifest (offline): one JSON object per line
  - audio: path or asset id
  - text: ground truth
  - tags: [“clean”, “noisy”, “farfield”, “m_speaker”]
  - snr/dbfs (optional), duration_ms (optional)
- Tuner output (best_config_asr.json):
  - vad: { engine: “silero|webrtc|energy”, params: {...} }
  - asr: { model: “tiny|base|…”, decode: { beamSize, temperature, patience, noRepeatNgram, suppressTokens, promptBias } }
  - score: { wer, cer, latencyMsP50/P95, rtf, falseTriggers, missed, composite }

Engineering plan
- Abstractions:
  - FrameSource (yields 320-sample frames + timestamps)
  - VadEngine (selectable impls)
  - AsrEngine (wrapper for your Whisper/ASR with pluggable decode params)
- Tests:
  - Unit: keep current tuner for speed; add a mock AsrEngine that returns text with controllable error/noise to validate scoring logic.
  - Instrumented: AsrEvaluationTest gated behind -DasrTune=true running small manifest (3–10 clips) through real VAD+ASR; writes best_config_asr.json.
- Gradle/CI:
  - New task: tuneAsr → runs connected tests with -DasrTune=true and uploads best_config_asr.json.
  - Nightly CI job optional; keep dataset small to avoid long emulator runs.
- App UX:
  - New CalibrationFragment (debug → later user-facing): prompt list, record, score, short search, save “User Profile” preset.
  - Reuse Diagnostics import/apply for applying ASR+VAD config.

Trade-offs
- Offline tuning yields best coverage (bigger search, reproducible). Emulator ASR can be slow—keep manifest tiny.
- On-device calibration should be constrained to fast-safe params to avoid battery drain and long waits.
