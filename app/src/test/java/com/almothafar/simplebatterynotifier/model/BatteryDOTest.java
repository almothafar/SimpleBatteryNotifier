package com.almothafar.simplebatterynotifier.model;

import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link BatteryDO} calculation logic and edge cases.
 */
@RunWith(Enclosed.class)
public class BatteryDOTest {

	/**
	 * {@link BatteryDO#getBatteryPercentage()} across normal, boundary and malformed inputs. A zero or
	 * negative scale must be guarded so it never yields Infinity/NaN, which would become
	 * Integer.MAX_VALUE when cast to int downstream.
	 */
	@RunWith(Parameterized.class)
	public static class GetBatteryPercentage {

		@Parameter(0) public String label;
		@Parameter(1) public int level;
		@Parameter(2) public int scale;
		@Parameter(3) public float expected;

		@Parameters(name = "{0}")
		public static Collection<Object[]> data() {
			return Arrays.asList(new Object[][]{
					{"normal 50/100", 50, 100, 50.0f},
					{"different scale 80/200", 80, 200, 40.0f},
					{"full battery", 100, 100, 100.0f},
					{"empty battery", 0, 100, 0.0f},
					{"decimal 33/100", 33, 100, 33.0f},
					// Malformed data must be guarded, not produce Infinity.
					{"zero scale guarded", 50, 0, 0.0f},
					{"negative scale guarded", 50, -1, 0.0f},
					// Defensive: out-of-range inputs still compute rather than crash.
					{"negative level", -10, 100, -10.0f},
					{"level exceeds scale", 150, 100, 150.0f},
			});
		}

		@Test
		public void matchesExpected() {
			final float result = new BatteryDO().setLevel(level).setScale(scale).getBatteryPercentage();
			assertEquals(label, expected, result, 0.01f);
		}
	}

	/**
	 * {@link BatteryDO#getBatteryPercentageInt()} — the app's single rounding policy (#158):
	 * truncation, so 19.6% reads as 19 for every integer consumer (alerts, rate samples, icons).
	 */
	@RunWith(Parameterized.class)
	public static class GetBatteryPercentageInt {

		@Parameter(0) public String label;
		@Parameter(1) public int level;
		@Parameter(2) public int scale;
		@Parameter(3) public int expected;

		@Parameters(name = "{0}")
		public static Collection<Object[]> data() {
			return Arrays.asList(new Object[][]{
					{"truncates 19.6 to 19 (not 20)", 196, 1000, 19},
					{"truncates 99.9 to 99", 999, 1000, 99},
					{"truncates 20.4 to 20", 204, 1000, 20},
					{"whole 48 stays 48", 48, 100, 48},
					{"full battery", 100, 100, 100},
					{"empty battery", 0, 100, 0},
					{"zero scale guarded", 50, 0, 0},
					{"negative scale guarded", 50, -1, 0},
			});
		}

		@Test
		public void matchesExpected() {
			assertEquals(label, expected, new BatteryDO().setLevel(level).setScale(scale).getBatteryPercentageInt());
		}
	}

	/**
	 * {@link BatteryDO#getPrecisePercentage()} / {@link BatteryDO#hasPrecisePercentage()} (#158/#204):
	 * the free fine-scale path; the synthesized path (counter ÷ <b>stable</b> capacity) with its
	 * in-bucket pass-through and out-of-bucket whole-percent fallback; and the integer fallback when
	 * the counter or the stable capacity is missing (#69/#94/#204).
	 */
	public static class PrecisePercentage {

		@Test
		public void fineScale_usesPlainFraction() {
			final BatteryDO battery = new BatteryDO().setLevel(8888).setScale(10000);
			assertTrue(battery.hasPrecisePercentage());
			assertEquals(88.88f, battery.getPrecisePercentage(), 0.001f);
		}

		@Test
		public void chargeCounter_fractionMovesAgainstFixedStableCapacity() {
			// The #204 regression pin: against a FIXED stable denominator the fraction must move as
			// the counter moves — the old per-tick denominator cancelled the counter out, so the
			// decimals were stuck at .00 forever.
			final BatteryDO battery = new BatteryDO().setLevel(40).setScale(100)
					.setStableCapacityMah(4000).setChargeCounterMicroAmpHours(1_620_000);
			assertTrue(battery.hasPrecisePercentage());
			assertEquals(40.5f, battery.getPrecisePercentage(), 0.001f);

			battery.setChargeCounterMicroAmpHours(1_630_000);
			assertEquals(40.75f, battery.getPrecisePercentage(), 0.001f);
		}

		@Test
		public void chargeCounter_synthesizesFractionWithinOsBucket() {
			// 3,525,000 µAh of a 4000 mAh stable capacity = 88.125%, inside the OS bucket [88, 89).
			final BatteryDO battery = new BatteryDO().setLevel(88).setScale(100)
					.setStableCapacityMah(4000).setChargeCounterMicroAmpHours(3_525_000);
			assertTrue(battery.hasPrecisePercentage());
			assertEquals(88.125f, battery.getPrecisePercentage(), 0.001f);
		}

