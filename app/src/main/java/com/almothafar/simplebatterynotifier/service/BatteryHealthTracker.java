package com.almothafar.simplebatterynotifier.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.util.Log;
import androidx.annotation.StringRes;
import androidx.preference.PreferenceManager;

import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.model.BatteryHealthGrade;

import static java.util.Objects.isNull;

/**
 * Tracks battery health metrics including charge cycles and estimated battery health.
 * <p>
 * Charge Cycle Definition:
 * A charge cycle is one full battery's worth of charge delivered (100 percentage-points). Partial
 * charges accumulate: e.g. charging 40%->90% twice counts as one cycle. This matches the industry
 * definition and, unlike a strict single low->high swing, counts real usage where the user tops up
 * before empty and unplugs before full. This tracked estimate is only the fallback; the OS-reported
 * count (EXTRA_CYCLE_COUNT, Android 14+) still takes precedence in {@link #getEffectiveCycleCount}.
 * <p>
 * Battery Health Estimation:
 * Based on charge cycles and typical lithium-ion battery degradation patterns:
 * - 0-300 cycles: Excellent (95-100% health)
 * - 300-500 cycles: Good (85-95% health)
 * - 500-800 cycles: Fair (70-85% health)
 * - 800+ cycles: Poor (<70% health)
 */
public class BatteryHealthTracker {

	private static final String TAG = "BatteryHealthTracker";

	// SharedPreferences keys
	private static final String PREF_CHARGE_CYCLES = "_battery_health_charge_cycles";
	private static final String PREF_FIRST_USE_DATE = "_battery_health_first_use_date";
	// Last observed battery level, and the fractional charge accrued toward the next whole cycle
	// (percentage-points, 0-99), persisted so a partial charge survives app/process restarts.
	private static final String PREF_LAST_LEVEL = "_battery_health_last_level";
	private static final String PREF_CYCLE_ACCRUAL_POINTS = "_battery_health_cycle_accrual_points";
	private static final String PREF_DESIGN_CAPACITY = "key_battery_design_capacity";
	// Debug-only cycle offset kept separate from real tracking so it can be cleared without
	// destroying genuine data (see the debug menu in BatteryInsightsActivity).
	private static final String PREF_DEBUG_CHARGE_CYCLES = "_battery_health_debug_charge_cycles";

	// One charge cycle = this many percentage-points of charge delivered to the battery.
	private static final int CYCLE_PERCENT_POINTS = 100;

	// Health estimation thresholds (cycle-based)
	private static final int EXCELLENT_THRESHOLD = 300;
	private static final int GOOD_THRESHOLD = 500;
	private static final int FAIR_THRESHOLD = 800;

	// Accepted range for a user-entered design (rated) capacity, in mAh
	public static final int MIN_DESIGN_CAPACITY_MAH = 500;
	public static final int MAX_DESIGN_CAPACITY_MAH = 15000;

	// Health-percentage thresholds for the measured (design-capacity-based) health figure
	private static final int EXCELLENT_HEALTH_PERCENT = 90;
	private static final int GOOD_HEALTH_PERCENT = 80;
	private static final int FAIR_HEALTH_PERCENT = 70;

	// Plausibility window for trusting the measured figure at all: the current full-capacity estimate
	// must land within [design x LOW, design x HIGH]. Outside it the device's charge counter disagrees
	// with the rated capacity so badly that no honest health figure can be shown — usually a broken or
	// quirky counter (some Kirin/HiSilicon devices report a small fixed uAh), though a genuinely failing
	// battery can also read this low. See issue #94 / {@link #isBatteryReadingUnreliable}.
	private static final float MEASURED_HEALTH_MIN_PLAUSIBLE_RATIO = 0.40f;
	private static final float MEASURED_HEALTH_MAX_PLAUSIBLE_RATIO = 1.15f;

