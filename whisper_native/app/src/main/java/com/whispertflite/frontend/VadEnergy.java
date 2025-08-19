package com.whispertflite.frontend;

/**
 * Simple energy-based VAD with hangover logic.
 * NOT production robust, first MVP gate before wakeword.
 */
public class VadEnergy implements BasicVad {
    public interface Listener extends BasicVad.Listener {}

    private final Listener listener;

    private final EdgeDetector edge = new EdgeDetector(3, 30);
    private float threshold;

    public VadEnergy(float thresholdRms, int hangoverFrames, Listener listener) {
        this.threshold = thresholdRms;
        this.listener = listener;
        try { edge.setHangoverFrames(hangoverFrames); } catch (Throwable ignore) {}
    }

    public void reset() {
        try { edge.reset(); } catch (Throwable ignore) {}
    }

    public void accept(float[] frame) {
        float rms = 0f;
        for (float v : frame) rms += v * v;
        rms = (float)Math.sqrt(rms / Math.max(1, frame.length));
        boolean speechLike = rms >= threshold;

        EdgeDetector.EdgeResult er = edge.update(speechLike);
        if (listener != null) {
            if (er.start) listener.onSpeechStart();
            if (er.end) listener.onSpeechEnd();
            listener.onFrameAccepted(frame, er.inSpeech);
        }
    }

    // Live tuning API
    public synchronized void setThreshold(float thr) {
        this.threshold = Math.max(0.001f, thr);
        // Keep continuity; just clear detector streaks
        try { edge.reset(); } catch (Throwable ignore) {}
    }

    public synchronized void setHangoverFrames(int frames) {
        try { edge.setHangoverFrames(Math.max(0, frames)); } catch (Throwable ignore) {}
    }

    public synchronized void setStartAttackFrames(int frames) {
        try { edge.setAttackFrames(Math.max(1, frames)); } catch (Throwable ignore) {}
    }

    public synchronized float getThreshold() { return threshold; }
}
