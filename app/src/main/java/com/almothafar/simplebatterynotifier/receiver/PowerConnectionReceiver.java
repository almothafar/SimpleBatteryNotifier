package com.almothafar.simplebatterynotifier.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.os.BatteryManager;
import android.widget.Toast;
import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.service.NotificationService;

import static java.util.Objects.isNull;

/**
 * Broadcast receiver for power connection/disconnection events.
 * Shows notifications and toasts when charger is plugged/unplugged.
 */
public class PowerConnectionReceiver extends BroadcastReceiver {

	/**
	 * Battery percentage threshold for healthy charging (charge when battery is low)
	 */
	private static final int HEALTHY_CHARGE_THRESHOLD = 20;

	/**
	 * Previous plugged state to prevent duplicate notifications for the same state
	 */
	private static int currentState = -1;

	/**
	 * Update the current plugged state (synchronized for thread safety)
	 *
	 * @param state The new plugged state
	 */
	public static synchronized void setCurrentState(final int state) {
		currentState = state;
	}

	@Override
	public void onReceive(final Context context, final Intent intent) {
		final Intent batteryStatus = context.getApplicationContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
		if (isNull(batteryStatus)) {
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
		final String toastMessage;

		if (pluggedState > 0) {
			// Charger connected
			final ChargerInfo chargerInfo = detectChargerType(pluggedState, resources);
			toastMessage = chargerInfo.connectedMessage;

			NotificationService.setIsHealthy(percentage <= HEALTHY_CHARGE_THRESHOLD);
			NotificationService.sendChargeNotification(context, chargerInfo.source);
		} else {
			// Charger disconnected
			toastMessage = resources.getString(R.string.charger_disconnected);
			BatteryLevelReceiver.resetVariables();
			NotificationService.clearNotifications(context);
		}

		Toast.makeText(context, toastMessage, Toast.LENGTH_LONG).show();
	}

	/**
	 * Detect charger type based on plugged state
	 *
	 * @param pluggedState The EXTRA_PLUGGED value from battery status intent
	 * @param resources    Resources for string lookup
	 *
	 * @return ChargerInfo containing messages and source type
	 */
	private ChargerInfo detectChargerType(final int pluggedState, final Resources resources) {
		if (pluggedState == BatteryManager.BATTERY_PLUGGED_USB) {
			return new ChargerInfo(
					resources.getString(R.string.charger_connected_usb),
					resources.getString(R.string.charger_usb)
			);
		}

		if (pluggedState == BatteryManager.BATTERY_PLUGGED_AC) {
			return new ChargerInfo(
					resources.getString(R.string.charger_connected_ac),
					resources.getString(R.string.charger_ac)
			);
		}

		if (pluggedState == BatteryManager.BATTERY_PLUGGED_WIRELESS) {
			return new ChargerInfo(
					resources.getString(R.string.charger_connected_wireless),
					resources.getString(R.string.charger_wireless)
			);
		}

		// Unknown charger type (e.g., future Android versions may add new types)
		return new ChargerInfo(
				resources.getString(R.string.charger_connected),
				resources.getString(R.string.charger)
		);
	}

	/**
	 * Simple data class to hold charger information
	 */
	private static class ChargerInfo {
		final String connectedMessage;
		final String source;

		ChargerInfo(final String connectedMessage, final String source) {
			this.connectedMessage = connectedMessage;
			this.source = source;
		}
	}
}