	/**
	 * Records battery state and updates charge cycle count if appropriate.
	 *
	 * @param context      Application context
	 * @param batteryLevel Current battery percentage (0-100)
	 * @param status       Battery charging status from BatteryManager
	 */
	public static void recordBatteryState(final Context context, final int batteryLevel, final int status) {
		if (isNull(context)) {
			Log.w(TAG, "Context is null, cannot record battery state");
			return;
		}

		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		final SharedPreferences.Editor editor = prefs.edit();
		boolean dirty = false;

		// Initialize first use date if not set
		if (prefs.getLong(PREF_FIRST_USE_DATE, 0) == 0) {
			editor.putLong(PREF_FIRST_USE_DATE, System.currentTimeMillis());
			dirty = true;
			Log.d(TAG, "First use date initialized");
		}

		// Accrue partial charge cycles from the rise in battery level while charging. Each 100
		// percentage-points of charge delivered counts as one cycle; the remainder carries over.
		final boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
				|| status == BatteryManager.BATTERY_STATUS_FULL;
		final int prevLevel = prefs.getInt(PREF_LAST_LEVEL, -1);
		final int carry = prefs.getInt(PREF_CYCLE_ACCRUAL_POINTS, 0);
		final CycleAccrual accrual = accruePartialCycles(prevLevel, batteryLevel, isCharging, carry);

		if (accrual.completedCycles() > 0) {
			final int total = prefs.getInt(PREF_CHARGE_CYCLES, 0) + accrual.completedCycles();
			editor.putInt(PREF_CHARGE_CYCLES, total);
			dirty = true;
			Log.i(TAG, "Charge cycle(s) completed (+" + accrual.completedCycles() + "). Total cycles: " + total);
		}
		if (accrual.carryPercentPoints() != carry) {
			editor.putInt(PREF_CYCLE_ACCRUAL_POINTS, accrual.carryPercentPoints());
			dirty = true;
		}
		if (batteryLevel != prevLevel) {
			editor.putInt(PREF_LAST_LEVEL, batteryLevel);
			dirty = true;
		}

		if (dirty) {
			editor.apply();
		}
	}

	/**
	 * Accrues fractional charge cycles from a rise in battery level.
	 * <p>
	 * A charge cycle is {@link #CYCLE_PERCENT_POINTS} percentage-points of charge delivered, so partial
	 * charges accumulate across sessions instead of requiring a single low-to-full swing. Only positive
	 * level deltas observed while charging count; discharging, a flat level, or an unknown previous
	 * level ({@code prevLevel < 0}) contribute nothing and leave the carry untouched. Pure and
	 * Android-free so it is unit-testable.
	 *
	 * @param prevLevel          last recorded battery level (0-100), or -1 when unknown
	 * @param currentLevel       current battery level (0-100)
	 * @param charging           whether the battery is currently charging (or full)
	 * @param carryPercentPoints percentage-points already accrued toward the next cycle (0-99)
	 *
	 * @return the whole cycles completed by this step and the new carry remainder
	 */
	static CycleAccrual accruePartialCycles(final int prevLevel, final int currentLevel,
	                                        final boolean charging, final int carryPercentPoints) {
		if (!charging || prevLevel < 0 || currentLevel <= prevLevel) {
			return new CycleAccrual(0, carryPercentPoints);
		}
		final int total = carryPercentPoints + (currentLevel - prevLevel);
		return new CycleAccrual(total / CYCLE_PERCENT_POINTS, total % CYCLE_PERCENT_POINTS);
	}

	/**
	 * Result of {@link #accruePartialCycles}: the whole cycles completed this step, and the leftover
	 * percentage-points carried toward the next cycle.
	 */
	record CycleAccrual(int completedCycles, int carryPercentPoints) {
	}

	/**
	 * Gets the total number of completed charge cycles.
	 *
	 * @param context Application context
	 *
	 * @return Number of charge cycles, or 0 if not initialized
	 */
	public static int getChargeCycles(final Context context) {
		if (isNull(context)) {
			return 0;
		}
		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		return prefs.getInt(PREF_CHARGE_CYCLES, 0);
	}

	/**
	 * Gets the best-available charge cycle count: the OS-reported value where the device exposes it
	 * (Android 14+ {@code EXTRA_CYCLE_COUNT}), otherwise this app's own tracked estimate. Any
	 * debug-injected cycles are added on top so the debug menu still visibly affects the display and
	 * health estimate (even on devices that report an OS cycle count).
	 * <p>
	 * Used for both the displayed cycle count and the health estimate so the two stay consistent.
	 *
	 * @param context Application context
	 *
	 * @return Charge cycle count (OS-reported if available, else the tracked estimate) plus any
	 * debug-injected cycles
	 */
	public static int getEffectiveCycleCount(final Context context) {
		if (isNull(context)) {
			return 0;
		}
		final int osCycleCount = SystemService.getChargeCycleCount(context);
		final int base = osCycleCount > 0 ? osCycleCount : getChargeCycles(context);
		return base + getDebugChargeCycles(context);
	}

