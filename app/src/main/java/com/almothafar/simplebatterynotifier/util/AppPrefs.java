package com.almothafar.simplebatterynotifier.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.model.LevelThresholds;

/**
 * Typed facade over the app's default {@link SharedPreferences} (#162): the single owner of each
 * migrated setting's <b>key + default + clamp</b>. Read sites call the typed accessor instead of
 * repeating {@code getInt(getString(R.string._pref_key_…), <inline default>)}, so a default can never
 * silently drift between the alert engine, the receiver and the UI.
 * <p>
 * <b>Migrated so far:</b>
 * <ul>
 *   <li>the critical/warning battery levels — the {@code 20}/{@code 40} literals that previously lived
 *       in {@code NotificationService}, {@code BatteryLevelReceiver}, {@code MainActivity} and the range
 *       slider's helper now derive from {@link #DEFAULT_CRITICAL_LEVEL} / {@link #DEFAULT_WARNING_LEVEL},
 *       and the pair travels as a {@link LevelThresholds};</li>
 *   <li>the shared "high drain" limit — its default, accepted range and clamp ({@link #drainLimitPph}
 *       + {@link #clampDrainLimit}) moved here from {@code BatteryRateTracker}, so "a corrupt stored
 *       value can't defeat the feature" lives in one place.</li>
 * </ul>
 * The one restatement that remains is the XML-declared slider default in {@code pref_alerts.xml}, which
 * the framework instantiates from XML and so cannot share a constant with — a comment ties the two, and
 * {@code AppPrefsTest} asserts they stay equal. Remaining settings migrate incrementally.
 */
public final class AppPrefs {

	/** Default critical battery level in percent — the single owner of this value (#162). */
	public static final int DEFAULT_CRITICAL_LEVEL = 20;
	/** Default warning battery level in percent — the single owner of this value (#162). */
	public static final int DEFAULT_WARNING_LEVEL = 40;

	/** Default "high drain" limit in %/h. */
	public static final int DEFAULT_DRAIN_LIMIT_PPH = 20;
	/** Lowest accepted drain limit in %/h; mirrors the slider's {@code android:min} in pref_alerts.xml. */
	public static final int MIN_DRAIN_LIMIT_PPH = 5;
	/** Highest accepted drain limit in %/h; mirrors the slider's {@code android:max} in pref_alerts.xml. */
	public static final int MAX_DRAIN_LIMIT_PPH = 60;

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
	public static int criticalLevel(Context context) {
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
	public static int warningLevel(Context context) {
		return prefs(context).getInt(context.getString(R.string._pref_key_warn_battery_level), DEFAULT_WARNING_LEVEL);
	}

	/**
	 * Both battery-level thresholds as one value, so critical and warning travel together instead of as a
	 * loose (int, int) pair callers must keep in the right order.
	 *
	 * @param context Application context
	 *
	 * @return the configured {@code (critical, warning)} thresholds
	 */
	public static LevelThresholds batteryLevels(Context context) {
		return new LevelThresholds(criticalLevel(context), warningLevel(context));
	}

	/**
	 * Persist both battery-level thresholds together. The settings-screen slider and the home-screen
	 * in-fly slider both write this pair, so the write lives here beside the matching reads.
	 *
	 * @param context Application context
	 * @param levels  the thresholds to store
	 */
	public static void setBatteryLevels(Context context, LevelThresholds levels) {
		prefs(context).edit()
		              .putInt(context.getString(R.string._pref_key_critical_battery_level), levels.critical())
		              .putInt(context.getString(R.string._pref_key_warn_battery_level), levels.warning())
		              .apply();
	}

	/**
	 * The user's shared "high drain" limit in %/h — the red line in the details table (#108) and the
	 * fast-drain alert trigger (#109). Reads {@link #DEFAULT_DRAIN_LIMIT_PPH} when unset and always
	 * {@link #clampDrainLimit clamps} the stored value, so a corrupt preference can't skew the red line
	 * or the alert trigger.
	 *
	 * @param context Application context
	 *
	 * @return the configured limit in %/h
	 */
	public static int drainLimitPph(Context context) {
		return clampDrainLimit(prefs(context).getInt(
				context.getString(R.string._pref_key_fast_drain_limit), DEFAULT_DRAIN_LIMIT_PPH));
	}

	/**
	 * Clamps a stored drain limit to {@code [MIN_DRAIN_LIMIT_PPH, MAX_DRAIN_LIMIT_PPH]}. The bounds mirror
	 * the slider's {@code android:min}/{@code android:max} in {@code pref_alerts.xml}. Pure so it is
	 * unit-testable.
	 *
	 * @param stored the raw persisted limit in %/h
	 *
	 * @return the limit clamped to {@code [MIN_DRAIN_LIMIT_PPH, MAX_DRAIN_LIMIT_PPH]}
	 */
	public static int clampDrainLimit(int stored) {
		return Math.max(MIN_DRAIN_LIMIT_PPH, Math.min(MAX_DRAIN_LIMIT_PPH, stored));
	}

	private static SharedPreferences prefs(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context);
	}
}
