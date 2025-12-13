package com.almothafar.simplebatterynotifier.ui.preference;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import androidx.preference.DialogPreference;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Custom TimePickerPreference for AndroidX preferences
 */
public class TimePickerPreference extends DialogPreference {
	private int hour = 0;
	private int minute = 0;

	public TimePickerPreference(final Context context) {
		this(context, null);
	}

	public TimePickerPreference(final Context context, final AttributeSet attrs) {
		this(context, attrs, androidx.preference.R.attr.dialogPreferenceStyle);
	}

	public TimePickerPreference(final Context context, final AttributeSet attrs, final int defStyleAttr) {
		super(context, attrs, defStyleAttr);

		setPositiveButtonText(android.R.string.ok);
		setNegativeButtonText(android.R.string.cancel);
		setDialogIcon(null);
	}

	/**
	 * Get the current time as a formatted string
	 *
	 * @return Time in "HH:MM" format
	 */
	public String getTime() {
		return String.format("%02d:%02d", hour, minute);
	}

	/**
	 * Set the time from a formatted string
	 *
	 * @param time Time string in "HH:MM" format
	 */
	public void setTime(String time) {
		if (isNull(time) || time.isEmpty()) {
			time = "00:00";
		}

		final String[] parts = time.split(":");
		if (parts.length == 2) {
			try {
				hour = Integer.parseInt(parts[0]);
				minute = Integer.parseInt(parts[1]);
			} catch (NumberFormatException e) {
				hour = 0;
				minute = 0;
			}
		}

		final String timeStr = String.format("%02d:%02d", hour, minute);
		persistString(timeStr);
		setSummary(timeStr);
	}

	public int getHour() {
		return hour;
	}

	public void setHour(final int hour) {
		this.hour = hour;
	}

	public int getMinute() {
		return minute;
	}

	public void setMinute(final int minute) {
		this.minute = minute;
	}

	@Override
	protected Object onGetDefaultValue(final TypedArray a, final int index) {
		return a.getString(index);
	}

	@Override
	protected void onSetInitialValue(final Object defaultValue) {
		final String time = getPersistedString(nonNull(defaultValue) ? defaultValue.toString() : "00:00");
		setTime(time);
	}

	/**
	 * Called when preference is attached to the preference hierarchy
	 * Ensures summary is updated when preference is displayed
	 */
	@Override
	public void onAttached() {
		super.onAttached();
		// Ensure summary is always displayed with current time value
		final String time = getPersistedString("00:00");
		setSummary(time);
	}
}
