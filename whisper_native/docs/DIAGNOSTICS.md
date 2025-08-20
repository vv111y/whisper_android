# Diagnostics & Tuning Guide

This guide explains the in-app Diagnostics system for the audio VAD + capture pipeline. It’s written for newcomers so you can confidently observe, tune, and export diagnostics without breaking things.

- What you’ll learn
  - How the pipeline works (high level)
  - What the diagnostic items mean (state, gates, counters, cadence)
  - How to use actions (Refresh, Reset, Export JSON, Simulate wake, Session self‑check)
  - How to quick‑tune parameters and what each knob does
  - How to use/export the JSON snapshot for bug reports

> Tip: Many controls are visible only in debug builds.

---

## 1) Pipeline 101: what’s going on

At 16 kHz, audio is processed in small frames (typically 320 samples ≈ 20 ms). The pipeline is:

1. Mic frames → VAD engine(s) decide speech vs silence per frame.
2. PipelineController (state machine) consumes frames + VAD decisions and orchestrates:
   - LISTENING: keeps a rolling pre‑roll buffer (leading context frames before speech)
   - CAPTURING: collects frames into an utterance until a stop condition
   - TRANSCRIBING: emits flattened PCM to ASR; then returns to LISTENING
3. Hysteresis and gates avoid false starts/early stops.

Important behaviors:
- Rising edge start: In SESSION mode, the first speech frame after silence starts capture.
- Pre‑roll: a few silence frames before the rising edge are included to avoid clipped onsets.
- Merge window: brief silences during speech are tolerated up to N frames before finalizing.
- Min utterance frames: too-short segments are discarded to reduce false starts.
- Cooldown: after finishing, new starts are temporarily blocked.
- Arming delay: small delay after entering LISTENING to avoid pops/echo-triggered starts.
- No‑frames abort: if capture starts but no frames arrive soon, abort and return to LISTENING (mainly for wake‑triggered start paths).

---

## 2) Diagnostics screen: sections and meanings

Open the Diagnostics screen from the app menu (debug builds show extra controls). You’ll see these sections:

### Pipeline status
- State & input gate
  - State: one of IDLE, LISTENING, CAPTURING, TRANSCRIBING
  - Input gated: true when mic events are intentionally ignored (e.g., during earcons or output playback)

- Start gates & tunables
  - Arming remaining (ms): time left before rising edge can start capture
  - Cooldown remaining (ms): time left before a new capture is allowed after finishing
  - Silence: current/required – how many consecutive non‑speech frames we have vs the minimum required before a valid start
  - Tunables: current values for all knobs listed below (pre‑roll, merge window, delays, limits)

### Counters
Each counter increments when that condition is hit, helping you understand why starts didn’t happen, or how a capture ended.
- Blocked
  - arming: rising edge occurred but arming delay wasn’t met
  - cooldown: rising edge occurred during cooldown
  - silence: rising edge occurred but not enough pre‑speech silence yet
- Capture
  - started: capture sessions started (after passing gates)
  - abortNoFrames: capture was started but aborted because no frames arrived within the grace window
- Finalize
  - silenceExceeded: capture ended because silence exceeded the merge window during CAPTURING
  - maxDuration: capture ended because max duration was reached (safety cap)
- Discard
  - tooShort: capture ended but total frames < minUtteranceFrames, so it was discarded
  - lowRms: capture ended but average RMS was extremely low; discarded as likely noise
- Utterances emitted: utterances that were finalized and handed to ASR

### VAD callback cadence (debug only)
Summarizes the time between VAD callbacks (average, min, max, p50, p95). Use Reset cadence stats to clear.
- Healthy cadence is near the frame size cadence (e.g., ~20 ms for 320‑sample frames at 16 kHz).
- Large variance suggests scheduling hiccups or heavy processing.

---

## 3) Actions
- Refresh: re-reads the live state and counters.
- Reset counters: sets all Diagnostics counters to 0.
- Export snapshot (JSON): builds a compact JSON report of state, gates, tunables, and counters for sharing.
- Simulate wake (debug): triggers a capture as if wakeword fired. Helpful for quick CAPTURING transitions without a real wakeword.
- Session self‑check (debug): runs a deterministic gating simulation (no mic) and toasts a short report to validate starts/finalization logic.
- Reset cadence stats (debug): clears the VAD cadence history buffer.

---

## 4) Quick tune (debug only): knobs and what they do
Use these sliders to experiment. Keep notes and revert if behavior worsens.

