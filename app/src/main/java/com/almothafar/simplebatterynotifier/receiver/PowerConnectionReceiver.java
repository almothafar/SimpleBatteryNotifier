package com.almothafar.simplebatterynotifier.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.os.BatteryManager;
import android.util.Log;
import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.service.NotificationService;

import static java.util.Objects.isNull;

/**
 * Broadcast receiver for power connection/disconnection events
 * <p>
 * This receiver monitors when the device is plugged in or unplugged from a charger
 * and triggers appropriate notifications. It detects the charger type (AC, USB, Wireless)
 * and determines whether the charging session qualifies as "healthy" based on battery level.
 * <p>
 * User Feedback: This receiver relies on system notifications for user feedback rather than
 * Toast messages. Notifications are preferred because they:
 * - Are accessible to screen readers (TalkBack)
 * - Persist and can be reviewed later
 * - Support actions and rich content
 * - Follow Material Design guidelines
 */
public class PowerConnectionReceiver extends BroadcastReceiver {

	private static final String TAG = PowerConnectionReceiver.class.getSimpleName();

	/**
	 * Battery percentage threshold for healthy charging (charge when battery is low)
	 */
	private static final int HEALTHY_CHARGE_THRESHOLD = 20;

	/**
	 * Previous plugged state to prevent duplicate notifications for the same state
	 * <p>
	 * This static field is thread-safe via synchronized access methods.
	 */
	private static int currentState = -1;

	/**
	 * Update the current plugged state (synchronized for thread safety)
	 * <p>
	 * Thread safety is important because BroadcastReceivers can be called
	 * concurrently from different threads.
	 *
	 * @param state The new plugged state
	 */
	public static synchronized void setCurrentState(final int state) {
		currentState = state;
	}

	/**
	 * Called when a power connection broadcast is received
	 * <p>
	 * This method determines the current battery state, detects charger type,
	 * and triggers appropriate notifications for the user.
	 *
	 * @param context The context in which the receiver is running
	 * @param intent  The intent being received
	 */
	@Override
	public void onReceive(final Context context, final Intent intent) {
		final Intent batteryStatus = context.getApplicationContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
		if (batteryStatus == null) {
			Log.w(TAG, "Unable to retrieve battery status");
			return; // Cannot determine battery status, exit early
		}

		final int pluggedState = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
		if (currentState == pluggedState) {
			return; // Same state as before, avoid duplicate notifications
		}
		setCurrentState(pluggedState);

		final int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
		final int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
		final int percentage = (int) ((level / (float) scale) * 100);

		final Resources resources = context.getResources();

		if (pluggedState > 0) {
			// Charger connected
			handleChargerConnected(context, pluggedState, percentage, resources);
		} else {
			// Charger disconnected
			handleChargerDisconnected(context);
		}
	}

	/**
	 * Handle charger connected event
	 * <p>
	 * Detects charger type, determines if charging is "healthy" (started at low battery),
	 * and sends appropriate notification to the user.
	 *
	 * @param context      The application context
	 * @param pluggedState The type of charger plugged in
	 * @param percentage   Current battery percentage
	 * @param resources    Resources for string lookup
	 */
	private void handleChargerConnected(final Context context, final int pluggedState,
	                                     final int percentage, final Resources resources) {
		final ChargerInfo chargerInfo = detectChargerType(pluggedState, resources);

		// Determine if this is a "healthy" charge (starting at low battery level)
		final boolean isHealthyCharge = percentage <= HEALTHY_CHARGE_THRESHOLD;
		NotificationService.setIsHealthy(isHealthyCharge);

		// Send notification to user
		NotificationService.sendChargeNotification(context, chargerInfo.source);

		Log.i(TAG, String.format("Charger connected: %s (Battery: %d%%, Healthy: %s)",
				chargerInfo.source, percentage, isHealthyCharge));
	}

	/**
	 * Handle charger disconnected event
	 * <p>
	 * Resets battery monitoring state and clears any active battery notifications.
	 *
	 * @param context The application context
	 */
	private void handleChargerDisconnected(final Context context) {
		BatteryLevelReceiver.resetVariables();
		NotificationService.clearNotifications(context);

		Log.i(TAG, "Charger disconnected");
	}

	/**
	 * Detect charger type based on plugged state
	 * <p>
	 * Supports AC, USB, and Wireless charging. Falls back to generic "Charger"
	 * for unknown types (e.g., future Android versions may add new charger types).
	 *
	 * @param pluggedState The EXTRA_PLUGGED value from battery status intent
	 * @param resources    Resources for string lookup
	 * @return ChargerInfo containing messages and source type
	 */
	private ChargerInfo detectChargerType(final int pluggedState, final Resources resources) {
		return switch (pluggedState) {
			case BatteryManager.BATTERY_PLUGGED_USB -> new ChargerInfo(
					resources.getString(R.string.charger_connected_usb),
					resources.getString(R.string.charger_usb)
			);
			case BatteryManager.BATTERY_PLUGGED_AC -> new ChargerInfo(
					resources.getString(R.string.charger_connected_ac),
					resources.getString(R.string.charger_ac)
			);
			case BatteryManager.BATTERY_PLUGGED_WIRELESS -> new ChargerInfo(
					resources.getString(R.string.charger_connected_wireless),
					resources.getString(R.string.charger_wireless)
			);
			default -> new ChargerInfo(
					resources.getString(R.string.charger_connected),
					resources.getString(R.string.charger)
			);
		};
	}

	/**
	 * Simple data class to hold charger information
	 *
	 * @param connectedMessage User-friendly message for charger connection
	 * @param source           Short description of charger type
	 */
	private record ChargerInfo(String connectedMessage, String source) {
	}
}
