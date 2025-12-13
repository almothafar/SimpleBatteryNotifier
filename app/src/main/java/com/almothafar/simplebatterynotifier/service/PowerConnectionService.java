package com.almothafar.simplebatterynotifier.service;

import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.IBinder;
import com.almothafar.simplebatterynotifier.receiver.BatteryLevelReceiver;
import com.almothafar.simplebatterynotifier.receiver.PowerConnectionReceiver;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Service to register battery monitoring receivers.
 * Registers PowerConnectionReceiver and BatteryLevelReceiver on service creation.
 */
public class PowerConnectionService extends Service {

	private PowerConnectionReceiver powerConnectionReceiver;
	private BatteryLevelReceiver batteryLevelReceiver;

	@Override
	public IBinder onBind(final Intent intent) {
		return null; // This is a started service, not a bound service
	}

	@Override
	public void onCreate() {
		registerPowerConnectionReceiver();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		unregisterReceivers();
	}

	@Override
	public int onStartCommand(final Intent intent, final int flags, final int startId) {
		return START_STICKY;
	}

	/**
	 * Register battery monitoring receivers.
	 * Initializes the current plugged state to avoid unnecessary triggers on the first battery change event.
	 */
	private void registerPowerConnectionReceiver() {
		final Intent batteryStatus = getApplicationContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
		final int plugged = batteryStatus == null ? -1 : batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);

		powerConnectionReceiver = new PowerConnectionReceiver();
		PowerConnectionReceiver.setCurrentState(plugged);

		batteryLevelReceiver = new BatteryLevelReceiver();

		final IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
		registerReceiver(powerConnectionReceiver, filter);
		registerReceiver(batteryLevelReceiver, filter);
	}

	/**
	 * Unregister battery monitoring receivers to prevent memory leaks
	 */
	private void unregisterReceivers() {
		if (nonNull(powerConnectionReceiver)) {
			try {
				unregisterReceiver(powerConnectionReceiver);
			} catch (IllegalArgumentException e) {
				// Receiver was already unregistered, ignore
			}
			powerConnectionReceiver = null;
		}

		if (nonNull(batteryLevelReceiver)) {
			try {
				unregisterReceiver(batteryLevelReceiver);
			} catch (IllegalArgumentException e) {
				// Receiver was already unregistered, ignore
			}
			batteryLevelReceiver = null;
		}
	}
}
