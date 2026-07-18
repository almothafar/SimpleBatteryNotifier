package com.almothafar.simplebatterynotifier.receiver;

import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.almothafar.simplebatterynotifier.receiver.BatteryLevelReceiver.LevelAlertState;
import com.almothafar.simplebatterynotifier.service.NotificationService;
import com.almothafar.simplebatterynotifier.service.SystemService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * Robolectric + Mockito tests for {@link BatteryLevelReceiver}'s threshold and de-duplication logic.
 * <p>
 * The receiver reads the delivered {@code ACTION_BATTERY_CHANGED} intent (#159) and delegates the
 * actual notification to {@link NotificationService}, whose static methods are mocked so we can
 * assert <em>which</em> alert (if any) it decides to send without doing real Android notification
 * work. {@link SystemService} is left real so it builds a {@code BatteryDO} from the intent we deliver.
 * <p>
 * Episode state lives in SharedPreferences (#164), and each {@code receive()} uses a fresh receiver
 * instance — so every multi-broadcast test also exercises the state surviving "process death"
 * (nothing is carried in memory between broadcasts).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BatteryLevelReceiverTest {

	private Context context;
	private Intent latestBattery;

	@Before
	public void setUp() {
		// Robolectric gives each test a fresh application (and thus fresh SharedPreferences), so the
		// persisted episode state (#164) always starts cleared; no manual reset needed.
		context = ApplicationProvider.getApplicationContext();
	}

	@Test
	public void discharging_belowCritical_sendsCriticalAlert() {
		publishBattery(BatteryManager.BATTERY_STATUS_DISCHARGING, 15, 100, 0);

		try (MockedStatic<NotificationService> ns = mockStatic(NotificationService.class)) {
			receive();
			ns.verify(() -> NotificationService.sendNotification(any(Context.class), eq(NotificationService.CRITICAL_TYPE)));
		}
	}

	@Test
	public void discharging_betweenCriticalAndWarning_sendsWarningAlert() {
		publishBattery(BatteryManager.BATTERY_STATUS_DISCHARGING, 35, 100, 0);

		try (MockedStatic<NotificationService> ns = mockStatic(NotificationService.class)) {
			receive();
			ns.verify(() -> NotificationService.sendNotification(any(Context.class), eq(NotificationService.WARNING_TYPE)));
		}
	}

	@Test
	public void discharging_aboveWarning_sendsNoAlert() {
		publishBattery(BatteryManager.BATTERY_STATUS_DISCHARGING, 80, 100, 0);

		try (MockedStatic<NotificationService> ns = mockStatic(NotificationService.class)) {
			receive();
			ns.verify(() -> NotificationService.sendNotification(any(Context.class), eq(NotificationService.WARNING_TYPE)), never());
			ns.verify(() -> NotificationService.sendNotification(any(Context.class), eq(NotificationService.CRITICAL_TYPE)), never());
		}
	}

	@Test
	public void discharging_warningNotRepeatedWhileStillInWarningBand() {
		try (MockedStatic<NotificationService> ns = mockStatic(NotificationService.class)) {
			publishBattery(BatteryManager.BATTERY_STATUS_DISCHARGING, 38, 100, 0);
			receive();
			// Still in the warning band, different level -> must not re-alert
			publishBattery(BatteryManager.BATTERY_STATUS_DISCHARGING, 35, 100, 0);
			receive();

			ns.verify(() -> NotificationService.sendNotification(any(Context.class), eq(NotificationService.WARNING_TYPE)), times(1));
		}
	}

	@Test
	public void discharging_alertEveryTick_repeatsCriticalAlert() {
		enableAlertEveryTick();

		try (MockedStatic<NotificationService> ns = mockStatic(NotificationService.class)) {
			publishBattery(BatteryManager.BATTERY_STATUS_DISCHARGING, 15, 100, 0);
			receive();
			publishBattery(BatteryManager.BATTERY_STATUS_DISCHARGING, 14, 100, 0);
			receive();

			ns.verify(() -> NotificationService.sendNotification(any(Context.class), eq(NotificationService.CRITICAL_TYPE)), times(2));
		}
	}

	@Test
	public void full_whileCharging_sendsFullAlertOnce() {
		// Simulate the battery already sitting at 100% (unchanged) so the charging/full branch runs
		saveLevelState(new LevelAlertState(100, BatteryLevelReceiver.NO_ALERT, false));
		publishBattery(BatteryManager.BATTERY_STATUS_FULL, 100, 100, BatteryManager.BATTERY_PLUGGED_AC);

		try (MockedStatic<NotificationService> ns = mockStatic(NotificationService.class)) {
			receive();
			receive(); // second identical tick must not re-send
			ns.verify(() -> NotificationService.sendNotification(any(Context.class), eq(NotificationService.FULL_LEVEL_TYPE)), times(1));
		}
	}

	@Test
	public void unplug_reArmsFullAlert_forNextChargeSession() {
		saveLevelState(new LevelAlertState(100, BatteryLevelReceiver.NO_ALERT, false));
		publishBattery(BatteryManager.BATTERY_STATUS_FULL, 100, 100, BatteryManager.BATTERY_PLUGGED_AC);

		try (MockedStatic<NotificationService> ns = mockStatic(NotificationService.class)) {
			receive();
			// Unplugged: the charge-session reset re-arms the full alert; plugging back in at full fires again.
			BatteryLevelReceiver.onChargerDisconnected(context);
			receive();

			ns.verify(() -> NotificationService.sendNotification(any(Context.class), eq(NotificationService.FULL_LEVEL_TYPE)), times(2));
		}
	}

	@Test
	public void temperature_hotSpell_alertsOnceAcrossBroadcasts() {
		try (MockedStatic<NotificationService> ns = mockStatic(NotificationService.class)) {
			// 46.0 °C — above the default 45 °C threshold. Each receive() is a fresh receiver instance,
			// so the second broadcast staying hot is exactly the killed-and-restarted-mid-spell case.
			publishBattery(BatteryManager.BATTERY_STATUS_DISCHARGING, 80, 100, 0, 460);
			receive();
			publishBattery(BatteryManager.BATTERY_STATUS_DISCHARGING, 79, 100, 0, 465);
			receive();

			ns.verify(() -> NotificationService.sendTemperatureNotification(any(Context.class), anyInt()), times(1));
		}
	}

	@Test
	public void temperature_cooledBelowHysteresis_alertsAgainOnNextSpell() {
		try (MockedStatic<NotificationService> ns = mockStatic(NotificationService.class)) {
			publishBattery(BatteryManager.BATTERY_STATUS_DISCHARGING, 80, 100, 0, 460);
			receive();
			// Cooled to 41.0 °C (≤ 45 − 3 hysteresis) — re-arms; the next spell alerts again.
			publishBattery(BatteryManager.BATTERY_STATUS_DISCHARGING, 79, 100, 0, 410);
			receive();
			publishBattery(BatteryManager.BATTERY_STATUS_DISCHARGING, 78, 100, 0, 460);
			receive();

			ns.verify(() -> NotificationService.sendTemperatureNotification(any(Context.class), anyInt()), times(2));
		}
	}

	@Test
	public void unexpectedAction_isIgnored() {
		// The receiver reads the delivered intent (#159); a wrong-action intent (whose missing extras
		// would otherwise read as a 0% battery) must be dropped by the guard, not alerted on.
		try (MockedStatic<NotificationService> ns = mockStatic(NotificationService.class)) {
			new BatteryLevelReceiver().onReceive(context, new Intent(Intent.ACTION_POWER_CONNECTED));
			ns.verifyNoInteractions();
		}
	}

	// --- helpers -------------------------------------------------------------

	private void receive() {
		new BatteryLevelReceiver().onReceive(context, latestBattery);
	}

	private void publishBattery(final int status, final int level, final int scale, final int plugged) {
		publishBattery(status, level, scale, plugged, 250); // 25.0 °C, well below the alert threshold
	}

	/**
	 * Build the battery-changed intent {@link #receive()} will deliver. The receiver reads the
	 * delivered intent directly (#159), so no sticky broadcast is involved anymore.
	 */
	private void publishBattery(final int status, final int level, final int scale, final int plugged,
	                            final int temperatureTenthsC) {
		final Intent battery = new Intent(Intent.ACTION_BATTERY_CHANGED);
		battery.putExtra(BatteryManager.EXTRA_STATUS, status);
		battery.putExtra(BatteryManager.EXTRA_LEVEL, level);
		battery.putExtra(BatteryManager.EXTRA_SCALE, scale);
		battery.putExtra(BatteryManager.EXTRA_PLUGGED, plugged);
		battery.putExtra(BatteryManager.EXTRA_PRESENT, true);
		battery.putExtra(BatteryManager.EXTRA_TEMPERATURE, temperatureTenthsC);
		latestBattery = battery;
	}

	private void enableAlertEveryTick() {
		PreferenceManager.getDefaultSharedPreferences(context)
				.edit()
				.putBoolean(context.getString(com.almothafar.simplebatterynotifier.R.string._pref_key_notify_every_tick), true)
				.commit();
	}

	private void saveLevelState(final LevelAlertState state) {
		BatteryLevelReceiver.saveLevelState(PreferenceManager.getDefaultSharedPreferences(context), state);
	}
}
