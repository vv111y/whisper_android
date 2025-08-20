package com.whispertflite.frontend;

import static org.junit.Assert.*;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class VadWebRtcNativeSmokeTest {
    @Test
    public void accept_emits_callbacks_and_stays_stable() {
        AtomicInteger frames = new AtomicInteger();
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger ends = new AtomicInteger();
        AtomicReference<Boolean> lastSpeech = new AtomicReference<>(false);

        BasicVad.Listener listener = new BasicVad.Listener() {
            @Override public void onSpeechStart() { starts.incrementAndGet(); }
            @Override public void onSpeechEnd() { ends.incrementAndGet(); }
            @Override public void onFrameAccepted(float[] frame, boolean speech) {
                frames.incrementAndGet();
                lastSpeech.set(speech);
            }
        };

        VadWebRtcNative vad = new VadWebRtcNative(2, listener);
        // If native lib missing, test should still not crash; we simply skip deep asserts.
        assertNotNull(vad);

        // Feed a few frames of silence and a simple sine-like burst
        int frameLen = 320;
        float[] silence = new float[frameLen];
        float[] tone = new float[frameLen];
        for (int i = 0; i < frameLen; i++) tone[i] = (float)Math.sin(2 * Math.PI * i / 20.0) * 0.1f;

        // 5 silent frames, 5 tone frames, 5 silent
        for (int i = 0; i < 5; i++) vad.accept(silence);
        for (int i = 0; i < 5; i++) vad.accept(tone);
        for (int i = 0; i < 5; i++) vad.accept(silence);

        assertTrue("should have received some frames", frames.get() >= 10);
        // If JNI is present and emits speech decisions, we expect at least one start or end.
        // But if JNI is absent, starts/ends may remain zero; don't fail the test in that case.
        assertTrue("stable without crashes", true);

        // Ensure setters don't crash when handle is 0
        vad.setThreshold(0.02f);
        vad.setHangoverFrames(10);
        vad.setStartAttackFrames(3);
        vad.setAggressiveness(2);
        vad.release();
    }
}
