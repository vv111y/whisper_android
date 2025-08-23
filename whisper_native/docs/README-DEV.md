# Whisper Android – INITIAL DEVELOPER DOCUMENT, START HERE  

This is the primary guide for all developers. Please consult this document regularly and update as needed.  
For each sprint use a new live document that 
1. defines the contract and requirements for the sprint 
2. snapshot of the current state (todos done, current, next)
3. todo list(s) that organizes the work

For each commit include an update to the sprint live doc so that all team members are up to date.

## PROJECT OVERVIEW

### Current Milestone

- Title: ASR + VAD Tuning Milestone
- Doc: [Asr_Tuning_Milestone.md](Asr_Tuning_Milestone.md)
- Updated: 2025-08-22

How to use this doc

- Start with the Current Milestone above; it is the live source of truth.
- Update this block (title/link/date) when the milestone changes.
- Keep build/test shortcuts and conventions (below) in sync with the milestone decisions.


This milestone works off the previous work done in:

- [VAD_Pipeline_Notes.md](VAD_Pipeline_Notes.md) This doc captures the current contract, tunables, and diagnostics for the voice pipeline and VAD engines.
- [VAD_pipeline_tests_tuning](VAD_pipeline_tests_tuning.md) This app ships a Voice Activity Detection (VAD) pipeline with robust golden-audio unit tests and a closed-loop auto-tuning harness to search for good runtime tunables.

Additional prior material [2025-08-xx uncertain dates, see git log]:

- [Diagnostics_Tuning_Guide_V1](Diagnostics_Tuning_Guide_V1.md) This guide explains the in-app Diagnostics system for the audio VAD + capture pipeline. It’s written for newcomers so you can confidently observe, tune, and export diagnostics without breaking things.
- [Feature-wakeword](Feature-wakeword.md) WIP,  alternative to session-listening which is current default
- [Feature-VAD_WebRTC_revert_notes](Feature-VAD_WebRTC_revert_notes.md) This document tracks a few targeted edits made during VAD/WebRTC experimentation that you may want to undo if they prove unnecessary (e.g., missing earcon was due to Do Not Disturb).


## Next Milestones

### WIP: Integrate Android TextToSpeech (TTS)

git info of prior work not finished:

most done on b9c934970e4eb598aab4294be472697a0e425fb0  wip: TTS feature
  Added a lightweight system TTS wrapper with policy support and mic-gating hooks.
  Extended Settings with TTS options (enabled, interrupt policy, double‑tap to stop).
  Integrated TTS into MainActivity:
  Initialized TTS and applied settings on launch and resume.
  Gated the mic on TTS start/end for strict “no barge‑in.”
  Stopped TTS on earbud media-double‑tap when enabled.
  Stopped TTS when session stops.

then a6add093d46248d6593424c0f0c6e3e1f6baddec  update GUI, fix bugs, start TTS and command routing
  Post‑TTS action flow
  Added a small post-TTS action state so after a spoken confirm we:
  Play the ready beep (mic gated; no barge-in), then
  Execute the action:
  New chat: re-arm session listening
  New recording: start the legacy Recorder
  Centralized TTS init via ensureTts()
  and call it on Activity start and when needed.

### file system access

use SAF and external store for single app folder


### WIP: command router & app functionality

chat mode
  "start new chat"
  on chat end give option: discard | name new chat
    if name -> (optional) add tags
            -> save chat

record mode
  "starting new recording"
  -> app: what kind? -> "personal" | "project `<x>`" | "work" | "new tag"
  if new tag -> app: say tag name -> "`<tag name>`" -> app: creates new tag meta-data
  -> recording starts.
  -> on tap end recording
  -> audio feedback recording saved

Other app functionality forthcoming
  ie. KB query | interaction


### feature: earbud mic implementation

Implement feature and add ASR auto-tune task for optimal settings for this input


### finish WIP: wakeword

refactor wakeword to conform to new setup
debug and tune after gathering audio samples



## Developer Conventions (Docs, Artifacts, Data)

- Filenames use underscore_case and start with type + date when relevant.
  - Sprints: `ASR_TUNING_SPRINT_YYYY_MM_DD.md`
  - Change reports: `CHANGE_REPORT_YYYY_MM_DD_HHMM_<topic>.md` in `docs/Reports/`
  - Decisions: `ADR_XXXX_<topic>_YYYY_MM_DD.md` in `docs/Decisions/`
- Curated configs (committed):
  - `configs/asr/best_config_asr_YYYY_MM_DD.json` and `configs/asr/current.json`
  - `configs/vad/best_config_vad_YYYY_MM_DD.json` and `configs/vad/current.json`
