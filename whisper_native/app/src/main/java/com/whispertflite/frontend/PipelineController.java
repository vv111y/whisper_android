package com.whispertflite.frontend;

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
    private int preRollFrames = 10; // ~200ms if frames are 20ms

    public PipelineController(int frameSamples, Listener listener) {
        this.frameSamples = frameSamples;
        this.listener = listener;
    }

    public State getState() { return state; }
    public Mode getMode() { return mode; }

    private void setState(State s) {
        if (s != state) {
            state = s;
            if (listener != null) listener.onStateChanged(state);
        }
    }

    public void startListening() { setState(State.LISTENING); }
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

    // Handle VAD speech start: in SESSION mode, begin capturing immediately (no wakeword)
    public void onSpeechStart() {
        if (mode == Mode.SESSION && state == State.LISTENING) {
            captureFrames.clear();
            // copy pre-roll frames into capture
            for (float[] fr : preRoll) {
                float[] copy = new float[fr.length];
                System.arraycopy(fr, 0, copy, 0, fr.length);
                captureFrames.add(copy);
            }
            capturingSpeechActive = false;
            setState(State.CAPTURING);
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
        // Maintain pre-roll while listening
        if (state == State.LISTENING) {
            // store a copy to avoid aliasing
            float[] copy = new float[frame.length];
            System.arraycopy(frame, 0, copy, 0, frame.length);
            preRoll.addLast(copy);
            while (preRoll.size() > preRollFrames) preRoll.pollFirst();
        }
        if (state == State.CAPTURING) {
            if (speech) {
                // store copy
                float[] copy = new float[frame.length];
                System.arraycopy(frame, 0, copy, 0, frame.length);
                captureFrames.add(copy);
                capturingSpeechActive = true;
            }
        }
    }

    public void onSpeechEnd() {
        if (state == State.CAPTURING && capturingSpeechActive) {
            // finalize utterance
            float[] pcm = flatten();
            setState(State.TRANSCRIBING);
            if (listener != null) listener.onUtteranceReady(pcm);
        }
    }

    public void onTranscriptionComplete() {
        if (state == State.TRANSCRIBING) {
            // go back to listening for next wake
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
}
