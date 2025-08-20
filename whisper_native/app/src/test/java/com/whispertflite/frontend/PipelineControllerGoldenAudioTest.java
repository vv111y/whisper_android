package com.whispertflite.frontend;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Golden-audio style offline regression: synthesize frames and validate
 * rising-edge start with pre-roll and finalize after merge window.
 */
public class PipelineControllerGoldenAudioTest {
    private static class FakeClock implements PipelineController.Clock {
        long t;
        FakeClock(long start) { this.t = start; }
        @Override public long now() { return t; }
        void advance(long ms) { t += ms; }
    }

    private static class SpyListener implements PipelineController.Listener {
        volatile PipelineController.State lastState;
        volatile double lastWakeScore;
        volatile float[] lastUtterance;
        @Override public void onStateChanged(PipelineController.State state) { lastState = state; }
        @Override public void onWakeTriggered(double score) { lastWakeScore = score; }
        @Override public void onUtteranceReady(float[] samples) { lastUtterance = samples; }
    }

    @Test
    public void session_risingEdge_preRoll_and_mergeWindow_finalize() {
        final int frameSamples = 320;
        FakeClock clk = new FakeClock(0);
        SpyListener listener = new SpyListener();
        PipelineController pc = new PipelineController(frameSamples, listener, clk);

        // Deterministic tunables
        pc.setMinArmDelayMs(0);
        pc.setInterUtteranceCooldownMs(0);
        pc.setRequiredSilenceFramesBeforeCapture(3);
        pc.setPreRollFrames(5);
        pc.setInCaptureSilenceFrames(3);
        pc.setMinUtteranceFrames(4);
        pc.setMaxCaptureMs(60_000);
        pc.setCaptureNoFramesAbortMs(10_000);

        // Start SESSION mode to use rising-edge semantics
        pc.startSession();

        // Build frames
        float[] silence = new float[frameSamples];
        float[] speech = new float[frameSamples];
        for (int i = 0; i < frameSamples; i++) {
            // small amplitude tone-like
            speech[i] = (float) (0.02 * Math.sin(2 * Math.PI * i / 20.0));
        }

        // Feed required pre-speech silence (LISTENING)
        for (int i = 0; i < 3; i++) {
            pc.onFrame(silence, false);
        }
        // Accumulate pre-roll frames (these should be included on start)
        for (int i = 0; i < 5; i++) {
            pc.onFrame(silence, false);
        }
        // Rising edge: speech=true should start capture and copy pre-roll
        pc.onFrame(speech, true);

        assertEquals("CAPTURING after rising edge", PipelineController.State.CAPTURING, pc.getState());
        assertTrue("diagCaptureStarted increments", pc.getDiagCaptureStarted() >= 1);

        // Provide enough speech frames to exceed min utter threshold
        for (int i = 0; i < 6; i++) pc.onFrame(speech, true);

        // Provide silence to exceed merge window and force finalize
        for (int i = 0; i < pc.getInCaptureSilenceFrames() + 1; i++) pc.onFrame(silence, false);

        // Finalization path sets state to TRANSCRIBING and invokes listener
        assertEquals(PipelineController.State.TRANSCRIBING, pc.getState());
        assertNotNull("utterance emitted", listener.lastUtterance);
        assertTrue("finalizeSilenceExceeded increments", pc.getDiagFinalizeSilenceExceeded() >= 1);
        assertEquals("utterancesEmitted increments", 1, pc.getDiagUtterancesEmitted());

        // Expect utterance length to include pre-roll + speech + a few silence frames
        int minExpectedFrames = 5 /*pre-roll*/ + 1 /*first speech*/ + 6 /*extra speech*/ + 1 /*at least one silent frame kept*/;
        int minSamples = minExpectedFrames * frameSamples;
        assertTrue("utterance length includes pre-roll and content", listener.lastUtterance.length >= minSamples);
    }

