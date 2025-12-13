package com.almothafar.simplebatterynotifier.ui.fragment;

import android.content.Context;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.TimePicker;
import androidx.annotation.NonNull;
import androidx.preference.PreferenceDialogFragmentCompat;

/**
 * Dialog fragment for TimePickerPreference
 * Provides a time picker dialog for selecting hours and minutes
 */
public class TimePickerPreferenceDialogFragmentCompat extends PreferenceDialogFragmentCompat {
	private TimePicker timePicker;

	/**
	 * Create a new instance of TimePickerPreferenceDialogFragmentCompat
	 *
	 * @param key The preference key
	 * @return New dialog fragment instance
	 */
	public static TimePickerPreferenceDialogFragmentCompat newInstance(final String key) {
		final TimePickerPreferenceDialogFragmentCompat fragment = new TimePickerPreferenceDialogFragmentCompat();
		final Bundle args = new Bundle(1);
		args.putString(ARG_KEY, key);
		fragment.setArguments(args);
		return fragment;
	}

	/**
	 * Create the dialog view with a TimePicker widget
	 *
	 * @param context The context for creating the view
	 * @return The TimePicker view
	 */
	@Override
	protected View onCreateDialogView(@NonNull final Context context) {
		timePicker = new TimePicker(context);
		timePicker.setIs24HourView(DateFormat.is24HourFormat(context));
		return timePicker;
	}

	/**
	 * Bind the preference value to the TimePicker
	 *
	 * @param view The dialog view
	 */
	@Override
	protected void onBindDialogView(@NonNull final View view) {
		super.onBindDialogView(view);

		final TimePickerPreference preference = (TimePickerPreference) getPreference();

		// Set the time to the TimePicker
		timePicker.setHour(preference.getHour());
		timePicker.setMinute(preference.getMinute());
	}

	/**
	 * Handle dialog close and save the selected time if positive button was clicked
	 *
	 * @param positiveResult True if the positive button was clicked
	 */
	@Override
	public void onDialogClosed(final boolean positiveResult) {
		if (positiveResult) {
			// Get the current values from the TimePicker
			final int hour = timePicker.getHour();
			final int minute = timePicker.getMinute();

			final TimePickerPreference preference = (TimePickerPreference) getPreference();

			final String time = String.format("%02d:%02d", hour, minute);

			if (preference.callChangeListener(time)) {
				preference.setTime(time);
			}
		}
	}
}
