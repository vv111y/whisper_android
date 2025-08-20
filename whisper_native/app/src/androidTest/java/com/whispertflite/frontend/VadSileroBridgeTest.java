package com.whispertflite.frontend;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class VadSileroBridgeTest {

    @Test
    public void onFrameAccepted_emits_per_call_even_without_full_512_chunk() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final int[] frames = {0};
        BasicVad.Listener listener = new BasicVad.Listener() {
            @Override public void onSpeechStart() {}
            @Override public void onSpeechEnd() {}
            @Override public void onFrameAccepted(float[] frame, boolean speech) { frames[0]++; }
        };
        VadSilero vad = new VadSilero(ctx, listener);
        // Force ready without loading model to avoid ONNX dependency during this small smoke test
        java.lang.reflect.Field ready = VadSilero.class.getDeclaredField("isReady");
        ready.setAccessible(true);
        ready.setBoolean(vad, true);

        // Feed frames that do not sum to 512 to avoid triggering ONNX path
        vad.accept(new float[320]); // +320
        vad.accept(new float[100]); // +420
        vad.accept(new float[91]);  // +511
        assertEquals(3, frames[0]);
    }
}
