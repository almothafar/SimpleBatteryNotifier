package com.almothafar.simplebatterynotifier.service;

import android.os.BatteryManager;

import com.almothafar.simplebatterynotifier.model.BatteryDO;
import com.almothafar.simplebatterynotifier.service.BatteryRateTracker.BatteryRate;
import com.almothafar.simplebatterynotifier.service.BatteryRateTracker.Sample;

import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the pure charge/drain-rate logic in {@link BatteryRateTracker} (issue #108).
 * Currents are in µA; times in millis. The android-free helpers carry the feature's correctness.
 */
@RunWith(Enclosed.class)
public class BatteryRateTrackerTest {

	private static final int NO_CURRENT = Integer.MIN_VALUE;

	/**
	 * {@link BatteryRateTracker#computeRate}: source selection (current vs level), the warm-up/static
	 * gates, the plausibility ceiling, and the independent current gating.
	 */
	public static class ComputeRate {

		@Test
		public void sourceA_dischargingFromAveragedCurrent() {
			final List<Sample> window = Arrays.asList(
					new Sample(0, 50, -800_000),
					new Sample(30_000, 50, -800_000),
					new Sample(60_000, 50, -800_000));
			final BatteryRate rate = BatteryRateTracker.computeRate(window, 4000, false, 60_000, -800_000);

			assertTrue(rate.hasRate());
			assertEquals(20, rate.percentPerHour()); // 800mA / 4000mAh * 100
			assertFalse(rate.charging());
			assertTrue(rate.hasCurrent());
			assertEquals(-800, rate.currentMilliAmps());
		}

		@Test
		public void sourceA_chargingSignsCurrentPositive() {
			final List<Sample> window = Arrays.asList(
					new Sample(0, 40, 800_000),
					new Sample(30_000, 40, 800_000),
					new Sample(60_000, 40, 800_000));
			final BatteryRate rate = BatteryRateTracker.computeRate(window, 4000, true, 60_000, 800_000);

			assertTrue(rate.hasRate());
			assertEquals(20, rate.percentPerHour());
			assertTrue(rate.charging());
			assertEquals(800, rate.currentMilliAmps());
		}

		@Test
		public void sourceA_takesPrecedenceOverLevelDelta() {
			// Level drops 8% over 4 min (source B would read ~120%/h), but a trustworthy current wins.
			final List<Sample> window = Arrays.asList(
					new Sample(0, 60, -800_000),
					new Sample(60_000, 58, -800_000),
					new Sample(120_000, 56, -800_000),
					new Sample(180_000, 54, -800_000),
					new Sample(240_000, 52, -800_000));
			final BatteryRate rate = BatteryRateTracker.computeRate(window, 4000, false, 240_000, -800_000);

			assertTrue(rate.hasRate());
			assertEquals(20, rate.percentPerHour());
		}

		@Test
		public void sourceB_levelOverTimeWhenNoCapacityOrCurrent() {
			// Kirin-style: capacity untrusted (0) and no usable current — the level-delta path carries it.
			final List<Sample> window = Arrays.asList(
					new Sample(0, 50, NO_CURRENT),
					new Sample(360_000, 47, NO_CURRENT));
			final BatteryRate rate = BatteryRateTracker.computeRate(window, 0, false, 360_000, NO_CURRENT);

			assertTrue(rate.hasRate());
			assertEquals(30, rate.percentPerHour()); // 3% over 0.1h
			assertFalse(rate.hasCurrent());
		}

		@Test
		public void warmUp_singleSampleHasNoRate() {
			final List<Sample> window = Arrays.asList(new Sample(0, 50, NO_CURRENT));
			final BatteryRate rate = BatteryRateTracker.computeRate(window, 4000, false, 0, NO_CURRENT);

			assertFalse(rate.hasRate());
			assertFalse(rate.hasCurrent());
		}

		@Test
		public void warmUp_shortSpanWithoutCurrentHasNoRate() {
			final List<Sample> window = Arrays.asList(
					new Sample(0, 50, NO_CURRENT),
					new Sample(30_000, 50, NO_CURRENT));
			final BatteryRate rate = BatteryRateTracker.computeRate(window, 0, false, 30_000, NO_CURRENT);

			assertFalse(rate.hasRate());
		}

		@Test
		public void staticLevel_hasNoRate() {
			final List<Sample> window = Arrays.asList(
					new Sample(0, 50, NO_CURRENT),
					new Sample(360_000, 50, NO_CURRENT));
			final BatteryRate rate = BatteryRateTracker.computeRate(window, 0, false, 360_000, NO_CURRENT);

			assertFalse(rate.hasRate());
		}

		@Test
		public void garbageRate_rejectedButCurrentStillShown() {
			// 3000mA on a 500mAh battery => 600%/h, past the ceiling: reject the rate, keep the mA.
			final List<Sample> window = Arrays.asList(
					new Sample(0, 50, -3_000_000),
					new Sample(30_000, 50, -3_000_000),
					new Sample(60_000, 50, -3_000_000));
			final BatteryRate rate = BatteryRateTracker.computeRate(window, 500, false, 60_000, -3_000_000);

			assertFalse(rate.hasRate());
			assertTrue(rate.hasCurrent());
			assertEquals(-3000, rate.currentMilliAmps());
		}

		@Test
		public void negligibleRate_roundsToZeroAndHides() {
			final List<Sample> window = Arrays.asList(
					new Sample(0, 50, -10_000),
					new Sample(30_000, 50, -10_000),
					new Sample(60_000, 50, -10_000));
			final BatteryRate rate = BatteryRateTracker.computeRate(window, 4000, false, 60_000, -10_000);

			assertFalse(rate.hasRate()); // 0.25%/h rounds to 0
			assertTrue(rate.hasCurrent());
			assertEquals(-10, rate.currentMilliAmps());
		}

		@Test
		public void currentZero_isHiddenWhileRateStillShown() {
			final List<Sample> window = Arrays.asList(
					new Sample(0, 50, NO_CURRENT),
					new Sample(360_000, 47, NO_CURRENT));
			final BatteryRate rate = BatteryRateTracker.computeRate(window, 0, false, 360_000, 0);

			assertTrue(rate.hasRate());
			assertFalse(rate.hasCurrent()); // 0 mA is not worth showing
		}

		@Test
		public void currentUnsupported_isHidden() {
			final List<Sample> window = Arrays.asList(
					new Sample(0, 50, NO_CURRENT),
					new Sample(360_000, 47, NO_CURRENT));
			final BatteryRate rate = BatteryRateTracker.computeRate(window, 0, false, 360_000, NO_CURRENT);

			assertFalse(rate.hasCurrent());
		}
	}

	/**
	 * {@link BatteryRateTracker#signedCurrentMilliAmps}: magnitude from the reading, sign from the
	 * charging direction (so an OEM's inverted sign convention can't flip the displayed sign).
	 */
	@RunWith(Parameterized.class)
	public static class CurrentSign {

		@Parameter(0) public String label;
		@Parameter(1) public int microAmps;
		@Parameter(2) public boolean charging;
		@Parameter(3) public int expectedMilliAmps;

		@Parameters(name = "{0}")
		public static Collection<Object[]> data() {
			return Arrays.asList(new Object[][]{
					{"discharging negative reading", -450_000, false, -450},
					{"charging positive reading", 900_000, true, 900},
					{"charging but device reports negative -> sign from status", -450_000, true, 450},
					{"discharging but device reports positive -> sign from status", 450_000, false, -450},
			});
		}

		@Test
		public void matchesExpected() {
			assertEquals(label, expectedMilliAmps, BatteryRateTracker.signedCurrentMilliAmps(microAmps, charging));
		}
	}

	/**
	 * {@link BatteryRateTracker#isPlausibleCurrentMicroAmps}: unsupported/out-of-range readings rejected.
	 */
	@RunWith(Parameterized.class)
	public static class CurrentPlausibility {

		@Parameter(0) public String label;
		@Parameter(1) public int microAmps;
		@Parameter(2) public boolean expected;

		@Parameters(name = "{0}")
		public static Collection<Object[]> data() {
			return Arrays.asList(new Object[][]{
					{"unsupported MIN_VALUE", Integer.MIN_VALUE, false},
					{"MAX_VALUE sentinel", Integer.MAX_VALUE, false},
					{"zero is a valid reading", 0, true},
					{"typical discharge", -500_000, true},
					{"typical charge", 900_000, true},
					{"20A is implausible", 20_000_000, false},
			});
		}

		@Test
		public void matchesExpected() {
			assertEquals(label, expected, BatteryRateTracker.isPlausibleCurrentMicroAmps(microAmps));
		}
	}

	/**
	 * {@link BatteryRateTracker#amberThreshold}: the amber line sits ~0.75x below the red limit.
	 */
	@RunWith(Parameterized.class)
	public static class AmberThreshold {

		@Parameter(0) public int limit;
		@Parameter(1) public int expected;

		@Parameters(name = "limit={0} -> amber={1}")
		public static Collection<Object[]> data() {
			return Arrays.asList(new Object[][]{
					{20, 15},
					{25, 19}, // round(18.75)
					{10, 8},  // round(7.5)
					{40, 30},
			});
		}

		@Test
		public void matchesExpected() {
			assertEquals(expected, BatteryRateTracker.amberThreshold(limit));
		}
	}

	/**
	 * {@link BatteryRateTracker#isChargingDirection}: charging and full map to the charging direction.
	 */
	@RunWith(Parameterized.class)
	public static class Direction {

		@Parameter(0) public String label;
		@Parameter(1) public int status;
		@Parameter(2) public boolean expected;

		@Parameters(name = "{0}")
		public static Collection<Object[]> data() {
			return Arrays.asList(new Object[][]{
					{"charging", BatteryManager.BATTERY_STATUS_CHARGING, true},
					{"full", BatteryManager.BATTERY_STATUS_FULL, true},
					{"discharging", BatteryManager.BATTERY_STATUS_DISCHARGING, false},
					{"not charging", BatteryManager.BATTERY_STATUS_NOT_CHARGING, false},
					{"unknown", BatteryManager.BATTERY_STATUS_UNKNOWN, false},
			});
		}

		@Test
		public void matchesExpected() {
			assertEquals(label, expected, BatteryRateTracker.isChargingDirection(status));
		}
	}

	/**
	 * {@link BatteryRateTracker#serializeSamples}/{@link BatteryRateTracker#parseSamples}: round-trip and
	 * malformed-entry skipping (validated, not caught — the project's "no silent catch" rule).
	 */
	public static class Serialization {

		@Test
		public void roundTripPreservesSamples() {
			final List<Sample> samples = Arrays.asList(
					new Sample(100, 50, -800_000),
					new Sample(200, 49, NO_CURRENT));
			final List<Sample> parsed = BatteryRateTracker.parseSamples(BatteryRateTracker.serializeSamples(samples));

			assertEquals(samples, parsed);
		}

		@Test
		public void emptyStringParsesToEmpty() {
			assertTrue(BatteryRateTracker.parseSamples("").isEmpty());
		}

		@Test
		public void malformedEntriesAreSkipped() {
			final List<Sample> parsed = BatteryRateTracker.parseSamples("100:50:x;bad;200:60:-800000");

			assertEquals(1, parsed.size());
			assertEquals(new Sample(200, 60, -800_000), parsed.get(0));
		}

		@Test
		public void overflowingEntriesAreSkippedNotThrown() {
			// "9999999999" fits the 10-digit int shape but overflows Integer.parseInt; a 19-digit
			// timestamp overflows Long.parseLong. Both must be skipped, not crash the receiver.
			final List<Sample> parsed = BatteryRateTracker.parseSamples(
					"100:50:9999999999;9999999999999999999:50:-800000;-100:-9999999999:0;200:60:-800000");

			assertEquals(1, parsed.size());
			assertEquals(new Sample(200, 60, -800_000), parsed.get(0));
		}

		@Test
		public void intBoundaryValuesSurviveParsing() {
			// The "no current" sentinel is Integer.MIN_VALUE itself — the overflow guard must keep
			// accepting the full int range, not just 9-digit values.
			final List<Sample> samples = Arrays.asList(
					new Sample(100, 50, Integer.MIN_VALUE),
					new Sample(200, 49, Integer.MAX_VALUE));
			final List<Sample> parsed = BatteryRateTracker.parseSamples(BatteryRateTracker.serializeSamples(samples));

			assertEquals(samples, parsed);
		}
	}

	/**
	 * {@link BatteryRateTracker#hasUsableLevel}: snapshots whose level/scale extras defaulted to -1
	 * (unavailable) must not be recorded — they'd read as a bogus 0% sample.
	 */
	public static class UsableLevel {

		@Test
		public void validReadingIsUsable() {
			assertTrue(BatteryRateTracker.hasUsableLevel(new BatteryDO().setLevel(50).setScale(100)));
		}

		@Test
		public void missingScaleIsNotUsable() {
			assertFalse(BatteryRateTracker.hasUsableLevel(new BatteryDO().setLevel(50).setScale(-1)));
			assertFalse(BatteryRateTracker.hasUsableLevel(new BatteryDO().setLevel(50).setScale(0)));
		}

		@Test
		public void missingLevelIsNotUsable() {
			assertFalse(BatteryRateTracker.hasUsableLevel(new BatteryDO().setLevel(-1).setScale(100)));
		}
	}

	/**
	 * {@link BatteryRateTracker#trimToWindow}: stale and future-dated samples are dropped, so a window
	 * persisted before a shutdown can't surface as a current rate when read back at boot.
	 */
	public static class TrimToWindow {

		@Test
		public void staleSamplesAreDropped() {
			// Samples from "last night" (8h before now) must not survive the load.
			final long now = 8L * 60 * 60 * 1000;
			final List<Sample> stale = Arrays.asList(new Sample(0, 80, -500_000), new Sample(600_000, 78, -500_000));

			assertTrue(BatteryRateTracker.trimToWindow(stale, now).isEmpty());
		}

		@Test
		public void freshSamplesAreKept() {
			final List<Sample> window = Arrays.asList(
					new Sample(0, 50, -500_000), new Sample(300_000, 49, -500_000));

			assertEquals(window, BatteryRateTracker.trimToWindow(window, 300_000));
		}

		@Test
		public void futureDatedSamplesAreDropped() {
			// A backwards clock jump leaves samples "from the future"; they must not be kept.
			final List<Sample> window = Arrays.asList(new Sample(500_000, 50, -500_000));

			assertTrue(BatteryRateTracker.trimToWindow(window, 100_000).isEmpty());
		}
	}

	/**
	 * {@link BatteryRateTracker#clampDrainLimit}: a stored limit outside the slider's range is clamped,
	 * so a corrupt preference can't skew the red line or the #109 trigger.
	 */
	@RunWith(Parameterized.class)
	public static class ClampDrainLimit {

		@Parameter(0) public int stored;
		@Parameter(1) public int expected;

		@Parameters(name = "stored={0} -> {1}")
		public static Collection<Object[]> data() {
			return Arrays.asList(new Object[][]{
					{20, 20},                                          // in range: unchanged
					{BatteryRateTracker.MIN_DRAIN_LIMIT_PPH, 5},       // boundaries kept
					{BatteryRateTracker.MAX_DRAIN_LIMIT_PPH, 60},
					{0, 5},                                            // below min: clamped up
					{-3, 5},
					{999, 60},                                         // above max: clamped down
			});
		}

		@Test
		public void matchesExpected() {
			assertEquals(expected, BatteryRateTracker.clampDrainLimit(stored));
		}
	}

	/**
	 * {@link BatteryRateTracker#appendAndTrim}: the min-spacing throttle and the trailing-window age trim.
	 */
	public static class Windowing {

		@Test
		public void appendsFirstSample() {
			final List<Sample> result = BatteryRateTracker.appendAndTrim(
					List.of(), new Sample(0, 50, -800_000), 0);
			assertEquals(1, result.size());
		}

		@Test
		public void throttlesSamplesCloserThanMinSpacing() {
			final List<Sample> window = List.of(new Sample(0, 50, -800_000));
			final List<Sample> result = BatteryRateTracker.appendAndTrim(window, new Sample(10_000, 50, -800_000), 10_000);
			assertEquals(1, result.size()); // 10s < 20s spacing: not added
		}

		@Test
		public void appendsOnceSpacingIsMet() {
			final List<Sample> window = List.of(new Sample(0, 50, -800_000));
			final List<Sample> result = BatteryRateTracker.appendAndTrim(window, new Sample(20_000, 50, -800_000), 20_000);
			assertEquals(2, result.size());
		}

		@Test
		public void dropsSamplesOlderThanWindow() {
			final List<Sample> window = List.of(new Sample(0, 50, -800_000));
			final List<Sample> result = BatteryRateTracker.appendAndTrim(window, new Sample(700_000, 45, -800_000), 700_000);
			assertEquals(1, result.size()); // the 0-time sample is past the 10-min window
			assertEquals(700_000, result.get(0).timeMillis());
		}
	}
}
