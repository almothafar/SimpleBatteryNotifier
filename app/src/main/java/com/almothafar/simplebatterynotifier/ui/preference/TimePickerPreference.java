package com.almothafar.simplebatterynotifier.ui.preference;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.util.Log;
import androidx.preference.DialogPreference;
import com.almothafar.simplebatterynotifier.util.GeneralHelper;

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
		return GeneralHelper.formatTime(hour, minute);
	}

	/**
	 * Set the time from a formatted string
	 * <p>
	 * The value is our own persisted "HH:MM", so anything that doesn't parse is unexpected
	 * corruption — reset to 00:00 and log. The shared {@link GeneralHelper#parseTimeToMinutes}
	 * accepts Eastern Arabic digits, so values written by older versions under the Arabic locale
	 * (via default-locale formatting) are kept and re-persisted in the canonical Western-digit
	 * form instead of being reset (issue #154).
	 *
	 * @param time Time string in "HH:MM" format
	 */
	public void setTime(final String time) {
		final int minutes = GeneralHelper.parseTimeToMinutes(time);
		if (minutes >= 0) {
			hour = minutes / 60;
			minute = minutes % 60;
		} else {
			Log.w(TAG, "Malformed persisted time \"" + time + "\"; defaulting to 00:00");
			hour = 0;
			minute = 0;
		}

		final String timeStr = GeneralHelper.formatTime(hour, minute);
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
