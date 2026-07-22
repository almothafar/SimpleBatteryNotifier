package com.almothafar.simplebatterynotifier.service;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Learns a <b>stable</b> full-battery-capacity estimate (mAh) by averaging trusted per-tick estimates
 * over time — the denominator that gives the synthesized sub-percent battery display real movement
 * (issue #204).
 * <p>
 * <b>Why a stable denominator.</b> The per-tick estimate ({@link SystemService#getBatteryCapacity})
 * is derived from the live charge counter, so dividing the counter by it cancels the counter out:
 * the "precise" percentage could only ever read {@code level.00}. Averaged over many spaced samples
 * the denominator stops tracking the live counter, and {@code counter / capacity} moves as real
 * charge moves.
 * <p>
 * <b>What is learned.</b> Only estimates from ticks whose charge counter passed the trust gates
 * (#69/#94) — the caller guards that — and only at battery levels where the whole-percent rounding
 * noise is small ({@link #MIN_SAMPLE_LEVEL_PERCENT}). Samples are spaced
 * ({@link #SAMPLE_SPACING_MS}) so a burst of battery broadcasts can't flood the average, and the
 * effective sample weight is floored at {@code 1/}{@link #SAMPLE_COUNT_CAP} so the average keeps
 * adapting as the battery ages. The running minimum/maximum ride along for the Insights capacity
 * display (#116).
 * <p>
 * <b>Warm-up.</b> Until {@link #MIN_STABLE_SAMPLES} spaced samples have been folded in,
 * {@link #observeAndAverage} returns 0 and the display honestly stays on whole percents.
 * <p>
 * <b>Storage.</b> The stats live in the backup-excluded transient file ({@link TransientState}):
 * another device's learned capacity is meaningless, and a fresh device re-learns within the first
 * hour of trusted readings. The pure helpers carry the correctness and are unit-tested without
 * Android, mirroring {@link CurrentUnitCalibrator}.
 */
public final class BatteryCapacityTracker {

	// Persisted learning state, kept in the backup-excluded transient file (TransientState, #167).
	private static final String PREF_AVERAGE_MAH = "_capacity_average_mah";
	private static final String PREF_SAMPLE_COUNT = "_capacity_sample_count";
	private static final String PREF_MIN_MAH = "_capacity_min_mah";
	private static final String PREF_MAX_MAH = "_capacity_max_mah";
	private static final String PREF_LAST_SAMPLE_AT = "_capacity_last_sample_at";

	// Below this battery level the whole-percent rounding dominates the estimate (at 20% the ±0.5
	// rounding is already a ±2.5% relative error); such samples would wobble the average for nothing.
	static final int MIN_SAMPLE_LEVEL_PERCENT = 20;
	// Minimum spacing between folded samples, so a burst of battery broadcasts (plug-in, screen-on)
	// contributes one sample, not a correlated pile taken at the same charge state.
	static final long SAMPLE_SPACING_MS = 5L * 60 * 1000;
	// How many spaced samples must be folded in before the average counts as stable. Before that the
	// display falls back to whole percents rather than trusting a barely-seeded average.
	static final int MIN_STABLE_SAMPLES = 6;
	// The incremental average's effective sample count is capped here, which floors every later
	// sample's weight at 1/60 — the average keeps following the battery as it ages instead of
	// freezing on ancient samples, while no single reading can jerk it around.
	static final int SAMPLE_COUNT_CAP = 60;

	private BatteryCapacityTracker() {
		// Utility class - prevent instantiation
	}

	/**
	 * Folds one trusted per-tick capacity estimate into the persisted running average and returns the
	 * stable capacity so far. The single entry point, called from
	 * {@link SystemService#getBatteryInfo} on ticks whose charge counter passed the trust gates —
	 * untrusted ticks must not call this (the caller guards, per the no-flag-argument convention).
	 *
	 * @param context      Application context
	 * @param estimateMah  this tick's full-capacity estimate in mAh (already plausibility-gated, #69)
	 * @param levelPercent the battery level as a whole percent, for the low-level noise gate
	 *
	 * @return the stable full capacity in mAh, or 0 while the learner is still warming up
	 */
	public static int observeAndAverage(Context context, int estimateMah, int levelPercent) {
		final SharedPreferences prefs = TransientState.prefs(context);
		final CapacityStats previous = loadStats(prefs);
		final CapacityStats updated = learn(previous, estimateMah, levelPercent, System.currentTimeMillis());

		// Persist only on change: rejected samples (spacing, level gate) write nothing.
		if (!updated.equals(previous)) {
			saveStats(prefs, updated);
		}
		return stableCapacityMah(updated);
	}

	/**
	 * Folds one estimate into the stats. Pure so it is unit-testable. A sample is rejected — the same
	 * instance returns, so the caller skips the persist — when the estimate is non-positive, the level
	 * is below {@link #MIN_SAMPLE_LEVEL_PERCENT}, or the previous sample is closer than
	 * {@link #SAMPLE_SPACING_MS}. An accepted sample moves the average by
	 * {@code (sample - average) / effectiveCount} with the count capped at {@link #SAMPLE_COUNT_CAP},
	 * and updates the running minimum/maximum.
	 *
	 * @param previous     the stats so far
	 * @param estimateMah  this tick's full-capacity estimate in mAh
	 * @param levelPercent the battery level as a whole percent
	 * @param nowMillis    current time in millis
	 *
	 * @return the updated stats, or {@code previous} itself when the sample was rejected
	 */
	static CapacityStats learn(CapacityStats previous, int estimateMah, int levelPercent, long nowMillis) {
		if (estimateMah <= 0 || levelPercent < MIN_SAMPLE_LEVEL_PERCENT) {
			return previous;
		}
		if (nowMillis - previous.lastSampleAt() < SAMPLE_SPACING_MS) {
			return previous;
		}
		if (previous.sampleCount() == 0) {
			return new CapacityStats(estimateMah, 1, estimateMah, estimateMah, nowMillis);
		}
		final int effectiveCount = Math.min(previous.sampleCount() + 1, SAMPLE_COUNT_CAP);
		final float average = previous.averageMah() + (estimateMah - previous.averageMah()) / effectiveCount;
		return new CapacityStats(
				average,
				Math.min(previous.sampleCount() + 1, SAMPLE_COUNT_CAP),
				Math.min(previous.minMah(), estimateMah),
				Math.max(previous.maxMah(), estimateMah),
				nowMillis);
	}

	/**
	 * The stable capacity under the warm-up rule. Pure so the boundary is unit-testable.
	 *
	 * @param stats the stats so far
	 *
	 * @return the average rounded to whole mAh once {@link #MIN_STABLE_SAMPLES} samples are in, else 0
	 */
	static int stableCapacityMah(CapacityStats stats) {
		return stats.sampleCount() >= MIN_STABLE_SAMPLES ? Math.round(stats.averageMah()) : 0;
	}

	/**
	 * Loads the persisted stats; package-private so the state tests can assert what was stored.
	 */
	static CapacityStats loadStats(SharedPreferences prefs) {
		return new CapacityStats(
				prefs.getFloat(PREF_AVERAGE_MAH, 0f),
				prefs.getInt(PREF_SAMPLE_COUNT, 0),
				prefs.getInt(PREF_MIN_MAH, 0),
				prefs.getInt(PREF_MAX_MAH, 0),
				prefs.getLong(PREF_LAST_SAMPLE_AT, 0L));
	}

	/**
	 * Persists the stats; package-private so the state tests can seed a warm learner.
	 */
	static void saveStats(SharedPreferences prefs, CapacityStats stats) {
		prefs.edit()
		     .putFloat(PREF_AVERAGE_MAH, stats.averageMah())
		     .putInt(PREF_SAMPLE_COUNT, stats.sampleCount())
		     .putInt(PREF_MIN_MAH, stats.minMah())
		     .putInt(PREF_MAX_MAH, stats.maxMah())
		     .putLong(PREF_LAST_SAMPLE_AT, stats.lastSampleAt())
		     .apply();
	}

	/**
	 * The persistent capacity-learning state.
	 *
	 * @param averageMah   running average of accepted estimates, in mAh (0 = nothing learned yet)
	 * @param sampleCount  how many spaced samples were folded in, capped at {@link #SAMPLE_COUNT_CAP}
	 * @param minMah       smallest accepted estimate, for the Insights capacity display (#116)
	 * @param maxMah       largest accepted estimate, for the Insights capacity display (#116)
	 * @param lastSampleAt when the last sample was folded in, for the spacing rule
	 */
	record CapacityStats(float averageMah, int sampleCount, int minMah, int maxMah, long lastSampleAt) {
	}
}
