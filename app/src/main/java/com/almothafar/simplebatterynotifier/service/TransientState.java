package com.almothafar.simplebatterynotifier.service;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The dedicated {@link SharedPreferences} file for volatile, device-specific tracker state (issue #167):
 * the drain/charge rate sample window (#108), the fast-drain / slow-charge streak state (#109/#123), and
 * the learned stable-capacity stats (#204).
 * <p>
 * Kept out of the default preferences so it can be excluded from cloud backup and device transfer — see
 * {@code res/xml/backup_rules.xml} and {@code res/xml/data_extraction_rules.xml}. Restoring another
 * device's rate window or alert streaks is meaningless: the window is age-trimmed and the streaks reset on
 * the next evaluation, so the state self-heals within a tick; the capacity stats re-learn over the first
 * hours of trusted readings. (Android backup rules exclude whole files, not individual keys, which is why
 * this state lives in its own file rather than the default prefs.)
 * <p>
 * Note: installs upgraded from before #167 leave inert copies of these keys in the default prefs — nothing
 * reads them there anymore, so they never affect behaviour; they are simply unused dead weight.
 */
final class TransientState {
	/** SharedPreferences file name; excluded from backup as {@code battery_transient.xml}. */
	static final String PREFS_FILE = "battery_transient";

	private TransientState() {
		// Utility class - prevent instantiation
	}

	/**
	 * The transient-state preferences file (private, app-owned), separate from the backed-up default
	 * preferences.
	 *
	 * @param context Application context
	 * @return the {@code battery_transient} SharedPreferences
	 */
	static SharedPreferences prefs(Context context) {
		return context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
	}
}
