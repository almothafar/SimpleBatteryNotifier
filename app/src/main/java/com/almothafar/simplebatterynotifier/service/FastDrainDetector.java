package com.almothafar.simplebatterynotifier.service;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.model.BatteryDO;
import com.almothafar.simplebatterynotifier.service.BatteryRateTracker.BatteryRate;

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

	// Persisted streak/hysteresis state (survives process restarts).
	private static final String PREF_STREAK_START = "_fast_drain_streak_start";
	private static final String PREF_ALERTED = "_fast_drain_alerted";
	private static final String PREF_LAST_REMINDER = "_fast_drain_last_reminder";
	private static final String PREF_LAST_SEEN_ABOVE = "_fast_drain_last_seen_above";

	// Defaults and accepted ranges (user-tunable), matching the settings XML min/max — enforced when the
	// preferences are read, so a corrupt/out-of-range value can't turn this into a spike alarm.
	static final int DEFAULT_SUSTAINED_MINUTES = 5;
	static final int MIN_SUSTAINED_MINUTES = 1;
	static final int MAX_SUSTAINED_MINUTES = 30;
	static final int DEFAULT_REMINDER_MINUTES = 15;
	static final int MIN_REMINDER_MINUTES = 5;
	static final int MAX_REMINDER_MINUTES = 60;

	// How long the streak survives without a fresh above-limit observation. The rate itself is smoothed
	// over BatteryRateTracker.WINDOW_MS, so continuity beyond that window is unknowable — after a longer
	// gap (process death, doze, unusable rate) the "sustained" claim has lapsed and the episode restarts,
	// rather than instantly alerting with a wildly inflated "for the last N minutes".
	static final long MAX_OBSERVATION_GAP_MS = BatteryRateTracker.WINDOW_MS;

	private static final long MS_PER_MINUTE = 60_000L;

	private static final FastDrainState CLEARED = new FastDrainState(0, false, 0, 0);

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
			clearState(prefs);
			return;
		}

		final int limit = BatteryRateTracker.getDrainLimitPercentPerHour(context);
		final long sustainedMs = minutesPref(prefs, context, R.string._pref_key_fast_drain_sustained_minutes,
				DEFAULT_SUSTAINED_MINUTES, MIN_SUSTAINED_MINUTES, MAX_SUSTAINED_MINUTES);
		final long reminderGapMs = minutesPref(prefs, context, R.string._pref_key_fast_drain_reminder_minutes,
				DEFAULT_REMINDER_MINUTES, MIN_REMINDER_MINUTES, MAX_REMINDER_MINUTES);
		final boolean activelyUsed = SystemService.isActivelyUsed(context);
		final long now = System.currentTimeMillis();

		final FastDrainState previous = loadState(prefs);
		final FastDrainDecision decision = decide(previous, rate.hasRate(), rate.percentPerHour(),
				limit, sustainedMs, reminderGapMs, activelyUsed, now);

		// Persist only on change: the common case (discharging below the limit) re-decides CLEARED on
		// every broadcast, and rewriting identical state would churn SharedPreferences for nothing.
		if (!decision.newState().equals(previous)) {
			saveState(prefs, decision.newState());
		}
		if (decision.shouldNotify()) {
			final int elapsedMinutes = Math.max(1, Math.round(decision.elapsedMs() / (float) MS_PER_MINUTE));
			NotificationService.sendFastDrainNotification(context, rate.percentPerHour(), limit, elapsedMinutes);
		}
	}

	/**
	 * Pure decision core, unit-testable with no Android dependencies.
	 * <ul>
	 *   <li><b>Rate unavailable</b> (#108 can't produce a %/h yet — warm-up, or an unsupported device):
	 *       the alert <em>sleeps</em>. No notification, and the streak is left intact so a brief data gap
	 *       doesn't reset it. Fail-quiet = no false alarms. Continuity is bounded, though: if the rate
	 *       hasn't been observed at/above the limit for {@link #MAX_OBSERVATION_GAP_MS}, the streak has
	 *       lapsed and the next above-limit reading starts a fresh episode instead of instantly alerting
	 *       with a duration built on unobserved time.</li>
	 *   <li><b>Rate below the limit</b>: the drain has calmed → re-arm the episode (clear the streak and
	 *       the alerted flag), so a later flare-up warns again (hysteresis).</li>
	 *   <li><b>Rate at/above the limit</b>: start the streak if new; once it has been sustained for the
	 *       window, fire the first alert (regardless of screen state — warn at least once). After that,
	 *       stay silent while actively used, but remind every {@code reminderGap} while off/locked.</li>
	 * </ul>
	 *
	 * @param state         current persisted state
	 * @param rateAvailable whether #108 produced a trustworthy %/h this tick
	 * @param ratePph       the drain rate magnitude in %/h (valid when {@code rateAvailable})
	 * @param limitPph      the user's high-drain limit in %/h
	 * @param sustainedMs   how long the rate must stay at/above the limit before the first alert
	 * @param reminderGapMs minimum gap between reminders while the screen is off/locked
	 * @param activelyUsed  whether the screen is on and unlocked right now
	 * @param nowMillis     current time in millis
	 *
	 * @return the notify flag, the new state to persist, and the streak's elapsed time (for the message)
	 */
	static FastDrainDecision decide(final FastDrainState state, final boolean rateAvailable, final int ratePph,
	                                final int limitPph, final long sustainedMs, final long reminderGapMs,
	                                final boolean activelyUsed, final long nowMillis) {
		if (!rateAvailable) {
			return new FastDrainDecision(false, state, 0); // sleep — keep the streak, don't fire
		}
		if (ratePph < limitPph) {
			return new FastDrainDecision(false, CLEARED, 0); // calmed — re-arm the episode
		}

		// Continuity: the sleep above keeps the streak through a brief data gap, but after a long one
		// (process death, doze) the "sustained" claim has lapsed — start a fresh episode instead of
		// alerting immediately with an inflated duration built on unobserved time.
		final boolean lapsed = state.streakStart() != 0
				&& nowMillis - state.lastSeenAbove() > MAX_OBSERVATION_GAP_MS;

		final long start = (state.streakStart() == 0 || lapsed) ? nowMillis : state.streakStart();
		final long elapsed = nowMillis - start;
		boolean alerted = !lapsed && state.alerted();
		long lastReminder = lapsed ? 0 : state.lastReminder();
		boolean notify = false;

		if (elapsed >= sustainedMs) {
			if (!alerted) {
				notify = true;          // first alert this episode, regardless of screen state
				alerted = true;
				lastReminder = nowMillis;
			} else if (!activelyUsed && nowMillis - lastReminder >= reminderGapMs) {
				notify = true;          // background drain the user can't see — remind on the gap
				lastReminder = nowMillis;
			}
		}
		return new FastDrainDecision(notify, new FastDrainState(start, alerted, lastReminder, nowMillis), elapsed);
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

	private static FastDrainState loadState(final SharedPreferences prefs) {
		return new FastDrainState(
				prefs.getLong(PREF_STREAK_START, 0),
				prefs.getBoolean(PREF_ALERTED, false),
				prefs.getLong(PREF_LAST_REMINDER, 0),
				prefs.getLong(PREF_LAST_SEEN_ABOVE, 0));
	}

	private static void saveState(final SharedPreferences prefs, final FastDrainState state) {
		prefs.edit()
		     .putLong(PREF_STREAK_START, state.streakStart())
		     .putBoolean(PREF_ALERTED, state.alerted())
		     .putLong(PREF_LAST_REMINDER, state.lastReminder())
		     .putLong(PREF_LAST_SEEN_ABOVE, state.lastSeenAbove())
		     .apply();
	}

	/**
	 * Clears the streak only when it isn't already clear, so charging/disabled broadcasts don't churn
	 * SharedPreferences on every tick.
	 *
	 * @param prefs the shared preferences
	 */
	private static void clearState(final SharedPreferences prefs) {
		if (!loadState(prefs).equals(CLEARED)) {
			saveState(prefs, CLEARED);
		}
	}

	/**
	 * Persisted streak/hysteresis state.
	 *
	 * @param streakStart   when the rate first reached the limit this episode (0 = no active streak)
	 * @param alerted       whether the first alert has fired this episode
	 * @param lastReminder  when the last (re)notification was sent
	 * @param lastSeenAbove when the rate was last observed at/above the limit; a gap longer than
	 *                      {@link #MAX_OBSERVATION_GAP_MS} lapses the streak (continuity unknowable)
	 */
	record FastDrainState(long streakStart, boolean alerted, long lastReminder, long lastSeenAbove) {
	}

	/**
	 * Result of {@link #decide}: whether to (re)notify, the new state to persist, and the streak's
	 * elapsed time (for the "for the last N minutes" message).
	 *
	 * @param shouldNotify whether to send a notification now
	 * @param newState     the state to persist
	 * @param elapsedMs    the streak's elapsed time in millis
	 */
	record FastDrainDecision(boolean shouldNotify, FastDrainState newState, long elapsedMs) {
	}
}
