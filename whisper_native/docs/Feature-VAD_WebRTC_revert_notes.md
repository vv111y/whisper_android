# Temporary changes that can be reverted later

This document tracks a few targeted edits made during VAD/WebRTC experimentation that you may want to undo if they prove unnecessary (e.g., missing earcon was due to Do Not Disturb).

## Summary of reversible edits

1) FrameEmitter: drop warmup frames on start
- File: `app/src/main/java/com/whispertflite/asr/FrameEmitter.java`
- Change: Drop the first 3 frames (~60 ms) after `start()` before delivering audio to VAD.
- Why: Avoids startup pops causing false speech starts with the Simple VAD.
- Revert: Remove the `warmupFramesToDrop` field, its reset in `start()`, and the condition that decrements it in `captureLoop()`.

2) PipelineController: stricter finalize guards
- File: `app/src/main/java/com/whispertflite/frontend/PipelineController.java`
- Change A: Raise `minUtteranceFrames` from 18 → 22 (~440 ms).
- Change B: Add low-RMS discard in `finalizeCapture()` for utterances with global RMS < 0.004.
- Why: Reduce phantom utterances caused by very short or ultra-quiet noise.
- Revert: Change `minUtteranceFrames` back to 18 and delete the RMS check block in `finalizeCapture()`.

3) MainActivity: explicit ready earcon + duplicate suppression
- File: `app/src/main/java/com/whispertflite/MainActivity.java`
- Changes:
  - Added `suppressNextListenTone` flag; used to avoid double-beep when we also play a tone on button press.
  - In `onStateChanged`, only play the LISTENING beep if `suppressNextListenTone` is false.
  - On `btnWakeListenStart` and `btnSessionStart`, set `suppressNextListenTone = true` and call `playStateTone(true)` immediately.
- Why: Ensure a single ready beep even if state change posts a tone; initially aimed to fix missing earcon.
- Revert: Remove the flag, its usage in listeners, and the explicit `playStateTone(true)` calls in the button handlers.

4) MainActivity: relax capture gating for WebRTC Native
- File: `app/src/main/java/com/whispertflite/MainActivity.java`
- Change: After `startSession()`, set `pipelineController.setRequiredSilenceFramesBeforeCapture(3)` when engine=WebRTC & impl=Native; else set 6.
- Why: Let WebRTC Native transition to CAPTURING more reliably.
- Revert: Remove this conditional and rely on the default in `PipelineController`.

5) PipelineController: new setter for required pre-speech silence
- File: `app/src/main/java/com/whispertflite/frontend/PipelineController.java`
- Change: Added `setRequiredSilenceFramesBeforeCapture(int)`.
- Why: Let UI policy adjust pre-speech silence without forking the controller.
- Revert: Remove the setter and any calls to it (not necessary if you keep the default behavior).

## Quick revert diff hints

- `FrameEmitter.java`: look for `warmupFramesToDrop` and delete related code.
- `PipelineController.java`: find `minUtteranceFrames = 22` and the block computing `rms` in `finalizeCapture()`; revert to previous values and remove the block.
- `MainActivity.java`: search for `suppressNextListenTone` and remove all references; also remove explicit `playStateTone(true)` calls in the wake/session start click handlers.
- `MainActivity.java`: search for `setRequiredSilenceFramesBeforeCapture` and remove that whole try/catch block.
- `PipelineController.java`: remove the new `setRequiredSilenceFramesBeforeCapture` method.

## Notes
- If Do Not Disturb blocked the earcon, you can leave audio logic as-is and rely on the state-driven tones only (no explicit tone on click). The suppression flag can be safely removed when you do that.
- If Simple VAD accuracy remains good without the warmup/RMS/min-length changes, you can revert 1) and 2) to reduce code complexity.
- Keep the WebRTC implementation selector and engine swap safety; they’re decoupled from these experiments.
