package com.almothafar.simplebatterynotifier.service;

import android.Manifest;
import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.model.ChargeSpeed;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

/**
 * Tests for the {@link NotificationService} dispatch layer: the charge-connected notification wiring
 * and the charge-style normalization. The channel/quiet-hours/ongoing-text logic it delegates to lives
 * in {@link NotificationChannelsTest}, {@link QuietHoursTest} and {@link OngoingStatusContentTest}.
 */
@RunWith(Enclosed.class)
public class NotificationServiceTest {

	/**
	 * The charge-connected notification wiring (#155): posted under its own ID so it can never
	 * replace a critical/warning/full level alert, cleared together with the level alert on
	 * disconnect, and the level alert's plug-in dismissal is the explicit
	 * {@link NotificationService#clearLevelAlert} — not an ID collision.
	 */
	@RunWith(RobolectricTestRunner.class)
	@Config(sdk = 34)
	public static class ChargeConnectedWiring {

		private Context context;
		private NotificationManager manager;

		@Before
		public void setUp() {
			context = ApplicationProvider.getApplicationContext();
			shadowOf((Application) context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
			// The "notification" charge style routes notifyChargeConnected to a real system notification.
			PreferenceManager.getDefaultSharedPreferences(context).edit()
					.putString(context.getString(R.string._pref_key_charge_notification_style),
							NotificationService.CHARGE_STYLE_NOTIFICATION)
					.commit();
			manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
		}

		@Test
		public void chargeNotification_doesNotReplaceLevelAlert() {
			NotificationService.sendNotification(context, AlertType.CRITICAL);
			NotificationService.notifyChargeConnected(context, ChargeSpeed.unknown(), false);

			// Distinct IDs: both must be showing, not "Charging started" swallowing the critical alert.
			assertEquals(2, shadowOf(manager).size());
		}

		@Test
		public void clearNotifications_onDisconnect_removesLevelAlertAndChargeNotification() {
			NotificationService.sendNotification(context, AlertType.CRITICAL);
			NotificationService.notifyChargeConnected(context, ChargeSpeed.unknown(), false);

			NotificationService.clearNotifications(context);

			// Neither a stale level alert nor a stale "Charging started" survives unplug.
			assertEquals(0, shadowOf(manager).size());
		}

		@Test
		public void clearLevelAlert_dismissesOnlyTheLevelAlert() {
			NotificationService.sendNotification(context, AlertType.CRITICAL);
			NotificationService.notifyChargeConnected(context, ChargeSpeed.unknown(), false);

			NotificationService.clearLevelAlert(context);

			// The plug-in dismissal targets the level alert; "Charging started" keeps showing.
			assertEquals(1, shadowOf(manager).size());
		}

		@Test
		public void clearFastDrainAlert_dismissesOnlyTheFastDrainAlert() {
			NotificationService.sendFastDrainNotification(context, 27, 20, 6);
			NotificationService.notifyChargeConnected(context, ChargeSpeed.unknown(), false);

			NotificationService.clearFastDrainAlert(context);

			// The plug-in dismissal targets the stale drain warning; "Charging started" keeps showing.
			assertEquals(1, shadowOf(manager).size());
		}

		@Test
		public void nullAlertType_postsNothing() {
			// The old int API's default branch posted a completely BLANK notification for an invalid
			// type (#160). With the enum, the only invalid value left is null — and it must be a no-op.
			NotificationService.sendNotification(context, null);

			assertEquals(0, shadowOf(manager).size());
		}
	}

	/**
	 * {@link NotificationService#resolveChargeStyle(String)} — normalizes the persisted charge-style
	 * preference, defaulting a null/blank/unrecognized value to Toast (issue #122).
	 */
	@RunWith(Parameterized.class)
	public static class ResolveChargeStyle {

		@Parameter(0) public String label;
		@Parameter(1) public String stored;
		@Parameter(2) public String expected;

		@Parameters(name = "{0}")
		public static Collection<Object[]> data() {
			return Arrays.asList(new Object[][]{
					{"toast kept", NotificationService.CHARGE_STYLE_TOAST, NotificationService.CHARGE_STYLE_TOAST},
					{"notification kept", NotificationService.CHARGE_STYLE_NOTIFICATION, NotificationService.CHARGE_STYLE_NOTIFICATION},
					{"none kept", NotificationService.CHARGE_STYLE_NONE, NotificationService.CHARGE_STYLE_NONE},
					{"null defaults to toast", null, NotificationService.CHARGE_STYLE_TOAST},
					{"blank defaults to toast", "", NotificationService.CHARGE_STYLE_TOAST},
					{"unknown defaults to toast", "bogus", NotificationService.CHARGE_STYLE_TOAST},
			});
		}

		@Test
		public void matchesExpected() {
			assertEquals(label, expected, NotificationService.resolveChargeStyle(stored));
		}
	}
}
