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

    if (logsPref != null) logsPref.setVisible(isDebug());
    if (simulateWakePref != null) simulateWakePref.setVisible(isDebug());

        if (resetPref != null) {
            resetPref.setOnPreferenceClickListener(p -> {
                try {
                    com.whispertflite.MainActivity act = (com.whispertflite.MainActivity) getActivity();
                    if (act != null && act.getPipelineController() != null) {
                        act.getPipelineController().resetDiagnostics();
                        updateUi();
                    }
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
                    com.whispertflite.MainActivity act = (com.whispertflite.MainActivity) getActivity();
                    if (act != null && act.getPipelineController() != null) {
                        String json = buildSnapshotJson(act.getPipelineController());
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
                    com.whispertflite.MainActivity act = (com.whispertflite.MainActivity) getActivity();
                    if (act != null && act.getPipelineController() != null) {
                        // simulate a wake trigger and start listening/capturing path
                        com.whispertflite.frontend.PipelineController pc = act.getPipelineController();
                        pc.startListening();
                        pc.onWakeTriggered(0.99);
                        updateUi();
                    }
                } catch (Throwable ignore) {}
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
        updateUi();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateUi();
    }

    private void updateUi() {
        try {
            com.whispertflite.MainActivity act = (com.whispertflite.MainActivity) getActivity();
            if (act == null) return;
            com.whispertflite.frontend.PipelineController pc = act.getPipelineController();
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
        } catch (Throwable ignore) {}
    }

    private boolean isDebug() {
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
}
