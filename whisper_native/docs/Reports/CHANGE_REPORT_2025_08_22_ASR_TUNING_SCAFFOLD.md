# ASR Tuning Scaffolding (androidTest + Gradle)

```yaml
date: 2025-08-22
time: 00:00
tz: PDT
branch: master
tags: [asr, tuning, tests, gradle]
participants: [will, copilot]
```

## Summary

- Added androidTest harness and Gradle wiring to support ASR-in-the-loop tuning with small local datasets.
- Normalized artifacts to `build/asr_tune/best_config_asr.json` and documented usage.
- Updated developer docs and agent guide to enforce “current milestone as single source of truth.”

## Decisions

- Use connected tests (androidTest) for ASR evaluation; unit tests remain for VAD and fast logic.
- Persist artifacts to `build/*` and maintain curated configs in `configs/*/current.json`.
- Agents must read `docs/README-DEV.md` → current milestone before tasks.

## Actions

- [x] Add `AsrEvaluationTuningTest` harness gated by `asrTune=true`.
- [x] Update root `tuneAsr` to pass runner args and `adb pull` artifact.
- [x] Add starter manifest `docs/data/tuning_manifest.json`.
- [x] Update `docs/Asr_Tuning_Milestone.md` and `docs/README-DEV.md`.
- [x] Add `.github/copilot-instructions.md` and agent operating rules.
- [ ] Replace placeholder harness with real ASR inference + WER/CER scoring.
- [ ] Add CI job to run `tuneAsr` on device/emulator.

## Open items

- Scoring: pick WER/CER impl and latency metrics; confirm model(s) used in loop.
- Data size: define tiny stable set for connected tests.

## Notes / Context

- androidTest writes to app external files dir so Gradle can pull without root.
- WAVs are local only under `datasets_local/audio/` (gitignored).

## Files changed

- build.gradle (root) — `tuneAsr` args + adb pull
- app/src/androidTest/java/com/whispertflite/frontend/AsrEvaluationTuningTest.java — harness
- docs/data/tuning_manifest.json — starter manifest
- docs/Asr_Tuning_Milestone.md, docs/README-DEV.md — docs updates
- .github/copilot-instructions.md — agent rules and workflows

## Commands

```bash
./gradlew tuneAsr -Dasr.manifest=docs/data/tuning_manifest.json -Dasr.audioDir=/abs/path/datasets_local/audio
```

## Artifacts / Links

- Output: build/asr_tune/best_config_asr.json

## Next steps

- Implement real ASR inference + WER/CER and latency metrics.
- Add CI workflow job for `tuneAsr` and artifact upload.
