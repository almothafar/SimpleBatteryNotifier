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
 * Unit tests for the pure logic in {@link NotificationService}: quiet hours, charge-style
 * normalization and versioned alert-channel IDs.
 * Times are expressed as minutes since midnight (hour * 60 + minute).
 */
@RunWith(Enclosed.class)
public class NotificationServiceTest {

	/**
	 * {@link NotificationService#isWithinTimeRange(int, int, int)} across daytime, overnight-wrap and
	 * degenerate windows. The label documents each row's intent.
	 */
	@RunWith(Parameterized.class)
	public static class TimeRange {

		// Daytime window 08:00 (480) – 23:00 (1380)
		private static final int DAY_START = 8 * 60;
		private static final int DAY_END = 23 * 60;

		// Overnight window 22:00 (1320) – 06:00 (360)
		private static final int NIGHT_START = 22 * 60;
		private static final int NIGHT_END = 6 * 60;

		@Parameter(0) public String label;
		@Parameter(1) public int now;
		@Parameter(2) public int start;
		@Parameter(3) public int end;
		@Parameter(4) public boolean expected;

		@Parameters(name = "{0}")
		public static Collection<Object[]> data() {
			return Arrays.asList(new Object[][]{
					{"daytime inside", 10 * 60, DAY_START, DAY_END, true},
					{"daytime before start excluded", 6 * 60 + 40, DAY_START, DAY_END, false},
					{"daytime start is inclusive", DAY_START, DAY_START, DAY_END, true},
					{"daytime end is exclusive", DAY_END, DAY_START, DAY_END, false},
					{"overnight late night inside", 22 * 60 + 30, NIGHT_START, NIGHT_END, true},
					{"overnight early morning inside", 2 * 60, NIGHT_START, NIGHT_END, true},
					{"overnight midday outside", 12 * 60, NIGHT_START, NIGHT_END, false},
					// Overnight window whose start/end share the same hour bucket (22:30 -> 22:00). The old
					// hour-only logic mishandled this; the minute-based logic must not.
					{"same-hour-bucket 23:30 inside", 23 * 60 + 30, 22 * 60 + 30, 22 * 60, true},
					{"same-hour-bucket 22:15 outside", 22 * 60 + 15, 22 * 60 + 30, 22 * 60, false},
					// start == end means the whole day is covered.
					{"equal times means whole day (00:00)", 0, 10 * 60, 10 * 60, true},
					{"equal times means whole day (23:59)", 23 * 60 + 59, 10 * 60, 10 * 60, true},
			});
		}

		@Test
		public void matchesExpected() {
			assertEquals(label, expected, NotificationService.isWithinTimeRange(now, start, end));
		}
	}

	/**
	 * {@link NotificationService#alertsAllowedNow(boolean, boolean, boolean)} — quiet-hours gating with
	 * the critical override (issue #111).
	 */
	@RunWith(Parameterized.class)
	public static class AlertsAllowedNow {

		@Parameter(0) public boolean withinWindow;
		@Parameter(1) public boolean isCritical;
		@Parameter(2) public boolean criticalIgnoresQuietHours;
		@Parameter(3) public boolean expected;

		@Parameters(name = "within={0} critical={1} override={2} -> {3}")
		public static Collection<Object[]> data() {
			return Arrays.asList(new Object[][]{
					{true, false, false, true},   // inside window -> always allowed
					{true, true, false, true},     // inside window -> allowed even without the override
					{false, false, true, false},   // outside, non-critical -> silenced
					{false, true, true, true},      // outside, critical breaks through when enabled
					{false, true, false, false},   // outside, critical silenced when override off
			});
		}

		@Test
		public void matchesExpected() {
			assertEquals(expected,
					NotificationService.alertsAllowedNow(withinWindow, isCritical, criticalIgnoresQuietHours));
		}
	}

