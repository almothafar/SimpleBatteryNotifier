package com.almothafar.simplebatterynotifier.ui;

import android.content.Context;
import android.content.Intent;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.text.TextUtils;
import android.util.AttributeSet;

import androidx.preference.Preference;

/**
 * Custom RingtonePreference for AndroidX
 * Launches the system ringtone picker when clicked
 */
public class RingtonePreference extends Preference {
    public static final String TAG = "RingtonePreference";

    private int ringtoneType = RingtoneManager.TYPE_NOTIFICATION;
    private String currentRingtoneUri;

    public RingtonePreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context, attrs);
    }

    public RingtonePreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    public RingtonePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public RingtonePreference(Context context) {
        super(context);
        init(context, null);
    }

    private void init(Context context, AttributeSet attrs) {
        if (attrs != null) {
            // Read ringtoneType attribute if present
            int type = attrs.getAttributeIntValue("http://schemas.android.com/apk/res/android", "ringtoneType", -1);
            if (type != -1) {
                ringtoneType = type;
            }
        }
    }

    public Intent createRingtonePickerIntent() {
        Intent intent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, ringtoneType);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getTitle());
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true);

        // Set current ringtone
        if (!TextUtils.isEmpty(currentRingtoneUri)) {
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(currentRingtoneUri));
        }

        return intent;
    }

    public int getRingtoneType() {
        return ringtoneType;
    }

    @Override
    protected void onSetInitialValue(Object defaultValue) {
        String uri = getPersistedString(defaultValue != null ? (String) defaultValue : "");
        setRingtoneUri(uri);
    }

    public void setRingtoneUri(String uri) {
        currentRingtoneUri = uri;
        persistString(uri);
        updateSummary();
    }

    public String getRingtoneUri() {
        return currentRingtoneUri;
    }

    private void updateSummary() {
        if (!TextUtils.isEmpty(currentRingtoneUri)) {
            try {
                Ringtone ringtone = RingtoneManager.getRingtone(getContext(), Uri.parse(currentRingtoneUri));
                if (ringtone != null) {
                    setSummary(ringtone.getTitle(getContext()));
                    return;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        setSummary("Default");
    }
}
