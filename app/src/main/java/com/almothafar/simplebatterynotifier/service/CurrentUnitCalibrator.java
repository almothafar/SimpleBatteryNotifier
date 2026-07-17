package com.almothafar.simplebatterynotifier.service;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

import static java.util.Objects.isNull;

/**
 * Auto-detects devices that report {@code BATTERY_PROPERTY_CURRENT_NOW} in <b>mA instead of µA</b>
 * and rescales their readings, so the app can show the <em>real</em> current instead of hiding a
 * 1000&times;-too-small one (issue #152 v2 — the Kirin/HiSilicon fuel-gauge unit bug, sibling of the
 * charge-counter bug in #69/#94).
 * <p>
 * <b>How the conclusion is reached.</b> The maximum |raw| reading ever observed is tracked
 * persistently, across sessions and charge/discharge alike. A genuine-µA device exceeds
 * {@link #MICRO_AMP_FLOOR_RAW} (20&nbsp;mA as µA) within moments of normal awake use — screen-on
 * draw alone is 100&nbsp;000+ µA. A mA-unit device physically cannot reach it: that would mean a
 * sustained 20+ A. So once enough spaced readings have been observed
 * ({@link #MIN_OBSERVED_SAMPLES} at {@link #MIN_COUNT_SPACING_MS} apart, &ge; 1 h of cumulative
 * observation) with the maximum still below the floor, the device is concluded to report mA and
 * every reading is scaled &times;1000.
 * <p>
 * <b>Self-correcting, never oscillating.</b> Observation always records the <em>raw</em> value
 * (before scaling), so scaled outputs can't feed back into the decision. If a large raw reading
 * ever arrives on a device previously concluded as mA (e.g. a firmware update fixes the unit), the
 * persisted maximum rises past the floor in the same call and scaling stops immediately and
 * permanently. That ordering also bounds the scaled result: scaling only ever applies to a |raw|
 * below the floor, so the &times;1000 product stays far inside int range.
 * <p>
 * <b>Bounded preference churn.</b> The maximum plateaus after the first minutes of use on any
 * device, and counting stops for good at {@link #MIN_OBSERVED_SAMPLES}; after warm-up the common
 * path writes nothing. The pure helpers carry the correctness and are unit-tested without Android.
 */
public final class CurrentUnitCalibrator {

	// Persisted observation state (survives process restarts, like BatteryRateTracker's window).
	private static final String PREF_MAX_ABS_RAW = "_current_unit_max_abs_raw";
	private static final String PREF_OBSERVED_COUNT = "_current_unit_observed_count";
	private static final String PREF_LAST_OBSERVED_AT = "_current_unit_last_observed_at";

	// A genuine-µA device exceeds this raw magnitude (20 mA as µA) within any awake minute; a mA-unit
	// device would need a sustained 20+ A to reach it. Kirin's mA-unit raw peaks around ~2000 charging.
	static final int MICRO_AMP_FLOOR_RAW = 20_000;
	// How many spaced readings must be observed before concluding mA units. With the spacing below this
	// is >= 1 h of cumulative awake observation (across sessions) — enough to have seen real load, so a
	// quiet first minute can't mislabel a genuine-µA device.
	static final int MIN_OBSERVED_SAMPLES = 60;
	// Minimum spacing between counted readings, so a burst of plug-in broadcasts can't inflate the
	// count into a premature conclusion. The maximum is still updated on every reading regardless.
	static final long MIN_COUNT_SPACING_MS = 60L * 1000;

	static final int MICRO_AMPS_PER_MILLI_AMP = 1000;

	// getIntProperty returns this for an unsupported property (same sentinel BatteryRateTracker gates on).
	private static final int PROPERTY_UNSUPPORTED = Integer.MIN_VALUE;

	private CurrentUnitCalibrator() {
		// Utility class - prevent instantiation
	}