	/**
	 * Gets the number of debug-injected charge cycles (0 in normal use). Tracked separately from real
	 * cycles so {@link #resetDebugData} can clear them without touching genuine tracking.
	 *
	 * @param context Application context
	 *
	 * @return Debug-injected cycle count
	 */
	public static int getDebugChargeCycles(final Context context) {
		if (isNull(context)) {
			return 0;
		}
		return PreferenceManager.getDefaultSharedPreferences(context).getInt(PREF_DEBUG_CHARGE_CYCLES, 0);
	}

	/**
	 * Gets the user-entered battery design (rated) capacity in mAh.
	 * <p>
	 * Android exposes no public API for the design capacity, so the user supplies it (from the
	 * manufacturer's specs) to enable a measured health figure. See {@link #getMeasuredHealthPercentage}.
	 *
	 * @param context Application context
	 *
	 * @return Design capacity in mAh, or 0 when unset
	 */
	public static int getDesignCapacity(final Context context) {
		if (isNull(context)) {
			return 0;
		}
		return PreferenceManager.getDefaultSharedPreferences(context).getInt(PREF_DESIGN_CAPACITY, 0);
	}

	/**
	 * Whether a design capacity has been set.
	 *
	 * @param context Application context
	 *
	 * @return true when the user has entered a design capacity
	 */
	public static boolean hasDesignCapacity(final Context context) {
		return getDesignCapacity(context) > 0;
	}

	/**
	 * Whether the given value is a plausible battery design capacity (within {@link #MIN_DESIGN_CAPACITY_MAH}
	 * to {@link #MAX_DESIGN_CAPACITY_MAH}).
	 *
	 * @param mAh Candidate capacity in mAh
	 *
	 * @return true when the value is within the accepted range
	 */
	public static boolean isValidDesignCapacity(final int mAh) {
		return mAh >= MIN_DESIGN_CAPACITY_MAH && mAh <= MAX_DESIGN_CAPACITY_MAH;
	}

	/**
	 * Stores (or clears) the user-entered design capacity.
	 * <p>
	 * A non-positive value clears the preference and reverts to the cycle-based health estimate.
	 *
	 * @param context Application context
	 * @param mAh     Design capacity in mAh, or {@code <= 0} to clear
	 */
	public static void setDesignCapacity(final Context context, final int mAh) {
		if (isNull(context)) {
			return;
		}
		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		if (mAh <= 0) {
			prefs.edit().remove(PREF_DESIGN_CAPACITY).apply();
			Log.i(TAG, "Design capacity cleared");
		} else {
			prefs.edit().putInt(PREF_DESIGN_CAPACITY, mAh).apply();
			Log.i(TAG, "Design capacity set to " + mAh + " mAh");
		}
	}

	/**
	 * Computes a measured battery-health percentage from the measured current full capacity and the
	 * user-entered design capacity.
	 * <p>
	 * {@code health % = current full capacity (measured) / design capacity (user-entered) * 100}.
	 * This is the accurate figure requested in #32; it applies whenever the user has entered a design
	 * capacity and the device reports the live charge counter used to estimate the current full
	 * capacity. It is shown regardless of charge level so it stays consistent with the displayed
	 * Capacity (#103); when no design capacity is set, callers fall back to the cycle-based estimate.
	 *
	 * @param context Application context
	 *
	 * @return Measured health percentage (1-100), or -1 when it cannot be determined
	 */
	public static int getMeasuredHealthPercentage(final Context context) {
		if (isNull(context)) {
			return -1;
		}
		return computeMeasuredHealth(
				SystemService.getBatteryCapacity(context),
				getDesignCapacity(context));
	}

