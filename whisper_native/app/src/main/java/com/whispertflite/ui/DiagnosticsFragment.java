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
    private Preference docsPref;
    private Preference simulateWakePref;
    private Preference sessionCheckPref;
    private Preference cadencePref;
    private Preference cadenceResetPref;
    private Preference presetDefaultPref;
    private Preference presetQuietPref;
    private Preference presetNoisyPref;
    private Preference presetResetPref;
    private Preference presetApplyAutoPref;
    private Preference presetImportAutoPref;
    private Preference presetSaveCustomPref;
    private Preference presetApplyCustomPref;
    private Preference presetClearCustomPref;
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
    docsPref = findPreference("diag_docs");
    simulateWakePref = findPreference("diag_simulate_wake");
    sessionCheckPref = findPreference("diag_session_check");
    cadencePref = findPreference("diag_cadence");
    cadenceResetPref = findPreference("diag_cadence_reset");
    presetDefaultPref = findPreference("diag_preset_default");
    presetQuietPref = findPreference("diag_preset_quiet");
    presetNoisyPref = findPreference("diag_preset_noisy");
    presetResetPref = findPreference("diag_preset_reset");
    presetSaveCustomPref = findPreference("diag_preset_save_custom");
    presetApplyCustomPref = findPreference("diag_preset_apply_custom");
    presetClearCustomPref = findPreference("diag_preset_clear_custom");
    presetApplyAutoPref = findPreference("diag_preset_apply_auto");
    presetImportAutoPref = findPreference("diag_preset_import_auto");
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
    if (cadencePref != null) cadencePref.setVisible(dbg);
    if (cadenceResetPref != null) cadenceResetPref.setVisible(dbg);
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
        if (docsPref != null) {
            docsPref.setOnPreferenceClickListener(p -> {
                try {
                    android.content.Context ctx = getContext();
                    if (ctx == null) return true;
                    android.content.Intent i = new android.content.Intent(ctx, com.whispertflite.ui.MarkdownViewerActivity.class);
                    i.putExtra("title", "Diagnostics docs");
                    i.putExtra("assetPath", "docs/DIAGNOSTICS.md");
                    startActivity(i);
                } catch (Throwable ignore) {}
                return true;
            });
        }
        if (cadenceResetPref != null) {
            cadenceResetPref.setOnPreferenceClickListener(p -> {
                try {
                    com.whispertflite.MainActivity act = (com.whispertflite.MainActivity) getActivity();
                    if (act != null) {
                        java.lang.reflect.Field f = com.whispertflite.MainActivity.class.getDeclaredField("vadCadence");
                        f.setAccessible(true);
                        Object cm = f.get(act);
                        if (cm instanceof com.whispertflite.frontend.CadenceMonitor) {
                            ((com.whispertflite.frontend.CadenceMonitor) cm).reset();
                        }
                    }
                } catch (Throwable ignore) {}
                updateUi();
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
    if (presetDefaultPref != null) presetDefaultPref.setOnPreferenceClickListener(p -> { applyPreset("default"); return true; });
    if (presetQuietPref != null) presetQuietPref.setOnPreferenceClickListener(p -> { applyPreset("quiet"); return true; });
    if (presetNoisyPref != null) presetNoisyPref.setOnPreferenceClickListener(p -> { applyPreset("noisy"); return true; });
    if (presetResetPref != null) presetResetPref.setOnPreferenceClickListener(p -> { applyPreset("default"); return true; });
    if (presetSaveCustomPref != null) presetSaveCustomPref.setOnPreferenceClickListener(p -> { saveCustomFromCurrent(); return true; });
    if (presetApplyCustomPref != null) presetApplyCustomPref.setOnPreferenceClickListener(p -> { applyPreset("custom"); return true; });
    if (presetClearCustomPref != null) presetClearCustomPref.setOnPreferenceClickListener(p -> { clearCustomPreset(); return true; });
    if (presetApplyAutoPref != null) presetApplyAutoPref.setOnPreferenceClickListener(p -> { applyPreset("auto"); return true; });
    if (presetImportAutoPref != null) presetImportAutoPref.setOnPreferenceClickListener(p -> { importAutoPresetViaDialog(); return true; });
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
                String preset = getLastPreset();
                if (preset != null && !preset.isEmpty()) s += "\nPreset: " + preset;
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
            if (cadencePref != null) cadencePref.setSummary(getCadenceSummary());
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

    private String getCadenceSummary() {
        if (!isDebug()) return "-";
        try {
            com.whispertflite.MainActivity act = (com.whispertflite.MainActivity) getActivity();
            if (act != null) {
                java.lang.reflect.Field f = com.whispertflite.MainActivity.class.getDeclaredField("vadCadence");
                f.setAccessible(true);
                Object cm = f.get(act);
                if (cm instanceof com.whispertflite.frontend.CadenceMonitor) {
                    return ((com.whispertflite.frontend.CadenceMonitor) cm).summary();
                }
            }
        } catch (Throwable ignore) {}
        return "-";
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

    private void applyPreset(String which) {
        try {
            com.whispertflite.frontend.PipelineController pc = getPc();
            if (pc == null) return;
            if ("default".equals(which)) {
                pc.setPreRollFrames(18);
                pc.setInCaptureSilenceFrames(35);
                pc.setMinArmDelayMs(600);
                pc.setInterUtteranceCooldownMs(800);
                pc.setMaxCaptureMs(12_000);
                pc.setMinUtteranceFrames(22);
                pc.setRequiredSilenceFramesBeforeCapture(6);
                pc.setCaptureNoFramesAbortMs(1200);
            } else if ("quiet".equals(which)) {
                pc.setPreRollFrames(14);
                pc.setInCaptureSilenceFrames(28);
                pc.setMinArmDelayMs(300);
                pc.setInterUtteranceCooldownMs(500);
                pc.setMaxCaptureMs(10_000);
                pc.setMinUtteranceFrames(18);
                pc.setRequiredSilenceFramesBeforeCapture(4);
                pc.setCaptureNoFramesAbortMs(1000);
            } else if ("noisy".equals(which)) {
                pc.setPreRollFrames(22);
                pc.setInCaptureSilenceFrames(20);
                pc.setMinArmDelayMs(800);
                pc.setInterUtteranceCooldownMs(1200);
                pc.setMaxCaptureMs(9_000);
                pc.setMinUtteranceFrames(28);
                pc.setRequiredSilenceFramesBeforeCapture(8);
                pc.setCaptureNoFramesAbortMs(1500);
            } else if ("custom".equals(which)) {
                if (!applyCustomTo(pc)) {
                    try { android.widget.Toast.makeText(getContext(), "No Custom preset saved", android.widget.Toast.LENGTH_SHORT).show(); } catch (Throwable ignore) {}
                    return;
                }
            } else if ("auto".equals(which)) {
                if (!applyAutoTo(pc)) {
                    try { android.widget.Toast.makeText(getContext(), "No Auto preset imported", android.widget.Toast.LENGTH_SHORT).show(); } catch (Throwable ignore) {}
                    return;
                }
            }
            saveLastPreset(which);
            updateUi();
            try { android.widget.Toast.makeText(getContext(), "Preset applied: " + which, android.widget.Toast.LENGTH_SHORT).show(); } catch (Throwable ignore) {}
        } catch (Throwable ignore) {}
    }

    private void saveCustomFromCurrent() {
        try {
            com.whispertflite.frontend.PipelineController pc = getPc();
            if (pc == null) return;
            android.content.Context ctx = getContext(); if (ctx == null) return;
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putInt("diag_custom_preRoll", pc.getPreRollFrames())
                .putInt("diag_custom_mergeSilence", pc.getInCaptureSilenceFrames())
                .putLong("diag_custom_minArm", pc.getMinArmDelayMs())
                .putLong("diag_custom_cooldown", pc.getInterUtteranceCooldownMs())
                .putLong("diag_custom_maxCapture", pc.getMaxCaptureMs())
                .putInt("diag_custom_minUtter", pc.getMinUtteranceFrames())
                .putInt("diag_custom_reqSilence", pc.getRequiredSilenceFramesBeforeCapture())
                .putLong("diag_custom_noFramesAbort", pc.getCaptureNoFramesAbortMs())
                .apply();
            saveLastPreset("custom");
            updateUi();
            try { android.widget.Toast.makeText(getContext(), "Saved Custom preset", android.widget.Toast.LENGTH_SHORT).show(); } catch (Throwable ignore) {}
        } catch (Throwable ignore) {}
    }

    private boolean applyCustomTo(com.whispertflite.frontend.PipelineController pc) {
        try {
            android.content.Context ctx = getContext(); if (ctx == null) return false;
            android.content.SharedPreferences sp = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx);
            if (!sp.contains("diag_custom_preRoll")) return false;
            pc.setPreRollFrames(sp.getInt("diag_custom_preRoll", pc.getPreRollFrames()));
            pc.setInCaptureSilenceFrames(sp.getInt("diag_custom_mergeSilence", pc.getInCaptureSilenceFrames()));
            pc.setMinArmDelayMs(sp.getLong("diag_custom_minArm", pc.getMinArmDelayMs()));
            pc.setInterUtteranceCooldownMs(sp.getLong("diag_custom_cooldown", pc.getInterUtteranceCooldownMs()));
            pc.setMaxCaptureMs(sp.getLong("diag_custom_maxCapture", pc.getMaxCaptureMs()));
            pc.setMinUtteranceFrames(sp.getInt("diag_custom_minUtter", pc.getMinUtteranceFrames()));
            pc.setRequiredSilenceFramesBeforeCapture(sp.getInt("diag_custom_reqSilence", pc.getRequiredSilenceFramesBeforeCapture()));
            pc.setCaptureNoFramesAbortMs(sp.getLong("diag_custom_noFramesAbort", pc.getCaptureNoFramesAbortMs()));
            return true;
        } catch (Throwable ignore) {
            return false;
        }
    }

    private boolean applyAutoTo(com.whispertflite.frontend.PipelineController pc) {
        try {
            android.content.Context ctx = getContext(); if (ctx == null) return false;
            android.content.SharedPreferences sp = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx);
            if (!sp.contains("diag_auto_preRoll")) return false;
            pc.setPreRollFrames(sp.getInt("diag_auto_preRoll", pc.getPreRollFrames()));
            pc.setInCaptureSilenceFrames(sp.getInt("diag_auto_mergeSilence", pc.getInCaptureSilenceFrames()));
            pc.setMinArmDelayMs(sp.getLong("diag_auto_minArm", pc.getMinArmDelayMs()));
            pc.setInterUtteranceCooldownMs(sp.getLong("diag_auto_cooldown", pc.getInterUtteranceCooldownMs()));
            pc.setMaxCaptureMs(sp.getLong("diag_auto_maxCapture", pc.getMaxCaptureMs()));
            pc.setMinUtteranceFrames(sp.getInt("diag_auto_minUtter", pc.getMinUtteranceFrames()));
            pc.setRequiredSilenceFramesBeforeCapture(sp.getInt("diag_auto_reqSilence", pc.getRequiredSilenceFramesBeforeCapture()));
            pc.setCaptureNoFramesAbortMs(sp.getLong("diag_auto_noFramesAbort", pc.getCaptureNoFramesAbortMs()));
            return true;
        } catch (Throwable ignore) {
            return false;
        }
    }

    private void importAutoPresetViaDialog() {
        try {
            if (!isDebug()) { try { android.widget.Toast.makeText(getContext(), "Debug only", android.widget.Toast.LENGTH_SHORT).show(); } catch (Throwable ignore) {} return; }
            android.content.Context ctx = getContext(); if (ctx == null) return;
            final android.widget.EditText input = new android.widget.EditText(ctx);
            input.setHint("Paste best_config JSON");
            new androidx.appcompat.app.AlertDialog.Builder(ctx)
                    .setTitle("Import Auto preset")
                    .setView(input)
                    .setPositiveButton("Import", (d, w) -> {
                        try {
                            String txt = input.getText().toString();
                            org.json.JSONObject jo = new org.json.JSONObject(txt);
                            // Accept either flat fields or nested under "params"
                            org.json.JSONObject p = jo.has("params") ? jo.getJSONObject("params") : jo;
                            android.content.SharedPreferences sp = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx);
                            sp.edit()
                                .putInt("diag_auto_preRoll", p.optInt("preRoll", 18))
                                .putInt("diag_auto_mergeSilence", p.optInt("mergeWin", 35))
                                .putLong("diag_auto_minArm", p.optLong("armMs", 600))
                                .putLong("diag_auto_cooldown", p.optLong("cooldownMs", 800))
                                .putLong("diag_auto_maxCapture", p.optLong("maxCaptureMs", 12_000))
                                .putInt("diag_auto_minUtter", p.optInt("minUtter", 22))
                                .putInt("diag_auto_reqSilence", p.optInt("reqSilence", 6))
                                .putLong("diag_auto_noFramesAbort", p.optLong("noFramesAbortMs", 1200))
                                .apply();
                            try { android.widget.Toast.makeText(ctx, "Imported Auto preset", android.widget.Toast.LENGTH_SHORT).show(); } catch (Throwable ignore) {}
                        } catch (Throwable t) {
                            try { android.widget.Toast.makeText(ctx, "Failed to parse JSON", android.widget.Toast.LENGTH_SHORT).show(); } catch (Throwable ignore) {}
                        }
                    })
                    .setNegativeButton("Cancel", (d, w) -> {})
                    .show();
        } catch (Throwable ignore) {}
    }

    private void clearCustomPreset() {
        try {
            android.content.Context ctx = getContext(); if (ctx == null) return;
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .remove("diag_custom_preRoll")
                .remove("diag_custom_mergeSilence")
                .remove("diag_custom_minArm")
                .remove("diag_custom_cooldown")
                .remove("diag_custom_maxCapture")
                .remove("diag_custom_minUtter")
                .remove("diag_custom_reqSilence")
                .remove("diag_custom_noFramesAbort")
                .apply();
            try { android.widget.Toast.makeText(getContext(), "Cleared Custom preset", android.widget.Toast.LENGTH_SHORT).show(); } catch (Throwable ignore) {}
        } catch (Throwable ignore) {}
    }

    private void saveLastPreset(String which) {
        try {
            android.content.Context ctx = getContext(); if (ctx == null) return;
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
                .edit().putString("diag_last_preset", which).apply();
        } catch (Throwable ignore) {}
    }

    private String getLastPreset() {
        try {
            android.content.Context ctx = getContext(); if (ctx == null) return null;
            return androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
                .getString("diag_last_preset", "");
        } catch (Throwable ignore) { return null; }
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
        // Version & build/device info
        sb.append("\"version\":1,");
        // Snapshot timestamps
        try {
            long epoch = java.lang.System.currentTimeMillis();
            long uptime = android.os.SystemClock.uptimeMillis();
            sb.append("\"snapshotEpochMs\":" ).append(epoch).append(',');
            sb.append("\"snapshotUptimeMs\":").append(uptime).append(',');
        } catch (Throwable ignore) {}
        try {
            android.content.Context ctx = getContext();
            if (ctx != null) {
                sb.append("\"app\":{");
                sb.append("\"pkg\":\"").append(ctx.getPackageName()).append("\",");
                android.content.pm.PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
                sb.append("\"versionName\":\"").append(pi.versionName).append("\",");
                sb.append("\"versionCode\":").append(pi.getLongVersionCode()).append('}').append(',');
            }
        } catch (Throwable ignore) {}
        try {
            sb.append("\"device\":{");
            sb.append("\"brand\":\"").append(android.os.Build.BRAND).append("\",");
            sb.append("\"model\":\"").append(android.os.Build.MODEL).append("\",");
            sb.append("\"sdk\":").append(android.os.Build.VERSION.SDK_INT).append('}').append(',');
        } catch (Throwable ignore) {}
        sb.append("\"state\":\"").append(pc.getState()).append("\",");
        sb.append("\"mode\":\"").append(pc.getMode()).append("\",");
        sb.append("\"inputGated\":").append(pc.isInputGated()).append(',');
        sb.append("\"loggingEnabled\":").append(pc.isLoggingEnabled()).append(',');
        try { String preset = getLastPreset(); if (preset != null && !preset.isEmpty()) { sb.append("\"preset\":\"").append(escape(preset)).append("\","); } } catch (Throwable ignore) {}
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
        // Cadence summary (debug only field, best-effort)
        try {
            com.whispertflite.MainActivity act = (com.whispertflite.MainActivity) getActivity();
            if (act != null) {
                java.lang.reflect.Field f = com.whispertflite.MainActivity.class.getDeclaredField("vadCadence");
                f.setAccessible(true);
                Object cm = f.get(act);
                if (cm instanceof com.whispertflite.frontend.CadenceMonitor) {
                    String summary = ((com.whispertflite.frontend.CadenceMonitor) cm).summary();
                    sb.append(',').append("\"cadence\":\"").append(escape(summary)).append("\"");
                }
            }
        } catch (Throwable ignore) {}
        sb.append('}');
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
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
