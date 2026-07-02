package com.almothafar.simplebatterynotifier.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the pure, Android-free helpers in {@link BatteryHealthTracker}.
 */
public class BatteryHealthTrackerTest {

	@Test
	public void computeMeasuredHealth_typicalReadings() {
		// 3800 mAh measured against a 4000 mAh design capacity -> 95%
		assertEquals(95, BatteryHealthTracker.computeMeasuredHealth(3800, 4000));
		// A healthy battery measuring at its rated capacity -> 100%
		assertEquals(100, BatteryHealthTracker.computeMeasuredHealth(4000, 4000));
		// Worn battery: 3000 of 5000 -> 60%
		assertEquals(60, BatteryHealthTracker.computeMeasuredHealth(3000, 5000));
	}

	@Test
	public void computeMeasuredHealth_clampsToRange() {
		// Measured above rated (fresh cell / rounding) is clamped to 100
		assertEquals(100, BatteryHealthTracker.computeMeasuredHealth(4200, 4000));
		// Never reports 0; the smallest positive result is 1
		assertEquals(1, BatteryHealthTracker.computeMeasuredHealth(1, 15000));
	}

	@Test
	public void computeMeasuredHealth_unusableInputs_returnsMinusOne() {
		assertEquals(-1, BatteryHealthTracker.computeMeasuredHealth(0, 4000));
		assertEquals(-1, BatteryHealthTracker.computeMeasuredHealth(3800, 0));
		assertEquals(-1, BatteryHealthTracker.computeMeasuredHealth(-5, 4000));
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
	public void statusForPercentage_bucketsMatchLabels() {
		assertEquals("Excellent", BatteryHealthTracker.statusForPercentage(100));
		assertEquals("Excellent", BatteryHealthTracker.statusForPercentage(90));
		assertEquals("Good", BatteryHealthTracker.statusForPercentage(89));
		assertEquals("Good", BatteryHealthTracker.statusForPercentage(80));
		assertEquals("Fair", BatteryHealthTracker.statusForPercentage(79));
		assertEquals("Fair", BatteryHealthTracker.statusForPercentage(70));
		assertEquals("Poor", BatteryHealthTracker.statusForPercentage(69));
		assertEquals("Poor", BatteryHealthTracker.statusForPercentage(0));
	}
}
