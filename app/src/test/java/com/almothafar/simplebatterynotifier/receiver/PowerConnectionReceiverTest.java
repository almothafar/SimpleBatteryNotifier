package com.almothafar.simplebatterynotifier.receiver;

import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;

import androidx.test.core.app.ApplicationProvider;

import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.service.NotificationService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

/**
 * Robolectric + Mockito tests for {@link PowerConnectionReceiver}: charger-type detection, the
 * healthy-charge flag, plugged-state de-duplication, and the disconnect cleanup path. The
 * {@link NotificationService} static methods are mocked so we can assert what the receiver decides
 * to do from the sticky {@code ACTION_BATTERY_CHANGED} intent.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PowerConnectionReceiverTest {

	private Context context;

	@Before
	public void setUp() {
		context = ApplicationProvider.getApplicationContext();
		// Reset the static plugged-state so each test starts from "unknown"
		PowerConnectionReceiver.setCurrentState(-1);
	}

	@Test
	public void connectedAc_sendsChargeNotificationWithAcSource() {
		publishBattery(BatteryManager.BATTERY_PLUGGED_AC, 50, 100);

		try (MockedStatic<NotificationService> ns = mockStatic(NotificationService.class)) {
			receive();
			ns.verify(() -> NotificationService.sendChargeNotification(any(Context.class),
					eq(context.getString(R.string.charger_ac))));
			ns.verify(() -> NotificationService.setIsHealthy(false));
		}
	}

	@Test
	public void connectedWireless_sendsChargeNotificationWithWirelessSource() {
		publishBattery(BatteryManager.BATTERY_PLUGGED_WIRELESS, 60, 100);

		try (MockedStatic<NotificationService> ns = mockStatic(NotificationService.class)) {
			receive();
			ns.verify(() -> NotificationService.sendChargeNotification(any(Context.class),
					eq(context.getString(R.string.charger_wireless))));
		}
	}

	@Test
	public void connectedAtLowBattery_marksChargeHealthy() {
		publishBattery(BatteryManager.BATTERY_PLUGGED_USB, 10, 100);

		try (MockedStatic<NotificationService> ns = mockStatic(NotificationService.class)) {
			receive();
			ns.verify(() -> NotificationService.setIsHealthy(true));
		}
	}

	@Test
	public void samePluggedState_sendsNoNotification() {
		PowerConnectionReceiver.setCurrentState(BatteryManager.BATTERY_PLUGGED_AC);
		publishBattery(BatteryManager.BATTERY_PLUGGED_AC, 50, 100);

		try (MockedStatic<NotificationService> ns = mockStatic(NotificationService.class)) {
			receive();
			ns.verify(() -> NotificationService.sendChargeNotification(any(Context.class), any(String.class)), never());
		}
	}

	@Test
	public void disconnected_clearsNotifications() {
		PowerConnectionReceiver.setCurrentState(BatteryManager.BATTERY_PLUGGED_AC);
		publishBattery(0, 50, 100); // unplugged

		try (MockedStatic<NotificationService> ns = mockStatic(NotificationService.class)) {
			receive();
			ns.verify(() -> NotificationService.clearNotifications(any(Context.class)));
			ns.verify(() -> NotificationService.sendChargeNotification(any(Context.class), any(String.class)), never());
		}
	}

	// --- helpers -------------------------------------------------------------

	private void receive() {
		new PowerConnectionReceiver().onReceive(context, new Intent(Intent.ACTION_POWER_CONNECTED));
	}

	@SuppressWarnings("deprecation")
	private void publishBattery(final int plugged, final int level, final int scale) {
		final Intent battery = new Intent(Intent.ACTION_BATTERY_CHANGED);
		battery.putExtra(BatteryManager.EXTRA_PLUGGED, plugged);
		battery.putExtra(BatteryManager.EXTRA_LEVEL, level);
		battery.putExtra(BatteryManager.EXTRA_SCALE, scale);
		battery.putExtra(BatteryManager.EXTRA_PRESENT, true);
		context.sendStickyBroadcast(battery);
	}
}
