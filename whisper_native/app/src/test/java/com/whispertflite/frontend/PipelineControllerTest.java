package com.whispertflite.frontend;

import org.junit.Test;
import static org.junit.Assert.*;

public class PipelineControllerTest {
    @Test
    public void start_blocked_by_arming_and_cooldown_and_silence() {
        class FakeClock implements PipelineController.Clock {
            long t = 0;
            public long now() { return t; }
            void tick(long ms) { t += ms; }
        }
        FakeClock clock = new FakeClock();
        PipelineController pc = new PipelineController(320, null, clock);
    pc.setLoggingEnabled(false);
        pc.setMinArmDelayMs(600);
        pc.setInterUtteranceCooldownMs(800);
        pc.setRequiredSilenceFramesBeforeCapture(3);
        pc.startSession(); // -> LISTENING, sets armedAt

        // Immediately feed a speech frame: should block by arming
        pc.onFrame(new float[320], true);
        assertEquals(PipelineController.State.LISTENING, pc.getState());
        assertEquals(1, pc.getDiagBlockedArming());

        // Simulate arming elapsed but insufficient silence frames
        pc.setMinArmDelayMs(0);
        clock.tick(1000); // exceed cooldown window
        pc.onFrame(new float[320], false); // 1 silence
        pc.onFrame(new float[320], false); // 2 silence
        pc.onFrame(new float[320], true);  // rising edge with only 2 silence -> block
        assertEquals(PipelineController.State.LISTENING, pc.getState());
        assertEquals(1, pc.getDiagBlockedSilence());

        // Provide sufficient silence
        pc.onFrame(new float[320], false); // 1
        pc.onFrame(new float[320], false); // 2
        pc.onFrame(new float[320], false); // 3
        pc.onFrame(new float[320], true);  // rising edge -> start
        assertEquals(PipelineController.State.CAPTURING, pc.getState());
        assertEquals(1, pc.getDiagCaptureStarted());

        // Finish capture to set cooldown
        // Simulate active speech and then long silence to finalize
        pc.onFrame(new float[320], true);
        for (int i = 0; i < 100; i++) pc.onFrame(new float[320], false);
        // Since we didn't provide a listener, we can't transition to TRANSCRIBING
        // but finalizeCapture path will set state to TRANSCRIBING, then back via onTranscriptionComplete
        pc.onTranscriptionComplete();
        assertEquals(PipelineController.State.LISTENING, pc.getState());

        // Now test cooldown blocks a new start
        pc.onFrame(new float[320], true);
        assertEquals(1, pc.getDiagBlockedCooldown());
    }
}
