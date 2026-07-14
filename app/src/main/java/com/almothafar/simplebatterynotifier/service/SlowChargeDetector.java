package com.almothafar.simplebatterynotifier.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import androidx.preference.PreferenceManager;

import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.model.BatteryDO;
import com.almothafar.simplebatterynotifier.model.ChargeSpeed;

import static java.util.Objects.isNull;

/**
 * Warns when charging power stays abnormally low for a <em>sustained</em> time (issue #123) — a strong
 * signal of a frayed cable, a dirty/loose port, or a dying charger.
 * <p>
 * The alerting layer on top of the estimated charging power from {@link ChargeSpeed} (#122). Like the
 * fast-drain alert (#109) it needs no per-app data and adds <b>no new timer</b>: it is evaluated on the
 * {@code ACTION_BATTERY_CHANGED} broadcasts that already flow while charging.
 * <p>
 * <b>Why each guard exists (the false-positive traps):</b>
 * <ul>
 *   <li><b>Taper near full</b> — every charger deliberately trickles above ~80%, so the level gate keeps
 *       this to {@code < 80%}.</li>
 *   <li><b>Deliberate pauses</b> — thermal throttling and battery-protect caps report {@code NOT_CHARGING}
 *       rather than {@code CHARGING}; the status gate skips them (they <em>sleep</em>, they don't clear the
 *       streak).</li>
 *   <li><b>Unknown charger capability</b> — no API exposes the brick's rating, so the floor sits below the
 *       weakest legitimate charger (~2.5 W wired): sustained power under it while low basically only means a
 *       damaged cable / dirty port / dying brick.</li>
 * </ul>
 * <b>Once per charge session:</b> it warns a single time, then stays quiet until the power recovers above
 * the floor (re-arm) or the charger is disconnected (state cleared). The pure {@link #decide} core is
 * unit-tested; the streak is persisted so it survives process restarts, like {@link FastDrainDetector}.
 * <p>
 * v2 — session-relative collapse detection (18 W → 4 W and stuck, which this absolute floor can't see) — is
 * tracked separately in #132 and deliberately not built here.
 */
public final class SlowChargeDetector {

	// Persisted streak/hysteresis state (survives process restarts).
	private static final String PREF_STREAK_START = "_slow_charge_streak_start";
	private static final String PREF_ALERTED = "_slow_charge_alerted";
	private static final String PREF_LAST_SEEN_BELOW = "_slow_charge_last_seen_below";

	// Trickle floor: sustained wired power below this while charging low is the damaged-cable signal. It
	// sits below the weakest legitimate charger (a 5 W cube at 5 W is fine), so genuine chargers never trip
	// it — the floor is deliberately conservative to keep v1 nearly false-positive-free.
	static final int FLOOR_MILLIWATTS = 2_500;   // 2.5 W
	// Above this level the charger deliberately tapers, so low power is expected — not a fault.
	static final int MAX_LEVEL_PERCENT = 80;
	// How long the power must stay below the floor before warning. A constant in v1 (not user-tunable, per
	// the issue): long enough that a brief post-plug dip or a single noisy reading can't trip it.
	static final long SUSTAINED_MS = 3L * 60 * 1000;

	// The streak survives a brief data gap but not a long one (process death, doze, an unusable reading):
	// past this the "sustained" claim is unverifiable, so a fresh episode starts rather than an inflated
	// duration. Same bound as the rate window, mirroring FastDrainDetector.
	static final long MAX_OBSERVATION_GAP_MS = BatteryRateTracker.WINDOW_MS;

	private static final SlowChargeState CLEARED = new SlowChargeState(0, false, 0);

	private SlowChargeDetector() {
		// Utility class - prevent instantiation
	}

	/**
	 * Evaluates the slow-charge rule against the current snapshot and warns once per session when charging
	 * power stays below the floor. Called from
	 * {@link com.almothafar.simplebatterynotifier.receiver.BatteryLevelReceiver} on each battery broadcast,
	 * next to the fast-drain wiring — no timer of its own.
	 *
	 * @param context   Application context
	 * @param batteryDO Current battery snapshot (may be null)
	 */
	public static void evaluate(final Context context, final BatteryDO batteryDO) {
		if (isNull(context) || isNull(batteryDO)) {
			return;
		}
		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		final boolean enabled = prefs.getBoolean(context.getString(R.string._pref_key_notify_slow_charge), true);
		final int status = batteryDO.getStatus();

		// Session boundary: only an active or thermally-paused charge is a candidate. Discharging, full,
		// unknown or unplugged ends the session and re-arms for next time.
		if (!enabled || (status != BatteryManager.BATTERY_STATUS_CHARGING
				&& status != BatteryManager.BATTERY_STATUS_NOT_CHARGING)) {
			clearState(prefs);
			return;
		}

		// Not eligible to fire right now, but the session continues → sleep (hold the streak, don't warn):
		// a thermal/battery-protect pause (NOT_CHARGING), the deliberate taper above 80%, or wireless (v1
		// judges wired charging only). The observation-gap lapse in decide() still resets a long-stale streak.
		final int level = Math.round(batteryDO.getBatteryPercentage());
		final boolean wired = batteryDO.getPlugged() != BatteryManager.BATTERY_PLUGGED_WIRELESS;
		if (status != BatteryManager.BATTERY_STATUS_CHARGING || !wired || level >= MAX_LEVEL_PERCENT) {
			return;
		}

		final ChargeSpeed speed = SystemService.getChargeSpeed(context);
		final long now = System.currentTimeMillis();
		final SlowChargeState previous = loadState(prefs);
		final SlowChargeDecision decision = decide(previous, speed.isKnown(), speed.getMilliwatts(),
				FLOOR_MILLIWATTS, SUSTAINED_MS, now);

		// Persist only on change: an eligible-but-healthy charge re-decides CLEARED on every broadcast, and
		// rewriting identical state would churn SharedPreferences for nothing.
		if (!decision.newState().equals(previous)) {
			saveState(prefs, decision.newState());
		}
		if (decision.shouldNotify()) {
			NotificationService.sendSlowChargeWarning(context, speed.getWatts());
		}
	}