	/**
	 * {@link NotificationService#versionedChannelId(String, int)} — versioned alert-channel IDs so a
	 * Vibrate change creates genuinely new channels instead of un-deleting old ones (issue #153).
	 * Version 1 must stay the original unsuffixed ID so existing installs keep their channels.
	 */
	@RunWith(Parameterized.class)
	public static class VersionedChannelId {

		@Parameter(0) public String label;
		@Parameter(1) public String baseId;
		@Parameter(2) public int version;
		@Parameter(3) public String expected;

		@Parameters(name = "{0}")
		public static Collection<Object[]> data() {
			return Arrays.asList(new Object[][]{
					{"version 1 keeps the legacy unsuffixed ID", "battery_critical", 1, "battery_critical"},
					{"version 2 appends _v2", "battery_critical", 2, "battery_critical_v2"},
					{"later versions keep counting", "battery_warning", 7, "battery_warning_v7"},
					{"different base IDs stay distinct", "battery_full", 2, "battery_full_v2"},
					{"defensive: version 0 treated as legacy", "battery_critical", 0, "battery_critical"},
					{"defensive: negative version treated as legacy", "battery_critical", -3, "battery_critical"},
			});
		}

		@Test
		public void matchesExpected() {
			assertEquals(label, expected, NotificationService.versionedChannelId(baseId, version));
		}
	}

	/**
	 * {@link NotificationService#boundOrDefaultMinutes(String, String)} — a corrupt stored
	 * quiet-hours bound falls back to that bound's default instead of crashing the alert path
	 * (issue #154). Runs under Robolectric because the fallback is logged.
	 */
	@RunWith(RobolectricTestRunner.class)
	@Config(sdk = 34)
	public static class BoundOrDefaultMinutes {

		private static final String DEFAULT_START = "06:30";

		@Test
		public void validStoredBoundWins() {
			assertEquals(22 * 60 + 15, NotificationService.boundOrDefaultMinutes("22:15", DEFAULT_START));
		}

		@Test
		public void malformedStoredBoundFallsBackToDefault() {
			assertEquals(6 * 60 + 30, NotificationService.boundOrDefaultMinutes("ab:cd", DEFAULT_START));
			assertEquals(6 * 60 + 30, NotificationService.boundOrDefaultMinutes(null, DEFAULT_START));
			assertEquals(6 * 60 + 30, NotificationService.boundOrDefaultMinutes("25:99", DEFAULT_START));
		}

		@Test
		public void unparseableDefaultFloorsAtMidnightInsteadOfPropagating() {
			// Can't happen with the real resource constants; the floor just keeps the impossible
			// case inside the valid minutes-of-day range.
			assertEquals(0, NotificationService.boundOrDefaultMinutes("bad", "also bad"));
		}
	}

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
			NotificationService.sendNotification(context, NotificationService.CRITICAL_TYPE);
			NotificationService.notifyChargeConnected(context, ChargeSpeed.unknown(), false);

			// Distinct IDs: both must be showing, not "Charging started" swallowing the critical alert.
			assertEquals(2, shadowOf(manager).size());
		}

		@Test
		public void clearNotifications_onDisconnect_removesLevelAlertAndChargeNotification() {
			NotificationService.sendNotification(context, NotificationService.CRITICAL_TYPE);
			NotificationService.notifyChargeConnected(context, ChargeSpeed.unknown(), false);

			NotificationService.clearNotifications(context);

			// Neither a stale level alert nor a stale "Charging started" survives unplug.
			assertEquals(0, shadowOf(manager).size());
		}

		@Test
		public void clearLevelAlert_dismissesOnlyTheLevelAlert() {
			NotificationService.sendNotification(context, NotificationService.CRITICAL_TYPE);
			NotificationService.notifyChargeConnected(context, ChargeSpeed.unknown(), false);

			NotificationService.clearLevelAlert(context);

			// The plug-in dismissal targets the level alert; "Charging started" keeps showing.
			assertEquals(1, shadowOf(manager).size());
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
