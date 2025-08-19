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

/**
 * Segmented toggle to select VAD engine: energy | webrtc | silero
 */
public class VadEnginePreference extends Preference {
    public static final String VALUE_ENERGY = "energy";
    public static final String VALUE_WEBRTC = "webrtc";
    public static final String VALUE_SILERO = "silero";

    private String currentValue = VALUE_ENERGY;

    public VadEnginePreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.pref_vad_engine);
        setSelectable(false);
        setPersistent(true);
    }

    @Override
    protected Object onGetDefaultValue(@NonNull android.content.res.TypedArray a, int index) {
        String def = a.getString(index);
        return def != null ? def : VALUE_ENERGY;
    }

    @Override
    protected void onSetInitialValue(@Nullable Object defaultValue) {
        String def = defaultValue instanceof String ? (String) defaultValue : VALUE_ENERGY;
        currentValue = getPersistedString(def);
        persistString(currentValue);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        View v = holder.itemView;
        ChipGroup group = v.findViewById(R.id.vad_engine_chip_group);
        Chip chipEnergy = v.findViewById(R.id.chip_energy);
        Chip chipWebrtc = v.findViewById(R.id.chip_webrtc);
        Chip chipSilero = v.findViewById(R.id.chip_silero);
        if (group == null || chipEnergy == null || chipWebrtc == null || chipSilero == null) return;

        currentValue = getPersistedString(currentValue != null ? currentValue : VALUE_ENERGY);
        int checkedId = R.id.chip_energy;
        if (VALUE_WEBRTC.equals(currentValue)) checkedId = R.id.chip_webrtc;
        else if (VALUE_SILERO.equals(currentValue)) checkedId = R.id.chip_silero;
        group.check(checkedId);

        chipEnergy.setOnClickListener(view -> setEngine(VALUE_ENERGY, group));
        chipWebrtc.setOnClickListener(view -> setEngine(VALUE_WEBRTC, group));
        chipSilero.setOnClickListener(view -> setEngine(VALUE_SILERO, group));
    }

    private void setEngine(String newValue, ChipGroup group) {
        if (newValue == null) return;
        String prev = currentValue;
        if (newValue.equals(prev)) return;
        if (callChangeListener(newValue)) {
            persistString(newValue);
            currentValue = newValue;
            int checkedId = R.id.chip_energy;
            if (VALUE_WEBRTC.equals(currentValue)) checkedId = R.id.chip_webrtc;
            else if (VALUE_SILERO.equals(currentValue)) checkedId = R.id.chip_silero;
            group.check(checkedId);
        } else {
            int checkedId = R.id.chip_energy;
            if (VALUE_WEBRTC.equals(prev)) checkedId = R.id.chip_webrtc;
            else if (VALUE_SILERO.equals(prev)) checkedId = R.id.chip_silero;
            group.check(checkedId);
        }
    }
}
