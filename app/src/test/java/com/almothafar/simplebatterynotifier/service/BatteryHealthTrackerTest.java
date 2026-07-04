package com.almothafar.simplebatterynotifier.service;

import com.almothafar.simplebatterynotifier.model.BatteryHealthGrade;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the pure, Android-free helpers in {@link BatteryHealthTracker}.
 */
public class BatteryHealthTrackerTest {

	// A near-full battery level, at/above the threshold, so these cases exercise the math (not the gate).
	private static final int FULL = 95;

	@Test
	public void computeMeasuredHealth_typicalReadings() {
		// 3800 mAh measured against a 4000 mAh design capacity -> 95%
		assertEquals(95, BatteryHealthTracker.computeMeasuredHealth(3800, 4000, FULL));
		// A healthy battery measuring at its rated capacity -> 100%
		assertEquals(100, BatteryHealthTracker.computeMeasuredHealth(4000, 4000, FULL));
		// Worn battery: 3000 of 5000 -> 60%
		assertEquals(60, BatteryHealthTracker.computeMeasuredHealth(3000, 5000, FULL));
	}

	@Test
	public void computeMeasuredHealth_clampsToRange() {
		// Measured above rated (fresh cell / rounding) is clamped to 100
		assertEquals(100, BatteryHealthTracker.computeMeasuredHealth(4200, 4000, FULL));
		// Never reports 0; the smallest positive result is 1
		assertEquals(1, BatteryHealthTracker.computeMeasuredHealth(1, 15000, FULL));
	}

	@Test
	public void computeMeasuredHealth_unusableInputs_returnsMinusOne() {
		assertEquals(-1, BatteryHealthTracker.computeMeasuredHealth(0, 4000, FULL));
		assertEquals(-1, BatteryHealthTracker.computeMeasuredHealth(3800, 0, FULL));
		assertEquals(-1, BatteryHealthTracker.computeMeasuredHealth(-5, 4000, FULL));
	}

	@Test
	public void computeMeasuredHealth_belowNearFullThreshold_returnsMinusOne() {
		final int threshold = BatteryHealthTracker.MEASURED_HEALTH_MIN_BATTERY_LEVEL;
		// Just below the threshold: withheld as too noisy, so callers fall back to the cycle estimate.
		assertEquals(-1, BatteryHealthTracker.computeMeasuredHealth(3800, 4000, threshold - 1));
		// A low charge level is withheld even with otherwise-usable capacity inputs.
		assertEquals(-1, BatteryHealthTracker.computeMeasuredHealth(3800, 4000, 5));
		// Unknown level (-1) is also withheld.
		assertEquals(-1, BatteryHealthTracker.computeMeasuredHealth(3800, 4000, -1));
		// Exactly at the threshold: the measured figure is returned.
		assertEquals(95, BatteryHealthTracker.computeMeasuredHealth(3800, 4000, threshold));
	}

	@Test
	public void accruePartialCycles_accumulatesPositiveDeltasWhileCharging() {
		// +50 (40 -> 90) while charging, no prior carry -> 0 cycles, carry 50
		final BatteryHealthTracker.CycleAccrual first =
				BatteryHealthTracker.accruePartialCycles(40, 90, true, 0);
		assertEquals(0, first.completedCycles());
		assertEquals(50, first.carryPercentPoints());

		// Another +50 on top of carry 50 -> exactly one cycle, carry resets to 0
		final BatteryHealthTracker.CycleAccrual second =
				BatteryHealthTracker.accruePartialCycles(40, 90, true, 50);
		assertEquals(1, second.completedCycles());
		assertEquals(0, second.carryPercentPoints());
	}

	@Test
	public void accruePartialCycles_ignoresDischargeFlatNotChargingAndUnknownPrev() {
		// Discharging (current < prev): no accrual, carry unchanged
		assertEquals(0, BatteryHealthTracker.accruePartialCycles(90, 40, true, 30).completedCycles());
		assertEquals(30, BatteryHealthTracker.accruePartialCycles(90, 40, true, 30).carryPercentPoints());
		// Flat level
		assertEquals(30, BatteryHealthTracker.accruePartialCycles(50, 50, true, 30).carryPercentPoints());
		// Not charging, even though the level rose
		assertEquals(30, BatteryHealthTracker.accruePartialCycles(40, 90, false, 30).carryPercentPoints());
		// Unknown previous level (-1): first observation only, nothing accrues
		assertEquals(30, BatteryHealthTracker.accruePartialCycles(-1, 90, true, 30).carryPercentPoints());
	}

	@Test
	public void isValidDesignCapacity_enforcesRange() {
		assertTrue(BatteryHealthTracker.isValidDesignCapacity(BatteryHealthTracker.MIN_DESIGN_CAPACITY_MAH));
		assertTrue(BatteryHealthTracker.isValidDesignCapacity(BatteryHealthTracker.MAX_DESIGN_CAPACITY_MAH));
		assertTrue(BatteryHealthTracker.isValidDesignCapacity(4000));
		assertFalse(BatteryHealthTracker.isValidDesignCapacity(BatteryHealthTracker.MIN_DESIGN_CAPACITY_MAH - 1));
		assertFalse(BatteryHealthTracker.isValidDesignCapacity(BatteryHealthTracker.MAX_DESIGN_CAPACITY_MAH + 1));
		assertFalse(BatteryHealthTracker.isValidDesignCapacity(0));
		assertFalse(BatteryHealthTracker.isValidDesignCapacity(-1));
	}

	@Test
	public void gradeForPercentage_bucketsMatchGrades() {
		assertEquals(BatteryHealthGrade.EXCELLENT, BatteryHealthTracker.gradeForPercentage(100));
		assertEquals(BatteryHealthGrade.EXCELLENT, BatteryHealthTracker.gradeForPercentage(90));
		assertEquals(BatteryHealthGrade.GOOD, BatteryHealthTracker.gradeForPercentage(89));
		assertEquals(BatteryHealthGrade.GOOD, BatteryHealthTracker.gradeForPercentage(80));
		assertEquals(BatteryHealthGrade.FAIR, BatteryHealthTracker.gradeForPercentage(79));
		assertEquals(BatteryHealthGrade.FAIR, BatteryHealthTracker.gradeForPercentage(70));
		assertEquals(BatteryHealthGrade.POOR, BatteryHealthTracker.gradeForPercentage(69));
		assertEquals(BatteryHealthGrade.POOR, BatteryHealthTracker.gradeForPercentage(0));
	}
}