- Build outputs (not committed):
  - `build/auto_tune/best_config.json` (VAD)
  - `build/asr_tune/best_config_asr.json` (ASR)
- Bundled default (optional):
  - `app/src/main/assets/configs/current.json` (merged from curated `current.json` files)
- Datasets (local only):
  - WAVs under `datasets_local/audio/` (gitignored)
  - Manifest committed at `docs/data/tuning_manifest_YYYY_MM_DD.json`
  - Override at runtime with `-Dasr.manifest=... -Dasr.audioDir=...`

## Build/Test quick reference

- Build APK: `./gradlew assembleDebug`
- Unit tests: `./gradlew testDebugUnitTest`
- Connected tests: `./gradlew connectedDebugAndroidTest`
- VAD tuner: `./gradlew tuneVad` → `build/auto_tune/best_config.json`
- ASR tuner: `./gradlew tuneAsr -Dasr.manifest=docs/data/tuning_manifest.json -Dasr.audioDir=/abs/path/datasets_local/audio` → `build/asr_tune/best_config_asr.json`
- Local CI (unit only): `./gradlew localCi`
- Local CI (with connected tests): `./gradlew localCiConnected`
- Bundle curated configs into assets: `./gradlew bundleCuratedConfig`

Notes:

- Starter manifest at `docs/data/tuning_manifest.json`; local WAVs go under `datasets_local/audio/` (gitignored).

## Reports & Sprints

- Use `docs/TEMPLATE.md` to write a Change Report after a work unit (1–4 related changes).
- Each PR (or local commit batch) should include: code changes + Change Report + sprint doc delta.


## TECH STACK

- Local builds for now; GitHub scaffolding ready when remote is added.

## HARDWARE

Devices for on device, connected tasks:

- Primary
  - Galaxy S21
  - Android 14
- Secondary
  - Pixel 8a
  - GrapheneOS latest

## Stable reference

Stable workflows

- Gradle tasks (root): `localCi`, `localCiConnected`, `tuneVad`, `tuneAsr`, `bundleCuratedConfig`.
- Tests: unit tests under `app/src/test/...`, connected tests under `app/src/androidTest/...`.
- Tuning harnesses: VAD (unit), ASR (androidTest); artifacts land in `build/*_tune/`.

Stable conventions

- Local datasets live at `datasets_local/audio/` (gitignored). Manifests go in `docs/data/`.
- Curated configs (committed): `configs/vad/current.json`, `configs/asr/current.json`.
- Build artifacts (not committed): `build/auto_tune/best_config.json`, `build/asr_tune/best_config_asr.json`.
- Optional bundling: `bundleCuratedConfig` writes `app/src/main/assets/configs/current.json`.

Key integration points

- VAD engines: Energy (Java), WebRTC (JNI), Silero (ONNX).
- ASR via TensorFlow Lite models; Silero VAD via ONNX Runtime; WebRTC VAD via JNI.

Gotchas

- The androidTest ASR harness writes artifacts to the app’s external files directory; collection tasks may `adb pull` from there (see tuneAsr wiring).

## WORKFLOW & RELEASE RULES

Use a simple, repo-first workflow. Keep chat ephemeral; persist outcomes in-repo.

- Discrete work unit (1–4 related changes):
  - Implement and verify (build/tests/linters).
  - Write a Change Report using `docs/TEMPLATE.md` under `docs/Reports/` (scope, decisions, artifacts, follow-ups).
  - Update the Current Milestone doc with progress, decisions, and artifact paths.
  - Commit code + report + milestone updates together with a meaningful message.
- Milestones:
  - Keep the "Current Milestone" block (top of this file) pointing to the live doc.
  - When a milestone completes, archive it and update this block to the next one.
- Artifacts and configs:
  - Do not commit build outputs (e.g., `build/auto_tune/**`, `build/asr_tune/**`).
  - Commit curated configs under `configs/**` and update `configs/*/current.json` when promoting results.
  - Optionally bundle defaults into `app/src/main/assets/configs/current.json` via `bundleCuratedConfig`.
- Datasets:
  - Keep audio data local (gitignored) under `datasets_local/**`.
  - Commit dataset manifests under `docs/data/` and pass overrides via Gradle props when tuning.
- Optional:
  - Add short notes to `docs/AGENT_NOTES.md` for tips or context that don’t fit a report.

## POSSIBLE OTHER HEADERS, USE AT YOUR DISCRETION

## DEBUGGING

## CODE STYLE

## FOLDER ORGANIZATION

## PROJECT-SPECIFIC STANDARDS

## REFERENCE EXAMPLES

## PROJECT DOCUMENTATION & CONTEXT SYSTEM

## FINAL DOs AND DON'Ts
