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

public class ListenModePreference extends Preference {
    // Values: wake (wakeword) | session (no wakeword, VAD capture)
    public static final String VALUE_WAKE = "wake";
    public static final String VALUE_SESSION = "session";
    private String currentValue = VALUE_SESSION;

    public ListenModePreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.pref_listen_mode);
        setSelectable(false);
        setPersistent(true);
    }

    @Override
    protected Object onGetDefaultValue(@NonNull android.content.res.TypedArray a, int index) {
        String def = a.getString(index);
        return def != null ? def : VALUE_SESSION;
    }

    @Override
    protected void onSetInitialValue(@Nullable Object defaultValue) {
        String def = defaultValue instanceof String ? (String) defaultValue : VALUE_SESSION;
        currentValue = getPersistedString(def);
        persistString(currentValue);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        View v = holder.itemView;
        ChipGroup group = v.findViewById(R.id.listen_chip_group);
        Chip chipWake = v.findViewById(R.id.chip_wake);
        Chip chipSession = v.findViewById(R.id.chip_session);
        if (group == null || chipWake == null || chipSession == null) return;

        // Load persisted or default value
        currentValue = getPersistedString(currentValue != null ? currentValue : VALUE_SESSION);
        group.check(VALUE_SESSION.equals(currentValue) ? R.id.chip_session : R.id.chip_wake);

        // Use direct chip click handlers for maximum compatibility
        chipWake.setOnClickListener(view -> setMode(VALUE_WAKE, group));
        chipSession.setOnClickListener(view -> setMode(VALUE_SESSION, group));
    }

    private void setMode(String newValue, ChipGroup group) {
        if (newValue == null) return;
        String prev = currentValue;
        if (newValue.equals(prev)) return;
        if (callChangeListener(newValue)) {
            persistString(newValue);
            currentValue = newValue;
            group.check(VALUE_SESSION.equals(currentValue) ? R.id.chip_session : R.id.chip_wake);
        } else {
            group.check(VALUE_SESSION.equals(prev) ? R.id.chip_session : R.id.chip_wake);
        }
    }
}
