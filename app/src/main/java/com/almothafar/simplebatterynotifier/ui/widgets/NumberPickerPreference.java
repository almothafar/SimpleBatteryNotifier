package com.almothafar.simplebatterynotifier.ui.widgets;

import android.content.Context;
import android.content.res.TypedArray;
import android.preference.DialogPreference;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.NumberPicker;

/**
 * Created by Al-Mothafar on 25/08/2015.
 */
public class NumberPickerPreference extends DialogPreference {

    /*public NumberPickerPreference(Context context, AttributeSet attrs) {
        super(context, attrs);

        setDialogLayoutResource(R.layout.numberpicker_dialog);
        setPositiveButtonText(android.R.string.ok);
        setNegativeButtonText(android.R.string.cancel);

        setDialogIcon(null);
    }*/


    private NumberPicker picker;
    private int value;

    private String prefix;
    private String postfix;
    private int minValue;
    private int maxValue;

    public NumberPickerPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialAttributes(attrs);
    }

    public NumberPickerPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initialAttributes(attrs);
    }

    private void initialAttributes(AttributeSet attrs) {
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

    @Override
    protected View onCreateDialogView() {
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        layoutParams.gravity = Gravity.CENTER;

        picker = new NumberPicker(getContext());
        picker.setLayoutParams(layoutParams);

        FrameLayout dialogView = new FrameLayout(getContext());
        dialogView.addView(picker);

        return dialogView;
    }

    @Override
    protected void onBindDialogView(View view) {
        super.onBindDialogView(view);
        // FIXME this should be parametter !
        picker.setMinValue(minValue);
        picker.setMaxValue(maxValue);
        picker.setValue(getValue());
        picker.setWrapSelectorWheel(false);
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        if (positiveResult) {
            int newValue = picker.getValue();
            if (callChangeListener(newValue)) {
                setValue(newValue);
            }
        }
    }

    @Override
    protected Object onGetDefaultValue(TypedArray a, int index) {
        return a.getInt(index, minValue);
    }

    @Override
    protected void onSetInitialValue(boolean restorePersistedValue, Object defaultValue) {
        setValue(restorePersistedValue ? getPersistedInt(minValue) : (Integer) defaultValue);
    }

    public void setValue(int value) {
        this.value = value;
        persistInt(this.value);
    }

    public int getValue() {
        return this.value;
    }

    @Override
    public void setSummary(int value) {
        setSummary(prefix + value + postfix);
    }
}