	/**
	 * Pure helper for the measured health percentage, unit-testable with no Android dependencies.
	 * <p>
	 * Returns the current full capacity as a percentage of the design capacity, clamped to 1-100. As
	 * long as a design capacity is set and the device reports a usable charge-counter estimate, the
	 * measured figure is always returned so it stays consistent with the displayed Capacity — the app
	 * no longer withholds it at low charge and silently substitutes the cycle-based estimate, which
	 * read a misleading 100% on a new phone (issue #103, superseding the earlier near-full gate #37).
	 * The worst readings are still handled elsewhere: {@code SystemService.estimateFullCapacityMah}
	 * rejects an out-of-range charge counter (yielding 0 here, hence -1), and estimates wildly out of
	 * line with the design capacity are flagged via {@link #isEstimateImplausible}.
	 *
	 * @param currentFullMah measured current full capacity in mAh (0 when unknown)
	 * @param designMah      user-entered design capacity in mAh (0 when unset)
	 *
	 * @return health percentage clamped to 1-100, or -1 when either input is unusable
	 */
	static int computeMeasuredHealth(final int currentFullMah, final int designMah) {
		if (currentFullMah <= 0 || designMah <= 0) {
			return -1;
		}
		final int percent = Math.round(currentFullMah * 100f / designMah);
		return Math.max(1, Math.min(100, percent));
	}

	/**
	 * Whether this device's battery capacity reading can't be trusted, because the charge-counter
	 * estimate of the current full capacity is wildly out of line with the user-entered design capacity.
	 * <p>
	 * Some devices (notably certain Kirin/HiSilicon chipsets) report a charge counter that bears no
	 * relation to the real capacity, which surfaces as an absurd capacity (e.g. 852 mAh) and the absurd
	 * health figure derived from it (e.g. 21% on a healthy 4000 mAh battery). When detected, callers
	 * should flag the reading — the home screen shows "Unknown" for the capacity, the insights screen
	 * keeps the figure but adds a warning — see issue #94. A genuinely worn-out battery can also read
	 * this low, a possibility the UI surfaces to the user.
	 *
	 * @param context Application context
	 *
	 * @return true when a design capacity is set, a charge-counter estimate exists, and the estimate is
	 * outside the plausible window around the design capacity
	 */
	public static boolean isBatteryReadingUnreliable(final Context context) {
		if (isNull(context)) {
			return false;
		}
		return isEstimateImplausible(
				SystemService.getBatteryCapacity(context),
				getDesignCapacity(context));
	}

	/**
	 * Pure helper for {@link #isBatteryReadingUnreliable}, unit-testable with no Android dependencies.
	 * <p>
	 * Returns false when either input is missing: without a design capacity there is nothing to
	 * cross-check against, and a missing estimate ({@code <= 0}) is "unavailable" rather than
	 * "implausible" — both cases fall through to the normal measured/estimated health handling.
	 *
	 * @param currentFullMah measured current full capacity in mAh (0 when unknown)
	 * @param designMah      user-entered design capacity in mAh (0 when unset)
	 *
	 * @return true when the estimate is below {@link #MEASURED_HEALTH_MIN_PLAUSIBLE_RATIO} or above
	 * {@link #MEASURED_HEALTH_MAX_PLAUSIBLE_RATIO} times the design capacity
	 */
	static boolean isEstimateImplausible(final int currentFullMah, final int designMah) {
		if (currentFullMah <= 0 || designMah <= 0) {
			return false;
		}
		return currentFullMah < designMah * MEASURED_HEALTH_MIN_PLAUSIBLE_RATIO
				|| currentFullMah > designMah * MEASURED_HEALTH_MAX_PLAUSIBLE_RATIO;
	}

	/**
	 * Maps a health percentage to a wear grade, consistent with the cycle-based buckets.
	 *
	 * @param healthPercentage Health percentage (0-100)
	 *
	 * @return the matching {@link BatteryHealthGrade}
	 */
	public static BatteryHealthGrade gradeForPercentage(final int healthPercentage) {
		if (healthPercentage >= EXCELLENT_HEALTH_PERCENT) {
			return BatteryHealthGrade.EXCELLENT;
		} else if (healthPercentage >= GOOD_HEALTH_PERCENT) {
			return BatteryHealthGrade.GOOD;
		} else if (healthPercentage >= FAIR_HEALTH_PERCENT) {
			return BatteryHealthGrade.FAIR;
		} else {
			return BatteryHealthGrade.POOR;
		}
	}

	/**
	 * Gets the date when health tracking was first initialized.
	 *
	 * @param context Application context
	 *
	 * @return Timestamp in milliseconds, or 0 if not initialized
	 */
	public static long getFirstUseDate(final Context context) {
		if (isNull(context)) {
			return 0;
		}
		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		return prefs.getLong(PREF_FIRST_USE_DATE, 0);
	}

