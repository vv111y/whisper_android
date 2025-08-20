package com.whispertflite.frontend;

import org.junit.Test;
import org.junit.Assume;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * Closed-loop auto-tuning harness for PipelineController tunables using synthetic golden-like scenarios.
 * Skipped by default; enable with -Dtune=true.
 */
public class PipelineAutoTuningTest {
    static class FakeClock implements PipelineController.Clock {
        long t; FakeClock(long start) { t = start; } @Override public long now() { return t; } void advance(long ms) { t += ms; }
    }
    static class SpyListener implements PipelineController.Listener {
        volatile PipelineController.State lastState;
        volatile double lastWakeScore;
        volatile float[] lastUtterance;
        @Override public void onStateChanged(PipelineController.State state) { lastState = state; }
        @Override public void onWakeTriggered(double score) { lastWakeScore = score; }
        @Override public void onUtteranceReady(float[] samples) { lastUtterance = samples; }
    }

    static class Metrics {
        long blockedArming, blockedCooldown, blockedSilence;
        long captureStarted, abortNoFrames, finalizeSilenceExceeded, finalizeMax, discardTooShort, discardLowRms, emitted;
    }

    static class ParamSet {
        int preRollFrames, inCaptureSilenceFrames, minUtteranceFrames, requiredSilenceFrames;
        long minArmDelayMs, cooldownMs;
        @Override public String toString() {
            return "Params{" +
                    "preRoll=" + preRollFrames +
                    ", mergeWin=" + inCaptureSilenceFrames +
                    ", minUtter=" + minUtteranceFrames +
                    ", reqSilence=" + requiredSilenceFrames +
                    ", armMs=" + minArmDelayMs +
                    ", cooldownMs=" + cooldownMs +
                    '}';
        }
    }

    private static void applyParams(PipelineController pc, ParamSet p) {
        pc.setPreRollFrames(p.preRollFrames);
        pc.setInCaptureSilenceFrames(p.inCaptureSilenceFrames);
        pc.setMinUtteranceFrames(p.minUtteranceFrames);
        pc.setRequiredSilenceFramesBeforeCapture(p.requiredSilenceFrames);
        pc.setMinArmDelayMs(p.minArmDelayMs);
        pc.setInterUtteranceCooldownMs(p.cooldownMs);
        pc.setMaxCaptureMs(12_000);
        pc.setCaptureNoFramesAbortMs(1_200);
    }

    private static Metrics runScenario_normalUtterance(ParamSet p) {
        int frameSamples = 320;
        FakeClock clk = new FakeClock(0);
        SpyListener listener = new SpyListener();
        PipelineController pc = new PipelineController(frameSamples, listener, clk);
        applyParams(pc, p);
        pc.startSession();
        float[] silence = new float[frameSamples];
        float[] speech = new float[frameSamples];
        for (int i=0;i<frameSamples;i++) speech[i] = 0.03f;

        // Meet required silence
        for (int i=0;i<p.requiredSilenceFrames;i++) pc.onFrame(silence,false);
        // rising edge + enough speech, then silence to finalize
        pc.onFrame(speech,true);
        pc.onFrame(speech,true);
        pc.onFrame(silence,false);

        Metrics m = collect(pc);
        return m;
    }

    private static Metrics runScenario_cooldownBlock(ParamSet p) {
        int frameSamples = 320;
        FakeClock clk = new FakeClock(0);
        SpyListener listener = new SpyListener();
        PipelineController pc = new PipelineController(frameSamples, listener, clk);
        applyParams(pc, p);
        pc.startSession();
        float[] silence = new float[frameSamples];
        float[] speech = new float[frameSamples];
        for (int i=0;i<frameSamples;i++) speech[i]=0.02f;

        for (int i=0;i<p.requiredSilenceFrames;i++) pc.onFrame(silence,false);
        pc.onFrame(speech,true);
        pc.onFrame(speech,true);
        pc.onFrame(silence,false);
        pc.onTranscriptionComplete();

        // Attempt within cooldown while past arming
        long adv = Math.max(0, (int)p.minArmDelayMs);
        clk.advance(Math.min(adv+1, Math.max(1, (int)p.cooldownMs/2)));
        pc.onFrame(silence,false);
        pc.onFrame(speech,true);

        return collect(pc);
    }

