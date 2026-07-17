package com.almothafar.simplebatterynotifier.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import androidx.preference.PreferenceManager;
import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.model.BatteryDO;
import com.almothafar.simplebatterynotifier.service.BatteryHealthTracker;
import com.almothafar.simplebatterynotifier.service.BatteryRateTracker;
import com.almothafar.simplebatterynotifier.service.FastDrainDetector;
import com.almothafar.simplebatterynotifier.service.NotificationService;
import com.almothafar.simplebatterynotifier.service.SlowChargeDetector;
import com.almothafar.simplebatterynotifier.service.SystemService;
import com.almothafar.simplebatterynotifier.util.TemperatureUtils;

/**
 * Broadcast receiver for monitoring battery level changes.
 * Sends notifications when battery reaches critical/warning levels or becomes full.
 */
public class BatteryLevelReceiver extends BroadcastReceiver {

	/**
	 * Static lock object for thread-safe access to static fields.
	 * Using synchronized(this) doesn't work for BroadcastReceivers since each broadcast creates a new instance.
	 */
	private static final Object LOCK = new Object();

	/**
	 * How far (in °C) the battery must cool below the threshold before another high-temperature
	 * alert can fire. Hysteresis prevents repeated alerts during a single hot spell.
	 */
	private static final int TEMPERATURE_HYSTERESIS_C = 3;

	/**
	 * Thread-safe static fields to track battery notification state
	 */
	private static volatile int prevLevel = 0;
	private static volatile int prevType = 0;
	private static volatile boolean fullNotificationCalled = false;
	private static volatile boolean temperatureAlertSent = false;

	/**
	 * Reset notification state when charger is disconnected
	 */
	public static void resetVariables() {
		synchronized (LOCK) {
			fullNotificationCalled = false;
			prevType = 0;
		}
	}

	@Override
	public void onReceive(final Context context, final Intent intent) {
		final Intent batteryStatus = context.getApplicationContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
		if (batteryStatus == null) {
			return; // Cannot determine battery status, exit early
		}

		final int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
		final boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING;
		final boolean isFull = status == BatteryManager.BATTERY_STATUS_FULL;

		// Reuse the sticky intent we already read above instead of triggering a second read.
		final BatteryDO batteryDO = SystemService.getBatteryInfo(context, batteryStatus);

		// Feed the charge/drain rate window from this broadcast (no polling timer of our own) so both the
		// ongoing notification below and the details table reflect the latest reading (issue #108). The
		// fast-drain alert (#109) then evaluates the same smoothed rate.
		final BatteryRateTracker.BatteryRate rate = BatteryRateTracker.record(context, batteryDO);

		// Keep the persistent foreground-service status notification live with the latest reading,
		// reusing the rate just computed instead of re-parsing the persisted sample window.
		NotificationService.updateOngoingNotification(context, batteryDO, rate);

		if (batteryDO == null) {
			// Without a real reading, don't assume a level. Previously this defaulted to 100%,
			// which silently suppressed genuine low/critical alerts on a transient read failure.
			return;
		}
		final int percentage = batteryDO.getBatteryPercentageInt();

		// Track battery health and charge cycles
		BatteryHealthTracker.recordBatteryState(context, percentage, status);

		final SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
		final int warningLevel = sharedPref.getInt(context.getString(R.string._pref_key_warn_battery_level), 40);
		final int criticalLevel = sharedPref.getInt(context.getString(R.string._pref_key_critical_battery_level), 20);
		final boolean warningEnabled = sharedPref.getBoolean(context.getString(R.string._pref_key_notify_for_warning_level), true);
		final boolean fullNotifyEnabled = sharedPref.getBoolean(context.getString(R.string._pref_key_notify_for_full_level), true);
		final boolean alertEveryTick = sharedPref.getBoolean(context.getString(R.string._pref_key_notify_every_tick), false);

		synchronized (LOCK) {
			final boolean isChanged = prevLevel != percentage;
			if (isChanged && !isCharging) {
				handleDischarging(context, percentage, criticalLevel, warningLevel, warningEnabled, alertEveryTick);
			} else {
				handleChargingOrFull(context, percentage, warningLevel, isFull, fullNotifyEnabled);
			}
			prevLevel = percentage;
		}

		handleTemperature(context, batteryDO, sharedPref);

		// #109: warn when the (smoothed #108) drain rate stays abnormally high for a sustained time.
		FastDrainDetector.evaluate(context, batteryDO, rate);

		// #123: warn when charging power stays abnormally low for a sustained time (frayed cable, dirty
		// port, or dying charger). Independent of the drain rate — it reads the estimated charge wattage.
		SlowChargeDetector.evaluate(context, batteryDO);
	}

