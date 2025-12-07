package com.almothafar.simplebatterynotifier.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.NumberPicker;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceDialogFragmentCompat;

import com.almothafar.simplebatterynotifier.ui.widgets.NumberPickerPreference;

/**
 * Dialog fragment for NumberPickerPreference
 */
public class NumberPickerPreferenceDialogFragmentCompat extends PreferenceDialogFragmentCompat {
    private NumberPicker mNumberPicker;

    public static NumberPickerPreferenceDialogFragmentCompat newInstance(String key) {
        NumberPickerPreferenceDialogFragmentCompat fragment = new NumberPickerPreferenceDialogFragmentCompat();
        Bundle args = new Bundle(1);
        args.putString(ARG_KEY, key);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    protected View onCreateDialogView(@NonNull Context context) {
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        layoutParams.gravity = Gravity.CENTER;

        mNumberPicker = new NumberPicker(context);
        mNumberPicker.setLayoutParams(layoutParams);

        FrameLayout dialogView = new FrameLayout(context);
        dialogView.addView(mNumberPicker);

        return dialogView;
    }

    @Override
    protected void onBindDialogView(@NonNull View view) {
        super.onBindDialogView(view);

        NumberPickerPreference preference = (NumberPickerPreference) getPreference();

        // Set the number picker range and current value
        mNumberPicker.setMinValue(preference.getMinValue());
        mNumberPicker.setMaxValue(preference.getMaxValue());
        mNumberPicker.setValue(preference.getValue());
        mNumberPicker.setWrapSelectorWheel(false);
    }

    @Override
    public void onDialogClosed(boolean positiveResult) {
        if (positiveResult) {
            // Get the current value from the NumberPicker
            int newValue = mNumberPicker.getValue();

            NumberPickerPreference preference = (NumberPickerPreference) getPreference();

            if (preference.callChangeListener(newValue)) {
                preference.setValue(newValue);
            }
        }
    }
}
