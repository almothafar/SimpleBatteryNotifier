package com.almothafar.simplebatterynotifier.receiver;

import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import com.almothafar.simplebatterynotifier.model.ChargeSpeed;
import com.almothafar.simplebatterynotifier.service.NotificationService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.robolectric.Shadows.shadowOf;

/**
 * Robolectric + Mockito tests for {@link PowerConnectionReceiver}: wired/wireless detection,
 * plugged-state de-duplication, and the disconnect cleanup path. The
 * {@link NotificationService} static methods are mocked so we can assert what the receiver decides
 * to do from the sticky {@code ACTION_BATTERY_CHANGED} intent.
 * <p>
 * The charge-connected notification is dispatched a short delay after connection (the charging
 * current is noisy right at plug-in), so tests advance the main looper by
 * {@link PowerConnectionReceiver#CHARGE_SAMPLE_DELAY_MS} to let the deferred sample run.
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
		// Drop any sample left pending by a previous test so it can't fire mid-test.
		PowerConnectionReceiver.cancelPendingSample();
	}

	@Test
	public void connectedWired_notifiesWiredCharging() {
		publishBattery(BatteryManager.BATTERY_PLUGGED_AC, 50, 100);

		try (MockedStatic<NotificationService> ns = mockStatic(NotificationService.class)) {
			receive();
			runPendingSample();
			ns.verify(() -> NotificationService.notifyChargeConnected(any(Context.class),
					any(ChargeSpeed.class), eq(false)));
		}
	}

	@Test
	public void connectedWireless_notifiesWirelessCharging() {
		publishBattery(BatteryManager.BATTERY_PLUGGED_WIRELESS, 60, 100);

		try (MockedStatic<NotificationService> ns = mockStatic(NotificationService.class)) {
			receive();
			runPendingSample();
			ns.verify(() -> NotificationService.notifyChargeConnected(any(Context.class),
					any(ChargeSpeed.class), eq(true)));
		}
	}

	@Test
	public void connectedAtLowBattery_notifiesLikeAnyOtherLevel() {
		publishBattery(BatteryManager.BATTERY_PLUGGED_USB, 10, 100);

		try (MockedStatic<NotificationService> ns = mockStatic(NotificationService.class)) {
			receive();
			runPendingSample();
			ns.verify(() -> NotificationService.notifyChargeConnected(any(Context.class),
					any(ChargeSpeed.class), eq(false)));
		}
	}

	@Test
	public void samePluggedState_sendsNoNotification() {
		PowerConnectionReceiver.setCurrentState(BatteryManager.BATTERY_PLUGGED_AC);
		publishBattery(BatteryManager.BATTERY_PLUGGED_AC, 50, 100);

		try (MockedStatic<NotificationService> ns = mockStatic(NotificationService.class)) {
			receive();
			runPendingSample();
			ns.verify(() -> NotificationService.notifyChargeConnected(any(Context.class),
					any(ChargeSpeed.class), anyBoolean()), never());
		}
	}

	@Test
	public void repeatedBroadcastForSameState_notifiesOnlyOnce() {
		// The dedupe updates the state atomically with the check (issue #156): the first receive
		// claims the new state, so an immediate duplicate broadcast must be swallowed.
		publishBattery(BatteryManager.BATTERY_PLUGGED_AC, 50, 100);

		try (MockedStatic<NotificationService> ns = mockStatic(NotificationService.class)) {
			receive();
			receive();
			runPendingSample();
			ns.verify(() -> NotificationService.notifyChargeConnected(any(Context.class),
					any(ChargeSpeed.class), anyBoolean()), times(1));
		}
	}

	@Test
	public void stateChangeAfterDedupe_stillProcesses() {
		// Connect, then unplug: the second broadcast differs from the recorded state, so it must
		// pass the dedupe and run the disconnect cleanup.
		publishBattery(BatteryManager.BATTERY_PLUGGED_AC, 50, 100);

		try (MockedStatic<NotificationService> ns = mockStatic(NotificationService.class)) {
			receive();
			publishBattery(0, 50, 100); // unplugged
			receive();
			runPendingSample();
			ns.verify(() -> NotificationService.clearNotifications(any(Context.class)));
		}
	}

	@Test
	public void disconnected_clearsNotifications() {
		PowerConnectionReceiver.setCurrentState(BatteryManager.BATTERY_PLUGGED_AC);
		publishBattery(0, 50, 100); // unplugged

		try (MockedStatic<NotificationService> ns = mockStatic(NotificationService.class)) {
			receive();
			runPendingSample();
			ns.verify(() -> NotificationService.clearNotifications(any(Context.class)));
			ns.verify(() -> NotificationService.notifyChargeConnected(any(Context.class),
					any(ChargeSpeed.class), anyBoolean()), never());
		}
	}

	// --- helpers -------------------------------------------------------------

	private void receive() {
		new PowerConnectionReceiver().onReceive(context, new Intent(Intent.ACTION_POWER_CONNECTED));
	}

	/**
	 * Advance the main looper past the sampling delay so the deferred charge-connected task runs.
	 */
	private void runPendingSample() {
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PowerConnectionReceiver.CHARGE_SAMPLE_DELAY_MS));
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
