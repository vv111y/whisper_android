package com.whispertflite.ui;

import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;
import androidx.preference.ListPreference;

import com.whispertflite.R;

public class SettingsFragment extends PreferenceFragmentCompat {
    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences, rootKey);
        // Listen mode (Wakeword vs Session)
        Preference listenMode = findPreference("pref_listen_mode");
        if (listenMode != null) {
            // Do not attempt to talk to MainActivity directly here; it may be SettingsActivity.
            // Persist and let MainActivity react via a SharedPreferences listener.
            listenMode.setOnPreferenceChangeListener((p, newVal) -> true);
        }
            // VAD engine segmented toggle
            Preference vadEngine = findPreference("pref_vad_engine");
            if (vadEngine != null) {
                vadEngine.setOnPreferenceChangeListener((p, newVal) -> true);
            }
    // Mode selector + buttons
    Preference mode = findPreference("pref_config_mode");
    Preference btnReset = findPreference("pref_reset_defaults");
    Preference btnSave = findPreference("pref_save_defaults");
    Preference btnFactory = findPreference("pref_factory_reset");
    if (mode != null) {
        mode.setOnPreferenceChangeListener((p, newVal) -> {
            showModeValues(String.valueOf(newVal));
            return true;
        });
        // Ensure current mode values are reflected on open
        String current = PreferenceManager.getDefaultSharedPreferences(requireContext()).getString("pref_config_mode", "cmd");
        showModeValues(current);
    }
    if (btnReset != null) btnReset.setOnPreferenceClickListener(p -> { resetDefaults(); return true; });
    if (btnSave != null) btnSave.setOnPreferenceClickListener(p -> { saveCurrentAsDefaults(); return true; });
    if (btnFactory != null) btnFactory.setOnPreferenceClickListener(p -> { factoryResetDefaults(); return true; });
        // Also handle clicking the row Defaults button via performClick()
        mode.setOnPreferenceClickListener(p -> { resetDefaults(); return true; });
    bindSummaries();

        // Populate model list from app's external files dir
        ListPreference modelList = findPreference("pref_model_file");
        Preference validatePref = findPreference("pref_validate_model");
    if (modelList != null) {
            try {
                android.content.Context ctx = requireContext();
                java.io.File dir = ctx.getExternalFilesDir(null);
                java.util.ArrayList<String> names = new java.util.ArrayList<>();
                java.util.ArrayList<String> paths = new java.util.ArrayList<>();
                if (dir != null && dir.exists()) {
                    java.io.File[] files = dir.listFiles();
                    if (files != null) {
                        for (java.io.File f : files) {
                            if (f.isFile() && f.getName().endsWith(".tflite")) {
                                names.add(f.getName());
                                paths.add(f.getAbsolutePath());
                            }
                        }
                    }
                }
                if (!names.isEmpty()) {
                    modelList.setEntries(names.toArray(new CharSequence[0]));
                    modelList.setEntryValues(paths.toArray(new CharSequence[0]));
                    // If not set, default to whisper-tiny.tflite if present
                    String cur = PreferenceManager.getDefaultSharedPreferences(ctx).getString("pref_model_file", null);
                    if (cur == null) {
                        for (int i = 0; i < names.size(); i++) {
                            if (names.get(i).equals("whisper-tiny.tflite")) {
                                PreferenceManager.getDefaultSharedPreferences(ctx).edit().putString("pref_model_file", paths.get(i)).apply();
                                break;
                            }
                        }
                    }
                    // Show filename only in summary
                    String pathNow = PreferenceManager.getDefaultSharedPreferences(ctx).getString("pref_model_file", null);
                    if (pathNow != null) {
                        modelList.setSummary(new java.io.File(pathNow).getName());
                    }
                } else {
                    modelList.setSummary("No .tflite files found in app storage");
                }
            } catch (Throwable ignore) {}

            modelList.setOnPreferenceChangeListener((pref, newVal) -> {
                    String path = String.valueOf(newVal);
                    // Auto-validate and block bad models
                    try {
                        android.content.Context ctx = requireContext();
                        boolean isEnglishOnly = path.endsWith(".en.tflite");
                        boolean isMultilingual = !isEnglishOnly;
                        com.whispertflite.engine.WhisperEngineNative engine = new com.whispertflite.engine.WhisperEngineNative(ctx);
                        int code = engine.validateModel(path, isMultilingual);
                        if (code != 0) {
                            String name = new java.io.File(path).getName();
                            showDialog("Model", "Invalid: " + name + "\n" + engine.lastError());
                            return false; // block selection (“ghost”)
                        }
                    } catch (Throwable t) {
                        showDialog("Model", "Validation error: " + t.getMessage());
                        return false;
                    }
                    // OK: update summary and notify MainActivity
                    try {
                        pref.setSummary(new java.io.File(path).getName());
                        com.whispertflite.MainActivity act = (com.whispertflite.MainActivity) getActivity();
                        if (act != null) act.onModelPreferenceChanged(path);
                    } catch (Throwable ignore) {}
                    return true;
                });
        }

        if (validatePref != null) {
            validatePref.setOnPreferenceClickListener(p -> {
                try {
                    android.content.Context ctx = requireContext();
                    android.content.SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(ctx);
                    String modelPath = sp.getString("pref_model_file", null);
                    if (modelPath == null) {
                        showDialog("Model Validation", "Select a model first in Settings");
                        return true;
                    }
                    boolean isEnglishOnly = modelPath.endsWith(".en.tflite");
                    boolean isMultilingual = !isEnglishOnly;
                    com.whispertflite.engine.WhisperEngineNative engine = new com.whispertflite.engine.WhisperEngineNative(ctx);
                    int code = engine.validateModel(modelPath, isMultilingual);
                    String name = new java.io.File(modelPath).getName();
                    String msg = code == 0 ? ("Model OK: " + name) : ("Invalid: code " + code + "\n" + engine.lastError());
                    showDialog("Model Validation", msg);
                } catch (Throwable t) {
                    showDialog("Model Validation", "Validation error: " + t.getMessage());
                }
                return true;
            });
        }
            // Bottom dual buttons (New defaults | Factory reset)
            com.whispertflite.ui.pref.DualButtonsPreference dual = findPreference("pref_dual_bottom");
            if (dual != null) {
                dual.setOnPreferenceClickListener(p -> true);
                dual.setListener(new com.whispertflite.ui.pref.DualButtonsPreference.Listener() {
                    @Override public void onLeftClick(Preference self) { saveCurrentAsDefaults(); }
                    @Override public void onRightClick(Preference self) { factoryResetDefaults(); }
                });
            }

        // Keep Recorder checkbox in sync when user toggles preference in Settings
        SwitchPreferenceCompat mediaToggle = findPreference("pref_capture_media");
        if (mediaToggle != null) {
            mediaToggle.setOnPreferenceChangeListener((pref, newVal) -> {
                try {
                    com.whispertflite.MainActivity act = (com.whispertflite.MainActivity) getActivity();
                    if (act != null) {
                        act.runOnUiThread(() -> {
                            android.widget.CheckBox cb = act.findViewById(com.whispertflite.R.id.chkCaptureMedia);
                            if (cb != null) cb.setChecked(Boolean.TRUE.equals(newVal) || (newVal instanceof Boolean && (Boolean) newVal));
                        });
                    }
                } catch (Throwable ignore) {}
                return true;
            });
        }
    }

    // Legacy: keep for internal use; no longer surfaced
    private void applyRouterProfile() {
    // Short commands (prefer user-saved defaults if available)
    android.content.SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(requireContext());
    int thr = sp.getInt("defaults_router_vad_thr", 35);
    int hang = sp.getInt("defaults_router_vad_hang", 30);
    int atk = sp.getInt("defaults_router_vad_atk", 3);
    int pre = sp.getInt("defaults_router_pre_roll", 12);
    int inc = sp.getInt("defaults_router_incap_silence", 20);
    int minU = sp.getInt("defaults_router_min_utter", 12);
    int arm = sp.getInt("defaults_router_arm_delay", 400);
    int cool = sp.getInt("defaults_router_cooldown", 600);
    int maxc = sp.getInt("defaults_router_max_capture", 8000);
    android.content.SharedPreferences.Editor e = sp.edit();
    // Generic keys (so sliders reflect instantly)
    e.putInt("pref_vad_threshold", thr)
     .putInt("pref_vad_hangover", hang)
     .putInt("pref_vad_attack", atk)
     .putInt("pref_pre_roll_frames", pre)
     .putInt("pref_incap_silence_frames", inc)
     .putInt("pref_min_utter_frames", minU)
     .putInt("pref_min_arm_delay_ms", arm)
     .putInt("pref_inter_cooldown_ms", cool)
     .putInt("pref_max_capture_ms", maxc);
    // Profile-specific copies (used at runtime for command mode)
    e.putInt("cmd_vad_threshold", thr)
     .putInt("cmd_vad_hangover", hang)
     .putInt("cmd_vad_attack", atk)
     .putInt("cmd_pre_roll_frames", pre)
     .putInt("cmd_incap_silence_frames", inc)
     .putInt("cmd_min_utter_frames", minU)
     .putInt("cmd_min_arm_delay_ms", arm)
     .putInt("cmd_inter_cooldown_ms", cool)
     .putInt("cmd_max_capture_ms", maxc)
     .apply();
    // Visually snap sliders
    setSeekValue("pref_vad_threshold", thr);
    setSeekValue("pref_vad_hangover", hang);
    setSeekValue("pref_vad_attack", atk);
    setSeekValue("pref_pre_roll_frames", pre);
    setSeekValue("pref_incap_silence_frames", inc);
    setSeekValue("pref_min_utter_frames", minU);
    setSeekValue("pref_min_arm_delay_ms", arm);
    setSeekValue("pref_inter_cooldown_ms", cool);
    setSeekValue("pref_max_capture_ms", maxc);
    // no toast
    }

    // Legacy: keep for internal use; no longer surfaced
    private void applyChatProfile() {
    // Longer multi-sentence (prefer user-saved defaults if available)
    android.content.SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(requireContext());
    int thr = sp.getInt("defaults_chat_vad_thr", 35);
    int hang = sp.getInt("defaults_chat_vad_hang", 30);
    int atk = sp.getInt("defaults_chat_vad_atk", 3);
    int pre = sp.getInt("defaults_chat_pre_roll", 20);
    int inc = sp.getInt("defaults_chat_incap_silence", 60);
    int minU = sp.getInt("defaults_chat_min_utter", 18);
    int arm = sp.getInt("defaults_chat_arm_delay", 600);
    int cool = sp.getInt("defaults_chat_cooldown", 800);
    int maxc = sp.getInt("defaults_chat_max_capture", 90_000);
    android.content.SharedPreferences.Editor e = sp.edit();
    // Generic keys (so sliders reflect instantly)
    e.putInt("pref_vad_threshold", thr)
     .putInt("pref_vad_hangover", hang)
     .putInt("pref_vad_attack", atk)
     .putInt("pref_pre_roll_frames", pre)
     .putInt("pref_incap_silence_frames", inc)
     .putInt("pref_min_utter_frames", minU)
     .putInt("pref_min_arm_delay_ms", arm)
     .putInt("pref_inter_cooldown_ms", cool)
     .putInt("pref_max_capture_ms", maxc);
    // Profile-specific copies (used at runtime for chat mode)
    e.putInt("chat_vad_threshold", thr)
     .putInt("chat_vad_hangover", hang)
     .putInt("chat_vad_attack", atk)
     .putInt("chat_pre_roll_frames", pre)
     .putInt("chat_incap_silence_frames", inc)
     .putInt("chat_min_utter_frames", minU)
     .putInt("chat_min_arm_delay_ms", arm)
     .putInt("chat_inter_cooldown_ms", cool)
     .putInt("chat_max_capture_ms", maxc)
     .apply();
    // Visually snap sliders
    setSeekValue("pref_vad_threshold", thr);
    setSeekValue("pref_vad_hangover", hang);
    setSeekValue("pref_vad_attack", atk);
    setSeekValue("pref_pre_roll_frames", pre);
    setSeekValue("pref_incap_silence_frames", inc);
    setSeekValue("pref_min_utter_frames", minU);
    setSeekValue("pref_min_arm_delay_ms", arm);
    setSeekValue("pref_inter_cooldown_ms", cool);
    setSeekValue("pref_max_capture_ms", maxc);
    // no toast
    }

    private void saveDefaults(String profile) {
        android.content.SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(requireContext());
        android.content.SharedPreferences.Editor e = sp.edit();
        // Snapshot current values under a namespaced key
    e.putInt("defaults_"+profile+"_vad_thr", sp.getInt("pref_vad_threshold", 35));
    e.putInt("defaults_"+profile+"_vad_hang", sp.getInt("pref_vad_hangover", 30));
    e.putInt("defaults_"+profile+"_vad_atk", sp.getInt("pref_vad_attack", 3));
        e.putInt("defaults_"+profile+"_pre_roll", sp.getInt("pref_pre_roll_frames", 18));
        e.putInt("defaults_"+profile+"_incap_silence", sp.getInt("pref_incap_silence_frames", 35));
        e.putInt("defaults_"+profile+"_min_utter", sp.getInt("pref_min_utter_frames", 18));
        e.putInt("defaults_"+profile+"_arm_delay", sp.getInt("pref_min_arm_delay_ms", 600));
        e.putInt("defaults_"+profile+"_cooldown", sp.getInt("pref_inter_cooldown_ms", 800));
        e.putInt("defaults_"+profile+"_max_capture", sp.getInt("pref_max_capture_ms", 12_000));
        e.apply();
    // no toast
    }

    private void bindSummaries() {
        // Show optimal/reference only; slider already shows current value on the right
        setSeekSummary("pref_vad_threshold", p -> "Optimal ~0.035 (maps RMS 0.005–0.1)");
        setSeekSummary("pref_vad_hangover", p -> "Optimal ~30 frames");
        setSeekSummary("pref_vad_attack", p -> "Optimal ~3 frames");
        setSeekSummary("pref_pre_roll_frames", p -> "Optimal ~18 frames");
        setSeekSummary("pref_incap_silence_frames", p -> "Optimal ~35 frames");
        setSeekSummary("pref_min_arm_delay_ms", p -> "Optimal ~600 ms");
        setSeekSummary("pref_inter_cooldown_ms", p -> "Optimal ~800 ms");
        setSeekSummary("pref_min_utter_frames", p -> "Optimal ~18 frames");
        setSeekSummary("pref_max_capture_ms", p -> "Commands ~12,000 ms; Chat 60,000–120,000 ms");
    }

    private void showDialog(String title, String msg) {
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show();
    }

    private interface SummaryMaker { String make(Preference p); }
    private void setSeekSummary(String key, SummaryMaker maker) {
        Preference p = findPreference(key);
        if (p != null) p.setSummary(maker.make(p));
    }
    private void setSeekValue(String key, int value) {
        Preference p = findPreference(key);
        if (p instanceof SeekBarPreference) {
            SeekBarPreference sb = (SeekBarPreference) p;
            sb.setValue(value);
        }
    }

    // Mode helpers
    private String modePrefix() {
        String m = PreferenceManager.getDefaultSharedPreferences(requireContext()).getString("pref_config_mode", "cmd");
        return ("chat".equals(m)) ? "chat" : "cmd";
    }

    private void showModeValues(String mode) {
        android.content.SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(requireContext());
        String p = ("chat".equals(mode)) ? "chat" : "cmd";
        // Read profile-specific values; fallback to generic prefs
        int thr = sp.getInt(p+"_vad_threshold", sp.getInt("pref_vad_threshold", 35));
        int hang = sp.getInt(p+"_vad_hangover", sp.getInt("pref_vad_hangover", 30));
        int atk = sp.getInt(p+"_vad_attack", sp.getInt("pref_vad_attack", 3));
        int pre = sp.getInt(p+"_pre_roll_frames", sp.getInt("pref_pre_roll_frames", 18));
        int inc = sp.getInt(p+"_incap_silence_frames", sp.getInt("pref_incap_silence_frames", 35));
        int minU = sp.getInt(p+"_min_utter_frames", sp.getInt("pref_min_utter_frames", 18));
        int arm = sp.getInt(p+"_min_arm_delay_ms", sp.getInt("pref_min_arm_delay_ms", 600));
        int cool = sp.getInt(p+"_inter_cooldown_ms", sp.getInt("pref_inter_cooldown_ms", 800));
        int maxc = sp.getInt(p+"_max_capture_ms", sp.getInt("pref_max_capture_ms", 12_000));
        // Snap sliders to these values
        setSeekValue("pref_vad_threshold", thr);
        setSeekValue("pref_vad_hangover", hang);
        setSeekValue("pref_vad_attack", atk);
        setSeekValue("pref_pre_roll_frames", pre);
        setSeekValue("pref_incap_silence_frames", inc);
        setSeekValue("pref_min_utter_frames", minU);
        setSeekValue("pref_min_arm_delay_ms", arm);
        setSeekValue("pref_inter_cooldown_ms", cool);
        setSeekValue("pref_max_capture_ms", maxc);
    }

    private void resetDefaults() {
        String p = modePrefix();
        // Load saved defaults for this mode (fall back to built-ins if not saved)
        android.content.SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(requireContext());
        int thr = sp.getInt("defaults_"+p+"_vad_thr", p.equals("cmd") ? 35 : 35);
        int hang = sp.getInt("defaults_"+p+"_vad_hang", p.equals("cmd") ? 30 : 30);
        int atk = sp.getInt("defaults_"+p+"_vad_atk", p.equals("cmd") ? 3 : 3);
        int pre = sp.getInt("defaults_"+p+"_pre_roll", p.equals("cmd") ? 12 : 20);
        int inc = sp.getInt("defaults_"+p+"_incap_silence", p.equals("cmd") ? 20 : 60);
        int minU = sp.getInt("defaults_"+p+"_min_utter", p.equals("cmd") ? 12 : 18);
        int arm = sp.getInt("defaults_"+p+"_arm_delay", p.equals("cmd") ? 400 : 600);
        int cool = sp.getInt("defaults_"+p+"_cooldown", p.equals("cmd") ? 600 : 800);
        int maxc = sp.getInt("defaults_"+p+"_max_capture", p.equals("cmd") ? 8000 : 90_000);
        // Persist to both generic and profile keys and snap sliders
        android.content.SharedPreferences.Editor e = sp.edit();
        e.putInt("pref_vad_threshold", thr)
         .putInt("pref_vad_hangover", hang)
         .putInt("pref_vad_attack", atk)
         .putInt("pref_pre_roll_frames", pre)
         .putInt("pref_incap_silence_frames", inc)
         .putInt("pref_min_utter_frames", minU)
         .putInt("pref_min_arm_delay_ms", arm)
         .putInt("pref_inter_cooldown_ms", cool)
         .putInt("pref_max_capture_ms", maxc)
         .putInt(p+"_vad_threshold", thr)
         .putInt(p+"_vad_hangover", hang)
         .putInt(p+"_vad_attack", atk)
         .putInt(p+"_pre_roll_frames", pre)
         .putInt(p+"_incap_silence_frames", inc)
         .putInt(p+"_min_utter_frames", minU)
         .putInt(p+"_min_arm_delay_ms", arm)
         .putInt(p+"_inter_cooldown_ms", cool)
         .putInt(p+"_max_capture_ms", maxc)
         .apply();
    // Update sliders immediately without relying on async apply()
    setSeekValue("pref_vad_threshold", thr);
    setSeekValue("pref_vad_hangover", hang);
    setSeekValue("pref_vad_attack", atk);
    setSeekValue("pref_pre_roll_frames", pre);
    setSeekValue("pref_incap_silence_frames", inc);
    setSeekValue("pref_min_utter_frames", minU);
    setSeekValue("pref_min_arm_delay_ms", arm);
    setSeekValue("pref_inter_cooldown_ms", cool);
    setSeekValue("pref_max_capture_ms", maxc);
    // Final pass: ensure any computed summaries or providers refresh from current mode values
    showModeValues(p);
    // no toast
    }

    private void saveCurrentAsDefaults() {
        String p = modePrefix();
        android.content.SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(requireContext());
        android.content.SharedPreferences.Editor e = sp.edit();
        e.putInt("defaults_"+p+"_vad_thr", sp.getInt("pref_vad_threshold", 35));
        e.putInt("defaults_"+p+"_vad_hang", sp.getInt("pref_vad_hangover", 30));
        e.putInt("defaults_"+p+"_vad_atk", sp.getInt("pref_vad_attack", 3));
        e.putInt("defaults_"+p+"_pre_roll", sp.getInt("pref_pre_roll_frames", 18));
        e.putInt("defaults_"+p+"_incap_silence", sp.getInt("pref_incap_silence_frames", 35));
        e.putInt("defaults_"+p+"_min_utter", sp.getInt("pref_min_utter_frames", 18));
        e.putInt("defaults_"+p+"_arm_delay", sp.getInt("pref_min_arm_delay_ms", 600));
        e.putInt("defaults_"+p+"_cooldown", sp.getInt("pref_inter_cooldown_ms", 800));
        e.putInt("defaults_"+p+"_max_capture", sp.getInt("pref_max_capture_ms", 12_000));
        e.apply();
    // no toast
    }
    private android.content.SharedPreferences getPrefs() {
        return androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext());
    }

    private void factoryResetDefaults() {
        // Curated defaults we settled on during tuning
        // Command profile (short commands, snappy):
        int cmd_thr = 35;
        int cmd_hang = 30;
        int cmd_atk = 3;
        int cmd_pre = 12;
        int cmd_incap = 20;
        int cmd_minU = 12;
        int cmd_arm = 400;
        int cmd_cool = 600;
        int cmd_maxc = 8000;
        // Chat profile (multi-sentence, longer capture):
        int chat_thr = 35;
        int chat_hang = 30;
        int chat_atk = 3;
        int chat_pre = 20;
        int chat_incap = 60;
        int chat_minU = 18;
        int chat_arm = 600;
        int chat_cool = 800;
        int chat_maxc = 90000; // 90s

        android.content.SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(requireContext());
        android.content.SharedPreferences.Editor e = sp.edit();
        // Save as mode defaults
        e.putInt("defaults_cmd_vad_thr", cmd_thr)
         .putInt("defaults_cmd_vad_hang", cmd_hang)
         .putInt("defaults_cmd_vad_atk", cmd_atk)
         .putInt("defaults_cmd_pre_roll", cmd_pre)
         .putInt("defaults_cmd_incap_silence", cmd_incap)
         .putInt("defaults_cmd_min_utter", cmd_minU)
         .putInt("defaults_cmd_arm_delay", cmd_arm)
         .putInt("defaults_cmd_cooldown", cmd_cool)
         .putInt("defaults_cmd_max_capture", cmd_maxc)
         .putInt("defaults_chat_vad_thr", chat_thr)
         .putInt("defaults_chat_vad_hang", chat_hang)
         .putInt("defaults_chat_vad_atk", chat_atk)
         .putInt("defaults_chat_pre_roll", chat_pre)
         .putInt("defaults_chat_incap_silence", chat_incap)
         .putInt("defaults_chat_min_utter", chat_minU)
         .putInt("defaults_chat_arm_delay", chat_arm)
         .putInt("defaults_chat_cooldown", chat_cool)
         .putInt("defaults_chat_max_capture", chat_maxc)
         .apply();

        // Also apply to the currently selected mode and generic prefs so UI updates instantly
        String p = modePrefix();
        int thr = p.equals("cmd") ? cmd_thr : chat_thr;
        int hang = p.equals("cmd") ? cmd_hang : chat_hang;
        int atk = p.equals("cmd") ? cmd_atk : chat_atk;
        int pre = p.equals("cmd") ? cmd_pre : chat_pre;
        int inc = p.equals("cmd") ? cmd_incap : chat_incap;
        int minU = p.equals("cmd") ? cmd_minU : chat_minU;
        int arm = p.equals("cmd") ? cmd_arm : chat_arm;
        int cool = p.equals("cmd") ? cmd_cool : chat_cool;
        int maxc = p.equals("cmd") ? cmd_maxc : chat_maxc;

        android.content.SharedPreferences.Editor e2 = sp.edit();
        e2.putInt("pref_vad_threshold", thr)
          .putInt("pref_vad_hangover", hang)
          .putInt("pref_vad_attack", atk)
          .putInt("pref_pre_roll_frames", pre)
          .putInt("pref_incap_silence_frames", inc)
          .putInt("pref_min_utter_frames", minU)
          .putInt("pref_min_arm_delay_ms", arm)
          .putInt("pref_inter_cooldown_ms", cool)
          .putInt("pref_max_capture_ms", maxc)
          .putInt(p+"_vad_threshold", thr)
          .putInt(p+"_vad_hangover", hang)
          .putInt(p+"_vad_attack", atk)
          .putInt(p+"_pre_roll_frames", pre)
          .putInt(p+"_incap_silence_frames", inc)
          .putInt(p+"_min_utter_frames", minU)
          .putInt(p+"_min_arm_delay_ms", arm)
          .putInt(p+"_inter_cooldown_ms", cool)
          .putInt(p+"_max_capture_ms", maxc)
          .apply();

    // Update sliders for current mode immediately without relying on async apply()
    setSeekValue("pref_vad_threshold", thr);
    setSeekValue("pref_vad_hangover", hang);
    setSeekValue("pref_vad_attack", atk);
    setSeekValue("pref_pre_roll_frames", pre);
    setSeekValue("pref_incap_silence_frames", inc);
    setSeekValue("pref_min_utter_frames", minU);
    setSeekValue("pref_min_arm_delay_ms", arm);
    setSeekValue("pref_inter_cooldown_ms", cool);
    setSeekValue("pref_max_capture_ms", maxc);
    // Final pass: ensure any computed summaries or providers refresh from current mode values
    showModeValues(p);
    // no toast
    }
}
