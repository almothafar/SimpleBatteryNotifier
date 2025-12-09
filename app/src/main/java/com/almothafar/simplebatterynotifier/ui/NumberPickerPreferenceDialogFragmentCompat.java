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
	private NumberPicker numberPicker;

	/**
	 * Create a new instance of the dialog fragment
	 *
	 * @param key The preference key
	 * @return New dialog fragment instance
	 */
	public static NumberPickerPreferenceDialogFragmentCompat newInstance(final String key) {
		final NumberPickerPreferenceDialogFragmentCompat fragment = new NumberPickerPreferenceDialogFragmentCompat();
		final Bundle args = new Bundle(1);
		args.putString(ARG_KEY, key);
		fragment.setArguments(args);
		return fragment;
	}

	@Override
	protected View onCreateDialogView(@NonNull final Context context) {
		final FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		layoutParams.gravity = Gravity.CENTER;

		numberPicker = new NumberPicker(context);
		numberPicker.setLayoutParams(layoutParams);

		final FrameLayout dialogView = new FrameLayout(context);
		dialogView.addView(numberPicker);

		return dialogView;
	}

	@Override
	protected void onBindDialogView(@NonNull final View view) {
		super.onBindDialogView(view);

		final NumberPickerPreference preference = (NumberPickerPreference) getPreference();

		// Set the number picker range and current value
		numberPicker.setMinValue(preference.getMinValue());
		numberPicker.setMaxValue(preference.getMaxValue());
		numberPicker.setValue(preference.getValue());
		numberPicker.setWrapSelectorWheel(false);
	}

	@Override
	public void onDialogClosed(final boolean positiveResult) {
		if (positiveResult) {
			// Get the current value from the NumberPicker
			final int newValue = numberPicker.getValue();

			final NumberPickerPreference preference = (NumberPickerPreference) getPreference();

			if (preference.callChangeListener(newValue)) {
				preference.setValue(newValue);
			}
		}
	}
}
