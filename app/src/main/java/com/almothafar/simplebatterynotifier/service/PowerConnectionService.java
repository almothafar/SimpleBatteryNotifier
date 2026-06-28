package com.almothafar.simplebatterynotifier.service;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.ServiceCompat;
import com.almothafar.simplebatterynotifier.model.BatteryDO;
import com.almothafar.simplebatterynotifier.receiver.BatteryLevelReceiver;
import com.almothafar.simplebatterynotifier.receiver.PowerConnectionReceiver;

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
		super.onCreate();
		// Promote to foreground first so the OS keeps the process (and our receivers) alive on Android 8+.
		startForegroundWithStatus();
		registerPowerConnectionReceiver();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		unregisterReceivers();
	}

	@Override
	public int onStartCommand(final Intent intent, final int flags, final int startId) {
		// Re-assert the foreground notification (e.g. after a START_STICKY restart delivers a null intent).
		startForegroundWithStatus();
		return START_STICKY;
	}

	/**
	 * Promote this service to the foreground with the persistent battery-status notification.
	 * <p>
	 * Required on Android 8+: a plain background service (and its runtime-registered battery
	 * receivers) is reaped shortly after the app leaves the foreground. The ongoing notification
	 * keeps monitoring alive so alerts are delivered while the app is closed.
	 */
	private void startForegroundWithStatus() {
		final BatteryDO batteryDO = SystemService.getBatteryInfo(this);
		final Notification notification = NotificationService.buildOngoingNotification(this, batteryDO);

		// The specialUse FGS type only exists from Android 14 (API 34); pass 0 on older versions.
		final int serviceType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
		                        ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
		                        : 0;

		ServiceCompat.startForeground(this, NotificationService.getOngoingNotificationId(), notification, serviceType);
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
