package com.almothafar.simplebatterynotifier.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.almothafar.simplebatterynotifier.R;

/**
 * Typed facade over the app's default {@link SharedPreferences} (#162): the single owner of each
 * migrated setting's <b>key + default</b> (and clamp, as more settings move here). Read sites call the
 * typed accessor instead of repeating {@code getInt(getString(R.string._pref_key_…), <inline default>)},
 * so a default can never silently drift between the alert engine, the receiver and the UI.
 * <p>
 * <b>Migrated so far:</b> the critical/warning battery levels. The same {@code 20}/{@code 40} literals
 * previously lived in {@code NotificationService}, {@code BatteryLevelReceiver}, {@code MainActivity}
 * and the range slider's helper; every Java read now derives its default from
 * {@link #DEFAULT_CRITICAL_LEVEL} / {@link #DEFAULT_WARNING_LEVEL}. The one restatement that remains is
 * the XML-declared slider default in {@code pref_alerts.xml} (see below), which the framework
 * instantiates from XML and so cannot share a constant with. Remaining settings migrate incrementally.
 * <p>
 * The XML-declared defaults in {@code pref_alerts.xml} ({@code criticalDefault}/{@code warningDefault})
 * must stay in step with these constants — the framework instantiates the slider from XML, so the two
 * cannot literally share a constant, but they describe the same value.
 */
public final class AppPrefs {

	/** Default critical battery level in percent — the single owner of this value (#162). */
	public static final int DEFAULT_CRITICAL_LEVEL = 20;
	/** Default warning battery level in percent — the single owner of this value (#162). */
	public static final int DEFAULT_WARNING_LEVEL = 40;

	private AppPrefs() {
		// Utility class
	}

	/**
	 * The critical battery level in percent: the level at/below which the critical alert fires and the
	 * home gauge turns red. Falls back to {@link #DEFAULT_CRITICAL_LEVEL} when unset.
	 *
	 * @param context Application context
	 *
	 * @return the configured critical level
	 */
	public static int criticalLevel(final Context context) {
		return prefs(context).getInt(context.getString(R.string._pref_key_critical_battery_level), DEFAULT_CRITICAL_LEVEL);
	}

	/**
	 * The warning battery level in percent: the level at/below which the warning alert fires and the
	 * home gauge turns amber. Falls back to {@link #DEFAULT_WARNING_LEVEL} when unset.
	 *
	 * @param context Application context
	 *
	 * @return the configured warning level
	 */
	public static int warningLevel(final Context context) {
		return prefs(context).getInt(context.getString(R.string._pref_key_warn_battery_level), DEFAULT_WARNING_LEVEL);
	}

	/**
	 * Persist the critical and warning battery levels together. The settings-screen slider and the
	 * home-screen in-fly slider both write this pair, so the write lives here beside the matching reads.
	 *
	 * @param context  Application context
	 * @param critical the critical level to store, in percent
	 * @param warning  the warning level to store, in percent
	 */
	public static void setBatteryLevels(final Context context, final int critical, final int warning) {
		prefs(context).edit()
		              .putInt(context.getString(R.string._pref_key_critical_battery_level), critical)
		              .putInt(context.getString(R.string._pref_key_warn_battery_level), warning)
		              .apply();
	}

	private static SharedPreferences prefs(final Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context);
	}
}