    private static Metrics runScenario_lowRmsReject(ParamSet p) {
        int frameSamples = 320;
        FakeClock clk = new FakeClock(0);
        SpyListener listener = new SpyListener();
        PipelineController pc = new PipelineController(frameSamples, listener, clk);
        applyParams(pc, p);
        pc.startSession();
        float[] silence = new float[frameSamples];
        float[] low = new float[frameSamples];
        for (int i=0;i<frameSamples;i++) low[i]=0.0035f;
        for (int i=0;i<p.requiredSilenceFrames;i++) pc.onFrame(silence,false);
        pc.onFrame(low,true);
        pc.onFrame(low,true);
        pc.onFrame(low,true);
        pc.onFrame(silence,false);
        return collect(pc);
    }

    private static Metrics runScenario_requiredSilenceBlocks(ParamSet p) {
        int frameSamples = 320;
        FakeClock clk = new FakeClock(0);
        SpyListener listener = new SpyListener();
        PipelineController pc = new PipelineController(frameSamples, listener, clk);
        applyParams(pc, p);
        pc.startSession();
        float[] silence = new float[frameSamples];
        float[] speech = new float[frameSamples];
        for (int i=0;i<frameSamples;i++) speech[i]=0.02f;

        // Attempt early -> expect blocked by silence
        if (p.minArmDelayMs>0) { clk.advance(p.minArmDelayMs); }
        pc.onFrame(speech,true);
        // Provide enough silence then start
        for (int i=0;i<p.requiredSilenceFrames;i++) pc.onFrame(silence,false);
        pc.onFrame(speech,true);
        pc.onFrame(speech,true);
        pc.onFrame(silence,false);
        return collect(pc);
    }

    private static Metrics runScenario_noiseBurstFalseTrigger(ParamSet p) {
        int frameSamples = 320;
        FakeClock clk = new FakeClock(0);
        SpyListener listener = new SpyListener();
        PipelineController pc = new PipelineController(frameSamples, listener, clk);
        applyParams(pc, p);
        pc.startSession();
        float[] silence = new float[frameSamples];
        float[] burst = new float[frameSamples];
        for (int i=0;i<frameSamples;i++) burst[i]=0.03f;

        // No required silence: try to provoke a too-short start & discard
        for (int i=0;i<Math.max(0,p.requiredSilenceFrames-1);i++) pc.onFrame(silence,false);
        pc.onFrame(burst,true); // rising edge
        pc.onFrame(silence,false); // finalize quickly (merge window default may include 1)
        return collect(pc);
    }

    private static Metrics runScenario_multiUtteranceConversation(ParamSet p) {
        int frameSamples = 320;
        FakeClock clk = new FakeClock(0);
        SpyListener listener = new SpyListener();
        PipelineController pc = new PipelineController(frameSamples, listener, clk);
        applyParams(pc, p);
        pc.startSession();
        float[] silence = new float[frameSamples];
        float[] speech = new float[frameSamples];
        for (int i=0;i<frameSamples;i++) speech[i]=0.025f;

        int turns = 3;
        for (int t=0;t<turns;t++) {
            for (int i=0;i<p.requiredSilenceFrames;i++) pc.onFrame(silence,false);
            pc.onFrame(speech,true);
            pc.onFrame(speech,true);
            pc.onFrame(silence,false);
            if (pc.getState()==PipelineController.State.TRANSCRIBING) pc.onTranscriptionComplete();
            // advance time some but maybe within cooldown once to reward blocks
            if (t==1) { clk.advance(Math.max(1, p.minArmDelayMs)); pc.onFrame(silence,false); pc.onFrame(speech,true); }
        }
        return collect(pc);
    }

