Wakeword (DTW, MFCC templates) - Developer Notes

Status
- Implemented: FrameEmitter (20 ms), VadEnergy (RMS+hangover), DtwWakewordDetector (MFCC+DTW), PipelineController (IDLE->LISTENING->CAPTURING->TRANSCRIBING), UI buttons.
- Pending: Real templates in assets/wake_templates.txt, threshold/window tuning.

Runtime params (must match template generation):
- sample_rate = 16000
- frame_len = 400 (25 ms)
- hop = 160 (10 ms)
- mel_bands = 26
- mfcc_coeffs = 13
- window_frames: choose based on keyword duration (e.g., 60 ≈ 600 ms). Ensure code and templates match.

Generate templates
1) Record 5–10 clean wakeword WAVs (16k mono).
2) Use the tooling script:
   python tools/make_wake_templates.py --audio_dir /path/to/wavs \
       --output app/src/main/assets/wake_templates.txt \
       --window_frames 60
3) Rebuild and run. Adjust DtwWakewordDetector threshold and window_frames to fit your keyword.

Tuning
- Log best DTW scores on positives/negatives; set threshold between them.
- Use debounce (already wired) to avoid retriggers.

Next steps (future branches)
- DONE Session listening (no wakeword): re-arm LISTENING after TTS, play ready click.
- DONE Direct buffer transcription (avoid temp WAV write).
- Command router and TTS.

Commit messages (suggested)
- feat(audio): add FrameEmitter and wake listen UI buttons
- feat(vad): add energy VAD and integrate with frame path
- feat(wakeword): add DTW wakeword detector and asset loading
- feat(pipeline): add PipelineController and wire to Whisper (no partials)
- docs(tools): add MFCC template generator and wakeword README