	/**
	 * Calculates the number of days since health tracking started.
	 *
	 * @param context Application context
	 *
	 * @return Number of days, or 0 if not initialized
	 */
	public static int getDaysSinceFirstUse(final Context context) {
		final long firstUseDate = getFirstUseDate(context);
		if (firstUseDate == 0) {
			return 0;
		}
		final long daysSince = (System.currentTimeMillis() - firstUseDate) / (1000L * 60 * 60 * 24);
		return (int) daysSince;
	}

	/**
	 * Estimates battery health as a percentage based on charge cycles.
	 * <p>
	 * Algorithm:
	 * - Starts at 100% for new batteries
	 * - Degrades gradually with charge cycles
	 * - Uses industry-standard degradation curves for lithium-ion batteries
	 *
	 * @param context Application context
	 *
	 * @return Estimated battery health percentage (0-100)
	 */
	public static int getEstimatedHealthPercentage(final Context context) {
		final int cycles = getEffectiveCycleCount(context);

		// Excellent health: 0-300 cycles (100% to 95%)
		if (cycles < EXCELLENT_THRESHOLD) {
			return 100 - (cycles * 5 / EXCELLENT_THRESHOLD);
		}

		// Good health: 300-500 cycles (95% to 85%)
		if (cycles < GOOD_THRESHOLD) {
			final int cyclesAboveExcellent = cycles - EXCELLENT_THRESHOLD;
			final int range = GOOD_THRESHOLD - EXCELLENT_THRESHOLD;
			return 95 - (cyclesAboveExcellent * 10 / range);
		}

		// Fair health: 500-800 cycles (85% to 70%)
		if (cycles < FAIR_THRESHOLD) {
			final int cyclesAboveGood = cycles - GOOD_THRESHOLD;
			final int range = FAIR_THRESHOLD - GOOD_THRESHOLD;
			return 85 - (cyclesAboveGood * 15 / range);
		}

		// Poor health: 800+ cycles (decreases below 70%)
		final int cyclesAboveFair = cycles - FAIR_THRESHOLD;
		final int health = 70 - (cyclesAboveFair * 30 / 500); // Degrades to 40% at 1300 cycles
		return Math.max(health, 40); // Minimum 40% health
	}

	/**
	 * Gets the cycle-based battery wear grade.
	 *
	 * @param context Application context
	 *
	 * @return the matching {@link BatteryHealthGrade}
	 */
	public static BatteryHealthGrade getHealthGrade(final Context context) {
		final int cycles = getEffectiveCycleCount(context);

		if (cycles < EXCELLENT_THRESHOLD) {
			return BatteryHealthGrade.EXCELLENT;
		} else if (cycles < GOOD_THRESHOLD) {
			return BatteryHealthGrade.GOOD;
		} else if (cycles < FAIR_THRESHOLD) {
			return BatteryHealthGrade.FAIR;
		} else {
			return BatteryHealthGrade.POOR;
		}
	}

	/**
	 * Gets a detailed health description with recommendations for the cycle-based grade.
	 *
	 * @param context Application context
	 *
	 * @return Detailed health description
	 */
	public static String getHealthDescription(final Context context) {
		return describeHealthGrade(context, getHealthGrade(context));
	}

	/**
	 * Resolves the localized label for a wear grade (e.g. "Excellent").
	 * <p>
	 * Kept as a resource id (not on the enum) so the model stays free of Android/resource coupling,
	 * mirroring {@code SystemService.getHealthString}. Callers pass it to {@code setText}/{@code getString}.
	 *
	 * @param grade the battery wear grade
	 *
	 * @return the string resource id for the grade's label
	 */
	@StringRes
	public static int labelResId(final BatteryHealthGrade grade) {
		return switch (grade) {
			case EXCELLENT -> R.string.battery_health_grade_excellent;
			case GOOD -> R.string.battery_health_grade_good;
			case FAIR -> R.string.battery_health_grade_fair;
			case POOR -> R.string.battery_health_grade_poor;
		};
	}