    private static Metrics runScenario_outputGating(ParamSet p) {
        int frameSamples = 320;
        SpyListener listener = new SpyListener();
        FakeClock clk = new FakeClock(0);
        PipelineController pc = new PipelineController(frameSamples, listener, clk);
        applyParams(pc, p);
        pc.startSession();
        float[] silence = new float[frameSamples];
        float[] speech = new float[frameSamples];
        for (int i=0;i<frameSamples;i++) speech[i]=0.03f;

        pc.onOutputStart();
        pc.onFrame(silence,false);
        pc.onFrame(speech,true); // ignored while gated
        pc.onOutputEnd();
        for (int i=0;i<p.requiredSilenceFrames;i++) pc.onFrame(silence,false);
        pc.onFrame(speech,true);
        pc.onFrame(speech,true);
        pc.onFrame(silence,false);
        return collect(pc);
    }

    private static Metrics collect(PipelineController pc) {
        Metrics m = new Metrics();
        m.blockedArming = pc.getDiagBlockedArming();
        m.blockedCooldown = pc.getDiagBlockedCooldown();
        m.blockedSilence = pc.getDiagBlockedSilence();
        m.captureStarted = pc.getDiagCaptureStarted();
        m.abortNoFrames = pc.getDiagAbortNoFrames();
        m.finalizeSilenceExceeded = pc.getDiagFinalizeSilenceExceeded();
        m.finalizeMax = pc.getDiagFinalizeMaxDuration();
        m.discardTooShort = pc.getDiagDiscardTooShort();
        m.discardLowRms = pc.getDiagDiscardLowRms();
        m.emitted = pc.getDiagUtterancesEmitted();
        return m;
    }

    private static int score(ParamSet p) {
        // Scenario 1: Normal utterance should emit 1, minimal penalties
        Metrics a = runScenario_normalUtterance(p);
        int s1 = 120 * Math.min(1, (int)a.emitted) - 40*(int)a.discardTooShort - 40*(int)a.discardLowRms - 5*(int)(a.blockedArming + a.blockedCooldown + a.blockedSilence);
        // Scenario 2: Cooldown block should register >=1 block
        Metrics b = runScenario_cooldownBlock(p);
        int s2 = 40 * Math.min(1, (int)b.blockedCooldown) + 10 * Math.min(1, (int)b.captureStarted) - 20 * (int)b.emitted; // discourage emission on second attempt
        // Scenario 3: Low RMS should be discarded
        Metrics c = runScenario_lowRmsReject(p);
        int s3 = 60 * Math.min(1, (int)c.discardLowRms) - 50 * Math.min(1, (int)c.emitted);
        // Scenario 4: Required silence should block first, then allow one
        Metrics d = runScenario_requiredSilenceBlocks(p);
        int s4 = 40 * Math.min(1, (int)d.blockedSilence) + 40 * Math.min(1, (int)d.captureStarted) + 40 * Math.min(1, (int)d.emitted);
        // Scenario 5: Noise burst should not produce emissions; too-short/discard is acceptable but not preferred
        Metrics e = runScenario_noiseBurstFalseTrigger(p);
        int s5 = 20 * (e.emitted==0 ? 1 : 0) - 10 * Math.min(1, (int)e.discardTooShort);
        // Scenario 6: Multi-utterance conversation should emit multiple but respect cooldown at least once
        Metrics f = runScenario_multiUtteranceConversation(p);
        int s6 = 30 * Math.min(1, (int)(f.captureStarted>=3 ? 1 : 0)) + 20 * Math.min(1, (int)(f.emitted>=3 ? 1 : 0)) + 10 * Math.min(1, (int)f.blockedCooldown);
        // Scenario 7: Output gating should suppress barge-in while gated, then allow one
        Metrics g = runScenario_outputGating(p);
        int s7 = 30 * Math.min(1, (int)g.emitted) + 10 * (g.blockedArming+g.blockedCooldown+g.blockedSilence==0 ? 1 : 0);
        return s1 + s2 + s3 + s4 + s5 + s6 + s7;
    }

