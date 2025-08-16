package com.whispertflite.ui;

import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import com.whispertflite.R;

public class SettingsFragment extends PreferenceFragmentCompat {
    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences, rootKey);
    Preference router = findPreference("pref_apply_router");
    Preference chat = findPreference("pref_apply_chat");
    if (router != null) router.setOnPreferenceClickListener(p -> { applyRouterProfile(); bindSummaries(); return true; });
    if (chat != null) chat.setOnPreferenceClickListener(p -> { applyChatProfile(); bindSummaries(); return true; });
    bindSummaries();
    }

    private void applyRouterProfile() {
        // Short commands
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit()
                .putInt("pref_pre_roll_frames", 12)
                .putInt("pref_incap_silence_frames", 20)
                .putInt("pref_min_utter_frames", 12)
                .putInt("pref_min_arm_delay_ms", 400)
                .putInt("pref_inter_cooldown_ms", 600)
                .putInt("pref_max_capture_ms", 8000)
                .apply();
        android.widget.Toast.makeText(getContext(), "Applied Command Router", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void applyChatProfile() {
        // Longer multi-sentence
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit()
                .putInt("pref_pre_roll_frames", 20)
                .putInt("pref_incap_silence_frames", 60)
                .putInt("pref_min_utter_frames", 18)
                .putInt("pref_min_arm_delay_ms", 600)
                .putInt("pref_inter_cooldown_ms", 800)
                .putInt("pref_max_capture_ms", 90_000)
                .apply();
        android.widget.Toast.makeText(getContext(), "Applied Chatbot", android.widget.Toast.LENGTH_SHORT).show();
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

    private interface SummaryMaker { String make(Preference p); }
    private void setSeekSummary(String key, SummaryMaker maker) {
        Preference p = findPreference(key);
        if (p != null) p.setSummary(maker.make(p));
    }
    private android.content.SharedPreferences getPrefs() {
        return androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext());
    }
}
