package com.almothafar.simplebatterynotifier.ui.preference;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.util.Log;
import androidx.preference.DialogPreference;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Custom TimePickerPreference for AndroidX preferences
 */
public class TimePickerPreference extends DialogPreference {
	private static final String TAG = "TimePickerPreference";

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

		// The value is our own persisted "HH:MM", so anything that doesn't validate is unexpected
		// corruption — validate the parts (no exception-as-control-flow) and log if we have to reset.
		final String[] parts = time.split(":");
		if (parts.length == 2 && isTimePart(parts[0], 23) && isTimePart(parts[1], 59)) {
			hour = Integer.parseInt(parts[0]);   // safe: isTimePart matched \d{1,2}
			minute = Integer.parseInt(parts[1]);
		} else {
			Log.w(TAG, "Malformed persisted time \"" + time + "\"; defaulting to 00:00");
			hour = 0;
			minute = 0;
		}

		final String timeStr = String.format("%02d:%02d", hour, minute);
		persistString(timeStr);
		setSummary(timeStr);
	}

	/**
	 * Whether {@code part} is a 1-2 digit number within {@code 0..max}. Pre-validates so the
	 * subsequent {@link Integer#parseInt} can't throw or overflow — no catch needed.
	 *
	 * @param part candidate hour/minute component
	 * @param max  inclusive upper bound (23 for hours, 59 for minutes)
	 *
	 * @return true when {@code part} is a valid time component
	 */
	private static boolean isTimePart(final String part, final int max) {
		if (!part.matches("\\d{1,2}")) {
			return false;
		}
		return Integer.parseInt(part) <= max; // safe: matched \d{1,2}, fits in an int
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
