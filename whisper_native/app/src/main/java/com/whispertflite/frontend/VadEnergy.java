package com.whispertflite.frontend;

/**
 * Simple energy-based VAD with hangover logic.
 * NOT production robust, first MVP gate before wakeword.
 */
public class VadEnergy {
    public interface Listener {
        void onSpeechStart();
        void onSpeechEnd();
        void onFrameAccepted(float[] frame, boolean speech);
    }

    private final Listener listener;

    private int hangoverFrames; // frames to wait after last speech before ending
    private int silenceCount = 0;
    private boolean inSpeech = false;
    // Require a few consecutive speech-like frames before declaring speech start (attack hysteresis)
    private int startAttackFrames = 3; // ~60ms if 20ms frames
    private int speechStreak = 0;

    private float threshold;

    public VadEnergy(float thresholdRms, int hangoverFrames, Listener listener) {
        this.threshold = thresholdRms;
        this.hangoverFrames = hangoverFrames;
        this.listener = listener;
    }

    public void reset() {
        silenceCount = 0;
        inSpeech = false;
    speechStreak = 0;
    }

    public void accept(float[] frame) {
        float rms = 0f;
        for (float v : frame) rms += v * v;
        rms = (float)Math.sqrt(rms / frame.length);
    boolean speechLike = rms >= threshold;

        if (speechLike) {
            silenceCount = 0;
            speechStreak++;
            if (!inSpeech && speechStreak >= startAttackFrames) {
                inSpeech = true;
                if (listener != null) listener.onSpeechStart();
            }
        } else if (inSpeech) {
            silenceCount++;
            if (silenceCount > hangoverFrames) {
                inSpeech = false;
                silenceCount = 0;
                speechStreak = 0;
                if (listener != null) listener.onSpeechEnd();
            }
        } else {
            // not in speech and not speechLike
            speechStreak = 0;
        }
        if (listener != null) listener.onFrameAccepted(frame, inSpeech);
    }

    // Live tuning API
    public synchronized void setThreshold(float thr) {
        this.threshold = Math.max(0.001f, thr);
        // do not force reset; keep continuity but clear streaks to avoid stale state
        silenceCount = 0;
        speechStreak = 0;
    }

    public synchronized void setHangoverFrames(int frames) {
        this.hangoverFrames = Math.max(0, frames);
    }

    public synchronized void setStartAttackFrames(int frames) {
        this.startAttackFrames = Math.max(1, frames);
    }

    public synchronized float getThreshold() { return threshold; }
    public synchronized int getHangoverFrames() { return hangoverFrames; }
    public synchronized int getStartAttackFrames() { return startAttackFrames; }
}