	/**
	 * Pure decision core, unit-testable with no Android dependencies. The caller has already confirmed
	 * eligibility (charging, wired, below the taper level); this decides purely on the power vs the floor
	 * and the sustained streak.
	 * <ul>
	 *   <li><b>Power unknown</b> (a device without {@code CURRENT_NOW}, or a blank reading): <em>sleep</em> —
	 *       no warning, streak untouched, so a brief gap doesn't reset it. The observation-gap lapse still
	 *       bounds continuity so a long gap can't later fire an inflated duration.</li>
	 *   <li><b>Power at/above the floor</b>: the charge is healthy → re-arm (clear the streak + alerted), so
	 *       a later collapse warns again (hysteresis).</li>
	 *   <li><b>Power below the floor</b>: start the streak if new; once it has held for the window, fire the
	 *       single warning for this charge session.</li>
	 * </ul>
	 *
	 * @param state       current persisted state
	 * @param powerKnown  whether the charge power could be estimated this tick
	 * @param milliwatts  the estimated charge power in mW (valid when {@code powerKnown})
	 * @param floorMw     the trickle floor in mW
	 * @param sustainedMs how long the power must stay below the floor before warning
	 * @param nowMillis   current time in millis
	 *
	 * @return whether to warn now, and the new state to persist
	 */
	static SlowChargeDecision decide(final SlowChargeState state,
	                                 final boolean powerKnown,
	                                 final int milliwatts,
	                                 final int floorMw,
	                                 final long sustainedMs,
	                                 final long nowMillis) {
		if (!powerKnown) {
			return new SlowChargeDecision(false, state); // sleep — keep the streak, don't warn
		}
		if (milliwatts >= floorMw) {
			return new SlowChargeDecision(false, CLEARED); // healthy power — re-arm the session
		}

		// Continuity: after a long observation gap the "sustained" claim has lapsed — start a fresh episode
		// rather than warn immediately with a duration built on unobserved time.
		final boolean lapsed = state.streakStart() != 0
				&& nowMillis - state.lastSeenBelow() > MAX_OBSERVATION_GAP_MS;
		final long start = (state.streakStart() == 0 || lapsed) ? nowMillis : state.streakStart();
		final long elapsed = nowMillis - start;
		boolean alerted = !lapsed && state.alerted();
		boolean notify = false;

		if (elapsed >= sustainedMs && !alerted) {
			notify = true;      // the single warning for this charge session
			alerted = true;
		}
		return new SlowChargeDecision(notify, new SlowChargeState(start, alerted, nowMillis));
	}

	private static SlowChargeState loadState(final SharedPreferences prefs) {
		return new SlowChargeState(
				prefs.getLong(PREF_STREAK_START, 0),
				prefs.getBoolean(PREF_ALERTED, false),
				prefs.getLong(PREF_LAST_SEEN_BELOW, 0));
	}

	private static void saveState(final SharedPreferences prefs, final SlowChargeState state) {
		prefs.edit()
		     .putLong(PREF_STREAK_START, state.streakStart())
		     .putBoolean(PREF_ALERTED, state.alerted())
		     .putLong(PREF_LAST_SEEN_BELOW, state.lastSeenBelow())
		     .apply();
	}

	/**
	 * Clears the streak only when it isn't already clear, so the common non-eligible broadcasts don't churn
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
	 * @param streakStart   when the power first dropped below the floor this episode (0 = no active streak)
	 * @param alerted       whether the one warning has fired this charge session
	 * @param lastSeenBelow when the power was last observed below the floor; a gap longer than
	 *                      {@link #MAX_OBSERVATION_GAP_MS} lapses the streak (continuity unverifiable)
	 */
	record SlowChargeState(long streakStart, boolean alerted, long lastSeenBelow) {
	}

	/**
	 * Result of {@link #decide}: whether to warn now, and the new state to persist.
	 *
	 * @param shouldNotify whether to send the warning now
	 * @param newState     the state to persist
	 */
	record SlowChargeDecision(boolean shouldNotify, SlowChargeState newState) {
	}
}
