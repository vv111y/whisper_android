package com.whispertflite.frontend;

import org.junit.Test;
import static org.junit.Assert.*;

public class PipelineControllerImmediateSilenceTest {
    @Test
    public void immediate_silence_after_start_discards_when_below_min_utterance() {
        class FakeClock implements PipelineController.Clock { public long now(){return 0;} }
        PipelineController pc = new PipelineController(320, null, new FakeClock());
        pc.setLoggingEnabled(false);
        // Remove gates and pre-roll; do not include silence in capture
        pc.setMinArmDelayMs(0);
        pc.setInterUtteranceCooldownMs(0);
        pc.setRequiredSilenceFramesBeforeCapture(0);
        pc.setPreRollFrames(0);
        pc.setInCaptureSilenceFrames(0);
        pc.setMinUtteranceFrames(2); // require at least 2 frames total
    // Note on contract: when a rising edge starts capture, PipelineController immediately
    // transitions to CAPTURING and returns from onFrame() to avoid double-processing the
    // same frame. With inCaptureSilenceFrames=0, the next silent frame finalizes; since
    // only one speech frame was captured, minUtteranceFrames=2 forces a discard.
        pc.startSession();
        float[] loud = new float[320]; for (int i=0;i<loud.length;i++) loud[i] = 0.02f;
        float[] zero = new float[320];
        // Rising edge -> CAPTURING (adds first loud frame)
        pc.onFrame(zero, false);
        pc.onFrame(loud, true);
        // Immediate silence with merge window=0 triggers finalize; capture had only 1 frame
        pc.onFrame(zero, false);
        assertEquals(PipelineController.State.LISTENING, pc.getState());
        assertEquals(1, pc.getDiagDiscardTooShort());
    }
}
