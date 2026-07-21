package com.almothafar.simplebatterynotifier.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import androidx.preference.PreferenceManager;

import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.model.BatteryDO;
import com.almothafar.simplebatterynotifier.model.ChargeSpeed;
import com.almothafar.simplebatterynotifier.service.SustainedConditionTracker.Outcome;
import com.almothafar.simplebatterynotifier.service.SustainedConditionTracker.Streak;
import com.almothafar.simplebatterynotifier.service.SustainedConditionTracker.StreakStore;

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

	// Persisted streak/hysteresis state (survives process restarts). Keys unchanged from before the
	// shared-core extraction (#163) so no in-progress episode is lost on upgrade.
	private static final String PREF_STREAK_START = "_slow_charge_streak_start";
	private static final String PREF_ALERTED = "_slow_charge_alerted";
	private static final String PREF_LAST_SEEN_BELOW = "_slow_charge_last_seen_below";

	// No reminder concept: slow charge warns once per session (three-arg store, no reminder key).
	private static final StreakStore STORE =
			new StreakStore(PREF_STREAK_START, PREF_ALERTED, PREF_LAST_SEEN_BELOW);

	// Trickle floor: sustained wired power below this while charging low is the damaged-cable signal. It
	// sits below the weakest legitimate charger (a 5 W cube at 5 W is fine), so genuine chargers never trip
	// it — the floor is deliberately conservative to keep v1 nearly false-positive-free.
	static final int FLOOR_MILLIWATTS = 2_500;   // 2.5 W
	// Above this level the charger deliberately tapers, so low power is expected — not a fault.
	static final int MAX_LEVEL_PERCENT = 80;
	// How long the power must stay below the floor before warning. A constant in v1 (not user-tunable, per
	// the issue): long enough that a brief post-plug dip or a single noisy reading can't trip it.
	static final long SUSTAINED_MS = 3L * 60 * 1000;

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
	public static void evaluate(Context context, BatteryDO batteryDO) {
		if (isNull(context) || isNull(batteryDO)) {
			return;
		}
		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		// Streak state is volatile and device-specific → the backup-excluded transient file (#167); the
		// enable setting stays in the default (backed-up) prefs.
		final SharedPreferences transientPrefs = TransientState.prefs(context);
		final boolean enabled = prefs.getBoolean(context.getString(R.string._pref_key_notify_slow_charge), true);
		final int status = batteryDO.getStatus();

		// Session boundary: only an active or thermally-paused charge is a candidate. Discharging, full,
		// unknown or unplugged ends the session and re-arms for next time.
		if (!enabled || (status != BatteryManager.BATTERY_STATUS_CHARGING
				&& status != BatteryManager.BATTERY_STATUS_NOT_CHARGING)) {
			STORE.clear(transientPrefs);
			return;
		}

		// Not eligible to fire right now, but the session continues → sleep (hold the streak, don't warn):
		// a thermal/battery-protect pause (NOT_CHARGING), the deliberate taper above 80%, or wireless (v1
		// judges wired charging only). The observation-gap lapse in decide() still resets a long-stale streak.
		final int level = batteryDO.getBatteryPercentageInt();
		final boolean wired = batteryDO.getPlugged() != BatteryManager.BATTERY_PLUGGED_WIRELESS;
		if (status != BatteryManager.BATTERY_STATUS_CHARGING || !wired || level >= MAX_LEVEL_PERCENT) {
			return;
		}

		// Judge the snapshot in hand, not a fresh hardware read: every surface in this tick (table row,
		// notification segment, this detector) must see the same reading (#157).
		final ChargeSpeed speed = ChargeSpeed.fromMeasurements(batteryDO.getCurrentMicroAmps(), batteryDO.getVoltage());
		final long now = System.currentTimeMillis();
		final Streak previous = STORE.load(transientPrefs);
		final Outcome decision = decide(previous, speed.isKnown(), speed.getMilliwatts(), FLOOR_MILLIWATTS, SUSTAINED_MS, now);

		STORE.saveIfChanged(transientPrefs, decision.newState());
		if (decision.shouldNotify()) {
			NotificationService.sendSlowChargeWarning(context, speed.getWatts());
		}
	}

	/**
	 * The slow-charge decision as a pure function of the estimated charge power, delegating the
	 * streak/lapse/hysteresis mechanics to {@link SustainedConditionTracker} (#163). The condition is
	 * "power below the floor"; the repeat policy is {@link SustainedConditionTracker#fireOnce} — a single
	 * warning per charge session, re-armed only when the power recovers. The caller has already confirmed
	 * eligibility (charging, wired, below the taper level).
	 *
	 * @param state       current persisted streak
	 * @param powerKnown  whether the charge power could be estimated this tick
	 * @param milliwatts  the estimated charge power in mW (valid when {@code powerKnown})
	 * @param floorMw     the trickle floor in mW
	 * @param sustainedMs how long the power must stay below the floor before warning
	 * @param nowMillis   current time in millis
	 *
	 * @return whether to warn now, the new streak to persist, and the streak's elapsed time
	 */
	static Outcome decide(Streak state,
	                      boolean powerKnown,
	                      int milliwatts,
	                      int floorMw,
	                      long sustainedMs,
	                      long nowMillis) {
		final boolean conditionActive = milliwatts < floorMw;
		return SustainedConditionTracker.decide(state, powerKnown, conditionActive, sustainedMs, nowMillis,
				SustainedConditionTracker.fireOnce());
	}
}
