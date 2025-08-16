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
        if (router != null) router.setOnPreferenceClickListener(p -> {
            applyRouterProfile();
            return true;
        });
        if (chat != null) chat.setOnPreferenceClickListener(p -> {
            applyChatProfile();
            return true;
        });
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
}
