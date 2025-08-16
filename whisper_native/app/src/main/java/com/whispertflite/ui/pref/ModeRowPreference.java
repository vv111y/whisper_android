package com.whispertflite.ui.pref;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.whispertflite.R;

public class ModeRowPreference extends Preference {
    private String currentValue = "cmd";

    public ModeRowPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.pref_mode_row);
        setSelectable(true);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        View v = holder.itemView;
        ChipGroup group = v.findViewById(R.id.mode_chip_group);
        Chip chipCmd = v.findViewById(R.id.chip_cmd);
        Chip chipChat = v.findViewById(R.id.chip_chat);
        MaterialButton btnDefaults = v.findViewById(R.id.btn_mode_defaults);
        if (group == null || chipCmd == null || chipChat == null || btnDefaults == null) return;

        currentValue = getPersistedString("cmd");
        group.check("chat".equals(currentValue) ? R.id.chip_chat : R.id.chip_cmd);

        group.setOnCheckedStateChangeListener((g, checkedIds) -> {
            if (checkedIds == null || checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            String newVal = id == R.id.chip_chat ? "chat" : "cmd";
            String prev = currentValue;
            if (!newVal.equals(prev)) {
                if (callChangeListener(newVal)) {
                    persistString(newVal);
                    currentValue = newVal;
                } else {
                    g.check("chat".equals(prev) ? R.id.chip_chat : R.id.chip_cmd);
                }
            }
        });

        btnDefaults.setOnClickListener(view -> performClick());
        v.setOnClickListener(view -> performClick());
    }
}
