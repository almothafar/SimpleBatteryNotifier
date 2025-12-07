package com.almothafar.simplebatterynotifier.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.TimePicker;
import androidx.annotation.NonNull;
import androidx.preference.PreferenceDialogFragmentCompat;

/**
 * Dialog fragment for TimePickerPreference
 */
public class TimePickerPreferenceDialogFragmentCompat extends PreferenceDialogFragmentCompat {
	private TimePicker mTimePicker;

	public static TimePickerPreferenceDialogFragmentCompat newInstance(String key) {
		TimePickerPreferenceDialogFragmentCompat fragment = new TimePickerPreferenceDialogFragmentCompat();
		Bundle args = new Bundle(1);
		args.putString(ARG_KEY, key);
		fragment.setArguments(args);
		return fragment;
	}

	@Override
	protected View onCreateDialogView(@NonNull Context context) {
		mTimePicker = new TimePicker(context);
		mTimePicker.setIs24HourView(DateFormat.is24HourFormat(context));
		return mTimePicker;
	}

	@Override
	protected void onBindDialogView(@NonNull View view) {
		super.onBindDialogView(view);

		TimePickerPreference preference = (TimePickerPreference) getPreference();

		// Set the time to the TimePicker
		mTimePicker.setHour(preference.getHour());
		mTimePicker.setMinute(preference.getMinute());
	}

	@Override
	public void onDialogClosed(boolean positiveResult) {
		if (positiveResult) {
			// Get the current values from the TimePicker
			int hour = mTimePicker.getHour();
			int minute = mTimePicker.getMinute();

			TimePickerPreference preference = (TimePickerPreference) getPreference();

			String time = String.format("%02d:%02d", hour, minute);

			if (preference.callChangeListener(time)) {
				preference.setTime(time);
			}
		}
	}
}