- Pre‑roll frames: number of listening frames kept before the rising edge and prepended to the utterance. Prevents clipped phonemes.
- Merge window (silence frames): how many silent frames we tolerate while CAPTURING before finalizing. Smaller → quicker cut; bigger → more tolerant pauses.
- Min arm delay (ms): minimum time after entering LISTENING before we allow capture start. Helps avoid pops/echo.
- Inter‑utterance cooldown (ms): minimum time after finishing before a new capture can start. Reduces rapid re-triggers.
- Max capture duration (ms): safety cap; forces finalize even if VAD keeps saying speech. Avoids runaways in noisy rooms.
- Min utterance frames: minimal total frame count for an utterance. If not met, we discard as too short.
- Required silence before start (frames): enforce N consecutive non‑speech frames before a valid rising‑edge start. Helps clean onsets.
- Abort if no frames (ms): if capture is started but no frames are appended for this long, abort (useful on wake‑triggered paths).

Guidance:
- Start conservative: pre‑roll ~ 10–20, merge window ~ 20–40 frames, min utter ~ 20–30 frames.
- Tune one parameter at a time. Use counters + cadence to assess impact.

---

## 5) JSON snapshot: what it is and how to use it
When you tap “Export snapshot (JSON),” the app builds a single JSON object with:

- state, mode, inputGated, loggingEnabled
- armingRemainingMs, cooldownRemainingMs
- silence: { current, required }
- tunables: { preRollFrames, mergeSilenceFrames, minArmDelayMs, cooldownMs, maxCaptureMs, minUtterFrames, noFramesAbortMs, frameSamples }
- counters: { blockedArming, blockedCooldown, blockedSilence, captureStarted, abortNoFrames, finalizeSilenceExceeded, finalizeMaxDuration, discardTooShort, discardLowRms, utterancesEmitted }

Example uses:
- Attach it to bug reports so others can reproduce your setup quickly.
- Compare before/after settings when tuning (keep snapshots alongside notes).
- If QA reports “starts are blocked,” check counters.blocked* and the gate values to see which gate is causing it.

How to share:
- The UI opens a share sheet with the JSON as text. Send it via Slack/email or save it to files.
- For privacy: the snapshot contains no audio or PII—only numeric settings and counters.

---

## 6) Interpreting common scenarios
- I hear clipped beginnings
  - Increase Pre‑roll frames; ensure Required silence before start isn’t too high for your environment.
- It stops too quickly between words
  - Increase Merge window (silence frames) and/or Min utterance frames.
- It never starts
  - Check Blocked counters: arming, cooldown, or silence. Lower Min arm delay or cooldown; lower Required silence frames; verify Input gated is false.
- It runs forever in noisy rooms
  - Lower Merge window, reduce Max capture duration, or raise Min utterance frames.
- Too many short false triggers
  - Increase Min utterance frames, increase Required silence before start, consider small arming delay.
- Cadence looks choppy (high p95)
  - Device under load or heavy model work. Try reducing logging, verify no excessive work on UI thread, and monitor native/ONNX usage.

---

## 7) Developer tips
- Debug-only toggles and Quick tune are hidden in release builds. If you don’t see them, you’re likely on a release build.
- The Diagnostics counters reset on demand; they’re great for short focused experiments. Take a snapshot, run a scenario, reset, repeat.
- Session self‑check is a fast smoke test for gating logic. Run it after changing thresholds.
- If integrating a new VAD engine, keep cadence steady and ensure the VAD returns a stable speech boolean per frame.

---

## 8) Internals reference (quick)
- State machine: IDLE → LISTENING → (on rising edge) CAPTURING → (on finalize) TRANSCRIBING → LISTENING
- Finalization conditions
  - Silence exceeded merge window (increments finalizeSilenceExceeded)
  - Max capture duration (increments finalizeMaxDuration)
  - Immediate finalize on next silent frame when merge window is 0
  - Discard paths: fewer than Min utterance frames (discardTooShort), very low RMS (discardLowRms)
- Rising-edge start detail: pre‑roll is copied, current speech frame is added, then we return from onFrame to avoid double-processing that same frame.

---

## 9) Troubleshooting checklists
- Starts blocked
  - Look at Arming remaining/Cooldown remaining, Silence current/required, and corresponding Blocked counters.
- Captures drop early
  - Merge window too small; increase it. Check finalizeSilenceExceeded counter.
- Utterances discarded a lot
  - Min utterance frames too high or RMS threshold too strict; verify your input level.
- No-frames aborts occur
  - Increase Abort if no frames (ms) or ensure capture actually appends frames (wake-triggered paths vs session rising edge).

---

If something is unclear or you’re adding new counters/tunables, update this doc so future devs can benefit.