	/**
	 * Send a high-temperature safety alert when the battery exceeds the configured threshold.
	 * <p>
	 * Uses hysteresis so a single hot spell triggers one alert; another alert can only fire once
	 * the battery has cooled at least {@link #TEMPERATURE_HYSTERESIS_C}°C below the threshold.
	 *
	 * @param context    The application context
	 * @param batteryDO  Current battery snapshot (may be null)
	 * @param sharedPref The shared preferences
	 */
	private void handleTemperature(final Context context, final BatteryDO batteryDO, final SharedPreferences sharedPref) {
		if (batteryDO == null) {
			return; // No reading available
		}

		final boolean enabled = sharedPref.getBoolean(context.getString(R.string._pref_key_notify_high_temperature), true);
		if (!enabled) {
			temperatureAlertSent = false; // Re-arm for when it's turned back on
			return;
		}

		// The threshold is stored canonically in Celsius; the battery reading is also Celsius
		// (tenths), so a chilly 45 °F (~7 °C) never trips a 45 °C threshold. See TemperatureUtils.
		final int thresholdCelsius = sharedPref.getInt(
				context.getString(R.string._pref_key_high_temperature_threshold),
				TemperatureUtils.DEFAULT_HIGH_TEMP_THRESHOLD_C);
		final int rawTenthsC = batteryDO.getTemperature();

		synchronized (LOCK) {
			if (TemperatureUtils.isAtOrAboveThreshold(rawTenthsC, thresholdCelsius) && !temperatureAlertSent) {
				NotificationService.sendTemperatureNotification(context, rawTenthsC);
				temperatureAlertSent = true;
			} else if (TemperatureUtils.isBelowResetThreshold(rawTenthsC, thresholdCelsius, TEMPERATURE_HYSTERESIS_C)) {
				temperatureAlertSent = false;
			}
		}
	}

	/**
	 * Handle battery notifications while discharging
	 */
	private void handleDischarging(final Context context,
	                               final int percentage,
	                               final int criticalLevel,
	                               final int warningLevel,
	                               final boolean warningEnabled,
	                               final boolean alertEveryTick) {
		// Force critical notification for very low battery (red alert level)
		if (percentage <= NotificationService.RED_ALERT_LEVEL) {
			prevType = 0;
		}

		// Handle critical level first, then warning
		if (percentage <= criticalLevel) {
			if (prevType != NotificationService.CRITICAL_TYPE || alertEveryTick) {
				NotificationService.sendNotification(context, NotificationService.CRITICAL_TYPE);
				prevType = NotificationService.CRITICAL_TYPE;
			}
		} else if (percentage <= warningLevel && warningEnabled) {
			if (prevType != NotificationService.WARNING_TYPE) {
				NotificationService.sendNotification(context, NotificationService.WARNING_TYPE);
				prevType = NotificationService.WARNING_TYPE;
			}
		}
	}

	/**
	 * Handle battery notifications while charging or full
	 */
	private void handleChargingOrFull(final Context context, final int percentage, final int warningLevel,
	                                  final boolean isFull, final boolean fullNotifyEnabled) {
		if (!fullNotificationCalled) {
			if (isFull && fullNotifyEnabled) {
				NotificationService.sendNotification(context, NotificationService.FULL_LEVEL_TYPE);
				fullNotificationCalled = true;
			}
		}

		// Reset full notification flag when battery drops below full threshold
		if (percentage <= NotificationService.FULL_PERCENTAGE && percentage > warningLevel) {
			fullNotificationCalled = false;
		}
	}
}