	/**
	 * Gets a localized, detailed health description for a wear grade.
	 * <p>
	 * Shared by the cycle-based and measured health paths so the wording stays consistent.
	 *
	 * @param context Application context (for resource resolution)
	 * @param grade   the battery wear grade
	 *
	 * @return Detailed, localized health description
	 */
	public static String describeHealthGrade(final Context context, final BatteryHealthGrade grade) {
		return context.getString(switch (grade) {
			case EXCELLENT -> R.string.battery_health_desc_excellent;
			case GOOD -> R.string.battery_health_desc_good;
			case FAIR -> R.string.battery_health_desc_fair;
			case POOR -> R.string.battery_health_desc_poor;
		});
	}

	/**
	 * Resets <em>all</em> health tracking data, including the first-use date and real charge cycles.
	 * Use with caution — for undoing only debug-injected cycles, prefer {@link #resetDebugData}.
	 *
	 * @param context Application context
	 */
	public static void resetHealthData(final Context context) {
		if (isNull(context)) {
			return;
		}
		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		prefs.edit()
		     .remove(PREF_CHARGE_CYCLES)
		     .remove(PREF_FIRST_USE_DATE)
		     .remove(PREF_LAST_LEVEL)
		     .remove(PREF_CYCLE_ACCRUAL_POINTS)
		     .remove(PREF_DEBUG_CHARGE_CYCLES)
		     .apply();
		Log.i(TAG, "Battery health data reset");
	}

	/**
	 * Clears only the debug-injected charge cycles, leaving the first-use date and real tracked
	 * cycles intact. This is the reset a tester wants after injecting dummy cycles.
	 *
	 * @param context Application context
	 */
	public static void resetDebugData(final Context context) {
		if (isNull(context)) {
			return;
		}
		PreferenceManager.getDefaultSharedPreferences(context)
		                 .edit()
		                 .remove(PREF_DEBUG_CHARGE_CYCLES)
		                 .apply();
		Log.i(TAG, "Debug-injected charge cycles cleared");
	}

	/**
	 * Adds debug-injected test charge cycles, tracked separately from real cycles so they can be
	 * cleared via {@link #resetDebugData} without destroying genuine tracking.
	 * DEBUG/TEST METHOD - Do not use in production!
	 *
	 * @param context Application context
	 * @param cyclesToAdd Number of cycles to add
	 */
	public static void addTestChargeCycles(final Context context, final int cyclesToAdd) {
		if (isNull(context)) {
			return;
		}
		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		final int currentDebugCycles = prefs.getInt(PREF_DEBUG_CHARGE_CYCLES, 0);
		prefs.edit()
		     .putInt(PREF_DEBUG_CHARGE_CYCLES, currentDebugCycles + cyclesToAdd)
		     .apply();
		Log.i(TAG, "Added " + cyclesToAdd + " debug charge cycles. Debug total: " + (currentDebugCycles + cyclesToAdd));
	}

	/**
	 * Gets debug information about the current tracking state.
	 * DEBUG/TEST METHOD - Returns detailed tracking status.
	 *
	 * @param context Application context
	 * @return Debug information string
	 */
	public static String getDebugInfo(final Context context) {
		if (isNull(context)) {
			return "Context is null";
		}
		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		final int cycles = prefs.getInt(PREF_CHARGE_CYCLES, 0);
		final int debugCycles = prefs.getInt(PREF_DEBUG_CHARGE_CYCLES, 0);
		final long firstUse = prefs.getLong(PREF_FIRST_USE_DATE, 0);
		final int accrualPoints = prefs.getInt(PREF_CYCLE_ACCRUAL_POINTS, 0);
		final int lastLevel = prefs.getInt(PREF_LAST_LEVEL, -1);

		return "Tracking Status:\n" +
				"- First Use: " + (firstUse == 0 ? "Not initialized" : new java.util.Date(firstUse)) + "\n" +
				"- Charge Cycles (real): " + cycles + "\n" +
				"- Charge Cycles (debug-injected): " + debugCycles + "\n" +
				"- Effective Cycle Count: " + getEffectiveCycleCount(context) + "\n" +
				"- Cycle accrual (toward next): " + accrualPoints + "/" + CYCLE_PERCENT_POINTS + "\n" +
				"- Last Level: " + (lastLevel < 0 ? "Unknown" : lastLevel + "%") + "\n" +
				"- Days Since First Use: " + getDaysSinceFirstUse(context);
	}
}
