package com.whispertflite.ui.pref;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.google.android.material.button.MaterialButton;
import com.whispertflite.R;

public class ButtonPreference extends Preference {
    public ButtonPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    setLayoutResource(R.layout.pref_full_button);
    setSelectable(true);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        View v = holder.itemView;
    v.setClickable(true);
    v.setOnClickListener(view -> performClick());
        MaterialButton btn = v.findViewById(R.id.pref_button);
        if (btn != null) {
            CharSequence title = getTitle();
            btn.setText(title != null ? title : "");
            btn.setEnabled(isEnabled());
            btn.setOnClickListener(view -> performClick());
        }
    }
}
