package com.whispertflite.ui;

import android.os.Bundle;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;
import com.whispertflite.R;

public class DiagnosticsFragment extends PreferenceFragmentCompat {
    private Preference statePref;
    private Preference gatePref;
    private Preference countersPref;
    private SwitchPreferenceCompat logsPref;
    private Preference resetPref;
    private Preference refreshPref;
    private Preference exportPref;
    private Preference simulateWakePref;
    private Preference sessionCheckPref;
    private androidx.preference.PreferenceCategory quickTuneCategory;
    private androidx.preference.SeekBarPreference qtPreRoll;
    private androidx.preference.SeekBarPreference qtMergeSilence;
    private androidx.preference.SeekBarPreference qtMinArm;
    private androidx.preference.SeekBarPreference qtCooldown;
    private androidx.preference.SeekBarPreference qtMaxCapture;
    private androidx.preference.SeekBarPreference qtMinUtter;
    private androidx.preference.SeekBarPreference qtReqSilence;
    private androidx.preference.SeekBarPreference qtNoFramesAbort;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences_diagnostics, rootKey);
        statePref = findPreference("diag_state");
        gatePref = findPreference("diag_gates");
        countersPref = findPreference("diag_counters");
    logsPref = findPreference("diag_verbose_logs");
    resetPref = findPreference("diag_reset");
    refreshPref = findPreference("diag_refresh");
    exportPref = findPreference("diag_export");
    simulateWakePref = findPreference("diag_simulate_wake");
    sessionCheckPref = findPreference("diag_session_check");
    quickTuneCategory = findPreference("diag_qt_category");
    qtPreRoll = findPreference("diag_qt_preRollFrames");
    qtMergeSilence = findPreference("diag_qt_mergeSilenceFrames");
    qtMinArm = findPreference("diag_qt_minArmMs");
    qtCooldown = findPreference("diag_qt_cooldownMs");
    qtMaxCapture = findPreference("diag_qt_maxCaptureMs");
    qtMinUtter = findPreference("diag_qt_minUtterFrames");
    qtReqSilence = findPreference("diag_qt_requiredSilenceFrames");
    qtNoFramesAbort = findPreference("diag_qt_noFramesAbortMs");

        boolean dbg = isDebug();
        if (logsPref != null) logsPref.setVisible(dbg);
        if (simulateWakePref != null) simulateWakePref.setVisible(dbg);
    if (sessionCheckPref != null) sessionCheckPref.setVisible(dbg);
        // Hide quick tune sliders in release builds
        if (!dbg) {
            if (quickTuneCategory != null) quickTuneCategory.setVisible(false);
            if (qtPreRoll != null) qtPreRoll.setVisible(false);
            if (qtMergeSilence != null) qtMergeSilence.setVisible(false);
            if (qtMinArm != null) qtMinArm.setVisible(false);
            if (qtCooldown != null) qtCooldown.setVisible(false);
            if (qtMaxCapture != null) qtMaxCapture.setVisible(false);
            if (qtMinUtter != null) qtMinUtter.setVisible(false);
            if (qtReqSilence != null) qtReqSilence.setVisible(false);
            if (qtNoFramesAbort != null) qtNoFramesAbort.setVisible(false);
        }

        if (resetPref != null) {
            resetPref.setOnPreferenceClickListener(p -> {
                try {
                    com.whispertflite.frontend.PipelineController pc = getPc();
                    if (pc == null) return true;
                    pc.resetDiagnostics();
                    updateUi();
                } catch (Throwable ignore) {}
                return true;
            });
        }
        if (refreshPref != null) {
            refreshPref.setOnPreferenceClickListener(p -> { updateUi(); return true; });
        }
        if (exportPref != null) {
            exportPref.setOnPreferenceClickListener(p -> {
                try {
                        com.whispertflite.frontend.PipelineController pc = getPc();
                        if (pc != null) {
                            String json = buildSnapshotJson(pc);
                        android.content.Intent sendIntent = new android.content.Intent(android.content.Intent.ACTION_SEND);
                        sendIntent.setType("application/json");
                        sendIntent.putExtra(android.content.Intent.EXTRA_TEXT, json);
                        android.content.Intent shareIntent = android.content.Intent.createChooser(sendIntent, "Share diagnostics snapshot");
                        startActivity(shareIntent);
                    }
                } catch (Throwable ignore) {}
                return true;
            });
        }
        if (simulateWakePref != null) {
            simulateWakePref.setOnPreferenceClickListener(p -> {
                try {
                    com.whispertflite.frontend.PipelineController pc = getPc();
                    if (pc == null) return true;
                    pc.startListening();
                    pc.onWakeTriggered(0.99);
                    updateUi();
                } catch (Throwable ignore) {}
                return true;
            });
        }
        if (sessionCheckPref != null) {
            sessionCheckPref.setOnPreferenceClickListener(p -> {
                try { runSessionSelfCheck(); } catch (Throwable ignore) {}
                return true;
            });
        }
        if (logsPref != null) {
            logsPref.setOnPreferenceChangeListener((p, newVal) -> {
                try {
                    com.whispertflite.MainActivity act = (com.whispertflite.MainActivity) getActivity();
                    if (act != null && act.getPipelineController() != null) {
                        act.getPipelineController().setLoggingEnabled(Boolean.TRUE.equals(newVal));
                    }
                } catch (Throwable ignore) {}
                return true;
            });
        }
    // Quick tune bindings (debug only)
    bindQuickTune();
        updateUi();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateUi();
    }

    @Override
    public void onStart() {
        super.onStart();
        // In case PipelineController wiring finishes after onCreatePreferences
        updateUi();
        // Small delayed refresh to catch late init
        try {
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.postDelayed(this::updateUi, 300);
        } catch (Throwable ignore) {}
    }

    private com.whispertflite.frontend.PipelineController getPc() {
        try {
            com.whispertflite.MainActivity act = (com.whispertflite.MainActivity) getActivity();
            if (act != null && act.getPipelineController() != null) return act.getPipelineController();
        } catch (Throwable ignore) {}
        try { return com.whispertflite.frontend.PipelineLocator.get(); } catch (Throwable ignore) {}
        return null;
    }

    private void updateUi() {
        try {
            com.whispertflite.frontend.PipelineController pc = getPc();
            if (pc == null) return;

            if (statePref != null) {
                String s = "State: " + pc.getState() + "\n" +
                        "Input gated: " + pc.isInputGated();
                statePref.setSummary(s);
            }
            if (gatePref != null) {
        String s = "Arming remaining: " + pc.getArmingRemainingMs() + " ms\n" +
            "Cooldown remaining: " + pc.getCooldownRemainingMs() + " ms\n" +
            "Silence: " + pc.getListeningSilenceFrames() + "/" + pc.getRequiredSilenceFramesBeforeCapture() + "\n" +
            "Tunables: preRoll=" + pc.getPreRollFrames() + ", mergeSilenceFrames=" + pc.getInCaptureSilenceFrames() + "\n" +
            "  minArmMs=" + pc.getMinArmDelayMs() + ", cooldownMs=" + pc.getInterUtteranceCooldownMs() + ", maxCaptureMs=" + pc.getMaxCaptureMs() + "\n" +
            "  minUtterFrames=" + pc.getMinUtteranceFrames() + ", noFramesAbortMs=" + pc.getCaptureNoFramesAbortMs() + ", frameSamples=" + pc.getFrameSamples();
                gatePref.setSummary(s);
            }
            if (countersPref != null) {
                String s = "Blocked: arming=" + pc.getDiagBlockedArming() +
                        ", cooldown=" + pc.getDiagBlockedCooldown() +
                        ", silence=" + pc.getDiagBlockedSilence() + "\n" +
                        "Capture: started=" + pc.getDiagCaptureStarted() +
                        ", abortNoFrames=" + pc.getDiagAbortNoFrames() + "\n" +
                        "Finalize: silenceExceeded=" + pc.getDiagFinalizeSilenceExceeded() +
                        ", maxDuration=" + pc.getDiagFinalizeMaxDuration() + "\n" +
                        "Discard: tooShort=" + pc.getDiagDiscardTooShort() +
                        ", lowRms=" + pc.getDiagDiscardLowRms() + "\n" +
                        "Utterances emitted: " + pc.getDiagUtterancesEmitted();
                countersPref.setSummary(s);
            }
            if (logsPref != null) logsPref.setChecked(pc.isLoggingEnabled());
            // Snap quick-tune sliders to current values
            if (qtPreRoll != null) qtPreRoll.setValue(pc.getPreRollFrames());
            if (qtMergeSilence != null) qtMergeSilence.setValue(pc.getInCaptureSilenceFrames());
            if (qtMinArm != null) qtMinArm.setValue((int) pc.getMinArmDelayMs());
            if (qtCooldown != null) qtCooldown.setValue((int) pc.getInterUtteranceCooldownMs());
            if (qtMaxCapture != null) qtMaxCapture.setValue((int) pc.getMaxCaptureMs());
            if (qtMinUtter != null) qtMinUtter.setValue(pc.getMinUtteranceFrames());
            if (qtReqSilence != null) qtReqSilence.setValue(pc.getRequiredSilenceFramesBeforeCapture());
            if (qtNoFramesAbort != null) qtNoFramesAbort.setValue((int) pc.getCaptureNoFramesAbortMs());
        } catch (Throwable ignore) {}
    }

    private void bindQuickTune() {
        try {
            if (!isDebug()) return;
            com.whispertflite.frontend.PipelineController pc = getPc();
            if (pc == null) return;
            if (qtPreRoll != null) qtPreRoll.setOnPreferenceChangeListener((p, v) -> { pc.setPreRollFrames(asInt(v)); updateUi(); return true; });
            if (qtMergeSilence != null) qtMergeSilence.setOnPreferenceChangeListener((p, v) -> { pc.setInCaptureSilenceFrames(asInt(v)); updateUi(); return true; });
            if (qtMinArm != null) qtMinArm.setOnPreferenceChangeListener((p, v) -> { pc.setMinArmDelayMs(asLong(v)); updateUi(); return true; });
            if (qtCooldown != null) qtCooldown.setOnPreferenceChangeListener((p, v) -> { pc.setInterUtteranceCooldownMs(asLong(v)); updateUi(); return true; });
            if (qtMaxCapture != null) qtMaxCapture.setOnPreferenceChangeListener((p, v) -> { pc.setMaxCaptureMs(asLong(v)); updateUi(); return true; });
            if (qtMinUtter != null) qtMinUtter.setOnPreferenceChangeListener((p, v) -> { pc.setMinUtteranceFrames(asInt(v)); updateUi(); return true; });
            if (qtReqSilence != null) qtReqSilence.setOnPreferenceChangeListener((p, v) -> { pc.setRequiredSilenceFramesBeforeCapture(asInt(v)); updateUi(); return true; });
            if (qtNoFramesAbort != null) qtNoFramesAbort.setOnPreferenceChangeListener((p, v) -> { pc.setCaptureNoFramesAbortMs(asLong(v)); updateUi(); return true; });
        } catch (Throwable ignore) {}
    }

    private int asInt(Object v) { try { return (v instanceof Integer) ? (Integer) v : Integer.parseInt(String.valueOf(v)); } catch (Throwable t) { return 0; } }
    private long asLong(Object v) { try { return (v instanceof Integer) ? ((Integer) v).longValue() : Long.parseLong(String.valueOf(v)); } catch (Throwable t) { return 0L; } }

    private boolean isDebug() {
        // Prefer robust runtime flag; fallback to BuildConfig reflection
        try {
            android.content.Context ctx = getContext();
            if (ctx != null) {
                int flags = ctx.getApplicationInfo().flags;
                boolean dbg = (flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
                if (dbg) return true;
            }
        } catch (Throwable ignore) {}
        try {
            Class<?> c = Class.forName("com.whispertflite.BuildConfig");
            java.lang.reflect.Field f = c.getField("DEBUG");
            return f.getBoolean(null);
        } catch (Throwable t) {
            return false;
        }
    }

    private String buildSnapshotJson(com.whispertflite.frontend.PipelineController pc) {
        // Build a compact JSON without external deps
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"state\":\"").append(pc.getState()).append("\",");
        sb.append("\"mode\":\"").append(pc.getMode()).append("\",");
        sb.append("\"inputGated\":").append(pc.isInputGated()).append(',');
        sb.append("\"loggingEnabled\":").append(pc.isLoggingEnabled()).append(',');
        sb.append("\"armingRemainingMs\":").append(pc.getArmingRemainingMs()).append(',');
        sb.append("\"cooldownRemainingMs\":").append(pc.getCooldownRemainingMs()).append(',');
        sb.append("\"silence\":{");
        sb.append("\"current\":").append(pc.getListeningSilenceFrames()).append(',');
        sb.append("\"required\":").append(pc.getRequiredSilenceFramesBeforeCapture()).append('}').append(',');
        sb.append("\"tunables\":{");
        sb.append("\"preRollFrames\":").append(pc.getPreRollFrames()).append(',');
        sb.append("\"mergeSilenceFrames\":").append(pc.getInCaptureSilenceFrames()).append(',');
        sb.append("\"minArmDelayMs\":").append(pc.getMinArmDelayMs()).append(',');
        sb.append("\"cooldownMs\":").append(pc.getInterUtteranceCooldownMs()).append(',');
        sb.append("\"maxCaptureMs\":").append(pc.getMaxCaptureMs()).append(',');
        sb.append("\"minUtterFrames\":").append(pc.getMinUtteranceFrames()).append(',');
        sb.append("\"noFramesAbortMs\":").append(pc.getCaptureNoFramesAbortMs()).append(',');
        sb.append("\"frameSamples\":").append(pc.getFrameSamples());
        sb.append('}').append(',');
        sb.append("\"counters\":{");
        sb.append("\"blockedArming\":").append(pc.getDiagBlockedArming()).append(',');
        sb.append("\"blockedCooldown\":").append(pc.getDiagBlockedCooldown()).append(',');
        sb.append("\"blockedSilence\":").append(pc.getDiagBlockedSilence()).append(',');
        sb.append("\"captureStarted\":").append(pc.getDiagCaptureStarted()).append(',');
        sb.append("\"abortNoFrames\":").append(pc.getDiagAbortNoFrames()).append(',');
        sb.append("\"finalizeSilenceExceeded\":").append(pc.getDiagFinalizeSilenceExceeded()).append(',');
        sb.append("\"finalizeMaxDuration\":").append(pc.getDiagFinalizeMaxDuration()).append(',');
        sb.append("\"discardTooShort\":").append(pc.getDiagDiscardTooShort()).append(',');
        sb.append("\"discardLowRms\":").append(pc.getDiagDiscardLowRms()).append(',');
        sb.append("\"utterancesEmitted\":").append(pc.getDiagUtterancesEmitted());
        sb.append('}');
        sb.append('}');
        return sb.toString();
    }

    // Debug-only: synthetic gating simulation to validate session behavior without mic
    private void runSessionSelfCheck() {
        if (!isDebug()) return;
        com.whispertflite.frontend.PipelineController pc = getPc();
        if (pc == null) return;
    // Snapshot tunables and then run a deterministic simulation
    int frameSamples = pc.getFrameSamples();
    long oldArm = pc.getMinArmDelayMs();
    long oldCooldown = pc.getInterUtteranceCooldownMs();
    int oldReqSil = pc.getRequiredSilenceFramesBeforeCapture();
    int oldMerge = pc.getInCaptureSilenceFrames();
    int oldMinUtter = pc.getMinUtteranceFrames();
    long oldAbort = pc.getCaptureNoFramesAbortMs();
    pc.resetDiagnostics();
        // Build some synthetic frames: silence, then speech blocks, then silence
        float[] silence = new float[frameSamples];
        float[] speech = new float[frameSamples];
        for (int i = 0; i < frameSamples; i++) speech[i] = (float) (0.02 * Math.sin(2 * Math.PI * i / 20.0));

        StringBuilder report = new StringBuilder();
        report.append("Session self-check:\n");
        try {
            // Start session
            pc.startSession();
            // Relax gates to exercise transitions deterministically
            pc.setMinArmDelayMs(0);
            pc.setInterUtteranceCooldownMs(0);
            pc.setRequiredSilenceFramesBeforeCapture(2);
            pc.setInCaptureSilenceFrames(Math.max(3, oldMerge));
            pc.setMinUtteranceFrames(Math.max(3, oldMinUtter));
            pc.setCaptureNoFramesAbortMs(5000);
            // 1) Immediately try to start with no silence -> should block on silence/arming
            int attempts = 5;
            for (int i = 0; i < attempts; i++) pc.onFrame(speech, true);
            boolean blockedByArming = pc.getDiagBlockedArming() > 0 || pc.getArmingRemainingMs() > 0; // arming is 0 now
            boolean blockedBySilence = pc.getDiagBlockedSilence() > 0 || pc.getListeningSilenceFrames() < pc.getRequiredSilenceFramesBeforeCapture();
            report.append("- Rising edge before arming/silence: ")
                  .append(blockedByArming || blockedBySilence ? "blocked (OK)" : "not blocked (check)").append('\n');

            // 2) Feed enough silence frames to satisfy required silence
            int reqSil = pc.getRequiredSilenceFramesBeforeCapture();
            for (int i = 0; i < reqSil + 1; i++) pc.onFrame(silence, false);
            // 3) Provide speech to trigger capture (respecting cooldown/arming)
            for (int i = 0; i < pc.getPreRollFrames() + 2; i++) pc.onFrame(silence, false);
            pc.onFrame(speech, true);
            // While capturing, provide min utterance frames worth of speech
            int minUtter = pc.getMinUtteranceFrames();
            for (int i = 0; i < minUtter + 2; i++) pc.onFrame(speech, true);
            // Then enough silence to force finalize via merge window
            for (int i = 0; i < pc.getInCaptureSilenceFrames() + 1; i++) pc.onFrame(silence, false);
            // At this point, we expect either TRANSCRIBING or back to LISTENING depending on listener
            com.whispertflite.frontend.PipelineController.State st = pc.getState();
            report.append("- Finalize after merge window: state=").append(st).append('\n');

            // 4) Cooldown check: immediately attempt another start should be blocked
            pc.onFrame(speech, true);
            boolean cooldownBlocks = pc.getDiagBlockedCooldown() > 0 || pc.getCooldownRemainingMs() > 0;
            report.append("- Cooldown blocks immediate retrigger: ").append(cooldownBlocks ? "yes" : "no").append('\n');
        } catch (Throwable t) {
            report.append("Error: ").append(t.getMessage()).append('\n');
        } finally {
            // Restore tunables
            try {
                pc.setMinArmDelayMs(oldArm);
                pc.setInterUtteranceCooldownMs(oldCooldown);
                pc.setRequiredSilenceFramesBeforeCapture(oldReqSil);
                pc.setInCaptureSilenceFrames(oldMerge);
                pc.setMinUtteranceFrames(oldMinUtter);
                pc.setCaptureNoFramesAbortMs(oldAbort);
            } catch (Throwable ignore) {}
        }

        try {
            android.widget.Toast.makeText(getContext(), report.toString(), android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable ignore) {}
        updateUi();
    }
}
