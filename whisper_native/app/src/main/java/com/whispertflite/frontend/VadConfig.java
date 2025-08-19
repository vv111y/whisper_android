package com.whispertflite.frontend;

import android.content.SharedPreferences;

/**
 * Configuration for building a VAD instance. Contains pre-mapped thresholds
 * so the factory does not need to inspect SharedPreferences directly.
 */
public class VadConfig {
    public final String engine;              // energy | webrtc | silero
    public final String webrtcImpl;          // simple | native
    public final int webrtcMode;             // 0..3
    public final float rmsThreshold;         // for energy/webrtc
    public final float sileroThreshold;      // probability [0..1]
    public final int hangoverFrames;
    public final int attackFrames;

    public VadConfig(String engine,
                     String webrtcImpl,
                     int webrtcMode,
                     float rmsThreshold,
                     float sileroThreshold,
                     int hangoverFrames,
                     int attackFrames) {
        this.engine = engine;
        this.webrtcImpl = webrtcImpl;
        this.webrtcMode = webrtcMode;
        this.rmsThreshold = rmsThreshold;
        this.sileroThreshold = sileroThreshold;
        this.hangoverFrames = hangoverFrames;
        this.attackFrames = attackFrames;
    }

    public static VadConfig fromPreferences(SharedPreferences prefs) {
        String engine = prefs.getString("pref_vad_engine", "energy");
        String impl = prefs.getString("pref_vad_webrtc_impl", "simple");
        String modeStr = prefs.getString("pref_vad_webrtc_mode", "2");
        int mode = 2;
        try { mode = Integer.parseInt(modeStr); } catch (Throwable ignore) {}

        // UI threshold slider 1..100
        int thrProg = prefs.getInt("pref_vad_threshold", 35);
        float rmsThr = 0.005f + (thrProg / 100f) * (0.1f - 0.005f);
        float sileroThr = 0.1f + (thrProg / 100f) * (0.9f - 0.1f);
        int hang = prefs.getInt("pref_vad_hangover", "silero".equals(engine) ? 15 : 30);
        int atk = prefs.getInt("pref_vad_attack", "silero".equals(engine) ? 2 : 3);

        return new VadConfig(engine, impl, mode, rmsThr, sileroThr, hang, atk);
    }
}
