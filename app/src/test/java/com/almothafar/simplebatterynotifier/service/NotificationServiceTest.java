package com.almothafar.simplebatterynotifier.service;

import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.assertEquals;

/**
 * Unit tests for the pure quiet-hours logic in {@link NotificationService}.
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
