package com.whispertflite.frontend;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Factory for building configured VAD instances from preferences.
 * Threshold mappings:
 *  - energy/webrtc: UI 1..100 -> RMS 0.005..0.1
 *  - silero: UI 1..100 -> prob 0.1..0.9
 */
public final class VadFactory {
    private VadFactory() {}

    public static BasicVad create(Context ctx,
                                  SharedPreferences prefs,
                                  BasicVad.Listener listener) {
        VadConfig cfg = VadConfig.fromPreferences(prefs);
        return create(ctx, cfg, listener);
    }

    public static BasicVad create(Context ctx,
                                  VadConfig cfg,
                                  BasicVad.Listener listener) {
        String engine = cfg.engine;
        int hang = cfg.hangoverFrames;
        int atk = cfg.attackFrames;

        if ("silero".equals(engine)) {
            VadSilero v = new VadSilero(ctx, listener);
            float thr = cfg.sileroThreshold;
            try {
                v.setThreshold(thr);
                v.setHangoverFrames(Math.max(0, hang));
                v.setStartAttackFrames(Math.max(1, atk));
            } catch (Throwable ignore) {}
            return v;
        }

        // Map common RMS threshold
        float thr = cfg.rmsThreshold;

        if ("webrtc".equals(engine)) {
            String impl = cfg.webrtcImpl;
            if ("native".equals(impl)) {
                VadWebRtcNative v = new VadWebRtcNative(cfg.webrtcMode, listener);
                try {
                    v.setThreshold(thr);
                    v.setHangoverFrames(hang);
                    v.setStartAttackFrames(atk);
                } catch (Throwable ignore) {}
                return v;
            } else {
                VadWebRtcSimple v = new VadWebRtcSimple(thr, hang, listener);
                try { v.setStartAttackFrames(atk); } catch (Throwable ignore) {}
                return v;
            }
        }

        // default: energy
        VadEnergy v = new VadEnergy(thr, hang, new VadEnergy.Listener() {
            @Override public void onSpeechStart() { if (listener != null) listener.onSpeechStart(); }
            @Override public void onSpeechEnd() { if (listener != null) listener.onSpeechEnd(); }
            @Override public void onFrameAccepted(float[] frame, boolean speech) {
                if (listener != null) listener.onFrameAccepted(frame, speech);
            }
        });
        try { v.setStartAttackFrames(atk); } catch (Throwable ignore) {}
        return v;
    }
}
