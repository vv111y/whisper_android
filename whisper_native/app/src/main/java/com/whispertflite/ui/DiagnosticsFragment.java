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

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences_diagnostics, rootKey);
        statePref = findPreference("diag_state");
        gatePref = findPreference("diag_gates");
        countersPref = findPreference("diag_counters");
        logsPref = findPreference("diag_verbose_logs");
        resetPref = findPreference("diag_reset");
        refreshPref = findPreference("diag_refresh");

    if (logsPref != null) logsPref.setVisible(isDebug());

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
                        "Silence: " + pc.getListeningSilenceFrames() + "/" + pc.getRequiredSilenceFramesBeforeCapture();
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
}
