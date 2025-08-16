package com.whispertflite.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class StartFragment extends Fragment {
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        android.widget.TextView tv = new android.widget.TextView(requireContext());
        tv.setText("Start");
        tv.setTextSize(24f);
        tv.setPadding(32, 32, 32, 32);
        return tv;
    }
}
