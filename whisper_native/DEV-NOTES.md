# Voice Pipeline & VAD Notes

This doc captures the current contract, tunables, and diagnostics for the voice pipeline and VAD engines.

## Frame cadence

- 16 kHz PCM, 320 samples per frame (~20 ms).
- Silero VAD runs with 512-sample inference internally; it consumes 320-sample frames and buffers to 512 under the hood.

## VAD engines

- All engines (Energy, WebRTC Simple/Native, Silero) emit per-frame `speechLike` booleans.
- A shared `EdgeDetector` applies hysteresis:
  - Attack: N consecutive speech-like frames start speech.
  - Hangover: N consecutive non-speech frames end speech. (End triggers when non-speech count >= hangover frames.)

## PipelineController contract

- States: IDLE → LISTENING → CAPTURING → TRANSCRIBING → LISTENING.
- Start of CAPTURING is frame-driven on a rising edge (non-speech → speech) while in LISTENING and Mode.SESSION.
- Gating at start:
  - Arming delay: ignore starts until `minArmDelayMs` elapsed since entering LISTENING.
  - Cooldown: block starts if `now - lastCaptureEnd < interUtteranceCooldownMs`.
  - Required pre-speech silence: need `requiredSilenceFramesBeforeCapture` trailing silence frames before the rising edge.
  - During CAPTURING:
  - Pre-roll frames collected in LISTENING are prepended to capture.
  - Speech frames appended; brief silences are tolerated up to `inCaptureSilenceFrames` to allow short pauses.
  - Finalize when silence exceeds merge window, when duration exceeds `maxCaptureMs`, or when explicitly completed by hints.
  - Enforce `minUtteranceFrames` and a low RMS floor to discard tiny/noisy clips.
  - No-barge-in: input can be gated via `gateInput(true)` or `onOutputStart()/onOutputEnd()`.
  - Timestamps use an injectable clock for testability; production uses `uptimeMillis()`.

## Diagnostics counters

Accessible via getters and reset with `resetDiagnostics()`.

- `diagBlockedArming`: Start blocked by arming delay.
- `diagBlockedCooldown`: Start blocked by cooldown.
- `diagBlockedSilence`: Start blocked by insufficient pre-speech silence.
- `diagCaptureStarted`: Successful capture starts.
- `diagAbortNoFrames`: Capture aborted because no frames arrived in grace window.
- `diagFinalizeSilenceExceeded`: Finalized due to silence exceeding merge window.
- `diagFinalizeMaxDuration`: Finalized due to max duration.
- `diagDiscardTooShort`: Discarded because below `minUtteranceFrames`.
- `diagDiscardLowRms`: Discarded due to low RMS.
- `diagUtterancesEmitted`: Emitted utterances for transcription.

## Tuning quick-start

- Attack/Hangover (EdgeDetector): 2–3 frames for attack; 2–5 frames for hangover.
- Start gates: set `minArmDelayMs ~ 600`, `cooldown ~ 800`, and `requiredSilenceFramesBeforeCapture ~ 3–6`.
- Merge window: `inCaptureSilenceFrames ~ 25–40` (0.5–0.8s) for natural short pauses.
- Min utterance frames: ~20–30 (0.4–0.6s) to avoid blips.
- RMS floor: conservative (~0.004) to drop near-silence.

## Testing

- Local unit tests (no Android runtime) with an injected fake clock and logging disabled.
  - `EdgeDetectorTest` verifies attack/hangover and reset behavior.
  - `PipelineControllerTest` verifies arming, cooldown, and required silence gating.
- Run: Gradle task `:app:testDebugUnitTest`.

## Notes

- `onSpeechStart/End` are diagnostic hints only; starts are driven by per-frame updates.
- Consider exposing a simple UI action to dump/reset diagnostics for field tuning.

## Diagnostics UI (Settings → Diagnostics)

Purpose: Inspect pipeline state/gates and counters at runtime, toggle verbose logs in debug, and export a snapshot.

- Status
  - “State & input gate”: current `State` and whether input is gated.
  - “Start gates”: arming and cooldown remaining, plus current/required silence frames.
- Counters
  - Shows all diagnostics counters listed above; press “Refresh” to update.
- Actions
  - Refresh: Updates the summaries immediately.
  - Reset counters: Calls `PipelineController.resetDiagnostics()`.
  - Verbose pipeline logs (debug only): Toggles `setLoggingEnabled()` at runtime.
  - Export snapshot (JSON): Shares a compact JSON with state, gates, and counters (no PII).
  - Simulate wake (debug only): Calls `startListening()` and then `onWakeTriggered(0.99)` to exercise the CAPTURING path quickly.

Debug detection

- Both `PipelineController` and `DiagnosticsFragment` use reflection of `com.whispertflite.BuildConfig.DEBUG` to avoid build-time coupling to the app module in tests.
- If reflection fails, debug-only UI and logging are treated as disabled.

Troubleshooting

- If unit tests complain about Android Log, ensure logging is disabled in tests via `setLoggingEnabled(false)` and use the fake clock constructor.
- If the Diagnostics screen title shows a literal ampersand, ensure `&` is escaped as `&amp;` in XML.
