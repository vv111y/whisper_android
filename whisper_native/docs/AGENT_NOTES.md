# AGENT_NOTES

Short, dated tips for agents. Keep this lean; milestone docs and reports remain the source of truth.

## 2025-08-22
- Established “single source of truth”: the current milestone under `docs/`, linked from `docs/README-DEV.md`.
- Added ASR tuning scaffolding:
  - Gradle `tuneAsr` passes runner args and adb-pulls artifact.
  - androidTest harness `AsrEvaluationTuningTest` writes `best_config_asr.json`.
  - Starter dataset manifest at `docs/data/tuning_manifest.json`.
- Artifact paths:
  - VAD tuner → `build/auto_tune/best_config.json`
  - ASR tuner → `build/asr_tune/best_config_asr.json`
- Conventions:
  - Local WAVs in `datasets_local/audio/` (gitignored)
  - Curated configs in `configs/{vad,asr}/current.json`
