package com.almothafar.simplebatterynotifier.ui.preference;

import com.almothafar.simplebatterynotifier.model.LevelThresholds;

import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the pure (context-free) clamping logic in {@link BatteryRangeSliderHelper}, which
 * guarantees the critical/warning pair is always inside the slider's bounds and at least
 * {@link BatteryRangeSliderHelper#MIN_SEPARATION} apart — the invariant that replaces the old
 * cross-field validation.
 */
@RunWith(Enclosed.class)
public class BatteryRangeSliderHelperTest {

	private static final int FROM = BatteryRangeSliderHelper.LEVEL_FROM;
	private static final int TO = BatteryRangeSliderHelper.LEVEL_TO;
	private static final int SEP = BatteryRangeSliderHelper.MIN_SEPARATION;

	/**
	 * {@link BatteryRangeSliderHelper#clampPair(LevelThresholds, int, int, int)} maps representative
	 * (critical, warning) inputs — valid, out-of-range, inverted, and too-close — to the expected
	 * clamped pair.
	 */
	@RunWith(Parameterized.class)
	public static class ClampPair {

		@Parameter(0) public int critical;
		@Parameter(1) public int warning;
		@Parameter(2) public int expectedCritical;
		@Parameter(3) public int expectedWarning;

		@Parameters(name = "clampPair({0},{1}) = [{2},{3}]")
		public static Collection<Object[]> data() {
			return Arrays.asList(new Object[][]{
					{20, 40, 20, 40},   // already-valid defaults, untouched
					{10, 50, 10, 50},   // full range, untouched
					{5, 40, 10, 40},    // critical below floor -> raised to floor
					{20, 60, 20, 50},   // warning above ceiling -> lowered to ceiling
					{30, 30, 30, 35},   // equal -> warning pushed up to keep the gap
					{40, 20, 40, 45},   // inverted -> warning pushed above critical
					{48, 49, 45, 50},   // too close near the ceiling -> both shifted down
					{2, 4, 10, 15},     // both below floor
					{60, 70, 45, 50},   // both above ceiling
			});
		}

		@Test
		public void clampsToExpectedPair() {
			final LevelThresholds result = BatteryRangeSliderHelper.clampPair(
					new LevelThresholds(critical, warning), FROM, TO, SEP);
			assertEquals("critical", expectedCritical, result.critical());
			assertEquals("warning", expectedWarning, result.warning());
		}
	}

	/**
	 * Whatever the input — including corrupted values far outside the range — the result must always
	 * be a usable pair the slider can accept without tripping its own validation.
	 */
	public static class Invariants {

		@Test
		public void everyInputYieldsAValidPair() {
			for (int critical = -20; critical <= 80; critical++) {
				for (int warning = -20; warning <= 80; warning++) {
					final LevelThresholds r = BatteryRangeSliderHelper.clampPair(
							new LevelThresholds(critical, warning), FROM, TO, SEP);
					final String at = "(" + critical + "," + warning + ")";
					assertTrue("critical in range at " + at, r.critical() >= FROM && r.critical() <= TO);
					assertTrue("warning in range at " + at, r.warning() >= FROM && r.warning() <= TO);
					assertTrue("separation kept at " + at, r.warning() - r.critical() >= SEP);
					assertTrue("critical below warning at " + at, r.critical() < r.warning());
				}
			}
		}
	}
}
