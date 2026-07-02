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
	public void chargeCycle_completesWhenLowThenChargedToFull() {
		// Drop to the low threshold -> a cycle begins but isn't counted yet
		BatteryHealthTracker.recordBatteryState(context, 15, BatteryManager.BATTERY_STATUS_DISCHARGING);
		assertEquals(0, BatteryHealthTracker.getChargeCycles(context));

		// Charge back up past the full threshold -> the cycle completes
		BatteryHealthTracker.recordBatteryState(context, 96, BatteryManager.BATTERY_STATUS_CHARGING);
		assertEquals(1, BatteryHealthTracker.getChargeCycles(context));
	}

	@Test
	public void chargeCycle_accumulatesAcrossPartialCharges() {
		// A mid-range reading between low and full must not start or complete a cycle
		BatteryHealthTracker.recordBatteryState(context, 15, BatteryManager.BATTERY_STATUS_DISCHARGING);
		BatteryHealthTracker.recordBatteryState(context, 50, BatteryManager.BATTERY_STATUS_DISCHARGING);
		assertEquals(0, BatteryHealthTracker.getChargeCycles(context));

		BatteryHealthTracker.recordBatteryState(context, 96, BatteryManager.BATTERY_STATUS_FULL);
		assertEquals(1, BatteryHealthTracker.getChargeCycles(context));
	}

	@Test
	public void chargeCycle_notCountedWhenReachingFullWithoutCharging() {
		BatteryHealthTracker.recordBatteryState(context, 15, BatteryManager.BATTERY_STATUS_DISCHARGING);
		// Back above full while NOT charging -> tracking resets without counting a cycle
		BatteryHealthTracker.recordBatteryState(context, 98, BatteryManager.BATTERY_STATUS_DISCHARGING);
		assertEquals(0, BatteryHealthTracker.getChargeCycles(context));

		// And a fresh low->full charge after the reset still counts exactly one cycle
		BatteryHealthTracker.recordBatteryState(context, 18, BatteryManager.BATTERY_STATUS_DISCHARGING);
		BatteryHealthTracker.recordBatteryState(context, 97, BatteryManager.BATTERY_STATUS_CHARGING);
		assertEquals(1, BatteryHealthTracker.getChargeCycles(context));
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
		BatteryHealthTracker.recordBatteryState(context, 12, BatteryManager.BATTERY_STATUS_DISCHARGING);
		BatteryHealthTracker.recordBatteryState(context, 99, BatteryManager.BATTERY_STATUS_CHARGING);
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
		// Without a design capacity there's nothing to measure against
		assertEquals(-1, BatteryHealthTracker.getMeasuredHealthPercentage(context));
	}

	@Test
	public void resetHealthData_clearsCyclesAndFirstUse() {
		BatteryHealthTracker.recordBatteryState(context, 10, BatteryManager.BATTERY_STATUS_DISCHARGING);
		BatteryHealthTracker.recordBatteryState(context, 96, BatteryManager.BATTERY_STATUS_CHARGING);
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
