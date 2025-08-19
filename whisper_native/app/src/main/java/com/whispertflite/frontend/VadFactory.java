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
        String engine = prefs.getString("pref_vad_engine", "energy");
        int hang = prefs.getInt("pref_vad_hangover", 30);
        int atk = prefs.getInt("pref_vad_attack", 3);

        if ("silero".equals(engine)) {
            VadSilero v = new VadSilero(ctx, listener);
            int thrProg = prefs.getInt("pref_vad_threshold", 35);
            float thr = 0.1f + (thrProg / 100f) * (0.9f - 0.1f);
            try {
                v.setThreshold(thr);
                v.setHangoverFrames(Math.max(0, prefs.getInt("pref_vad_hangover", 15)));
                v.setStartAttackFrames(Math.max(1, prefs.getInt("pref_vad_attack", 2)));
            } catch (Throwable ignore) {}
            return v;
        }

        // Map common RMS threshold
        int thrProg = prefs.getInt("pref_vad_threshold", 35);
        float thr = 0.005f + (thrProg / 100f) * (0.1f - 0.005f);

        if ("webrtc".equals(engine)) {
            String impl = prefs.getString("pref_vad_webrtc_impl", "simple");
            if ("native".equals(impl)) {
                String modeStr = prefs.getString("pref_vad_webrtc_mode", "2");
                int mode = 2; try { mode = Integer.parseInt(modeStr); } catch (Throwable ignore) {}
                VadWebRtcNative v = new VadWebRtcNative(mode, listener);
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
