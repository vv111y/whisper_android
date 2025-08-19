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

    @Test
    public void finalize_on_silence_exceeded() {
        class FakeClock implements PipelineController.Clock { long t=0; public long now(){return t;} void tick(long ms){t+=ms;} }
        FakeClock clock = new FakeClock();
        PipelineController.Listener listener = new PipelineController.Listener() {
            @Override public void onStateChanged(PipelineController.State state) {}
            @Override public void onWakeTriggered(double score) {}
            @Override public void onUtteranceReady(float[] samples) {}
        };
        PipelineController pc = new PipelineController(320, listener, clock);
        pc.setLoggingEnabled(false);
        pc.setMinArmDelayMs(0);
        pc.setInterUtteranceCooldownMs(0);
        pc.setRequiredSilenceFramesBeforeCapture(0);
        pc.setInCaptureSilenceFrames(2);
        pc.setMinUtteranceFrames(3);
        pc.startSession();
    // Helper frames
    float[] zero = new float[320];
    float[] loud = new float[320];
    for (int i=0;i<loud.length;i++) loud[i] = 0.01f;
    // Rising edge -> start
    pc.onFrame(zero, false);
    pc.onFrame(loud, true);
    // Speech frames (non-zero amplitude to pass RMS floor)
    pc.onFrame(loud, true);
    pc.onFrame(loud, true);
        // Silence within merge window
    pc.onFrame(zero, false);
    pc.onFrame(zero, false);
        // Silence exceeding merge window triggers finalize
    pc.onFrame(zero, false);
        assertEquals(PipelineController.State.TRANSCRIBING, pc.getState());
    }

    @Test
    public void finalize_on_max_duration() {
        class FakeClock implements PipelineController.Clock { long t=0; public long now(){return t;} void tick(long ms){t+=ms;} }
        FakeClock clock = new FakeClock();
        PipelineController pc = new PipelineController(320, null, clock);
        pc.setLoggingEnabled(false);
        pc.setMinArmDelayMs(0);
        pc.setInterUtteranceCooldownMs(0);
        pc.setRequiredSilenceFramesBeforeCapture(0);
        pc.setMaxCaptureMs(1000);
        pc.setMinUtteranceFrames(1);
        pc.startSession();
    float[] zero = new float[320];
    float[] loud = new float[320];
    for (int i=0;i<loud.length;i++) loud[i] = 0.01f;
    // Start
    pc.onFrame(zero, false);
    pc.onFrame(loud, true);
        // Provide speech frames and advance time beyond max
    pc.onFrame(loud, true);
        clock.tick(1500);
    pc.onFrame(loud, true);
        assertEquals(PipelineController.State.TRANSCRIBING, pc.getState());
        assertEquals(1, pc.getDiagFinalizeMaxDuration());
    }

    @Test
    public void discard_too_short() {
        class FakeClock implements PipelineController.Clock { long t=0; public long now(){return t;} }
        PipelineController pc = new PipelineController(320, null, new FakeClock());
        pc.setLoggingEnabled(false);
        pc.setMinArmDelayMs(0);
        pc.setInterUtteranceCooldownMs(0);
        pc.setRequiredSilenceFramesBeforeCapture(0);
        pc.setInCaptureSilenceFrames(0);
        pc.setMinUtteranceFrames(5);
        pc.startSession();
        // Start
        pc.onFrame(new float[320], false);
        pc.onFrame(new float[320], true);
        // Only a couple of frames, then exceed silence window to force finalize
        pc.onFrame(new float[320], true);
        pc.onFrame(new float[320], false);
        pc.onFrame(new float[320], false);
        pc.onFrame(new float[320], false);
        assertEquals(PipelineController.State.LISTENING, pc.getState());
        assertEquals(1, pc.getDiagDiscardTooShort());
    }

    @Test
    public void discard_low_rms() {
        class FakeClock implements PipelineController.Clock { long t=0; public long now(){return t;} }
        PipelineController pc = new PipelineController(320, null, new FakeClock());
        pc.setLoggingEnabled(false);
        pc.setMinArmDelayMs(0);
        pc.setInterUtteranceCooldownMs(0);
        pc.setRequiredSilenceFramesBeforeCapture(0);
        pc.setInCaptureSilenceFrames(0);
        pc.setMinUtteranceFrames(3);
        pc.startSession();
        // Start
        pc.onFrame(new float[320], false);
        pc.onFrame(new float[320], true);
        // Very low amplitude frames
        float[] quiet = new float[320];
        pc.onFrame(quiet, true);
        pc.onFrame(quiet, true);
        // Force finalize via silence
        pc.onFrame(new float[320], false);
        pc.onFrame(new float[320], false);
        pc.onFrame(new float[320], false);
        assertEquals(PipelineController.State.LISTENING, pc.getState());
        assertEquals(1, pc.getDiagDiscardLowRms());
    }

    @Test
    public void abort_no_frames_received() {
        class FakeClock implements PipelineController.Clock { long t=0; public long now(){return t;} void tick(long ms){t+=ms;} }
        FakeClock clock = new FakeClock();
        PipelineController pc = new PipelineController(320, null, clock);
        pc.setLoggingEnabled(false);
    // Use wake-triggered capture, which does not prefill captureFrames
    pc.startListening();
    pc.onWakeTriggered(1.0);
    // Advance time beyond abort window and feed one frame to hit abort check
    clock.tick(2000);
    pc.onFrame(new float[320], false);
        assertEquals(PipelineController.State.LISTENING, pc.getState());
        assertEquals(1, pc.getDiagAbortNoFrames());
    }
}
