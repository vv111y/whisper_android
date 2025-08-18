package com.whispertflite.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

public class BeepPlayer {
    private final int sampleRate = 16000;
    private AudioTrack listenTrack; // single beep
    private AudioTrack ackTrack;    // first beep of double
    private AudioTrack nackTrack;   // second beep of double

    public BeepPlayer() {
        // Simple, reliable earcons (plain sines)
        listenTrack = buildStaticTrack(makeSine(1000.0, 0.25)); // ~250ms @1kHz
        ackTrack = buildStaticTrack(makeSine(800.0, 0.12));     // 120ms @800Hz
        nackTrack = buildStaticTrack(makeSine(600.0, 0.12));    // 120ms @600Hz
    }

    private AudioTrack buildStaticTrack(byte[] pcm16) {
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        AudioFormat fmt = new AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        AudioTrack t = new AudioTrack(attrs, fmt, pcm16.length, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE);
        t.write(pcm16, 0, pcm16.length);
        t.setVolume(1f);
        return t;
    }

    private byte[] makeSine(double hz, double seconds) {
        int totalSamples = (int)Math.round(seconds * sampleRate);
        byte[] out = new byte[totalSamples * 2]; // 16-bit mono
        double twoPiF = 2.0 * Math.PI * hz;
        for (int i = 0; i < totalSamples; i++) {
            double v = Math.sin(twoPiF * i / sampleRate);
            short s = (short)Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (int)Math.round(v * 0.4 * Short.MAX_VALUE)));
            out[2*i] = (byte)(s & 0xFF);
            out[2*i + 1] = (byte)((s >> 8) & 0xFF);
        }
        return out;
    }

    public void playListeningBeep() {
        if (listenTrack == null) return;
        try { listenTrack.stop(); } catch (Exception ignore) {}
        try { listenTrack.flush(); } catch (Exception ignore) {}
        try { listenTrack.setPlaybackHeadPosition(0); } catch (Exception ignore) {}
        try { listenTrack.play(); } catch (Exception ignore) {}
    }

    public void playIdleDoubleBeep(Runnable onSecondComplete) {
        if (ackTrack == null || nackTrack == null) return;
        try { ackTrack.stop(); nackTrack.stop(); } catch (Exception ignore) {}
        try { ackTrack.flush(); nackTrack.flush(); } catch (Exception ignore) {}
        try { ackTrack.setPlaybackHeadPosition(0); nackTrack.setPlaybackHeadPosition(0); } catch (Exception ignore) {}
        try { ackTrack.play(); } catch (Exception ignore) {}
        // Play second a bit later
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try { nackTrack.play(); } catch (Exception ignore) {}
            if (onSecondComplete != null) {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(onSecondComplete, 140);
            }
        }, 160);
    }

    // Quick negative feedback earcon ("dud"): play the lower second beep only
    public void playDud() {
        if (nackTrack == null) return;
        try { nackTrack.stop(); } catch (Exception ignore) {}
        try { nackTrack.flush(); } catch (Exception ignore) {}
        try { nackTrack.setPlaybackHeadPosition(0); } catch (Exception ignore) {}
        try { nackTrack.play(); } catch (Exception ignore) {}
    }

    public void release() {
        try { if (listenTrack != null) { listenTrack.release(); listenTrack = null; } } catch (Exception ignore) {}
        try { if (ackTrack != null) { ackTrack.release(); ackTrack = null; } } catch (Exception ignore) {}
        try { if (nackTrack != null) { nackTrack.release(); nackTrack = null; } } catch (Exception ignore) {}
    }
}
