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

    private final float thresholdRms; // e.g., 0.02f
    private final int hangoverFrames; // frames to wait after last speech before ending
    private int silenceCount = 0;
    private boolean inSpeech = false;
    // Require a few consecutive speech-like frames before declaring speech start (attack hysteresis)
    private int startAttackFrames = 3; // ~60ms if 20ms frames
    private int speechStreak = 0;

    public VadEnergy(float thresholdRms, int hangoverFrames, Listener listener) {
        this.thresholdRms = thresholdRms;
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
        boolean speechLike = rms >= thresholdRms;

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
}
