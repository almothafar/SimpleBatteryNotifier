package com.almothafar.simplebatterynotifier.service;

import android.content.Context;
import android.os.BatteryManager;

import androidx.test.core.app.ApplicationProvider;

import com.almothafar.simplebatterynotifier.model.BatteryHealthGrade;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Robolectric tests for the stateful, SharedPreferences-backed parts of {@link BatteryHealthTracker}:
 * the charge-cycle state machine and the user-entered design-capacity persistence. Each test gets a
 * fresh application (and therefore empty default SharedPreferences), so no manual reset is needed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BatteryHealthTrackerStateTest {

	private Context context;

	@Before
	public void setUp() {
		context = ApplicationProvider.getApplicationContext();
	}

	@Test
	public void chargeCycle_completesOnFullChargeFromEmpty() {
		// Establish a starting level (first reading only seeds prevLevel, nothing accrues)
		BatteryHealthTracker.recordBatteryState(context, 0, BatteryManager.BATTERY_STATUS_DISCHARGING);
		assertEquals(0, BatteryHealthTracker.getChargeCycles(context));

		// A full 0 -> 100 charge delivers 100 percentage-points = exactly one cycle
		BatteryHealthTracker.recordBatteryState(context, 100, BatteryManager.BATTERY_STATUS_CHARGING);
		assertEquals(1, BatteryHealthTracker.getChargeCycles(context));
	}

	@Test
	public void chargeCycle_accumulatesAcrossPartialCharges() {
		// Two 40 -> 90 partial charges together deliver 100 percentage-points = one cycle.
		BatteryHealthTracker.recordBatteryState(context, 40, BatteryManager.BATTERY_STATUS_DISCHARGING);
		BatteryHealthTracker.recordBatteryState(context, 90, BatteryManager.BATTERY_STATUS_CHARGING); // +50 -> carry 50
		assertEquals(0, BatteryHealthTracker.getChargeCycles(context));

		BatteryHealthTracker.recordBatteryState(context, 40, BatteryManager.BATTERY_STATUS_DISCHARGING); // drains, no accrual
		BatteryHealthTracker.recordBatteryState(context, 90, BatteryManager.BATTERY_STATUS_CHARGING); // +50 -> 1 cycle
		assertEquals(1, BatteryHealthTracker.getChargeCycles(context));
	}

	@Test
	public void chargeCycle_singlePartialChargeDoesNotComplete() {
		// A single 20 -> 95 charge (the old "swing" that used to count 1) is only 0.75 of a cycle now.
		BatteryHealthTracker.recordBatteryState(context, 20, BatteryManager.BATTERY_STATUS_DISCHARGING);
		BatteryHealthTracker.recordBatteryState(context, 95, BatteryManager.BATTERY_STATUS_CHARGING);
		assertEquals(0, BatteryHealthTracker.getChargeCycles(context));
	}

	@Test
	public void chargeCycle_dischargingNeverCounts() {
		BatteryHealthTracker.recordBatteryState(context, 90, BatteryManager.BATTERY_STATUS_DISCHARGING);
		BatteryHealthTracker.recordBatteryState(context, 10, BatteryManager.BATTERY_STATUS_DISCHARGING);
		assertEquals(0, BatteryHealthTracker.getChargeCycles(context));
	}

	@Test
	public void firstUseDate_isInitializedOnFirstRecord() {
		assertEquals(0, BatteryHealthTracker.getFirstUseDate(context));
		BatteryHealthTracker.recordBatteryState(context, 55, BatteryManager.BATTERY_STATUS_DISCHARGING);
		assertTrue(BatteryHealthTracker.getFirstUseDate(context) > 0);
		assertTrue(BatteryHealthTracker.getDaysSinceFirstUse(context) >= 0);
	}

	@Test
	public void effectiveCycleCount_fallsBackToTrackedEstimate() {
		// No OS EXTRA_CYCLE_COUNT is available under test, so the effective count is the tracked one
		BatteryHealthTracker.recordBatteryState(context, 0, BatteryManager.BATTERY_STATUS_DISCHARGING);
		BatteryHealthTracker.recordBatteryState(context, 100, BatteryManager.BATTERY_STATUS_CHARGING);
		assertEquals(1, BatteryHealthTracker.getEffectiveCycleCount(context));
	}

	@Test
	public void designCapacity_persistsAndClears() {
		assertFalse(BatteryHealthTracker.hasDesignCapacity(context));
		assertEquals(0, BatteryHealthTracker.getDesignCapacity(context));

		BatteryHealthTracker.setDesignCapacity(context, 4000);
		assertTrue(BatteryHealthTracker.hasDesignCapacity(context));
		assertEquals(4000, BatteryHealthTracker.getDesignCapacity(context));

		// A non-positive value clears the stored capacity
		BatteryHealthTracker.setDesignCapacity(context, 0);
		assertFalse(BatteryHealthTracker.hasDesignCapacity(context));
		assertEquals(0, BatteryHealthTracker.getDesignCapacity(context));
	}

	@Test
	public void measuredHealth_requiresDesignCapacity() {
		// Without a design capacity there's nothing to measure against, whatever capacity was read
		assertEquals(-1, BatteryHealthTracker.getMeasuredHealthPercentage(context, 4400));
	}

	@Test
	public void autoDetectDesignCapacity_storesDetectedValueOnceWhenUnset() {
		// A readable kernel value is stored as the design capacity...
		assertTrue(BatteryHealthTracker.applyAutoDetectedDesignCapacity(context, 4700));
		assertEquals(4700, BatteryHealthTracker.getDesignCapacity(context));
		// ...and a later detection never overrides the now-present value.
		assertFalse(BatteryHealthTracker.applyAutoDetectedDesignCapacity(context, 5000));
		assertEquals(4700, BatteryHealthTracker.getDesignCapacity(context));
	}

	@Test
	public void autoDetectDesignCapacity_neverOverridesUserValue() {
		BatteryHealthTracker.setDesignCapacity(context, 4000);
		assertFalse(BatteryHealthTracker.applyAutoDetectedDesignCapacity(context, 4700));
		assertEquals(4000, BatteryHealthTracker.getDesignCapacity(context));
	}

	@Test
	public void autoDetectDesignCapacity_attemptsAtMostOnce() {
		// First attempt finds nothing readable (0) but records the attempt...
		assertFalse(BatteryHealthTracker.applyAutoDetectedDesignCapacity(context, 0));
		assertFalse(BatteryHealthTracker.hasDesignCapacity(context));
		// ...so even a later successful read is ignored (a user who cleared the value isn't re-populated).
		assertFalse(BatteryHealthTracker.applyAutoDetectedDesignCapacity(context, 4700));
		assertFalse(BatteryHealthTracker.hasDesignCapacity(context));
	}

	@Test
	public void resetHealthData_clearsCyclesAndFirstUse() {
		BatteryHealthTracker.recordBatteryState(context, 0, BatteryManager.BATTERY_STATUS_DISCHARGING);
		BatteryHealthTracker.recordBatteryState(context, 100, BatteryManager.BATTERY_STATUS_CHARGING);
		assertEquals(1, BatteryHealthTracker.getChargeCycles(context));

		BatteryHealthTracker.resetHealthData(context);
		assertEquals(0, BatteryHealthTracker.getChargeCycles(context));
		assertEquals(0, BatteryHealthTracker.getFirstUseDate(context));
	}

	@Test
	public void gradeAndDescription_stayConsistentForNewBattery() {
		// A brand-new battery (0 cycles) grades Excellent with the matching description
		final BatteryHealthGrade grade = BatteryHealthTracker.getHealthGrade(context);
		assertEquals(BatteryHealthGrade.EXCELLENT, grade);
		assertEquals(BatteryHealthTracker.describeHealthGrade(context, grade),
				BatteryHealthTracker.getHealthDescription(context));
	}
}
