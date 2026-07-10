package com.almothafar.simplebatterynotifier.service;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the pure quiet-hours range logic in {@link NotificationService}.
 * Times are expressed as minutes since midnight (hour * 60 + minute).
 */
public class NotificationServiceTest {

	// Daytime window 08:00 (480) – 23:00 (1380)
	private static final int DAY_START = 8 * 60;
	private static final int DAY_END = 23 * 60;

	// Overnight window 22:00 (1320) – 06:00 (360)
	private static final int NIGHT_START = 22 * 60;
	private static final int NIGHT_END = 6 * 60;

	@Test
	public void daytime_inside() {
		assertTrue(NotificationService.isWithinTimeRange(10 * 60, DAY_START, DAY_END));
	}

	@Test
	public void daytime_beforeStart_excluded() {
		assertFalse(NotificationService.isWithinTimeRange(6 * 60 + 40, DAY_START, DAY_END));
	}

	@Test
	public void daytime_startIsInclusive() {
		assertTrue(NotificationService.isWithinTimeRange(DAY_START, DAY_START, DAY_END));
	}

	@Test
	public void daytime_endIsExclusive() {
		assertFalse(NotificationService.isWithinTimeRange(DAY_END, DAY_START, DAY_END));
	}

	@Test
	public void overnight_lateNight_inside() {
		assertTrue(NotificationService.isWithinTimeRange(22 * 60 + 30, NIGHT_START, NIGHT_END));
	}

	@Test
	public void overnight_earlyMorning_inside() {
		assertTrue(NotificationService.isWithinTimeRange(2 * 60, NIGHT_START, NIGHT_END));
	}

	@Test
	public void overnight_midday_outside() {
		assertFalse(NotificationService.isWithinTimeRange(12 * 60, NIGHT_START, NIGHT_END));
	}

	/**
	 * Overnight window whose start/end share the same hour bucket, e.g. 22:30 → 22:00.
	 * The old hour-only logic mishandled this; the minute-based logic must not.
	 */
	@Test
	public void overnight_sameHourBucket() {
		final int start = 22 * 60 + 30; // 22:30
		final int end = 22 * 60;        // 22:00
		assertTrue(NotificationService.isWithinTimeRange(23 * 60 + 30, start, end));  // 23:30 inside
		assertFalse(NotificationService.isWithinTimeRange(22 * 60 + 15, start, end)); // 22:15 outside
	}

	@Test
	public void equalTimes_meansWholeDay() {
		assertTrue(NotificationService.isWithinTimeRange(0, 10 * 60, 10 * 60));
		assertTrue(NotificationService.isWithinTimeRange(23 * 60 + 59, 10 * 60, 10 * 60));
	}

	// --- alertsAllowedNow: quiet-hours gating with the critical override (issue #111) ---

	@Test
	public void insideWindow_alwaysAllowed() {
		assertTrue(NotificationService.alertsAllowedNow(true, false, false));
		assertTrue(NotificationService.alertsAllowedNow(true, true, false));
	}

	@Test
	public void outsideWindow_nonCritical_silenced() {
		assertFalse(NotificationService.alertsAllowedNow(false, false, true));
	}

	@Test
	public void outsideWindow_critical_breaksThroughWhenEnabled() {
		assertTrue(NotificationService.alertsAllowedNow(false, true, true));
	}

	@Test
	public void outsideWindow_critical_silencedWhenOverrideOff() {
		assertFalse(NotificationService.alertsAllowedNow(false, true, false));
	}
}
