package com.whispertflite.frontend;

public interface BasicVad {
    interface Listener {
        void onSpeechStart();
        void onSpeechEnd();
        void onFrameAccepted(float[] frame, boolean speech);
    }

    void reset();
    void accept(float[] frame);
    void setThreshold(float thr);
    void setHangoverFrames(int frames);
    void setStartAttackFrames(int frames);
}