		@Test
		public void chargeCounter_belowOsPercent_fallsBackToWholePercent() {
			// 3,480,000 µAh / 4000 mAh = 87.0% — outside the OS bucket [88, 89). No pinned fake
			// "88.00" anymore: the plain whole percent comes back and renders as a clean "88" (#204).
			final BatteryDO battery = new BatteryDO().setLevel(88).setScale(100)
					.setStableCapacityMah(4000).setChargeCounterMicroAmpHours(3_480_000);
			assertEquals(88.0f, battery.getPrecisePercentage(), 0.001f);
		}

		@Test
		public void chargeCounter_aboveOsBucket_fallsBackToWholePercent() {
			// 3,580,000 µAh / 4000 mAh = 89.5% — outside the OS bucket [88, 89). Was pinned to a
			// fake 88.99 ceiling before #204; now the whole percent comes back instead.
			final BatteryDO battery = new BatteryDO().setLevel(88).setScale(100)
					.setStableCapacityMah(4000).setChargeCounterMicroAmpHours(3_580_000);
			assertEquals(88.0f, battery.getPrecisePercentage(), 0.001f);
		}

		@Test
		public void chargeCounter_fractionJustBelowNextPercent_cappedAtPointNinetyNine() {
			// 88.9975% is genuinely in-bucket; the .99 cap only keeps the two-decimal rendering from
			// rounding up into the next whole percent.
			final BatteryDO battery = new BatteryDO().setLevel(88).setScale(100)
					.setStableCapacityMah(4000).setChargeCounterMicroAmpHours(3_559_900);
			assertEquals(88.99f, battery.getPrecisePercentage(), 0.001f);
		}

		@Test
		public void chargeCounter_atFull_neverExceedsHundred() {
			// OS says 100; a lagging counter (99.75%) is out of bucket and must still read exactly
			// 100, never 100.99.
			final BatteryDO battery = new BatteryDO().setLevel(100).setScale(100)
					.setStableCapacityMah(4000).setChargeCounterMicroAmpHours(3_990_000);
			assertEquals(100.0f, battery.getPrecisePercentage(), 0.001f);
		}

		@Test
		public void noChargeCounter_fallsBackToWholePercent() {
			final BatteryDO battery = new BatteryDO().setLevel(88).setScale(100).setStableCapacityMah(4000);
			assertFalse(battery.hasPrecisePercentage());
			assertEquals(88.0f, battery.getPrecisePercentage(), 0.001f);
		}

		@Test
		public void noStableCapacity_fallsBackToWholePercent() {
			// Stable capacity 0 = the learner is still warming up, or the counter is untrusted on
			// this device (#69/#94) — no fake precision either way.
			final BatteryDO battery = new BatteryDO().setLevel(88).setScale(100)
					.setStableCapacityMah(0).setChargeCounterMicroAmpHours(3_525_000);
			assertFalse(battery.hasPrecisePercentage());
			assertEquals(88.0f, battery.getPrecisePercentage(), 0.001f);
		}

		@Test
		public void instantCapacityAloneDoesNotSynthesize() {
			// The per-tick counter-derived estimate must never feed the fraction again — dividing
			// the counter by itself is the #204 bug.
			final BatteryDO battery = new BatteryDO().setLevel(88).setScale(100)
					.setCapacity(4000).setChargeCounterMicroAmpHours(3_525_000);
			assertFalse(battery.hasPrecisePercentage());
			assertEquals(88.0f, battery.getPrecisePercentage(), 0.001f);
		}
	}

	/**
	 * Builder chaining, decimal precision and the health-status enum are distinct concerns, kept as
	 * named tests.
	 */
	public static class Behaviour {

		@Test
		public void getBatteryPercentage_oneThird_maintainsPrecision() {
			assertEquals(33.333f, new BatteryDO().setLevel(1).setScale(3).getBatteryPercentage(), 0.001f);
		}

		@Test
		public void setters_returnThis_forMethodChaining() {
			final BatteryDO battery = new BatteryDO();
			final BatteryDO result = battery
					.setLevel(50)
					.setScale(100)
					.setStatus(3)
					.setPlugged(1);
			assertSame("Builder pattern should return same instance", battery, result);
		}

		@Test
		public void healthStatus_defaultsToUnknown() {
			assertEquals(BatteryHealthStatus.UNKNOWN, new BatteryDO().getHealthStatus());
		}

		@Test
		public void setHealthStatus_storesCorrectValue() {
			final BatteryDO battery = new BatteryDO();
			battery.setHealthStatus(BatteryHealthStatus.CRITICAL);
			assertEquals(BatteryHealthStatus.CRITICAL, battery.getHealthStatus());
		}
	}
}
