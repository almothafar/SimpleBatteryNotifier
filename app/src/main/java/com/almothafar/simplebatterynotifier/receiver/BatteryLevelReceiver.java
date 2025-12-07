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
import com.almothafar.simplebatterynotifier.service.NotificationService;
import com.almothafar.simplebatterynotifier.service.SystemService;

import static java.util.Objects.isNull;

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
	 * Thread-safe static fields to track battery notification state
	 */
	private static volatile int prevLevel = 0;
	private static volatile int prevType = 0;
	private static volatile boolean fullNotificationCalled = false;

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
		if (isNull(batteryStatus)) {
			return; // Cannot determine battery status, exit early
		}

		final int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
		final boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING;
		final boolean isFull = status == BatteryManager.BATTERY_STATUS_FULL;

		final BatteryDO batteryDO = SystemService.getBatteryInfo(context);
		final int percentage = (int) batteryDO.getBatteryPercentage();

		final SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
		final int warningLevel = sharedPref.getInt(context.getString(R.string._pref_key_warn_battery_level), 40);
		final int criticalLevel = sharedPref.getInt(context.getString(R.string._pref_key_critical_battery_level), 20);
		final boolean warningEnabled = sharedPref.getBoolean(context.getString(R.string._pref_key_notify_for_warning_level), true);
		final boolean fullNotifyEnabled = sharedPref.getBoolean(context.getString(R.string._pref_key_notify_for_full_level), true);
		final boolean alertEveryTick = sharedPref.getBoolean(context.getString(R.string._pref_key_notify_every_tick), false);

		final boolean isChanged = prevLevel != percentage;

		synchronized (LOCK) {
			if (isChanged && !isCharging) {
				handleDischarging(context, percentage, criticalLevel, warningLevel, warningEnabled, alertEveryTick);
			} else {
				handleChargingOrFull(context, percentage, warningLevel, isFull, fullNotifyEnabled);
			}
			prevLevel = percentage;
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
