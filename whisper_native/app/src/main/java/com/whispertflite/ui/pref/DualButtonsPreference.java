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

public class DualButtonsPreference extends Preference {
    public interface Listener {
        void onLeftClick(Preference self);
        void onRightClick(Preference self);
    }

    private Listener listener;

    public DualButtonsPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.pref_dual_buttons);
        setSelectable(true);
    }

    public void setListener(Listener l) {
        this.listener = l;
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        View v = holder.itemView;
        MaterialButton left = v.findViewById(R.id.left_button);
        MaterialButton right = v.findViewById(R.id.right_button);
        if (left != null) left.setOnClickListener(view -> { if (listener != null) listener.onLeftClick(this); });
        if (right != null) right.setOnClickListener(view -> { if (listener != null) listener.onRightClick(this); });
        v.setOnClickListener(view -> performClick());
    }
}
