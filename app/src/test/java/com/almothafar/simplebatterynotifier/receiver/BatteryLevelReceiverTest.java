package com.almothafar.simplebatterynotifier.receiver;

import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;

import androidx.test.core.app.ApplicationProvider;

import com.almothafar.simplebatterynotifier.service.NotificationService;
import com.almothafar.simplebatterynotifier.service.SystemService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * Robolectric + Mockito tests for {@link BatteryLevelReceiver}'s threshold and de-duplication logic.
 * <p>
 * The receiver reads the sticky {@code ACTION_BATTERY_CHANGED} intent and delegates the actual
 * notification to {@link NotificationService}, whose static methods are mocked so we can assert
 * <em>which</em> alert (if any) it decides to send without doing real Android notification work.
 * {@link SystemService} is left real so it builds a {@code BatteryDO} from the sticky intent we set.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BatteryLevelReceiverTest {

	private Context context;

	@Before
	public void setUp() {
		context = ApplicationProvider.getApplicationContext();
		// Clear the static de-dup state between tests so ordering can't leak through
		setStatic("prevLevel", 0);
		setStatic("prevType", 0);
		setStatic("fullNotificationCalled", false);
		setStatic("temperatureAlertSent", false);
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
		setStatic("prevLevel", 100);
		publishBattery(BatteryManager.BATTERY_STATUS_FULL, 100, 100, BatteryManager.BATTERY_PLUGGED_AC);

		try (MockedStatic<NotificationService> ns = mockStatic(NotificationService.class)) {
			receive();
			receive(); // second identical tick must not re-send
			ns.verify(() -> NotificationService.sendNotification(any(Context.class), eq(NotificationService.FULL_LEVEL_TYPE)), times(1));
		}
	}

	// --- helpers -------------------------------------------------------------

	private void receive() {
		new BatteryLevelReceiver().onReceive(context, new Intent(Intent.ACTION_BATTERY_CHANGED));
	}

	@SuppressWarnings("deprecation")
	private void publishBattery(final int status, final int level, final int scale, final int plugged) {
		final Intent battery = new Intent(Intent.ACTION_BATTERY_CHANGED);
		battery.putExtra(BatteryManager.EXTRA_STATUS, status);
		battery.putExtra(BatteryManager.EXTRA_LEVEL, level);
		battery.putExtra(BatteryManager.EXTRA_SCALE, scale);
		battery.putExtra(BatteryManager.EXTRA_PLUGGED, plugged);
		battery.putExtra(BatteryManager.EXTRA_PRESENT, true);
		battery.putExtra(BatteryManager.EXTRA_TEMPERATURE, 250); // 25.0 °C, well below the alert threshold
		context.sendStickyBroadcast(battery);
	}

	private void enableAlertEveryTick() {
		androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
				.edit()
				.putBoolean(context.getString(com.almothafar.simplebatterynotifier.R.string._pref_key_notify_every_tick), true)
				.commit();
	}

	private static void setStatic(final String fieldName, final Object value) {
		try {
			final Field field = BatteryLevelReceiver.class.getDeclaredField(fieldName);
			field.setAccessible(true);
			field.set(null, value);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Unable to reset " + fieldName, e);
		}
	}
}
