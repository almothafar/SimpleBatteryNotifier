package com.almothafar.simplebatterynotifier.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.util.Log;
import androidx.preference.PreferenceManager;

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
	// Debug-only cycle offset kept separate from real tracking so it can be cleared without
	// destroying genuine data (see the debug menu in BatteryInsightsActivity).
	private static final String PREF_DEBUG_CHARGE_CYCLES = "_battery_health_debug_charge_cycles";

	// Battery thresholds for cycle tracking
	private static final int LOW_BATTERY_THRESHOLD = 20;
	private static final int FULL_BATTERY_THRESHOLD = 95;

	// Health estimation thresholds
	private static final int EXCELLENT_THRESHOLD = 300;
	private static final int GOOD_THRESHOLD = 500;
	private static final int FAIR_THRESHOLD = 800;

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
	 * Gets a human-readable health status description.
	 *
	 * @param context Application context
	 *
	 * @return Health status: "Excellent", "Good", "Fair", or "Poor"
	 */
	public static String getHealthStatus(final Context context) {
		final int cycles = getEffectiveCycleCount(context);

		if (cycles < EXCELLENT_THRESHOLD) {
			return "Excellent";
		} else if (cycles < GOOD_THRESHOLD) {
			return "Good";
		} else if (cycles < FAIR_THRESHOLD) {
			return "Fair";
		} else {
			return "Poor";
		}
	}

	/**
	 * Gets a detailed health description with recommendations.
	 *
	 * @param context Application context
	 *
	 * @return Detailed health description
	 */
	public static String getHealthDescription(final Context context) {
		final int cycles = getEffectiveCycleCount(context);

		if (cycles < EXCELLENT_THRESHOLD) {
			return "Your battery is in excellent condition. Continue with normal usage patterns.";
		} else if (cycles < GOOD_THRESHOLD) {
			return "Your battery is in good condition with minimal degradation. Normal usage expected.";
		} else if (cycles < FAIR_THRESHOLD) {
			return "Your battery shows moderate wear. You may notice slightly reduced battery life.";
		} else {
			return "Your battery has significant wear. Consider battery replacement if experiencing poor performance.";
		}
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
