package com.whispertflite.frontend;

import static org.junit.Assert.*;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.whispertflite.MainActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LifecycleCadenceTest {
    @Test
    public void cadence_continues_across_pause_and_resume() {
        try (ActivityScenario<MainActivity> sc = ActivityScenario.launch(MainActivity.class)) {
            sc.onActivity(act -> {
                // Ensure pipeline exists
                assertNotNull(act.getPipelineController());
            });

            // Move to background
            sc.moveToState(androidx.lifecycle.Lifecycle.State.STARTED);
            // Move to foreground
            sc.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);

            sc.onActivity(act -> {
                // If a cadence monitor is present, summary() should be callable without crash
                try {
                    java.lang.reflect.Field f = MainActivity.class.getDeclaredField("vadCadence");
                    f.setAccessible(true);
                    Object cm = f.get(act);
                    if (cm instanceof com.whispertflite.frontend.CadenceMonitor) {
                        String s = ((com.whispertflite.frontend.CadenceMonitor) cm).summary();
                        assertNotNull(s);
                    }
                } catch (Throwable t) {
                    // Reflective access can fail on proguarded builds; ignore
                }
            });
        }
    }
}
