# Whisper Android – VAD pipeline tests and auto-tuning Milestone

## Phase 1 of full auto-tuning and auto-configuration of the speech system
This sprint ships a Voice Activity Detection (VAD) pipeline with robust golden-audio unit tests and a closed-loop auto-tuning harness to search for good runtime tunables.

## Golden-audio tests (offline, fast)

- Location: `app/src/test/java/com/whispertflite/frontend/`
- Key file: `PipelineControllerGoldenAudioTest.java`
- Covered behaviors: rising edge + pre-roll, merge-window finalize, cooldown blocking, low-RMS discard, required-silence gating, max-duration finalize, multi-utterance cadence, counters histogram, output gating (no barge-in), pre-roll saturation, edge timing boundaries, long-session stamina, RMS threshold boundaries.
- Run (unit tests only):

```sh
./gradlew testDebugUnitTest --console=plain
```

## Closed-loop auto-tuning

- Key file: `PipelineAutoTuningTest.java` (skipped unless enabled)
- What it does: samples tunable parameters; runs multiple synthetic scenarios; scores configs; prints Top K; writes best config to `build/auto_tune/best_config.json`.
- Enable and run locally:

```sh
./gradlew tuneVad
# or
./gradlew testDebugUnitTest -Dtune=true --console=plain
```

- Optional controls:

```sh
# deterministic run
./gradlew testDebugUnitTest -Dtune=true -Dtune.seed=123 -Dtune.samples=300
```

### JSON output schema

`build/auto_tune/best_config.json` example:

```json
{
  "params": {
    "preRoll": 18,
    "mergeWin": 35,
    "minUtter": 22,
    "reqSilence": 6,
    "armMs": 600,
    "cooldownMs": 800,
    "maxCaptureMs": 12000,
    "noFramesAbortMs": 1200
  },
  "score": 245
}
```

Notes:

- All fields map 1:1 to `PipelineController` setters.
- Fields may also be provided flat at the top-level (without `params`).

## Importing the tuned preset in-app (Debug builds)

Diagnostics screen provides:

- “Import Auto preset (paste or pick file)” — accepts the JSON above.
- “Apply preset: Auto (imported)” — applies values into the running `PipelineController`.

On success, values persist under `diag_auto_*` SharedPreferences and can be reapplied.

## CI

- Workflow: `.github/workflows/android-ci.yml`
  - Build/Lint/Unit and Connected (emulator) tests.
  - Nightly (03:00 UTC) or manual dispatch job `tune` runs the auto-tuner and uploads artifact:
    - Artifact name: `auto-tune-best-config`
    - File: `build/auto_tune/best_config.json`

## Build and install locally

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.whispertflite/.MainActivity
```

## Troubleshooting

- Tuner wrote no file: ensure `-Dtune=true` (or use `./gradlew tuneVad`).
- Diagnostics “Auto” missing: available only on Debug builds.
- Import parse error: ensure JSON schema matches the example (both nested `params` and flat forms are accepted).

## Notes

- Adjust scenario weights in `PipelineAutoTuningTest` to reflect priorities (missed onsets vs false triggers, etc.).
- Add or refine tuner scenarios for more realistic coverage (noise bursts, long pauses, multi-speaker).