    @Test
    public void tooShortUtterance_isDiscarded() {
        final int frameSamples = 320;
        FakeClock clk = new FakeClock(0);
        SpyListener listener = new SpyListener();
        PipelineController pc = new PipelineController(frameSamples, listener, clk);

    pc.setMinArmDelayMs(0);
    pc.setInterUtteranceCooldownMs(0);
    pc.setRequiredSilenceFramesBeforeCapture(0);
    pc.setPreRollFrames(0);
    pc.setInCaptureSilenceFrames(0); // immediate finalize on first silent frame
    pc.setMinUtteranceFrames(4); // require more than we will provide

        pc.startSession();
        float[] silence = new float[frameSamples];
        float[] tinySpeech = new float[frameSamples];
        for (int i = 0; i < frameSamples; i++) tinySpeech[i] = 0.01f; // above RMS floor but we'll keep count short

    // Rising edge: a single speech frame, then silence should finalize immediately
    pc.onFrame(tinySpeech, true);
    pc.onFrame(silence, false);

        // Should be discarded as too short
        assertEquals("back to LISTENING after discard", PipelineController.State.LISTENING, pc.getState());
        assertNull("no utterance emitted", listener.lastUtterance);
        assertTrue("discardTooShort increments", pc.getDiagDiscardTooShort() >= 1);
        assertEquals("no emitted utterances", 0, pc.getDiagUtterancesEmitted());
    }

    @Test
    public void cooldown_blocks_immediate_retrigger() {
        final int frameSamples = 320;
        FakeClock clk = new FakeClock(0);
        SpyListener listener = new SpyListener();
        PipelineController pc = new PipelineController(frameSamples, listener, clk);

        pc.setMinArmDelayMs(0);
        pc.setInterUtteranceCooldownMs(1000); // 1s cooldown
        pc.setRequiredSilenceFramesBeforeCapture(2);
        pc.setPreRollFrames(2);
        pc.setInCaptureSilenceFrames(2);
        pc.setMinUtteranceFrames(3);

        pc.startSession();
        float[] silence = new float[frameSamples];
        float[] speech = new float[frameSamples];
        for (int i = 0; i < frameSamples; i++) speech[i] = 0.02f;

        // First utterance
        pc.onFrame(silence, false);
        pc.onFrame(silence, false);
        pc.onFrame(speech, true);
        pc.onFrame(speech, true);
        pc.onFrame(speech, true);
        pc.onFrame(silence, false);
        pc.onFrame(silence, false);
        pc.onFrame(silence, false); // finalize via merge window
    assertEquals(PipelineController.State.TRANSCRIBING, pc.getState());
    assertEquals(1, pc.getDiagUtterancesEmitted());
    // Simulate transcription completion to return to LISTENING and set cooldown start
    pc.onTranscriptionComplete();

        // Immediately attempt another start within cooldown
        // No time advance -> now - lastCaptureEndUptimeMs == 0 < 1000
    pc.onFrame(silence, false); // listening pre-frame
        pc.onFrame(speech, true);   // rising edge attempt

        // Should be blocked by cooldown; state remains LISTENING
        assertEquals(PipelineController.State.LISTENING, pc.getState());
        assertTrue("blockedCooldown increments", pc.getDiagBlockedCooldown() >= 1);
        assertEquals("still one capture started", 1, pc.getDiagCaptureStarted());
    }

    @Test
    public void lowRms_utterance_isDiscarded() {
        final int frameSamples = 320;
        FakeClock clk = new FakeClock(0);
        SpyListener listener = new SpyListener();
        PipelineController pc = new PipelineController(frameSamples, listener, clk);

        pc.setMinArmDelayMs(0);
        pc.setInterUtteranceCooldownMs(0);
        pc.setRequiredSilenceFramesBeforeCapture(2);
        pc.setPreRollFrames(1);
        pc.setInCaptureSilenceFrames(0); // finalize on first silence
        pc.setMinUtteranceFrames(3);     // ensure we don't hit tooShort first

        pc.startSession();
        float[] silence = new float[frameSamples];
        float[] veryLow = new float[frameSamples];
        for (int i = 0; i < frameSamples; i++) veryLow[i] = 0.0005f; // RMS well below 0.004

        // Arm silence
        pc.onFrame(silence, false);
        pc.onFrame(silence, false);
        // Rising edge + minimal speech frames to meet minUtteranceFrames
        pc.onFrame(veryLow, true);
        pc.onFrame(veryLow, true);
        pc.onFrame(veryLow, true);
        // Finalize with a silence frame
        pc.onFrame(silence, false);

        // Should discard due to low RMS
        assertEquals(PipelineController.State.LISTENING, pc.getState());
        assertNull("no utterance emitted", listener.lastUtterance);
        assertTrue("discardLowRms increments", pc.getDiagDiscardLowRms() >= 1);
        assertEquals("no emitted utterances", 0, pc.getDiagUtterancesEmitted());
    }

