<!--
  docs/ASR_TUNING_Milestone.md
  A living plan for ASR + VAD tuning: offline auto-optimization and in-app calibration.
-->
# ASR + VAD Tuning Milestone

This document tracks a two-tier optimization strategy for both offline ASR-in-the-loop tuning and on-device calibration. Use it to coordinate tasks, track progress, and capture decisions.

## Table of Contents
- [Goals](#goals)
- [Offline Comprehensive Tuning](#offline-comprehensive-tuning)
- [On-Device Calibration](#on-device-calibration)
- [Data Manifest Schema](#data-manifest-schema)
- [Output Configuration Schema](#output-configuration-schema)
- [Engineering Plan](#engineering-plan)
- [Testing Strategy](#testing-strategy)
- [Gradle & CI](#gradle--ci)
- [App UX & Calibration Flow](#app-ux--calibration-flow)
- [Trade-offs](#trade-offs)
- [Next Steps](#next-steps)

## Goals
- Automate selection of optimal VAD and ASR parameters for target hardware and environment.
- Provide a lightweight in-app calibration for end users to fine-tune on their own voice/hardware.
- Track configuration artifacts and ensure reproducible, versioned tuning outputs.

## Offline Comprehensive Tuning
Run on CI or a local workstation using small test recordings and ground-truth transcripts.

**Dataset**: short 16 kHz mono clips + JSONL manifest with fields: `audio`, `text`, `tags`, optional SNR/duration.

**Search space**:
- VAD engine: `energy` | `webrtc` | `silero`
- VAD tunables: pre-roll, merge window, min utterance, required silence, arming/cooldown delays.
- ASR model: `tiny`, `base`, `small`, quantized variants.
- Whisper decode params: beam size, temperature, patience, noRepeatNgram, suppressTokens, promptBias.

**Metrics**:
- Primary: WER (Word Error Rate), CER (Character Error Rate)
- Secondary: latency (P50/P95), real-time factor (RTF), false triggers, missed onsets, completeness, CPU/battery impact.

**Scoring**: multi-objective weighted sum with hard constraints (e.g., RTF < 1.0, latency < 500 ms).

## On-Device Calibration
Expose a quick calibration flow in-app (Debug → User build later) that:

- Presents 3–6 prompts for the user to read.
- Records audio and compares ASR output against known text.
- Searches a narrow parameter neighborhood around the offline preset (VAD engine choice, key decode knobs).
- Stores a `User Profile` preset in SharedPreferences, layered on top of the offline config.

Model selection remains fixed per build; in-app tuning only adjusts lightweight parameters.

## Data Manifest Schema
Each line in `tuning_manifest.jsonl`:
```json
{
  "audio": "history/clip1.wav",
  "text": "Hello world",
  "tags": ["clean", "speaker1"],
  "snr": 25.0,
  "duration_ms": 1200
}
```

## Output Configuration Schema
Output file `best_config_asr.json`:
```json
{
  "vad": {
    "engine": "silero",
    "params": { /* preRoll, mergeWin, ... */ }
  },
  "asr": {
    "model": "base",
    "quant": "int8",
    "decode": { /* beamSize, temperature, ... */ }
  },
  "score": {
    "wer": 0.12,
    "latencyP95": 450,
    "rtf": 0.8
  }
}
```

## Engineering Plan
- Define abstractions: `FrameSource`, `VadEngine`, `AsrEngine`.
- Parameterize `PipelineAutoTuningTest` and add `AsrEvaluationTest` (skipped by default) for offline tuning.
- Implement Gradle task `tuneAsr` to invoke connected tests with `-DasrTune=true` and persist `best_config_asr.json`.

## Testing Strategy
- **Unit tests**: mock `AsrEngine` to validate scoring logic quickly.
- **Instrumented tests**: small manifest (3–10 clips) across real VAD+ASR engines (androidTest).

## Gradle & CI
- New task `tuneAsr` in `build.gradle` (root) under `verification` group.
- CI job (nightly or manual) runs `./gradlew tuneAsr` and uploads `best_config_asr.json`.

## App UX & Calibration Flow
- Add `CalibrationFragment` in Diagnostics for debug builds.
- Display prompts, record, show progress, and write `user_profile.json`.

## Trade-offs
- **Offline tuning**: exhaustive, reproducible, but slower (emulator or host CPU).
- **On-device calibration**: fast, private, but limited parameter scope to preserve UX and battery.

## Next Steps
- [ ] Collect or record representative audio clips and assemble `tuning_manifest.jsonl`.
- [ ] Scaffold `AsrEvaluationTest` and Gradle `tuneAsr` task (disabled by default).
- [ ] Update CI workflow to include ASR tuning job.
- [ ] Prototype `CalibrationFragment` UI and persistence logic.

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
