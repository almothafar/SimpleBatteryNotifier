package com.almothafar.simplebatterynotifier.receiver;

import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import com.almothafar.simplebatterynotifier.model.ChargeSpeed;
import com.almothafar.simplebatterynotifier.model.ChargeSpeedTier;
import com.almothafar.simplebatterynotifier.service.NotificationService;
import com.almothafar.simplebatterynotifier.service.SystemService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.Duration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.robolectric.Shadows.shadowOf;

/**
 * Robolectric + Mockito tests for {@link PowerConnectionReceiver}: wired/wireless detection,
 * plugged-state de-duplication, and the disconnect cleanup path. The
 * {@link NotificationService} static methods are mocked so we can assert what the receiver decides
 * to do from the delivered {@code ACTION_BATTERY_CHANGED} intent (#159).
 * <p>
 * The charge-connected notification is dispatched a short delay after connection (the charging
 * current is noisy right at plug-in), so tests advance the main looper by
 * {@link PowerConnectionReceiver#CHARGE_SAMPLE_DELAY_MS} to let the deferred sample run.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PowerConnectionReceiverTest {

	private Context context;
	private Intent latestBattery;

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
	public void connected_dismissesLevelAlertImmediately() {
		publishBattery(BatteryManager.BATTERY_PLUGGED_AC, 10, 100);

		try (MockedStatic<NotificationService> ns = mockStatic(NotificationService.class)) {
			receive();
			// Verified before the delayed speed sample runs: charging resolves the low-battery
			// concern, so the alert's dismissal must not wait out the sampling delay (#155).
			ns.verify(() -> NotificationService.clearLevelAlert(any(Context.class)));
		}
	}

	@Test
	public void connected_clearsStaleFastDrainAlert() {
		publishBattery(BatteryManager.BATTERY_PLUGGED_AC, 40, 100);

		try (MockedStatic<NotificationService> ns = mockStatic(NotificationService.class)) {
			receive();
			// Charging makes a "battery draining fast" warning stale, so it's dismissed at plug-in —
			// immediately, alongside the level alert, not after the speed-sample delay.
			ns.verify(() -> NotificationService.clearFastDrainAlert(any(Context.class)));
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
	public void unexpectedAction_isIgnored() {
		// The receiver reads the delivered intent (#159), so an intent with the wrong action must be
		// dropped by the guard instead of being misread as a battery snapshot.
		publishBattery(BatteryManager.BATTERY_PLUGGED_AC, 50, 100);

		try (MockedStatic<NotificationService> ns = mockStatic(NotificationService.class)) {
			new PowerConnectionReceiver().onReceive(context, new Intent(Intent.ACTION_POWER_CONNECTED));
			runPendingSample();
			ns.verifyNoInteractions();
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

	@Test
	public void fastChargerRampingThroughHandshake_reportsRampedTierNotTrickle() {
		// A fast charger draws the ~2 W USB default while negotiating, then jumps to full power (#227).
		// The sampler must keep going through the early trickle readings and report the ramped tier.
		publishBattery(BatteryManager.BATTERY_PLUGGED_AC, 30, 100);
		final ChargeSpeed handshake = ChargeSpeed.fromMeasurements(400_000, 5_000); // ~2 W  -> trickle
		final ChargeSpeed ramped = ChargeSpeed.fromMeasurements(4_000_000, 5_000);  // ~20 W -> fast

		try (MockedStatic<NotificationService> ns = mockStatic(NotificationService.class);
		     MockedStatic<SystemService> ss = mockStatic(SystemService.class)) {
			ss.when(() -> SystemService.getChargeSpeed(any(Context.class))).thenReturn(handshake, handshake, ramped);
			receive();
			runPendingSample();
			ns.verify(() -> NotificationService.notifyChargeConnected(any(Context.class),
					argThat(speed -> speed.getTier() == ChargeSpeedTier.FAST), eq(false)));
		}
	}

	@Test
	public void sustainedTrickle_reportsSlowChargingAfterWindow() {
		// A genuinely weak charger stays at trickle for the whole window: the last-resort path still
		// surfaces "Slow charging" rather than an unknown/plain message.
		publishBattery(BatteryManager.BATTERY_PLUGGED_AC, 30, 100);
		final ChargeSpeed trickle = ChargeSpeed.fromMeasurements(400_000, 5_000); // ~2 W -> trickle

		try (MockedStatic<NotificationService> ns = mockStatic(NotificationService.class);
		     MockedStatic<SystemService> ss = mockStatic(SystemService.class)) {
			ss.when(() -> SystemService.getChargeSpeed(any(Context.class))).thenReturn(trickle);
			receive();
			runPendingSample();
			ns.verify(() -> NotificationService.notifyChargeConnected(any(Context.class),
					argThat(speed -> speed.getTier() == ChargeSpeedTier.TRICKLE), eq(false)));
		}
	}

	@Test
	public void higherPowerOf_prefersTheStrongerReadingAndDropsUnknown() {
		final ChargeSpeed unknown = ChargeSpeed.unknown();
		final ChargeSpeed trickle = ChargeSpeed.fromMeasurements(400_000, 5_000);  // ~2 W
		final ChargeSpeed fast = ChargeSpeed.fromMeasurements(4_000_000, 5_000);   // ~20 W

		// Any real reading beats the seed unknown, regardless of order.
		assertSame(trickle, PowerConnectionReceiver.higherPowerOf(unknown, trickle));
		assertSame(trickle, PowerConnectionReceiver.higherPowerOf(trickle, unknown));
		// The higher-power reading wins, so a ramp can't be lost to an earlier low sample.
		assertSame(fast, PowerConnectionReceiver.higherPowerOf(trickle, fast));
		assertSame(fast, PowerConnectionReceiver.higherPowerOf(fast, trickle));
	}

	@Test
	public void isRamped_isTrueOnlyFromFastTierUp() {
		assertFalse(PowerConnectionReceiver.isRamped(ChargeSpeed.unknown()));
		assertFalse(PowerConnectionReceiver.isRamped(ChargeSpeed.fromMeasurements(400_000, 5_000)));   // ~2 W  trickle
		assertFalse(PowerConnectionReceiver.isRamped(ChargeSpeed.fromMeasurements(1_400_000, 5_000))); // ~7 W  normal
		assertTrue(PowerConnectionReceiver.isRamped(ChargeSpeed.fromMeasurements(4_000_000, 5_000)));  // ~20 W fast
		assertTrue(PowerConnectionReceiver.isRamped(ChargeSpeed.fromMeasurements(8_000_000, 5_000)));  // ~40 W super fast
	}

	// --- helpers -------------------------------------------------------------

	private void receive() {
		new PowerConnectionReceiver().onReceive(context, latestBattery);
	}

	/**
	 * Advance the main looper past the whole sampling window so the deferred charge-connected task
	 * runs. The speed is re-sampled up to {@link PowerConnectionReceiver#MAX_CHARGE_SAMPLE_ATTEMPTS}
	 * times (Robolectric reports no charging current, so every attempt reads "unknown"), then notifies
	 * once with that unknown speed — so the looper must be idled across all of them.
	 */
	private void runPendingSample() {
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(
				PowerConnectionReceiver.CHARGE_SAMPLE_DELAY_MS * PowerConnectionReceiver.MAX_CHARGE_SAMPLE_ATTEMPTS));
	}

	/**
	 * Build the battery-changed intent {@link #receive()} will deliver (the receiver reads the
	 * delivered intent directly since #159), and publish it as the sticky broadcast too — the
	 * delayed sample's still-plugged re-check reads the sticky state.
	 */
	@SuppressWarnings("deprecation")
	private void publishBattery(final int plugged, final int level, final int scale) {
		final Intent battery = new Intent(Intent.ACTION_BATTERY_CHANGED);
		battery.putExtra(BatteryManager.EXTRA_PLUGGED, plugged);
		battery.putExtra(BatteryManager.EXTRA_LEVEL, level);
		battery.putExtra(BatteryManager.EXTRA_SCALE, scale);
		battery.putExtra(BatteryManager.EXTRA_PRESENT, true);
		context.sendStickyBroadcast(battery);
		latestBattery = battery;
	}
}
