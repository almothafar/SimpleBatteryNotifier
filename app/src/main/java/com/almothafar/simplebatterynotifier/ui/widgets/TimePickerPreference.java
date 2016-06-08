package com.almothafar.simplebatterynotifier.ui.widgets;

import android.content.Context;
import android.content.res.TypedArray;
import android.os.Build;
import android.preference.DialogPreference;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TimePicker;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Created by Al-Mothafar on 26/08/2015.
 */
public class TimePickerPreference extends DialogPreference {
    private int mHour = 0;
    private int mMinute = 0;
    private TimePicker picker = null;

    public static int getHour(String time) {
        String[] pieces = time.split(":");
        return Integer.parseInt(pieces[0]);
    }

    public static int getMinute(String time) {
        String[] pieces = time.split(":");
        return Integer.parseInt(pieces[1]);
    }

    public TimePickerPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setTime(int hour, int minute) {
        mHour = hour;
        mMinute = minute;
        String time = toTime(mHour, mMinute);
        persistString(time);
        notifyDependencyChange(shouldDisableDependents());
        notifyChanged();
    }

    public String toTime(int hour, int minute) {
        return String.valueOf(hour) + ":" + String.valueOf(minute);
    }

    public void updateSummary() {
        String time = String.valueOf(mHour) + ":" + String.valueOf(mMinute);
        setSummary(time24to12(time));
    }

    @Override
    protected View onCreateDialogView() {
        picker = new TimePicker(getContext());
        return picker;
    }

    @Override
    protected void onBindDialogView(View v) {
        super.onBindDialogView(v);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            //noinspection deprecation
            picker.setCurrentHour(mHour);
            //noinspection deprecation
            picker.setCurrentMinute(mMinute);
        } else {
            picker.setHour(mHour);
            picker.setMinute(mMinute);
        }
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        super.onDialogClosed(positiveResult);

        if (positiveResult) {
            int currHour = 0;
            int currMinute = 0;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                //noinspection deprecation
                currHour = picker.getCurrentHour();
                //noinspection deprecation
                currMinute = picker.getCurrentMinute();
            } else {
                currHour = picker.getHour();
                currMinute = picker.getMinute();
            }

            if (!callChangeListener(toTime(currHour, currMinute))) {
                return;
            }

            // persist
            setTime(currHour, currMinute);
            updateSummary();
        }
    }

    @Override
    protected Object onGetDefaultValue(TypedArray a, int index) {
        return a.getString(index);
    }

    @Override
    protected void onSetInitialValue(boolean restorePersistedValue, Object defaultValue) {
        String time = null;

        if (restorePersistedValue) {
            String DEFAULT_VALUE = "00:00";
            if (defaultValue == null) {
                time = getPersistedString(DEFAULT_VALUE);
            }
            else {
                time = getPersistedString(DEFAULT_VALUE);
            }
        }
        else {
            time = defaultValue.toString();
        }

        int currHour = getHour(time);
        int currMinute = getMinute(time);
        // need to persist here for default value to work
        setTime(currHour, currMinute);
        updateSummary();
    }

    public static Date toDate(String inTime) {
        try {
            DateFormat inTimeFormat = new SimpleDateFormat("HH:mm", Locale.US);
            return inTimeFormat.parse(inTime);
        } catch(ParseException e) {
            return null;
        }
    }

    public static String time24to12(String inTime) {
        Date inDate = toDate(inTime);
        if(inDate != null) {
            DateFormat outTimeFormat = new SimpleDateFormat("hh:mm a", Locale.US);
            return outTimeFormat.format(inDate);
        } else {
            return inTime;
        }
    }
}
