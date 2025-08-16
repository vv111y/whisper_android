package com.whispertflite.ui.pref;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.whispertflite.R;

public class ModeTogglePreference extends Preference {
    public static final String VALUE_CMD = "cmd";
    public static final String VALUE_CHAT = "chat";
    private String currentValue;

    public ModeTogglePreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    setLayoutResource(R.layout.pref_mode_toggle);
        setSelectable(false);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        View v = holder.itemView;
        ChipGroup group = v.findViewById(R.id.mode_chip_group);
        Chip chipCmd = v.findViewById(R.id.chip_cmd);
        Chip chipChat = v.findViewById(R.id.chip_chat);
        if (group == null || chipCmd == null || chipChat == null) return;

        currentValue = getPersistedString(VALUE_CMD);
        group.check(VALUE_CHAT.equals(currentValue) ? R.id.chip_chat : R.id.chip_cmd);

        group.setOnCheckedStateChangeListener((g, checkedIds) -> {
            if (checkedIds == null || checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            String newValue = id == R.id.chip_chat ? VALUE_CHAT : VALUE_CMD;
            String prev = currentValue;
            if (!newValue.equals(prev)) {
                if (callChangeListener(newValue)) {
                    persistString(newValue);
                    currentValue = newValue;
                } else {
                    // revert
                    g.check(VALUE_CHAT.equals(prev) ? R.id.chip_chat : R.id.chip_cmd);
                }
            }
        });
    }
}
