package com.almothafar.simplebatterynotifier.service;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.model.BatteryDO;
import com.almothafar.simplebatterynotifier.service.BatteryRateTracker.BatteryRate;
import com.almothafar.simplebatterynotifier.service.SustainedConditionTracker.Outcome;
import com.almothafar.simplebatterynotifier.service.SustainedConditionTracker.Streak;
import com.almothafar.simplebatterynotifier.service.SustainedConditionTracker.StreakStore;

import static java.util.Objects.isNull;

/**
 * Warns when the battery drains abnormally fast for a <em>sustained</em> time (issue #109).
 * <p>
 * This is the alerting layer on top of the drain rate from {@link BatteryRateTracker} (#108). It needs
 * no per-app data — it watches the whole-battery %/h — so it is fully buildable by a normal app. It is
 * evaluated on each {@code ACTION_BATTERY_CHANGED} broadcast; there is <b>no new timer/alarm/wakelock</b>
 * (reminders piggyback the broadcasts, which are frequent precisely while draining fast).
 * <p>
 * <b>Sustained, not spikes:</b> the rate (already smoothed by #108) must stay at/above the user's limit
 * for the whole window (default 5 min); a brief flare breaks the streak. <b>Context-aware repeats:</b>
 * warn once per episode while the screen is on and unlocked (the user can see it), but remind every
 * {@code reminderGap} while the screen is off or locked (background drain the user can't see — the
 * highest-value case). Hysteresis re-arms the episode only once the rate drops back below the limit,
 * exactly like the high-temperature alert. The decision core is pure and unit-tested; the streak is
 * persisted so it survives process restarts, like {@link BatteryHealthTracker}.
 */
public final class FastDrainDetector {

	// Persisted streak/hysteresis state (survives process restarts). Keys unchanged from before the
	// shared-core extraction (#163) so no in-progress episode is lost on upgrade.
	private static final String PREF_STREAK_START = "_fast_drain_streak_start";
	private static final String PREF_ALERTED = "_fast_drain_alerted";
	private static final String PREF_LAST_REMINDER = "_fast_drain_last_reminder";
	private static final String PREF_LAST_SEEN_ABOVE = "_fast_drain_last_seen_above";

	private static final StreakStore STORE =
			new StreakStore(PREF_STREAK_START, PREF_ALERTED, PREF_LAST_SEEN_ABOVE, PREF_LAST_REMINDER);

	// Defaults and accepted ranges (user-tunable), matching the settings XML min/max — enforced when the
	// preferences are read, so a corrupt/out-of-range value can't turn this into a spike alarm.
	static final int DEFAULT_SUSTAINED_MINUTES = 5;
	static final int MIN_SUSTAINED_MINUTES = 1;
	static final int MAX_SUSTAINED_MINUTES = 30;
	static final int DEFAULT_REMINDER_MINUTES = 15;
	static final int MIN_REMINDER_MINUTES = 5;
	static final int MAX_REMINDER_MINUTES = 60;

	private static final long MS_PER_MINUTE = 60_000L;

	private FastDrainDetector() {
		// Utility class - prevent instantiation
	}

