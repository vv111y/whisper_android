package com.whispertflite.frontend;

/**
 * Lightweight, Java-only VAD that approximates WebRTC VAD behavior using
 * RMS threshold + zero-crossing-rate windowing, with attack/hangover logic.
 * This is a stopgap for experimentation without native deps.
 */
public class VadWebRtcSimple implements BasicVad {
    private final BasicVad.Listener listener;

    private int hangoverFrames = 30;
    private int silenceCount = 0;
    private boolean inSpeech = false;
    private int startAttackFrames = 3;
    private int speechStreak = 0;

    private float threshold = 0.035f; // maps RMS
    // ZCR bounds typical for voiced phonemes (heuristic)
    private float zcrMin = 0.01f;
    private float zcrMax = 0.25f;

    public VadWebRtcSimple(float thresholdRms, int hangoverFrames, BasicVad.Listener listener) {
        this.threshold = thresholdRms;
        this.hangoverFrames = hangoverFrames;
        this.listener = listener;
    }

    @Override
    public void reset() {
        silenceCount = 0;
        inSpeech = false;
        speechStreak = 0;
    }

    @Override
    public void accept(float[] frame) {
        // Compute RMS and Zero Crossing Rate
        float rms = 0f;
        int zc = 0;
        float prev = frame.length > 0 ? frame[0] : 0f;
        for (int i = 0; i < frame.length; i++) {
            float v = frame[i];
            rms += v * v;
            if ((v >= 0f && prev < 0f) || (v < 0f && prev >= 0f)) zc++;
            prev = v;
        }
        rms = (float) Math.sqrt(rms / Math.max(1, frame.length));
        float zcr = (float) zc / Math.max(1, frame.length - 1);

        boolean speechLike = (rms >= threshold) && (zcr >= zcrMin && zcr <= zcrMax);

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
            speechStreak = 0;
        }
        if (listener != null) listener.onFrameAccepted(frame, inSpeech);
    }

    @Override
    public synchronized void setThreshold(float thr) {
        this.threshold = Math.max(0.001f, thr);
        silenceCount = 0;
        speechStreak = 0;
    }

    @Override
    public synchronized void setHangoverFrames(int frames) {
        this.hangoverFrames = Math.max(0, frames);
    }

    @Override
    public synchronized void setStartAttackFrames(int frames) {
        this.startAttackFrames = Math.max(1, frames);
    }

    // Optional tuning for zcr window
    public synchronized void setZcrRange(float min, float max) {
        this.zcrMin = Math.max(0f, min);
        this.zcrMax = Math.max(this.zcrMin, max);
    }
}
