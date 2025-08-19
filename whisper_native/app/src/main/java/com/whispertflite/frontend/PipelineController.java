package com.whispertflite.frontend;

import android.os.SystemClock;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;

/**
 * Minimal state controller for wakeword -> capture -> transcribe (single-shot, no partials).
 */
public class PipelineController {
    public enum State { IDLE, LISTENING, CAPTURING, TRANSCRIBING }
    public enum Mode { WAKEWORD, SESSION }

    public interface Listener {
        void onStateChanged(State state);
        void onWakeTriggered(double score);
        void onUtteranceReady(float[] samples); // deliver full utterance PCM for transcription
    }

    private State state = State.IDLE;
    private Mode mode = Mode.WAKEWORD;
    private final Listener listener;
    private final int frameSamples; // e.g., 320

    private final List<float[]> captureFrames = new ArrayList<>();
    private boolean capturingSpeechActive = false; // tracks if any speech seen in current capture

    // Pre-roll buffer: keep last N frames while LISTENING to include leading phonemes
    private final ArrayDeque<float[]> preRoll = new ArrayDeque<>();
    private int preRollFrames = 18; // ~360ms if frames are 20ms

    // Input gating: when true, drop/ignore all mic-driven events to avoid barge-in during output
    private boolean inputGated = false;
    // Internal merge: while CAPTURING, tolerate short silences before finalizing
    private int inCaptureSilenceFrames = 35; // ~700ms to allow short sentence pauses
    private int inCaptureSilenceCount = 0;
    // Cooldown to avoid immediate re-triggers after finishing an utterance
    private long lastCaptureEndUptimeMs = 0L;
    private long interUtteranceCooldownMs = 800L; // 0.8s
    // Arming delay after transitioning to LISTENING, to ignore initial pops/echo
    private long listeningArmedAtUptimeMs = 0L;
    private long minArmDelayMs = 600L; // 0.6s
    // Require preceding silence before allowing a new capture (guards against earcon tail / init noise)
    private int listeningSilenceFrames = 0;
    private int requiredSilenceFramesBeforeCapture = 6; // ~120ms to reduce clipped onsets

    // Safety: cap max capture duration to avoid runaways on noisy environments
    private long captureStartUptimeMs = 0L;
    private long maxCaptureMs = 12_000L; // 12 seconds
    private int minUtteranceFrames = 22; // ~440ms, reduce false-on short noises

    public PipelineController(int frameSamples, Listener listener) {
        this.frameSamples = frameSamples;
        this.listener = listener;
    }

    public State getState() { return state; }
    public Mode getMode() { return mode; }
    public boolean isInputGated() { return inputGated; }
    // Allow UI to temporarily gate input during short tones without forcing state changes
    public void gateInput(boolean gated) { this.inputGated = gated; }

    private void setState(State s) {
        if (s != state) {
            state = s;
            if (state == State.LISTENING) {
                listeningArmedAtUptimeMs = android.os.SystemClock.uptimeMillis();
                listeningSilenceFrames = 0;
                // Clear pre-roll and pending capture state when re-entering LISTENING
                preRoll.clear();
                captureFrames.clear();
                capturingSpeechActive = false;
            }
            if (listener != null) listener.onStateChanged(state);
        }
    }

    public void startListening() {
        setState(State.LISTENING);
        listeningArmedAtUptimeMs = android.os.SystemClock.uptimeMillis();
    }
    public void stop() { setState(State.IDLE); captureFrames.clear(); }

    // Session-mode scaffolding (no-op transitions for commit 1)
    public void startSession() {
        mode = Mode.SESSION;
        startListening();
    }

    public void stopSession() {
        mode = Mode.WAKEWORD; // revert to default
        stop();
    }

    // Output lifecycle hooks to implement no barge-in behavior
    public void onOutputStart() {
        inputGated = true;
    }

    public void onOutputEnd() {
        inputGated = false;
        // Re-arm listening if we were idle; respect current mode
        if (state == State.IDLE) setState(State.LISTENING);
    listeningSilenceFrames = 0;
    }

    // User-initiated pause/resume of session listening
    public void pauseListening() {
        inputGated = true;
        setState(State.IDLE);
    }

    public void resumeListening() {
        inputGated = false;
        setState(State.LISTENING);
        listeningArmedAtUptimeMs = android.os.SystemClock.uptimeMillis();
    listeningSilenceFrames = 0;
    }

    // Handle VAD speech start: in SESSION mode, begin capturing immediately (no wakeword)
    public void onSpeechStart() {
        if (inputGated) return;
        if (mode == Mode.SESSION && state == State.LISTENING) {
            long now = SystemClock.uptimeMillis();
            // Respect arming delay right after entering LISTENING
            if (now - listeningArmedAtUptimeMs < minArmDelayMs) return;
            // Require some preceding silence before we accept a new capture start
            if (listeningSilenceFrames < requiredSilenceFramesBeforeCapture) return;
            if (now - lastCaptureEndUptimeMs < interUtteranceCooldownMs) {
                // Still in cooldown; ignore this start
                return;
            }
            captureFrames.clear();
            // copy pre-roll frames into capture
            for (float[] fr : preRoll) {
                float[] copy = new float[fr.length];
                System.arraycopy(fr, 0, copy, 0, fr.length);
                captureFrames.add(copy);
            }
            capturingSpeechActive = false;
            setState(State.CAPTURING);
            captureStartUptimeMs = now;
        }
    }

