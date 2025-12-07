package com.almothafar.simplebatterynotifier.ui.widgets;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import androidx.preference.DialogPreference;

/**
 * Custom NumberPickerPreference for AndroidX
 * Created by Al-Mothafar on 25/08/2015.
 * Migrated to AndroidX preferences
 */
public class NumberPickerPreference extends DialogPreference {

	private int value;

	private String prefix = "";
	private String postfix = "";
	private int minValue = 0;
	private int maxValue = 100;

	public NumberPickerPreference(Context context) {
		this(context, null);
	}

	public NumberPickerPreference(Context context, AttributeSet attrs) {
		this(context, attrs, androidx.preference.R.attr.dialogPreferenceStyle);
	}

	public NumberPickerPreference(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);

		setPositiveButtonText(android.R.string.ok);
		setNegativeButtonText(android.R.string.cancel);
		setDialogIcon(null);

		initialAttributes(attrs);
	}

	public int getValue() {
		return this.value;
	}

	public void setValue(int value) {
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
	protected Object onGetDefaultValue(TypedArray a, int index) {
		return a.getInt(index, minValue);
	}

	@Override
	protected void onSetInitialValue(Object defaultValue) {
		setValue(getPersistedInt(defaultValue != null ? (Integer) defaultValue : minValue));
	}

	private void initialAttributes(AttributeSet attrs) {
		if (attrs == null) {
			return;
		}

		for (int i = 0; i < attrs.getAttributeCount(); i++) {
			String attr = attrs.getAttributeName(i);
			String val = attrs.getAttributeValue(i);
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

	private void updateSummary() {
		setSummary(prefix + value + postfix);
	}
}
