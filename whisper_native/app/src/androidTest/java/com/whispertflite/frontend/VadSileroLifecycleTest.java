package com.whispertflite.frontend;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class VadSileroLifecycleTest {
    @Test
    public void reset_and_recreate_preserve_per_frame_cadence() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final int[] frames = {0};
        BasicVad.Listener listener = new BasicVad.Listener() {
            @Override public void onSpeechStart() {}
            @Override public void onSpeechEnd() {}
            @Override public void onFrameAccepted(float[] frame, boolean speech) { frames[0]++; }
        };
        VadSilero vad = new VadSilero(ctx, listener);
        // Force ready to bypass ONNX load
        java.lang.reflect.Field ready = VadSilero.class.getDeclaredField("isReady");
        ready.setAccessible(true);
        ready.setBoolean(vad, true);

        // Feed a couple frames
        vad.accept(new float[320]);
        vad.accept(new float[320]);
        int before = frames[0];
        assertTrue(before >= 2);

        // Simulate lifecycle: reset and recreate
        vad.reset();
        vad.release();
        VadSilero vad2 = new VadSilero(ctx, listener);
        ready.setBoolean(vad2, true);
        frames[0] = 0;
        vad2.accept(new float[320]);
        vad2.accept(new float[320]);
        assertTrue(frames[0] >= 2);
    }
}
