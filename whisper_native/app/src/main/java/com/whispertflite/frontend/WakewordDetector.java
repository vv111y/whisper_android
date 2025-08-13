package com.whispertflite.frontend;

public interface WakewordDetector {
    interface Listener {
        void onWakeTriggered(double score);
    }
    void acceptFrame(float[] frame, boolean speech);
    void reset();
}
