package com.almothafar.simplebatterynotifier.service;

import com.almothafar.simplebatterynotifier.model.BatteryHealthGrade;

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
 * Unit tests for the pure, Android-free helpers in {@link BatteryHealthTracker}.
 */
@RunWith(Enclosed.class)
public class BatteryHealthTrackerTest {

	/**
	 * {@link BatteryHealthTracker#estimatedHealthForCycles(int)} — the cycle-based degradation curve
	 * (#161 extracted it as a pure core so one cycle read serves every figure on a screen).
	 */
	@RunWith(Parameterized.class)
	public static class EstimatedHealthForCycles {

		@Parameter(0) public String label;
		@Parameter(1) public int cycles;
		@Parameter(2) public int expected;

		@Parameters(name = "{0}")
		public static Collection<Object[]> data() {
			return Arrays.asList(new Object[][]{
					{"new battery -> 100%", 0, 100},
					{"mid-excellent 150 -> 98%", 150, 98},
					{"excellent boundary 300 -> 95%", 300, 95},
					{"mid-good 400 -> 90%", 400, 90},
					{"good boundary 500 -> 85%", 500, 85},
					{"mid-fair 650 -> 78%", 650, 78},
					{"fair boundary 800 -> 70%", 800, 70},
					{"deep poor 1300 -> 40%", 1300, 40},
					{"floor holds at 40%", 2000, 40},
			});
		}

		@Test
		public void matchesExpected() {
			assertEquals(label, expected, BatteryHealthTracker.estimatedHealthForCycles(cycles));
		}
	}

	/**
	 * {@link BatteryHealthTracker#gradeForCycles(int)} — the cycle-count bucket boundaries, which must
	 * stay consistent with the {@link BatteryHealthTracker#estimatedHealthForCycles(int)} curve.
	 */
	@RunWith(Parameterized.class)
	public static class GradeForCycles {

		@Parameter(0) public int cycles;
		@Parameter(1) public BatteryHealthGrade expected;

		@Parameters(name = "{0} cycles -> {1}")
		public static Collection<Object[]> data() {
			return Arrays.asList(new Object[][]{
					{0, BatteryHealthGrade.EXCELLENT},
					{299, BatteryHealthGrade.EXCELLENT},
					{300, BatteryHealthGrade.GOOD},
					{499, BatteryHealthGrade.GOOD},
					{500, BatteryHealthGrade.FAIR},
					{799, BatteryHealthGrade.FAIR},
					{800, BatteryHealthGrade.POOR},
					{2000, BatteryHealthGrade.POOR},
			});
		}

		@Test
		public void matchesExpected() {
			assertEquals(expected, BatteryHealthTracker.gradeForCycles(cycles));
		}
	}

	/**
	 * {@link BatteryHealthTracker#computeMeasuredHealth(int, int)} — measured capacity as a percentage
	 * of design capacity, clamped to 1..100, or -1 when the inputs are unusable.
	 */
	@RunWith(Parameterized.class)
	public static class ComputeMeasuredHealth {

		@Parameter(0) public String label;
		@Parameter(1) public int measuredMah;
		@Parameter(2) public int designMah;
		@Parameter(3) public int expected;

		@Parameters(name = "{0}")
		public static Collection<Object[]> data() {
			return Arrays.asList(new Object[][]{
					{"3800 of 4000 -> 95%", 3800, 4000, 95},
					{"rated capacity -> 100%", 4000, 4000, 100},
					{"worn 3000 of 5000 -> 60%", 3000, 5000, 60},
					// #103: shown regardless of charge level; ~4400 of a 4700 design reads 94%.
					{"4400 of 4700 -> 94%", 4400, 4700, 94},
					// Clamped to range: above rated -> 100, tiny positive -> 1 (never 0).
					{"above rated clamped to 100", 4200, 4000, 100},
					{"smallest positive is 1", 1, 15000, 1},
					// Unusable inputs -> -1.
					{"zero measured", 0, 4000, -1},
					{"zero design", 3800, 0, -1},
					{"negative measured", -5, 4000, -1},
			});
		}

		@Test
		public void matchesExpected() {
			assertEquals(label, expected, BatteryHealthTracker.computeMeasuredHealth(measuredMah, designMah));
		}
	}

