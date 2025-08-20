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

    @Test
    public void preroll_is_included_in_emitted_utterance() {
        class FakeClock implements PipelineController.Clock { long t=0; public long now(){return t;} }
        final int fs = 320;
        final java.util.concurrent.atomic.AtomicReference<float[]> captured = new java.util.concurrent.atomic.AtomicReference<>();
        PipelineController.Listener listener = new PipelineController.Listener() {
            @Override public void onStateChanged(PipelineController.State state) {}
            @Override public void onWakeTriggered(double score) {}
            @Override public void onUtteranceReady(float[] samples) { captured.set(samples); }
        };
        PipelineController pc = new PipelineController(fs, listener, new FakeClock());
        pc.setLoggingEnabled(false);
        pc.setMinArmDelayMs(0);
        pc.setInterUtteranceCooldownMs(0);
        pc.setRequiredSilenceFramesBeforeCapture(0);
        pc.setInCaptureSilenceFrames(0);
        pc.setMinUtteranceFrames(2);
        pc.setPreRollFrames(3);
        pc.startSession();
        // Build distinctive frames A,B,C for pre-roll and D for speech
        float[] A = new float[fs]; java.util.Arrays.fill(A, 1.0f);
        float[] B = new float[fs]; java.util.Arrays.fill(B, 2.0f);
        float[] C = new float[fs]; java.util.Arrays.fill(C, 3.0f);
        float[] D = new float[fs]; java.util.Arrays.fill(D, 4.0f);
        float[] Z = new float[fs]; // silence
        // Feed pre-roll while listening
        pc.onFrame(A, false);
        pc.onFrame(B, false);
        pc.onFrame(C, false);
        // Rising edge -> start capture, then provide min utterance frames with non-zero amplitude
        pc.onFrame(D, true);
        pc.onFrame(D, true);
        // Force finalize (merge window is 0)
        pc.onFrame(Z, false);
        // Listener should have received utterance starting with A,B,C
        float[] out = captured.get();
        assertNotNull(out);
        // Check first 3 frames of output
        for (int i=0;i<fs;i++) assertEquals(1.0f, out[i], 1e-6f); // A
        for (int i=0;i<fs;i++) assertEquals(2.0f, out[fs + i], 1e-6f); // B
        for (int i=0;i<fs;i++) assertEquals(3.0f, out[2*fs + i], 1e-6f); // C
    }

    @Test
    public void session_capture_is_not_aborted_by_no_frames_prefill() {
        class FakeClock implements PipelineController.Clock { long t=0; public long now(){return t;} void tick(long ms){t+=ms;} }
        FakeClock clock = new FakeClock();
        PipelineController pc = new PipelineController(320, null, clock);
        pc.setLoggingEnabled(false);
        pc.setMinArmDelayMs(0);
        pc.setInterUtteranceCooldownMs(0);
        pc.setRequiredSilenceFramesBeforeCapture(0);
        pc.setInCaptureSilenceFrames(5);
        pc.setCaptureNoFramesAbortMs(200);
        pc.startSession();
        // Build frames
        float[] zero = new float[320];
        float[] loud = new float[320]; for (int i=0;i<loud.length;i++) loud[i]=0.01f;
        // Fill some pre-roll
        pc.onFrame(zero, false);
        pc.onFrame(zero, false);
        pc.onFrame(zero, false);
        // Rising edge -> CAPTURING with pre-roll prefilled
        pc.onFrame(loud, true);
        // Advance time beyond abort window and feed one frame to evaluate abort check
        clock.tick(500);
        pc.onFrame(zero, false);
        // Should not abort due to prefilled capture frames; state stays CAPTURING or transitions via silence rules, but abort count remains 0
        assertEquals(0, pc.getDiagAbortNoFrames());
    }

    @Test
    public void rising_edge_triggers_only_once() {
        class FakeClock implements PipelineController.Clock { public long now(){return 0;} }
        PipelineController pc = new PipelineController(320, null, new FakeClock());
        pc.setLoggingEnabled(false);
        pc.setMinArmDelayMs(0);
        pc.setInterUtteranceCooldownMs(0);
        pc.setRequiredSilenceFramesBeforeCapture(0);
        pc.startSession();
        float[] loud = new float[320]; for (int i=0;i<loud.length;i++) loud[i]=0.01f;
        // Feed multiple consecutive speech frames while listening; start should occur once
        pc.onFrame(loud, true); // rising edge -> start
        // Now state is CAPTURING; additional listening starts cannot occur
        pc.onFrame(loud, true);
        pc.onFrame(loud, true);
        assertEquals(1, pc.getDiagCaptureStarted());
    }

    @Test
    public void cooldown_blocks_then_allows_after_elapsed() {
        class FakeClock implements PipelineController.Clock { long t=0; public long now(){return t;} void tick(long ms){t+=ms;} }
        FakeClock clock = new FakeClock();
        PipelineController pc = new PipelineController(320, null, clock);
        pc.setLoggingEnabled(false);
        pc.setMinArmDelayMs(0);
        pc.setInterUtteranceCooldownMs(500);
        pc.setRequiredSilenceFramesBeforeCapture(0);
        pc.setInCaptureSilenceFrames(1);
        pc.setMinUtteranceFrames(2);
        pc.startSession();
        float[] loud = new float[320]; for (int i=0;i<loud.length;i++) loud[i]=0.01f;
        float[] zero = new float[320];
        // First start
        pc.onFrame(loud, true);
        pc.onFrame(loud, true);
        // Finalize by silence
        pc.onFrame(zero, false);
        pc.onFrame(zero, false);
        // Return to listening
        pc.onTranscriptionComplete();
        assertEquals(1, pc.getDiagCaptureStarted());
        // Immediate retrigger attempt -> blocked by cooldown
        pc.onFrame(loud, true);
        assertEquals(1, pc.getDiagBlockedCooldown());
        // After cooldown elapsed, rising edge should start again
        clock.tick(600);
        // Feed a silence then rising edge
        pc.onFrame(zero, false);
        pc.onFrame(loud, true);
        assertEquals(2, pc.getDiagCaptureStarted());
    }

    @Test
    public void low_rms_boundary_not_discarded_when_above_threshold() {
        class FakeClock implements PipelineController.Clock { public long now(){return 0;} }
        // With constant amplitude frames, RMS equals amplitude
        float amp = 0.005f; // slightly above 0.004 threshold
        PipelineController.Listener listener = new PipelineController.Listener() {
            @Override public void onStateChanged(PipelineController.State state) {}
            @Override public void onWakeTriggered(double score) {}
            @Override public void onUtteranceReady(float[] samples) {}
        };
        PipelineController pc = new PipelineController(320, listener, new FakeClock());
        pc.setLoggingEnabled(false);
        pc.setMinArmDelayMs(0);
        pc.setInterUtteranceCooldownMs(0);
        pc.setRequiredSilenceFramesBeforeCapture(0);
        pc.setInCaptureSilenceFrames(0);
        pc.setMinUtteranceFrames(3);
        pc.startSession();
        float[] zero = new float[320];
        float[] quiet = new float[320]; java.util.Arrays.fill(quiet, amp);
        // Rising edge and provide enough frames
        pc.onFrame(zero, false);
        pc.onFrame(quiet, true);
        pc.onFrame(quiet, true);
        pc.onFrame(quiet, true);
        // Force finalize by silence
        pc.onFrame(zero, false);
        assertEquals(PipelineController.State.TRANSCRIBING, pc.getState());
        assertEquals(0, pc.getDiagDiscardLowRms());
    }

    @Test
    public void finalize_by_silence_increments_counter() {
        class FakeClock implements PipelineController.Clock { public long now(){return 0;} }
        PipelineController pc = new PipelineController(320, null, new FakeClock());
        pc.setLoggingEnabled(false);
        pc.setMinArmDelayMs(0);
        pc.setInterUtteranceCooldownMs(0);
        pc.setRequiredSilenceFramesBeforeCapture(0);
        pc.setInCaptureSilenceFrames(2);
        pc.setMinUtteranceFrames(2);
        pc.startSession();
        float[] loud = new float[320]; for (int i=0;i<loud.length;i++) loud[i]=0.01f;
        float[] zero = new float[320];
        // Rising edge -> start
        pc.onFrame(zero, false);
        pc.onFrame(loud, true);
        // Meet min utterance frames
        pc.onFrame(loud, true);
        // Silence within merge window
        pc.onFrame(zero, false);
        pc.onFrame(zero, false);
        // Exceed merge window to force finalize
        pc.onFrame(zero, false);
        assertEquals(PipelineController.State.TRANSCRIBING, pc.getState());
        assertEquals(1, pc.getDiagFinalizeSilenceExceeded());
    }

    @Test
    public void does_not_finalize_before_max_duration_boundary() {
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
        float[] loud = new float[320]; for (int i=0;i<loud.length;i++) loud[i]=0.01f;
        // Start and provide speech
        pc.onFrame(zero, false);
        pc.onFrame(loud, true);
        // Exactly at boundary: should NOT finalize
        clock.tick(1000);
        pc.onFrame(loud, true);
        assertEquals(PipelineController.State.CAPTURING, pc.getState());
        assertEquals(0, pc.getDiagFinalizeMaxDuration());
        // Exceed boundary: should finalize and increment counter
        clock.tick(1);
        pc.onFrame(loud, true);
        assertEquals(PipelineController.State.TRANSCRIBING, pc.getState());
        assertEquals(1, pc.getDiagFinalizeMaxDuration());
    }
}
