package com.almothafar.simplebatterynotifier.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import androidx.preference.DialogPreference;

/**
 * Custom TimePickerPreference for AndroidX
 */
public class TimePickerPreference extends DialogPreference {
	private int mHour = 0;
	private int mMinute = 0;

	public TimePickerPreference(Context context) {
		this(context, null);
	}

	public TimePickerPreference(Context context, AttributeSet attrs) {
		this(context, attrs, androidx.preference.R.attr.dialogPreferenceStyle);
	}

	public TimePickerPreference(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);

		setPositiveButtonText(android.R.string.ok);
		setNegativeButtonText(android.R.string.cancel);

		// Set dialog icon if needed
		setDialogIcon(null);
	}

	public String getTime() {
		return String.format("%02d:%02d", mHour, mMinute);
	}

	public void setTime(String time) {
		if (time == null || time.isEmpty()) {
			time = "00:00";
		}

		String[] parts = time.split(":");
		if (parts.length == 2) {
			try {
				mHour = Integer.parseInt(parts[0]);
				mMinute = Integer.parseInt(parts[1]);
			} catch (NumberFormatException e) {
				mHour = 0;
				mMinute = 0;
			}
		}

		String timeStr = String.format("%02d:%02d", mHour, mMinute);
		persistString(timeStr);
		setSummary(timeStr);
	}

	public int getHour() {
		return mHour;
	}

	public void setHour(int hour) {
		mHour = hour;
	}

	public int getMinute() {
		return mMinute;
	}

	public void setMinute(int minute) {
		mMinute = minute;
	}

	@Override
	protected Object onGetDefaultValue(TypedArray a, int index) {
		return a.getString(index);
	}

	@Override
	protected void onSetInitialValue(Object defaultValue) {
		String time = getPersistedString(defaultValue != null ? defaultValue.toString() : "00:00");
		setTime(time);
	}
}
