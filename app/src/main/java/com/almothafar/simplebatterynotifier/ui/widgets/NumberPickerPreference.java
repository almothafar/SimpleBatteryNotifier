package com.almothafar.simplebatterynotifier.ui.widgets;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import androidx.preference.DialogPreference;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Custom NumberPickerPreference for AndroidX preferences
 */
public class NumberPickerPreference extends DialogPreference {

	private int value;

	private String prefix = "";
	private String postfix = "";
	private int minValue = 0;
	private int maxValue = 100;

	public NumberPickerPreference(final Context context) {
		this(context, null);
	}

	public NumberPickerPreference(final Context context, final AttributeSet attrs) {
		this(context, attrs, androidx.preference.R.attr.dialogPreferenceStyle);
	}

	public NumberPickerPreference(final Context context, final AttributeSet attrs, final int defStyleAttr) {
		super(context, attrs, defStyleAttr);

		setPositiveButtonText(android.R.string.ok);
		setNegativeButtonText(android.R.string.cancel);
		setDialogIcon(null);

		initializeAttributes(attrs);
	}

	public int getValue() {
		return this.value;
	}

	public void setValue(final int value) {
		this.value = value;
		persistInt(this.value);
		updateSummary();
	}

	public int getMinValue() {
		return minValue;
	}

	public int getMaxValue() {
		return maxValue;
	}

	@Override
	protected Object onGetDefaultValue(final TypedArray a, final int index) {
		return a.getInt(index, minValue);
	}

	@Override
	protected void onSetInitialValue(final Object defaultValue) {
		setValue(getPersistedInt(nonNull(defaultValue) ? (Integer) defaultValue : minValue));
	}

	/**
	 * Initialize custom attributes from XML
	 *
	 * @param attrs AttributeSet from XML
	 */
	private void initializeAttributes(final AttributeSet attrs) {
		if (isNull(attrs)) {
			return;
		}

		for (int i = 0; i < attrs.getAttributeCount(); i++) {
			final String attr = attrs.getAttributeName(i);
			final String val = attrs.getAttributeValue(i);
			if (attr.equalsIgnoreCase("minRangeValue")) {
				this.minValue = Integer.parseInt(val);
			} else if (attr.equalsIgnoreCase("maxRangeValue")) {
				this.maxValue = Integer.parseInt(val);
			} else if (attr.equalsIgnoreCase("valuePrefix")) {
				this.prefix = val;
			} else if (attr.equalsIgnoreCase("valuePostfix")) {
				this.postfix = val;
			}
		}
	}

	/**
	 * Update the preference summary with current value
	 */
	private void updateSummary() {
		setSummary(prefix + value + postfix);
	}
}
