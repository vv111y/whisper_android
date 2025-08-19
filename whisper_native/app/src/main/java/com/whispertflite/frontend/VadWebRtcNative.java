package com.whispertflite.frontend;

public class VadWebRtcNative implements BasicVad {
    static {
        try { System.loadLibrary("audioEngine"); } catch (Throwable ignore) {}
    }

    private final BasicVad.Listener listener;
    private long handle = 0;
    private int sampleRate = 16000;
    private int frameLen = 320; // 20 ms at 16 kHz

    public VadWebRtcNative(int aggressiveness, BasicVad.Listener listener) {
        this.listener = listener;
        this.handle = nativeCreate(aggressiveness);
    }

    public boolean isReady() { return handle != 0; }

    @Override
    public void reset() {
        // no persistent buffers in native stub; noop
    }

    @Override
    public void accept(float[] frame) {
        if (handle == 0 || frame == null) return;
        // Convert float [-1,1] to int16
        short[] s = new short[frame.length];
        for (int i = 0; i < frame.length; i++) {
            float v = frame[i];
            if (v > 1f) v = 1f; else if (v < -1f) v = -1f;
            s[i] = (short) Math.round(v * 32767f);
        }
        int speech = nativeProcess(handle, s, sampleRate, frameLen);
        boolean inSpeech = (speech == 1);
        if (listener != null) listener.onFrameAccepted(frame, inSpeech);
        // Speech edges are inferred here with a tiny hangover to align with existing callbacks
        edgeDetect(inSpeech);
    }

    private boolean prevSpeech = false;
    private int hangCount = 0;
    private int hangoverFrames = 30;
    private int startAttackFrames = 3;
    private int streak = 0;
    private void edgeDetect(boolean speechLike) {
        if (speechLike) {
            hangCount = 0;
            if (!prevSpeech) {
                streak++;
                if (streak >= startAttackFrames) {
                    prevSpeech = true;
                    streak = 0;
                    if (listener != null) listener.onSpeechStart();
                }
            }
        } else if (prevSpeech) {
            hangCount++;
            if (hangCount > hangoverFrames) {
                prevSpeech = false;
                hangCount = 0;
                if (listener != null) listener.onSpeechEnd();
            }
        } else {
            streak = 0;
        }
    }

    @Override
    public synchronized void setThreshold(float thr) { nativeSetThreshold(handle, thr); }
    @Override
    public synchronized void setHangoverFrames(int frames) { this.hangoverFrames = Math.max(0, frames); }
    @Override
    public synchronized void setStartAttackFrames(int frames) { this.startAttackFrames = Math.max(1, frames); }

    public synchronized void setAggressiveness(int mode) { nativeSetMode(handle, mode); }

    public synchronized void release() { if (handle != 0) { nativeRelease(handle); handle = 0; } }

    // JNI
    private static native long nativeCreate(int mode);
    private static native void nativeRelease(long handle);
    private static native int nativeProcess(long handle, short[] frame, int sampleRate, int frameLen);
    private static native void nativeSetMode(long handle, int mode);
    private static native void nativeSetThreshold(long handle, float thr);
}