    @Test
    public void wakeTriggered_noFrames_abort_after_grace() {
        final int frameSamples = 320;
        // Start clock sufficiently ahead so abort condition is met on first CAPTURING frame
        FakeClock clk = new FakeClock(2000);
        SpyListener listener = new SpyListener();
        PipelineController pc = new PipelineController(frameSamples, listener, clk);

        pc.setCaptureNoFramesAbortMs(500);
        pc.setMinArmDelayMs(0);
        pc.setInterUtteranceCooldownMs(0);

        // Go to listening and trigger wake start (no frames appended yet)
        pc.startListening();
        pc.onWakeTriggered(0.9);
        assertEquals(PipelineController.State.CAPTURING, pc.getState());

        // First frame arrives after a long time with no frames captured -> abort
        float[] silence = new float[frameSamples];
        pc.onFrame(silence, false);

        assertEquals("aborted back to LISTENING", PipelineController.State.LISTENING, pc.getState());
        assertTrue("abortNoFrames increments", pc.getDiagAbortNoFrames() >= 1);
        assertEquals("no utterances emitted", 0, pc.getDiagUtterancesEmitted());
    }

    @Test
    public void required_silence_gate_blocks_until_met() {
        final int frameSamples = 320;
        FakeClock clk = new FakeClock(0);
        SpyListener listener = new SpyListener();
        PipelineController pc = new PipelineController(frameSamples, listener, clk);

        pc.setMinArmDelayMs(0);
        pc.setInterUtteranceCooldownMs(0);
        pc.setRequiredSilenceFramesBeforeCapture(3);
        pc.setPreRollFrames(2);
        pc.setInCaptureSilenceFrames(2);

        pc.startSession();
        float[] silence = new float[frameSamples];
        float[] speech = new float[frameSamples];
        for (int i = 0; i < frameSamples; i++) speech[i] = 0.02f;

        // Provide insufficient silence (2 < 3)
        pc.onFrame(silence, false);
        pc.onFrame(silence, false);
        // Rising edge attempt should be blocked by required silence
        pc.onFrame(speech, true);
        assertEquals("still LISTENING when required silence not met", PipelineController.State.LISTENING, pc.getState());
        assertTrue("blockedSilence increments", pc.getDiagBlockedSilence() >= 1);

    // Provide additional silence frames to meet requirement (need 3 consecutive)
    pc.onFrame(silence, false);
    pc.onFrame(silence, false);
    pc.onFrame(silence, false);
        // Now attempt rising edge again; should start capturing
        pc.onFrame(speech, true);
        assertEquals(PipelineController.State.CAPTURING, pc.getState());
        assertTrue(pc.getDiagCaptureStarted() >= 1);
    }

    @Test
    public void max_capture_duration_forces_finalize() {
        final int frameSamples = 320;
        FakeClock clk = new FakeClock(0);
        SpyListener listener = new SpyListener();
        PipelineController pc = new PipelineController(frameSamples, listener, clk);

        pc.setMinArmDelayMs(0);
        pc.setInterUtteranceCooldownMs(0);
        pc.setRequiredSilenceFramesBeforeCapture(0);
        pc.setPreRollFrames(0);
        pc.setInCaptureSilenceFrames(100); // avoid silence finalization
        pc.setMinUtteranceFrames(1);
    pc.setMaxCaptureMs(1000); // respects controller's minimum cap (>=1000ms)

        pc.startSession();
        float[] speech = new float[frameSamples];
        for (int i = 0; i < frameSamples; i++) speech[i] = 0.02f;

        // Start capture on rising edge
        pc.onFrame(speech, true); // time 0ms
        assertEquals(PipelineController.State.CAPTURING, pc.getState());

    // Feed continuous speech, advancing clock ~20ms per frame for > 1000ms
    for (int i = 0; i < 60; i++) {
            clk.advance(20);
            pc.onFrame(speech, true);
            if (pc.getState() == PipelineController.State.TRANSCRIBING) break;
        }

        assertEquals("finalized due to max duration", PipelineController.State.TRANSCRIBING, pc.getState());
        assertTrue("finalizeMaxDuration increments", pc.getDiagFinalizeMaxDuration() >= 1);
        assertNotNull("utterance emitted", listener.lastUtterance);
    }
}
