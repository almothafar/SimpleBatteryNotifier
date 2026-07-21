package com.almothafar.simplebatterynotifier.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.util.GeneralHelper;

import java.time.LocalTime;

/**
 * The quiet-hours and silent-mode <em>policy</em> for alerts (issue #166): given the user's time-range
 * window and the critical-alert override, decide whether an alert may sound/vibrate right now.
 * <p>
 * Split out of {@code NotificationService} so the single-responsibility line is clear and the pure
 * decision cores stay easy to unit-test. The core checks ({@link #isWithinTimeRange},
 * {@link #alertsAllowedNow}, {@link #boundOrDefaultMinutes}) are Android-free and directly tested;
 * {@link #boundOrDefaultMinutes} never throws so a corrupt stored bound can't turn every alert into a
 * crash loop (issue #154).
 */
final class QuietHours {
	private static final String TAG = QuietHours.class.getSimpleName();

	private QuietHours() {
		// Utility class - prevent instantiation
	}

	/**
	 * Whether the current time falls inside the user's allowed notification window
	 * (always true when the time-range limit is disabled).
	 *
	 * @param context The application context
	 * @param prefs   SharedPreferences containing user settings
	 * @return true if alerts are allowed at the current time
	 */
	static boolean isWithinNotificationWindow(Context context, SharedPreferences prefs) {
		// Default ON to match the toggle's XML default (pref_behaviour.xml). Reading false here let a
		// fresh install that never opened Time Settings alert around the clock, so quiet hours silently
		// did nothing (issue #111). Now quiet hours apply by default (06:30–23:30).
		final boolean limitedTime = prefs.getBoolean(context.getString(R.string._pref_key_notifications_time_range), true);
		final String defaultStartTime = context.getString(R.string._pref_value_notifications_time_range_start);
		final String defaultEndTime = context.getString(R.string._pref_value_notifications_time_range_end);
		final String startTime = prefs.getString(context.getString(R.string._pref_key_notifications_time_range_start), defaultStartTime);
		final String endTime = prefs.getString(context.getString(R.string._pref_key_notifications_time_range_end), defaultEndTime);
		return isWithinTime(startTime, endTime, defaultStartTime, defaultEndTime) || !limitedTime;
	}

	/**
	 * Whether an alert may sound/vibrate right now, given the quiet-hours window and the critical-alert
	 * override. Alerts are allowed inside the window; a critical alert may additionally be allowed to
	 * break through quiet hours when the user has left that option on (default), so a genuinely low
	 * battery still wakes them (issue #111). Pure and Android-free so it is unit-testable.
	 *
	 * @param withinWindow             whether now falls inside the allowed notification window
	 * @param isCritical               whether this is a critical (about-to-die) alert
	 * @param criticalIgnoresQuietHours whether critical alerts are allowed to break through quiet hours
	 * @return true when the alert may sound/vibrate now
	 */
	static boolean alertsAllowedNow(boolean withinWindow, boolean isCritical, boolean criticalIgnoresQuietHours) {
		return withinWindow || (isCritical && criticalIgnoresQuietHours);
	}

	/**
	 * Whether the user opted to override silent/DND mode for alerts.
	 *
	 * @param context The application context
	 * @param prefs   SharedPreferences containing user settings
	 * @return true if silent mode should be overridden
	 */
	static boolean shouldIgnoreSilentMode(Context context, SharedPreferences prefs) {
		return !prefs.getBoolean(context.getString(R.string._pref_key_notifications_apply_silent_mode), false);
	}

	/**
	 * Check if current time is within notification time range. Never throws: this runs on the
	 * broadcast path of every alert, and a corrupt stored bound must not turn alerting into a crash
	 * loop (issue #154) — a malformed bound falls back to that bound's default instead.
	 *
	 * @param startTime        Start time string (HH:mm format)
	 * @param endTime          End time string (HH:mm format)
	 * @param defaultStartTime Default window start, used when {@code startTime} is malformed
	 * @param defaultEndTime   Default window end, used when {@code endTime} is malformed
	 * @return true if current time is within range
	 */
	private static boolean isWithinTime(String startTime, String endTime, String defaultStartTime, String defaultEndTime) {
		final LocalTime now = LocalTime.now();
		final int nowMinutes = now.getHour() * 60 + now.getMinute();
		final int startMinutes = boundOrDefaultMinutes(startTime, defaultStartTime);
		final int endMinutes = boundOrDefaultMinutes(endTime, defaultEndTime);
		return isWithinTimeRange(nowMinutes, startMinutes, endMinutes);
	}

	/**
	 * A quiet-hours window bound as minutes since midnight, falling back to the bound's default when
	 * the stored value is malformed (backup/restore corruption, prefs damage — issue #154). The
	 * fallback is logged: a corrupt pref is unexpected and should be visible, just never fatal.
	 *
	 * @param storedTime  the persisted "HH:MM" bound
	 * @param defaultTime the bound's default from resources, expected to always parse
	 * @return the bound in minutes since midnight
	 */
	static int boundOrDefaultMinutes(String storedTime, String defaultTime) {
		final int minutes = GeneralHelper.parseTimeToMinutes(storedTime);
		if (minutes >= 0) {
			return minutes;
		}
		Log.w(TAG, "Malformed quiet-hours time \"" + storedTime + "\"; using default " + defaultTime);
		// The default is a compile-time resource constant, so this parse can't realistically fail;
		// floor at midnight rather than propagate -1 into the range check just in case.
		return Math.max(0, GeneralHelper.parseTimeToMinutes(defaultTime));
	}

	/**
	 * Pure minute-of-day range check. Handles same-day, overnight and equal-times windows
	 * (the previous implementation ignored minutes and mishandled overnight ranges that shared
	 * the same hour bucket).
	 * <ul>
	 *   <li>start &lt; end: inside when {@code start <= now < end} (e.g. 08:00–23:00).</li>
	 *   <li>start &gt; end: overnight window, inside when {@code now >= start || now < end} (e.g. 22:00–06:00).</li>
	 *   <li>start == end: treated as a 24-hour window (always inside).</li>
	 * </ul>
	 * Start is inclusive, end is exclusive.
	 *
	 * @param nowMinutes   Current time as minutes since midnight
	 * @param startMinutes Window start as minutes since midnight
	 * @param endMinutes   Window end as minutes since midnight
	 * @return true if now falls inside the window
	 */
	static boolean isWithinTimeRange(int nowMinutes, int startMinutes, int endMinutes) {
		if (startMinutes == endMinutes) {
			return true; // Whole day
		}
		if (startMinutes < endMinutes) {
			return nowMinutes >= startMinutes && nowMinutes < endMinutes;
		}
		// Overnight window wraps past midnight
		return nowMinutes >= startMinutes || nowMinutes < endMinutes;
	}
}