	/**
	 * Evaluates the fast-drain rule against the freshly-computed rate and (re)notifies when warranted.
	 * Called from {@link com.almothafar.simplebatterynotifier.receiver.BatteryLevelReceiver} after the
	 * rate is recorded, so it reads the same smoothed value the table and notification show.
	 *
	 * @param context   Application context
	 * @param batteryDO Current battery snapshot (may be null)
	 * @param rate      The rate just computed by {@link BatteryRateTracker#record}
	 */
	public static void evaluate(final Context context, final BatteryDO batteryDO, final BatteryRate rate) {
		if (isNull(context) || isNull(batteryDO)) {
			return;
		}
		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

		final boolean enabled = prefs.getBoolean(context.getString(R.string._pref_key_notify_fast_drain), true);
		final boolean discharging = !BatteryRateTracker.isChargingDirection(batteryDO.getStatus());
		// Only while discharging, and only when enabled. Either way the episode is re-armed (charging, or
		// the feature being off, ends any streak) so a later fast discharge starts fresh.
		if (!enabled || !discharging) {
			STORE.clear(prefs);
			return;
		}

		final int limit = BatteryRateTracker.getDrainLimitPercentPerHour(context);
		final long sustainedMs = minutesPref(prefs, context, R.string._pref_key_fast_drain_sustained_minutes,
				DEFAULT_SUSTAINED_MINUTES, MIN_SUSTAINED_MINUTES, MAX_SUSTAINED_MINUTES);
		final long reminderGapMs = minutesPref(prefs, context, R.string._pref_key_fast_drain_reminder_minutes,
				DEFAULT_REMINDER_MINUTES, MIN_REMINDER_MINUTES, MAX_REMINDER_MINUTES);
		final boolean activelyUsed = SystemService.isActivelyUsed(context);
		final long now = System.currentTimeMillis();

		final Streak previous = STORE.load(prefs);
		final Outcome decision = decide(previous, rate.hasRate(), rate.percentPerHour(),
				limit, sustainedMs, reminderGapMs, activelyUsed, now);

		STORE.saveIfChanged(prefs, decision.newState());
		if (decision.shouldNotify()) {
			final int elapsedMinutes = Math.max(1, Math.round(decision.elapsedMs() / (float) MS_PER_MINUTE));
			NotificationService.sendFastDrainNotification(context, rate.percentPerHour(), limit, elapsedMinutes);
		}
	}

	/**
	 * The fast-drain decision as a pure function of the drain rate, delegating the streak/lapse/hysteresis
	 * mechanics to {@link SustainedConditionTracker} (#163). The condition is "rate at/above the limit";
	 * the repeat policy is {@link SustainedConditionTracker#withReminders} — the first alert fires
	 * regardless of screen state, then reminders repeat only while the screen is off/locked (background
	 * drain the user can't see). Kept as a rate-oriented method so the behaviour contract stays unit-tested
	 * against the domain inputs.
	 *
	 * @param state         current persisted streak
	 * @param rateAvailable whether #108 produced a trustworthy %/h this tick
	 * @param ratePph       the drain rate magnitude in %/h (valid when {@code rateAvailable})
	 * @param limitPph      the user's high-drain limit in %/h
	 * @param sustainedMs   how long the rate must stay at/above the limit before the first alert
	 * @param reminderGapMs minimum gap between reminders while the screen is off/locked
	 * @param activelyUsed  whether the screen is on and unlocked right now
	 * @param nowMillis     current time in millis
	 *
	 * @return the notify flag, the new streak to persist, and the streak's elapsed time (for the message)
	 */
	static Outcome decide(final Streak state, final boolean rateAvailable, final int ratePph,
	                      final int limitPph, final long sustainedMs, final long reminderGapMs,
	                      final boolean activelyUsed, final long nowMillis) {
		return SustainedConditionTracker.decide(state, rateAvailable, ratePph >= limitPph,
				sustainedMs, nowMillis, SustainedConditionTracker.withReminders(activelyUsed, reminderGapMs));
	}

	private static long minutesPref(final SharedPreferences prefs, final Context context, final int keyRes,
	                                final int defaultMinutes, final int minMinutes, final int maxMinutes) {
		return clampMinutesToMs(prefs.getInt(context.getString(keyRes), defaultMinutes), minMinutes, maxMinutes);
	}

	/**
	 * Clamps a stored minutes preference to its slider range and converts to millis. Mirrors
	 * {@link BatteryRateTracker#clampDrainLimit}: the slider constrains UI input, but a corrupt or
	 * out-of-range stored value (e.g. 0 sustained minutes) would otherwise defeat the sustained-window
	 * requirement and fire on a momentary spike. Pure so it is unit-testable.
	 *
	 * @param storedMinutes the raw persisted value in minutes
	 * @param minMinutes    the slider's minimum
	 * @param maxMinutes    the slider's maximum
	 *
	 * @return the clamped duration in milliseconds
	 */
	static long clampMinutesToMs(final int storedMinutes, final int minMinutes, final int maxMinutes) {
		return Math.max(minMinutes, Math.min(maxMinutes, storedMinutes)) * MS_PER_MINUTE;
	}
}
