package com.almothafar.simplebatterynotifier.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.util.Log;
import androidx.preference.PreferenceManager;

import java.util.Calendar;

import static java.util.Objects.isNull;

/**
 * Tracks battery health metrics including charge cycles and estimated battery health.
 *
 * Charge Cycle Definition:
 * A full charge cycle is counted when the battery charges from <= 20% to >= 95%.
 * This follows industry standards where partial charges accumulate to form complete cycles.
 *
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
	 * @param context Application context
	 * @param batteryLevel Current battery percentage (0-100)
	 * @param status Battery charging status from BatteryManager
	 */
	public static void recordBatteryState(final Context context, final int batteryLevel, final int status) {
		if (isNull(context)) {
			Log.w(TAG, "Context is null, cannot record battery state");
			return;
		}

		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

		// Initialize first use date if not set
		if (prefs.getLong(PREF_FIRST_USE_DATE, 0) == 0) {
			prefs.edit()
					.putLong(PREF_FIRST_USE_DATE, System.currentTimeMillis())
					.apply();
			Log.d(TAG, "First use date initialized");
		}

		// Track charge cycle progress
		final boolean cycleInProgress = prefs.getBoolean(PREF_CYCLE_IN_PROGRESS, false);
		final boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
				|| status == BatteryManager.BATTERY_STATUS_FULL;

		// Start a charge cycle when battery is low
		if (batteryLevel <= LOW_BATTERY_THRESHOLD && !cycleInProgress) {
			prefs.edit()
					.putBoolean(PREF_CYCLE_IN_PROGRESS, true)
					.putLong(PREF_LAST_LOW_BATTERY, System.currentTimeMillis())
					.apply();
			Log.d(TAG, "Charge cycle started at " + batteryLevel + "%");
		}

		// Complete a charge cycle when battery reaches full while charging
		if (cycleInProgress && isCharging && batteryLevel >= FULL_BATTERY_THRESHOLD) {
			final int currentCycles = prefs.getInt(PREF_CHARGE_CYCLES, 0);
			prefs.edit()
					.putInt(PREF_CHARGE_CYCLES, currentCycles + 1)
					.putBoolean(PREF_CYCLE_IN_PROGRESS, false)
					.apply();
			Log.i(TAG, "Charge cycle completed! Total cycles: " + (currentCycles + 1));
		}

		// Reset cycle tracking if battery goes back to high without charging
		if (cycleInProgress && !isCharging && batteryLevel > FULL_BATTERY_THRESHOLD) {
			prefs.edit()
					.putBoolean(PREF_CYCLE_IN_PROGRESS, false)
					.apply();
			Log.d(TAG, "Charge cycle reset - battery was not charged to full");
		}
	}

	/**
	 * Gets the total number of completed charge cycles.
	 *
	 * @param context Application context
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
	 * Gets the date when health tracking was first initialized.
	 *
	 * @param context Application context
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
	 *
	 * Algorithm:
	 * - Starts at 100% for new batteries
	 * - Degrades gradually with charge cycles
	 * - Uses industry-standard degradation curves for lithium-ion batteries
	 *
	 * @param context Application context
	 * @return Estimated battery health percentage (0-100)
	 */
	public static int getEstimatedHealthPercentage(final Context context) {
		final int cycles = getChargeCycles(context);

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
	 * @return Health status: "Excellent", "Good", "Fair", or "Poor"
	 */
	public static String getHealthStatus(final Context context) {
		final int cycles = getChargeCycles(context);

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
	 * @return Detailed health description
	 */
	public static String getHealthDescription(final Context context) {
		final int cycles = getChargeCycles(context);

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
	 * Resets all health tracking data. Use with caution!
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
				.apply();
		Log.i(TAG, "Battery health data reset");
	}
}
