# Offline Speech-Centric Personal Assistant (Whisper + TFLite)

Hands-free, privacy-focused personal assistant. All ASR runs on-device via Whisper TFLite. The chat feature can talk to a local at-home LLM over a standard HTTP API.

There are three voice interaction modes:
1) App controls (Command Router): real-time, short utterances; fast response
2) Chat: conversational input; up to several minutes; transcribe immediately and send to LLM
3) Entry recording: arbitrary length dictation; audio saved and transcribed in background

| Input type      | Note                                                                               |
|-----------------|------------------------------------------------------------------------------------|
| App controls    | Real time, short, fast response                                                    |
| Chat interface  | Short to ~5 minutes; immediate transcribe for LLM prompt                           |
| Entry recording | Arbitrary long input; audio saved; transcription lower priority in background      |

What’s new in this fork
- Media controls capture without playing audio
  - Foreground Service + MediaSession in PLAYING state with transient audio focus; no looping WAV
  - Optional muted AudioTrack fallback is present but disabled by default
  - Earbud Play/Pause/Next/Previous mapped to app actions; toggle in Settings
- Reliable earcons (beeps) without barge-in
  - Short beeps for state changes; mic is gated during playback to prevent self-trigger
  - Arming delay + cooldown + silence requirement to avoid false starts and looping
- Live-tunable VAD and capture pipeline
  - All timing and sensitivity knobs exposed in Settings; changes apply immediately
  - Two profiles (Command, Chat) with per-mode settings and defaults management
- Navigation & Settings UX
  - AppBar + Drawer (Start, Chat, Recorder); Settings via gear icon
  - Recorder is its own fragment; Settings is a Preference screen with compact sliders
  - Mode toggle (Command | Chat), Defaults / New defaults / Factory reset
- Model selection + validator
  - Choose a .tflite model in Settings; model is auto-validated on selection; invalid models are blocked
  - Detailed native validation uses safe FlatBuffer checks (no interpreter build)

Architecture overview

Audio routing and media keys
- A Foreground Service hosts a MediaSession that’s setActive(true) and advertises a PLAYING PlaybackState so it ranks for AVRCP earbud buttons.
- We request transient audio focus with ASSISTANT or MEDIA usage. No audio output is required to receive media keys.
- Earbud button events are handled in both transport callbacks (play/pause/next/prev) and onMediaButtonEvent for robustness.
- A muted AudioTrack fallback exists for OEMs that deprioritize background sessions; it is only engaged if needed (off by default).

Capture pipeline (mic → Whisper)
- Voice gating/no-barge-in: When the app plays earcons, the mic is gated so tones cannot trigger VAD.
- VAD (energy-based) detects speech start/stop:
  - Arming delay after entering Listening (ignore starts briefly)
  - Pre-roll keeps some audio before detected start to avoid clipped onsets
  - Silence merge/end-of-input: allow short pauses within the same utterance; finalize on longer silence
  - Minimum utterance length and cooldown to filter spurious triggers and avoid immediate re-triggers
- Normalization (optional): DC removal and peak/RMS normalization before Whisper to reduce level variability
- Transcription:
  - Session modes: immediate transcription on utterance finalize
  - Entry recording: save audio first; transcribe in background

TTS (planned)
- Short voice prompts for state/command confirmations, with mic gating and transient focus
- Current build keeps earcons; TTS is on the roadmap

Settings guide

Model & Media
- Model file
  - Pick a Whisper .tflite file. The app auto-validates on selection; invalid models are rejected with a message.
  - Tip: Use models built for TensorFlow Lite with standard ops; the validator checks FlatBuffer integrity and expected I/O shapes.
- Earbud media controls
  - Toggle to capture Play/Pause/Next/Previous from earbuds. When off, the app releases the MediaSession and focus.

Audio preprocessing
- Normalize captured audio
  - Reduce level variability before whisper. Can improve recognition on very quiet or loud recordings.

Mode selector (Command | Chat)
- Segmented toggle determines which set of sliders you’re editing. Each mode has its own saved values.

VAD and pipeline sliders
- Threshold (sensitivity)
  - Energy floor for speech start; higher = less sensitive; lower = more sensitive
  - Typical: Command ~0.035 RMS; Chat ~0.035 RMS