	/**
	 * {@link BatteryHealthTracker#gradeForPercentage(int)} — bucket boundaries mapping a health
	 * percentage to a {@link BatteryHealthGrade}.
	 */
	@RunWith(Parameterized.class)
	public static class GradeForPercentage {

		@Parameter(0) public int percentage;
		@Parameter(1) public BatteryHealthGrade expected;

		@Parameters(name = "{0}% -> {1}")
		public static Collection<Object[]> data() {
			return Arrays.asList(new Object[][]{
					{100, BatteryHealthGrade.EXCELLENT},
					{90, BatteryHealthGrade.EXCELLENT},
					{89, BatteryHealthGrade.GOOD},
					{80, BatteryHealthGrade.GOOD},
					{79, BatteryHealthGrade.FAIR},
					{70, BatteryHealthGrade.FAIR},
					{69, BatteryHealthGrade.POOR},
					{0, BatteryHealthGrade.POOR},
			});
		}

		@Test
		public void matchesExpected() {
			assertEquals(expected, BatteryHealthTracker.gradeForPercentage(percentage));
		}
	}

	/**
	 * {@link BatteryHealthTracker#isValidDesignCapacity(int)} — accepts only capacities inside the
	 * supported mAh range.
	 */
	@RunWith(Parameterized.class)
	public static class IsValidDesignCapacity {

		@Parameter(0) public String label;
		@Parameter(1) public int capacityMah;
		@Parameter(2) public boolean expected;

		@Parameters(name = "{0}")
		public static Collection<Object[]> data() {
			return Arrays.asList(new Object[][]{
					{"min boundary", BatteryHealthTracker.MIN_DESIGN_CAPACITY_MAH, true},
					{"max boundary", BatteryHealthTracker.MAX_DESIGN_CAPACITY_MAH, true},
					{"typical 4000", 4000, true},
					{"below min", BatteryHealthTracker.MIN_DESIGN_CAPACITY_MAH - 1, false},
					{"above max", BatteryHealthTracker.MAX_DESIGN_CAPACITY_MAH + 1, false},
					{"zero", 0, false},
					{"negative", -1, false},
			});
		}

		@Test
		public void matchesExpected() {
			assertEquals(label, expected, BatteryHealthTracker.isValidDesignCapacity(capacityMah));
		}
	}

	/**
	 * {@link BatteryHealthTracker#isEstimateImplausible(int, int)} — flags measured estimates that sit
	 * too far from the design capacity to be trusted (issue #94).
	 */
	@RunWith(Parameterized.class)
	public static class IsEstimateImplausible {

		@Parameter(0) public String label;
		@Parameter(1) public int estimateMah;
		@Parameter(2) public int designMah;
		@Parameter(3) public boolean expected;

		@Parameters(name = "{0}")
		public static Collection<Object[]> data() {
			return Arrays.asList(new Object[][]{
					// Far from design -> implausible. An 852 mAh estimate against a 4000 mAh design is
					// implausibly low (the real #94 case); 5000 against 4000 is impossibly high.
					{"852 of 4000 implausibly low", 852, 4000, true},
					{"5000 of 4000 impossibly high", 5000, 4000, true},
					// Healthy / lightly worn batteries stay within the plausibility window.
					{"rated capacity", 4000, 4000, false},
					{"lightly worn 3800", 3800, 4000, false},
					{"genuinely worn 2000 (50%)", 2000, 4000, false},
					// Missing inputs are "unavailable", not "implausible".
					{"no estimate (0)", 0, 4000, false},
					{"no design capacity", 852, 0, false},
					{"negative estimate", -5, 4000, false},
			});
		}

		@Test
		public void matchesExpected() {
			assertEquals(label, expected, BatteryHealthTracker.isEstimateImplausible(estimateMah, designMah));
		}
	}

	/**
	 * {@link BatteryHealthTracker#accruePartialCycles(int, int, boolean, int)} carries partial charge
	 * deltas across calls and only counts a full cycle once 100 percentage-points have accrued while
	 * charging. Its stateful, two-step behaviour reads clearer as named scenarios than as a data table.
	 */
	public static class AccruePartialCycles {

		@Test
		public void accumulatesPositiveDeltasWhileCharging() {
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
		public void ignoresDischargeFlatNotChargingAndUnknownPrev() {
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
	}
}