	/**
	 * Records a raw {@code CURRENT_NOW} reading into the persistent observation and returns it in µA —
	 * scaled &times;1000 when the device has been concluded to report mA, unchanged otherwise. The single
	 * entry point, called from {@link SystemService#getInstantaneousCurrentMicroAmps(Context)} so every
	 * consumer (the rate tracker, the Current row, {@link com.almothafar.simplebatterynotifier.model.ChargeSpeed})
	 * sees the same corrected value.
	 *
	 * @param context    Application context
	 * @param rawCurrent the raw {@code getIntProperty(BATTERY_PROPERTY_CURRENT_NOW)} value
	 *
	 * @return the reading in µA; sentinels ({@link Integer#MIN_VALUE}/{@link Integer#MAX_VALUE}) and a
	 *         blank 0 pass through untouched
	 */
	public static int observeAndScale(final Context context, final int rawCurrent) {
		if (isNull(context) || rawCurrent == PROPERTY_UNSUPPORTED || rawCurrent == Integer.MAX_VALUE || rawCurrent == 0) {
			return rawCurrent; // sentinel or blank: nothing to learn, nothing to scale
		}
		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		final Observation previous = loadObservation(prefs);
		final Observation updated = observe(previous, rawCurrent, System.currentTimeMillis());

		// Persist only on change: after warm-up (max plateaued, count capped) this writes nothing.
		if (!updated.equals(previous)) {
			saveObservation(prefs, updated);
		}
		// Decide from the *updated* observation: a first-ever large reading voids a stale mA conclusion
		// in this same call, which also guarantees any scaled |raw| is below the floor (no overflow).
		return scaledMicroAmps(rawCurrent, isMilliAmpUnits(updated));
	}

	/**
	 * Folds one raw reading into the observation. Pure so it is unit-testable. The maximum is updated on
	 * every reading; the count only for readings spaced {@link #MIN_COUNT_SPACING_MS} apart, and stops
	 * for good at {@link #MIN_OBSERVED_SAMPLES} (bounding lifetime preference writes). Returns the same
	 * instance when nothing changed, so the caller can skip the persist.
	 *
	 * @param previous   the observation so far
	 * @param rawCurrent a real (non-sentinel, nonzero) raw reading
	 * @param nowMillis  current time in millis
	 *
	 * @return the updated observation, or {@code previous} itself when unchanged
	 */
	static Observation observe(final Observation previous, final int rawCurrent, final long nowMillis) {
		final int maxAbsRaw = Math.max(previous.maxAbsRaw(), Math.abs(rawCurrent));
		final boolean countable = previous.observedCount() < MIN_OBSERVED_SAMPLES
				&& nowMillis - previous.lastObservedAt() >= MIN_COUNT_SPACING_MS;
		if (countable) {
			return new Observation(maxAbsRaw, previous.observedCount() + 1, nowMillis);
		}
		if (maxAbsRaw != previous.maxAbsRaw()) {
			return new Observation(maxAbsRaw, previous.observedCount(), previous.lastObservedAt());
		}
		return previous;
	}

	/**
	 * Whether the observation concludes the device reports mA instead of µA: enough spaced readings
	 * seen, and the maximum |raw| still below the µA floor. Pure so the boundary is unit-testable.
	 *
	 * @param observation the observation so far
	 *
	 * @return true when the device's {@code CURRENT_NOW} unit is concluded to be mA
	 */
	static boolean isMilliAmpUnits(final Observation observation) {
		return observation.observedCount() >= MIN_OBSERVED_SAMPLES
				&& observation.maxAbsRaw() < MICRO_AMP_FLOOR_RAW;
	}

	/**
	 * The reading in µA under a unit conclusion. Pure so the scaling is unit-testable.
	 *
	 * @param rawCurrent    a real raw reading
	 * @param milliAmpUnits whether the device is concluded to report mA
	 *
	 * @return {@code rawCurrent} &times; 1000 under mA units, else {@code rawCurrent} unchanged
	 */
	static int scaledMicroAmps(final int rawCurrent, final boolean milliAmpUnits) {
		return milliAmpUnits ? rawCurrent * MICRO_AMPS_PER_MILLI_AMP : rawCurrent;
	}

	private static Observation loadObservation(final SharedPreferences prefs) {
		return new Observation(
				prefs.getInt(PREF_MAX_ABS_RAW, 0),
				prefs.getInt(PREF_OBSERVED_COUNT, 0),
				prefs.getLong(PREF_LAST_OBSERVED_AT, 0));
	}

	private static void saveObservation(final SharedPreferences prefs, final Observation observation) {
		prefs.edit()
		     .putInt(PREF_MAX_ABS_RAW, observation.maxAbsRaw())
		     .putInt(PREF_OBSERVED_COUNT, observation.observedCount())
		     .putLong(PREF_LAST_OBSERVED_AT, observation.lastObservedAt())
		     .apply();
	}

	/**
	 * The persistent unit-observation state.
	 *
	 * @param maxAbsRaw      the largest |raw| reading ever observed (0 = nothing observed yet)
	 * @param observedCount  how many spaced readings have been counted, capped at {@link #MIN_OBSERVED_SAMPLES}
	 * @param lastObservedAt when the last counted reading was observed, for the spacing rule
	 */
	record Observation(int maxAbsRaw, int observedCount, long lastObservedAt) {
	}
}
