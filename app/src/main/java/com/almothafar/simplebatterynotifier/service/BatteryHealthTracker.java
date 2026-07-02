package com.almothafar.simplebatterynotifier.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.util.Log;
import androidx.preference.PreferenceManager;

import com.almothafar.simplebatterynotifier.model.BatteryHealthGrade;

import static java.util.Objects.isNull;

/**
 * Tracks battery health metrics including charge cycles and estimated battery health.
 * <p>
 * Charge Cycle Definition:
 * A full charge cycle is counted when the battery charges from <= 20% to >= 95%.
 * This follows industry standards where partial charges accumulate to form complete cycles.
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
	private static final String PREF_LAST_LOW_BATTERY = "_battery_health_last_low_battery";
	private static final String PREF_CYCLE_IN_PROGRESS = "_battery_health_cycle_in_progress";
	private static final String PREF_DESIGN_CAPACITY = "key_battery_design_capacity";
	// Debug-only cycle offset kept separate from real tracking so it can be cleared without
	// destroying genuine data (see the debug menu in BatteryInsightsActivity).
	private static final String PREF_DEBUG_CHARGE_CYCLES = "_battery_health_debug_charge_cycles";

	// Battery thresholds for cycle tracking
	private static final int LOW_BATTERY_THRESHOLD = 20;
	private static final int FULL_BATTERY_THRESHOLD = 95;

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

	// Minimum battery level (%) at which the measured health figure is trustworthy. The current
	// full-capacity estimate is charge-counter (µAh) ÷ CAPACITY (integer %), so at low charge the
	// integer rounding of CAPACITY dominates and the figure becomes jumpy. Below this level we
	// withhold the measured figure and fall back to the cycle-based estimate. See issue #37.
	static final int MEASURED_HEALTH_MIN_BATTERY_LEVEL = 80;

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

		// Track charge cycle progress (the three branches below are mutually exclusive)
		final boolean cycleInProgress = prefs.getBoolean(PREF_CYCLE_IN_PROGRESS, false);
		final boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
				|| status == BatteryManager.BATTERY_STATUS_FULL;

		if (batteryLevel <= LOW_BATTERY_THRESHOLD && !cycleInProgress) {
			// Start a charge cycle when battery is low
			editor.putBoolean(PREF_CYCLE_IN_PROGRESS, true)
			      .putLong(PREF_LAST_LOW_BATTERY, System.currentTimeMillis());
			dirty = true;
			Log.d(TAG, "Charge cycle started at " + batteryLevel + "%");
		} else if (cycleInProgress && isCharging && batteryLevel >= FULL_BATTERY_THRESHOLD) {
			// Complete a charge cycle when battery reaches full while charging
			final int currentCycles = prefs.getInt(PREF_CHARGE_CYCLES, 0);
			editor.putInt(PREF_CHARGE_CYCLES, currentCycles + 1)
			      .putBoolean(PREF_CYCLE_IN_PROGRESS, false);
			dirty = true;
			Log.i(TAG, "Charge cycle completed! Total cycles: " + (currentCycles + 1));
		} else if (cycleInProgress && !isCharging && batteryLevel > FULL_BATTERY_THRESHOLD) {
			// Reset cycle tracking if battery goes back to high without charging
			editor.putBoolean(PREF_CYCLE_IN_PROGRESS, false);
			dirty = true;
			Log.d(TAG, "Charge cycle reset - battery was not charged to full");
		}

		if (dirty) {
			editor.apply();
		}
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
	 * This is the accurate figure requested in #32; it only applies when the user has entered a
	 * design capacity, the device reports the live charge counter used to estimate the current full
	 * capacity, <em>and</em> the battery is charged to at least {@link #MEASURED_HEALTH_MIN_BATTERY_LEVEL}%
	 * (below which the estimate is too noisy — see issue #37).
	 *
	 * @param context Application context
	 *
	 * @return Measured health percentage (1-100), or -1 when it cannot be determined or the battery is
	 * too low for a stable reading
	 */
	public static int getMeasuredHealthPercentage(final Context context) {
		if (isNull(context)) {
			return -1;
		}
		return computeMeasuredHealth(
				SystemService.getBatteryCapacity(context),
				getDesignCapacity(context),
				SystemService.getBatteryLevelPercent(context));
	}

	/**
	 * Pure helper for the measured health percentage, unit-testable with no Android dependencies.
	 * <p>
	 * The measured figure is withheld (returns -1) below {@link #MEASURED_HEALTH_MIN_BATTERY_LEVEL}%
	 * charge, where the charge-counter estimate is dominated by the integer CAPACITY rounding and
	 * would otherwise show a jumpy value (issue #37). Callers then fall back to the cycle-based estimate.
	 *
	 * @param currentFullMah     measured current full capacity in mAh (0 when unknown)
	 * @param designMah          user-entered design capacity in mAh (0 when unset)
	 * @param batteryLevelPercent current battery level 1-100 (-1 when unknown)
	 *
	 * @return health percentage clamped to 1-100, or -1 when any input is unusable or the battery is
	 * below the near-full threshold
	 */
	static int computeMeasuredHealth(final int currentFullMah, final int designMah, final int batteryLevelPercent) {
		if (currentFullMah <= 0 || designMah <= 0) {
			return -1;
		}
		// Too low (or unknown) to trust the charge-counter estimate: defer to the cycle-based figure.
		if (batteryLevelPercent < MEASURED_HEALTH_MIN_BATTERY_LEVEL) {
			return -1;
		}
		final int percent = Math.round(currentFullMah * 100f / designMah);
		return Math.max(1, Math.min(100, percent));
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
		return describeHealthGrade(getHealthGrade(context));
	}

	/**
	 * Gets a detailed health description for a wear grade.
	 * <p>
	 * Shared by the cycle-based and measured health paths so the wording stays consistent.
	 *
	 * @param grade the battery wear grade
	 *
	 * @return Detailed health description
	 */
	public static String describeHealthGrade(final BatteryHealthGrade grade) {
		return switch (grade) {
			case EXCELLENT -> "Your battery is in excellent condition. Continue with normal usage patterns.";
			case GOOD -> "Your battery is in good condition with minimal degradation. Normal usage expected.";
			case FAIR -> "Your battery shows moderate wear. You may notice slightly reduced battery life.";
			case POOR -> "Your battery has significant wear. Consider battery replacement if experiencing poor performance.";
		};
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
		     .remove(PREF_LAST_LOW_BATTERY)
		     .remove(PREF_CYCLE_IN_PROGRESS)
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
		final boolean cycleInProgress = prefs.getBoolean(PREF_CYCLE_IN_PROGRESS, false);
		final long lastLowBattery = prefs.getLong(PREF_LAST_LOW_BATTERY, 0);

		return "Tracking Status:\n" +
				"- First Use: " + (firstUse == 0 ? "Not initialized" : new java.util.Date(firstUse)) + "\n" +
				"- Charge Cycles (real): " + cycles + "\n" +
				"- Charge Cycles (debug-injected): " + debugCycles + "\n" +
				"- Effective Cycle Count: " + getEffectiveCycleCount(context) + "\n" +
				"- Cycle in Progress: " + cycleInProgress + "\n" +
				"- Last Low Battery: " + (lastLowBattery == 0 ? "Never" : new java.util.Date(lastLowBattery)) + "\n" +
				"- Days Since First Use: " + getDaysSinceFirstUse(context);
	}
}