- Attack frames
  - Frames of consistent energy above threshold to confirm start (responsiveness vs stability)
  - Typical: 3 frames
- Hangover frames
  - Frames of silence required to confirm end (stability vs responsiveness)
  - Typical: Command ~30; Chat ~30
- Pre-roll frames
  - Audio prepended before detected start to avoid clipped onsets
  - Typical: Command ~12; Chat ~20
- In-capture silence frames (pause tolerance)
  - Max short pause allowed without ending the utterance (lets you pause between sentences)
  - Typical: Command ~20; Chat ~60
- Min utterance frames
  - Minimum length required to accept an utterance (filters very short noise)
  - Typical: Command ~12; Chat ~18
- Arming delay (ms)
  - Ignore starts immediately after entering Listening to avoid self-trigger from UI sounds/noise
  - Typical: Command ~400ms; Chat ~600ms
- Inter-utterance cooldown (ms)
  - Ignore starts right after finalizing to avoid bounce/echo re-triggers
  - Typical: Command ~600ms; Chat ~800ms
- Max capture (ms)
  - Safety cap for ongoing capture (prevents runaway in noise). For Chat you can set 60–120s
  - Typical: Command ~8000ms; Chat ~90000ms

Profiles and defaults
- Defaults (button)
  - Resets the current mode’s sliders to its saved defaults
- New defaults (button)
  - Saves current slider values as the defaults for the current mode (so future “Defaults” apply your custom baseline)
- Factory reset (button at bottom)
  - Restores our curated baseline defaults for both modes and applies them to the current mode
- Curated baselines
  - Command: thr 0.035, atk 3, hang 30, pre 12, in-cap 20, minU 12, arm 400ms, cool 600ms, max 8000ms
  - Chat:    thr 0.035, atk 3, hang 30, pre 20, in-cap 60, minU 18, arm 600ms, cool 800ms, max 90000ms

How the settings affect accuracy and UX
- Sensitivity (Threshold) and Attack determine how easily speech starts; too low can trigger on noise; too high can miss soft speech
- Pre-roll avoids cutting leading phonemes; increase if starts feel clipped
- In-capture silence and Hangover control how you can pause mid-sentence; increase for multi-sentence dictation
- Min utterance + Cooldown filter very short noises and prevent rapid retrigger
- Arming delay prevents startup pops and earcon echoes from triggering
- Max capture protects from endless capture in noise; must be larger for Chat

Model compatibility and selection
- The app uses a safe model validator:
  - Verifies the .tflite FlatBuffer and expected input shape (mel=80 x time, float32)
  - Avoids building an interpreter in validation to prevent native crashes
- Only models that pass validation can be applied from Settings
- If a model fails validator (e.g., some third-party base.en TFLite), it’s likely built with ops or metadata incompatible with the bundled TFLite runtime

UI overview
- Drawer navigation
  - Start, Chat, Recorder; Settings via the gear icon in the drawer
- Recorder fragment
  - Start/stop listening; state earcons; status output
- Chat fragment (placeholder)
  - Sends recognized text to LLM endpoint (configurable later)
- Start fragment (placeholder)
  - Quick actions and help
- Settings
  - Model & Media, Mode toggle, compact sliders, and defaults management

Privacy
- Audio never leaves the device unless you explicitly use Chat with a remote LLM endpoint
- Models are local; no cloud ASR

Build and run
- Android Studio Hedgehog+ recommended
- Place your Whisper .tflite models in the app’s files directory (Settings will list them)
- Select a model in Settings (auto-validates), then use Recorder to test Commands or Chat

Troubleshooting
- Earbud buttons not received
  - Ensure “Earbud media controls” is ON; another app may be the active MediaSession; briefly play/pause to rank this app
- Self-trigger on first Listening
  - Increase Arming delay and/or Cooldown; earcons are mic-gated, but rooms with strong reflections may need more
- Starts feel clipped
  - Increase Pre-roll frames
- Utterances end too soon during a pause
  - Increase In-capture silence frames (and possibly Hangover)
- Very noisy environment
  - Raise Threshold slightly and consider increasing Attack

Roadmap
- TTS prompts with mic gating and transient focus
- Polished Chat UI and endpoint configuration
- Pre-validate and hide invalid models in the model picker
- Optional embedded TTS for guaranteed offline