    public void onWakeTriggered(double score) {
        if (state != State.LISTENING) return;
        if (listener != null) listener.onWakeTriggered(score);
        captureFrames.clear();
        capturingSpeechActive = false;
        setState(State.CAPTURING);
    }

    // Called for every frame while VAD processes; speech=true if inside speech
    public void onFrame(float[] frame, boolean speech) {
    if (inputGated) return;
        // Maintain pre-roll while listening
        if (state == State.LISTENING) {
            // store a copy to avoid aliasing
            float[] copy = new float[frame.length];
            System.arraycopy(frame, 0, copy, 0, frame.length);
            preRoll.addLast(copy);
            while (preRoll.size() > preRollFrames) preRoll.pollFirst();
            // Track silence frames while listening (before speech onset)
            if (speech) {
                listeningSilenceFrames = 0;
            } else {
                if (listeningSilenceFrames < 1000) listeningSilenceFrames++; // cap to avoid overflow
            }
        }
        if (state == State.CAPTURING) {
            long now = SystemClock.uptimeMillis();
            if (capturingSpeechActive && (now - captureStartUptimeMs) > maxCaptureMs) {
                // Force finalize to keep UX responsive
                finalizeCapture();
                return;
            }
            if (speech) {
                // store copy
                float[] copy = new float[frame.length];
                System.arraycopy(frame, 0, copy, 0, frame.length);
                captureFrames.add(copy);
                capturingSpeechActive = true;
                inCaptureSilenceCount = 0;
            } else {
                // count brief silences and keep capturing; onSpeechEnd() will decide when to end
                inCaptureSilenceCount++;
                if (inCaptureSilenceCount <= inCaptureSilenceFrames) {
                    // Still consider this part of the same utterance; optionally include a few silent frames for context
                    float[] copy = new float[frame.length];
                    System.arraycopy(frame, 0, copy, 0, frame.length);
                    captureFrames.add(copy);
                } else {
                    // Silence exceeded merge window: finalize here proactively
                    finalizeCapture();
                    return;
                }
            }
        }
    }

    public void onSpeechEnd() {
    if (inputGated) return;
        if (state == State.CAPTURING && capturingSpeechActive) {
            // If we only saw a very short pause, do not finalize here; let frames continue to merge
            if (inCaptureSilenceCount > 0 && inCaptureSilenceCount <= inCaptureSilenceFrames) {
                // ignore this end; capturing continues until a longer pause happens
                return;
            }
            finalizeCapture();
        }
    }

    public void onTranscriptionComplete() {
        if (state == State.TRANSCRIBING) {
            // go back to listening for next wake
            lastCaptureEndUptimeMs = SystemClock.uptimeMillis();
            setState(State.LISTENING);
            captureFrames.clear();
            capturingSpeechActive = false;
        }
    }

    private float[] flatten() {
        int total = captureFrames.size() * frameSamples;
        float[] out = new float[total];
        int idx = 0;
        for (float[] fr : captureFrames) {
            System.arraycopy(fr, 0, out, idx, fr.length);
            idx += fr.length;
        }
        return out;
    }

    private void finalizeCapture() {
        // Enforce a minimal utterance duration to reduce false positives
        int minFrames = Math.max(1, minUtteranceFrames);
        if (captureFrames.size() < minFrames) {
            // Too short; discard and return to listening
            captureFrames.clear();
            capturingSpeechActive = false;
            lastCaptureEndUptimeMs = SystemClock.uptimeMillis();
            setState(State.LISTENING);
            return;
        }
        // Additional guard: if RMS of the utterance is extremely low, discard as noise
        float rmsSum = 0f;
        int n = 0;
        for (float[] fr : captureFrames) {
            for (float v : fr) { rmsSum += v * v; n++; }
        }
        float rms = (float)Math.sqrt(rmsSum / Math.max(1, n));
        if (rms < 0.004f) { // conservative floor
            captureFrames.clear();
            capturingSpeechActive = false;
            lastCaptureEndUptimeMs = SystemClock.uptimeMillis();
            setState(State.LISTENING);
            return;
        }
        float[] pcm = flatten();
        setState(State.TRANSCRIBING);
        if (listener != null) listener.onUtteranceReady(pcm);
    }

    // Live tuning setters
    public void setPreRollFrames(int frames) {
        this.preRollFrames = Math.max(0, frames);
        preRoll.clear();
    }
    public void setInCaptureSilenceFrames(int frames) { this.inCaptureSilenceFrames = Math.max(0, frames); }
    public void setInterUtteranceCooldownMs(long ms) { this.interUtteranceCooldownMs = Math.max(0L, ms); }
    public void setMinArmDelayMs(long ms) { this.minArmDelayMs = Math.max(0L, ms); }
    public void setMaxCaptureMs(long ms) { this.maxCaptureMs = Math.max(1000L, ms); }
    public void setMinUtteranceFrames(int frames) { this.minUtteranceFrames = Math.max(1, frames); }
    public void setRequiredSilenceFramesBeforeCapture(int frames) { this.requiredSilenceFramesBeforeCapture = Math.max(0, frames); }
}