    @Test
    public void auto_tune_report_top_configs_when_enabled() {
        // Skip unless explicitly enabled to keep CI fast.
        boolean enabled = Boolean.getBoolean("tune");
        Assume.assumeTrue("Enable with -Dtune=true", enabled);

        // Search space (kept small for speed; random sample)
        int[] preRoll = {0, 2, 4};
        int[] mergeWin = {0, 2, 4};
        int[] minUtter = {2, 3, 4};
        int[] reqSilence = {0, 2, 3, 5};
        long[] armMs = {0L, 100L, 300L};
        long[] cooldownMs = {400L, 800L};

    int samples = Integer.getInteger("tune.samples", 200);
    long seed = Long.getLong("tune.seed", 42L);
    Random rng = new Random(seed);
    List<ParamSet> candidates = new ArrayList<>();
    for (int i=0;i<samples;i++) { // random sample rather than full grid
            ParamSet p = new ParamSet();
            p.preRollFrames = preRoll[rng.nextInt(preRoll.length)];
            p.inCaptureSilenceFrames = mergeWin[rng.nextInt(mergeWin.length)];
            p.minUtteranceFrames = minUtter[rng.nextInt(minUtter.length)];
            p.requiredSilenceFrames = reqSilence[rng.nextInt(reqSilence.length)];
            p.minArmDelayMs = armMs[rng.nextInt(armMs.length)];
            p.cooldownMs = cooldownMs[rng.nextInt(cooldownMs.length)];
            candidates.add(p);
        }

        List<int[]> scored = new ArrayList<>(); // [score, index]
        for (int i=0;i<candidates.size();i++) {
            int sc = score(candidates.get(i));
            scored.add(new int[]{sc, i});
        }
        scored.sort(Comparator.comparingInt(a -> -a[0]));

        // Report top 5
        int topK = Math.min(5, scored.size());
    System.out.println("=== Auto-Tuning Top Configs (score) ===");
        for (int k=0;k<topK;k++) {
            int idx = scored.get(k)[1];
            int sc = scored.get(k)[0];
            System.out.println(sc + "\t" + candidates.get(idx));
        }

        // Sanity: best score should be above a minimum baseline
    assertTrue("Best score should exceed baseline", scored.get(0)[0] > 180);

        // Persist best config to build/auto_tune/best_config.json for easy import
        try {
            int bestIdx = scored.get(0)[1];
            ParamSet bp = candidates.get(bestIdx);
            org.json.JSONObject jo = new org.json.JSONObject();
            org.json.JSONObject params = new org.json.JSONObject();
            params.put("preRoll", bp.preRollFrames);
            params.put("mergeWin", bp.inCaptureSilenceFrames);
            params.put("minUtter", bp.minUtteranceFrames);
            params.put("reqSilence", bp.requiredSilenceFrames);
            params.put("armMs", bp.minArmDelayMs);
            params.put("cooldownMs", bp.cooldownMs);
            // include common safety fields so import sets all in one go
            params.put("maxCaptureMs", 12_000);
            params.put("noFramesAbortMs", 1_200);
            jo.put("params", params);
            jo.put("score", scored.get(0)[0]);

            java.nio.file.Path outDir = java.nio.file.Paths.get("build", "auto_tune");
            java.nio.file.Files.createDirectories(outDir);
            java.nio.file.Path out = outDir.resolve("best_config.json");
            java.nio.file.Files.write(out, jo.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            System.out.println("Wrote best config to: " + out.toAbsolutePath());
        } catch (Throwable t) {
            System.out.println("Failed to write best_config.json: " + t);
        }
    }
}
