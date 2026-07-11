package com.almothafar.simplebatterynotifier.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.almothafar.simplebatterynotifier.model.ChargeSpeed;
import com.almothafar.simplebatterynotifier.service.NotificationService;
import com.almothafar.simplebatterynotifier.service.SystemService;

/**
 * Broadcast receiver for power connection/disconnection events.
 * <p>
 * When the device is plugged in, this reports what's actually useful — the estimated charging speed
 * and whether it's wired or wireless — rather than the old, often-misleading "AC charger connected"
 * message (issue #122). The AC/USB distinction was dropped because {@code EXTRA_PLUGGED} reports
 * {@code BATTERY_PLUGGED_AC} for many power banks and fast chargers, so it couldn't be trusted; the
 * wired/wireless split, by contrast, is reliable.
 * <p>
 * Charging current reads 0 or noisy for a moment right at plug-in, so the speed is sampled a short
 * delay after connection (see {@link #CHARGE_SAMPLE_DELAY_MS}) rather than synchronously here. The
 * foreground {@code PowerConnectionService} keeps the process alive across that delay.
 */
public class PowerConnectionReceiver extends BroadcastReceiver {

	private static final String TAG = PowerConnectionReceiver.class.getSimpleName();

	/**
	 * Battery percentage threshold for healthy charging (charge when battery is low)
	 */
	private static final int HEALTHY_CHARGE_THRESHOLD = 20;

	/**
	 * Delay before sampling the charging current, giving it time to stabilise after plug-in.
	 * Package-visible so tests can advance the main looper by exactly this amount.
	 */
	static final long CHARGE_SAMPLE_DELAY_MS = 2000L;

	/**
	 * Previous plugged state to prevent duplicate notifications for the same state
	 * <p>
	 * This static field is thread-safe via synchronized access methods.
	 */
	private static int currentState = -1;

	// Main-thread handler used to sample the charging speed a short delay after connection. Static so a
	// stale pending sample can be cancelled if the charger is unplugged (or re-plugged) during the delay.
	private static final Handler sampleHandler = new Handler(Looper.getMainLooper());
	private static Runnable pendingSample;

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
	 * This method determines the current battery state, detects whether charging is wired or
	 * wireless, and schedules the charge-connected notification for the user.
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

		if (pluggedState > 0) {
			// Charger connected
			handleChargerConnected(context, pluggedState, percentage);
		} else {
			// Charger disconnected
			handleChargerDisconnected(context);
		}
	}

	/**
	 * Handle charger connected event.
	 * <p>
	 * Records whether this is a "healthy" charge (started at low battery), determines wired vs
	 * wireless, and schedules the speed sample + notification for a short delay later.
	 *
	 * @param context      The application context
	 * @param pluggedState The type of charger plugged in
	 * @param percentage   Current battery percentage
	 */
	private void handleChargerConnected(final Context context, final int pluggedState, final int percentage) {
		// Determine if this is a "healthy" charge (starting at low battery level)
		final boolean isHealthyCharge = percentage <= HEALTHY_CHARGE_THRESHOLD;
		NotificationService.setIsHealthy(isHealthyCharge);

		final boolean wireless = pluggedState == BatteryManager.BATTERY_PLUGGED_WIRELESS;
		final Context appContext = context.getApplicationContext();

		// Sample the charging speed after a short delay (the current is 0/noisy right at plug-in), then
		// notify. Re-check that we're still plugged in, in case the charger was pulled during the delay.
		scheduleSample(() -> {
			if (!isStillPlugged(appContext)) {
				return;
			}
			final ChargeSpeed speed = SystemService.getChargeSpeed(appContext);
			NotificationService.notifyChargeConnected(appContext, speed, wireless, isHealthyCharge);
		});

		Log.i(TAG, String.format("Charger connected (Battery: %d%%, Wireless: %s, Healthy: %s)",
				percentage, wireless, isHealthyCharge));
	}

	/**
	 * Handle charger disconnected event
	 * <p>
	 * Cancels any pending speed sample, resets battery monitoring state and clears active notifications.
	 *
	 * @param context The application context
	 */
	private void handleChargerDisconnected(final Context context) {
		cancelPendingSample();
		BatteryLevelReceiver.resetVariables();
		NotificationService.clearNotifications(context);

		Log.i(TAG, "Charger disconnected");
	}

	/**
	 * Whether a charger is still connected. Used to abort a pending speed sample if the charger was
	 * unplugged during the sampling delay.
	 *
	 * @param context The application context
	 *
	 * @return true when still plugged into a power source
	 */
	private static boolean isStillPlugged(final Context context) {
		final Intent batteryStatus = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
		final int plugged = batteryStatus == null ? 0 : batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
		return plugged > 0;
	}

	/**
	 * Schedule the delayed charge sample, cancelling any previously scheduled one so a quick
	 * unplug/replug doesn't fire twice.
	 *
	 * @param sample The sampling task to run after {@link #CHARGE_SAMPLE_DELAY_MS}
	 */
	private static synchronized void scheduleSample(final Runnable sample) {
		cancelPendingSample();
		pendingSample = sample;
		sampleHandler.postDelayed(sample, CHARGE_SAMPLE_DELAY_MS);
	}

	/**
	 * Cancel any pending delayed charge sample.
	 */
	static synchronized void cancelPendingSample() {
		if (pendingSample != null) {
			sampleHandler.removeCallbacks(pendingSample);
			pendingSample = null;
		}
	}
}
