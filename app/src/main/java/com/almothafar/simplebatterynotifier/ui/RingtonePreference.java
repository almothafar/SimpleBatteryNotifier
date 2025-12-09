package com.almothafar.simplebatterynotifier.ui;

import android.content.Context;
import android.content.Intent;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.Log;
import androidx.preference.Preference;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Custom RingtonePreference for AndroidX
 * Launches the system ringtone picker when clicked
 */
public class RingtonePreference extends Preference {
	private static final String TAG = "RingtonePreference";

	private int ringtoneType = RingtoneManager.TYPE_NOTIFICATION;
	private String currentRingtoneUri;

	/**
	 * Constructor with all parameters
	 *
	 * @param context      The context
	 * @param attrs        Attribute set
	 * @param defStyleAttr Default style attribute
	 * @param defStyleRes  Default style resource
	 */
	public RingtonePreference(final Context context, final AttributeSet attrs,
	                          final int defStyleAttr, final int defStyleRes) {
		super(context, attrs, defStyleAttr, defStyleRes);
		initialize(context, attrs);
	}

	/**
	 * Constructor with context, attributes, and style attribute
	 *
	 * @param context      The context
	 * @param attrs        Attribute set
	 * @param defStyleAttr Default style attribute
	 */
	public RingtonePreference(final Context context, final AttributeSet attrs, final int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		initialize(context, attrs);
	}

	/**
	 * Constructor with context and attributes
	 *
	 * @param context The context
	 * @param attrs   Attribute set
	 */
	public RingtonePreference(final Context context, final AttributeSet attrs) {
		super(context, attrs);
		initialize(context, attrs);
	}

	/**
	 * Constructor with context only
	 *
	 * @param context The context
	 */
	public RingtonePreference(final Context context) {
		super(context);
		initialize(context, null);
	}

	/**
	 * Create an intent to launch the system ringtone picker
	 *
	 * @return Intent configured for ringtone selection
	 */
	public Intent createRingtonePickerIntent() {
		final Intent intent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
		intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, ringtoneType);
		intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getTitle());
		intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
		intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true);

		// Set the current ringtone
		if (nonNull(currentRingtoneUri) && !currentRingtoneUri.isEmpty()) {
			intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(currentRingtoneUri));
		}

		return intent;
	}

	/**
	 * Get the ringtone type
	 *
	 * @return Ringtone type constant from RingtoneManager
	 */
	public int getRingtoneType() {
		return ringtoneType;
	}

	/**
	 * Get the current ringtone URI
	 *
	 * @return Current ringtone URI string
	 */
	public String getRingtoneUri() {
		return currentRingtoneUri;
	}

	/**
	 * Set the ringtone URI and persist it
	 *
	 * @param uri The ringtone URI to set
	 */
	public void setRingtoneUri(final String uri) {
		currentRingtoneUri = uri;
		persistString(uri);
		updateSummary();
	}

	/**
	 * Set the initial value from preferences
	 *
	 * @param defaultValue The default value if no persisted value exists
	 */
	@Override
	protected void onSetInitialValue(final Object defaultValue) {
		final String uri = getPersistedString(nonNull(defaultValue) ? (String) defaultValue : "");
		setRingtoneUri(uri);
	}

	/**
	 * Initialize the preference from attributes
	 *
	 * @param context The context
	 * @param attrs   Attribute set, may be null
	 */
	private void initialize(final Context context, final AttributeSet attrs) {
		if (nonNull(attrs)) {
			// Read ringtoneType attribute if present
			final int type = attrs.getAttributeIntValue("http://schemas.android.com/apk/res/android", "ringtoneType", -1);
			if (type != -1) {
				ringtoneType = type;
			}
		}
	}

	/**
	 * Update the preference summary with the ringtone title
	 */
	private void updateSummary() {
		if (nonNull(currentRingtoneUri) && !currentRingtoneUri.isEmpty()) {
			try {
				final Ringtone ringtone = RingtoneManager.getRingtone(getContext(), Uri.parse(currentRingtoneUri));
				if (nonNull(ringtone)) {
					setSummary(ringtone.getTitle(getContext()));
					return;
				}
			} catch (Exception e) {
				final String errorMsg = e.getMessage();
				if (nonNull(errorMsg)) {
					Log.e(TAG, "Error loading ringtone: " + errorMsg);
				}
			}
		}
		setSummary("Default");
	}
}
