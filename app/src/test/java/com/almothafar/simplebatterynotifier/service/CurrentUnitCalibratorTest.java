package com.almothafar.simplebatterynotifier.service;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.almothafar.simplebatterynotifier.service.CurrentUnitCalibrator.Observation;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the CURRENT_NOW unit auto-calibration (issue #152 v2). The pure helpers — the
 * observation fold, the mA-units conclusion, and the scaling — carry the feature's correctness;
 * currents are raw {@code getIntProperty} values, times in millis.
 */
@RunWith(Enclosed.class)
public class CurrentUnitCalibratorTest {

	private static final int SAMPLES = CurrentUnitCalibrator.MIN_OBSERVED_SAMPLES;
	private static final int FLOOR = CurrentUnitCalibrator.MICRO_AMP_FLOOR_RAW;
	private static final long SPACING = CurrentUnitCalibrator.MIN_COUNT_SPACING_MS;

	/**
	 * {@link CurrentUnitCalibrator#observe}: the max-tracking, the count spacing rule, the count cap,
	 * and the unchanged-instance contract the persist-on-change relies on.
	 */
	public static class Observe {

		@Test
		public void firstReadingCountsAndSetsMax() {
			final Observation result = CurrentUnitCalibrator.observe(new Observation(0, 0, 0), -800, SPACING);

			assertEquals(new Observation(800, 1, SPACING), result);
		}

		@Test
		public void maxTracksMagnitudeRegardlessOfSign() {
			final Observation result = CurrentUnitCalibrator.observe(new Observation(800, 1, 0), -1_500, SPACING);

			assertEquals(1_500, result.maxAbsRaw());
		}

		@Test
		public void closelySpacedReadingIsNotCounted() {
			final Observation previous = new Observation(800, 1, 100_000);
			final Observation result = CurrentUnitCalibrator.observe(previous, -700, 100_000 + SPACING - 1);

			assertEquals(1, result.observedCount());
		}

		@Test
		public void closelySpacedReadingStillRaisesMax() {
			// The max must never miss a large reading, even inside the count-spacing window — it is
			// what protects a genuine-µA device from being mislabeled.
			final Observation previous = new Observation(800, 1, 100_000);
			final Observation result = CurrentUnitCalibrator.observe(previous, -250_000, 100_000 + 1);

			assertEquals(250_000, result.maxAbsRaw());
			assertEquals(1, result.observedCount());
		}

		@Test
		public void spacedReadingIsCounted() {
			final Observation previous = new Observation(800, 1, 100_000);
			final Observation result = CurrentUnitCalibrator.observe(previous, -700, 100_000 + SPACING);

			assertEquals(2, result.observedCount());
			assertEquals(100_000 + SPACING, result.lastObservedAt());
		}

		@Test
		public void countStopsAtTheCap() {
			// Once concluded-or-not, counting stops for good — this is what bounds lifetime pref writes.
			final Observation previous = new Observation(800, SAMPLES, 100_000);
			final Observation result = CurrentUnitCalibrator.observe(previous, -700, 100_000 + 10 * SPACING);

			assertSame(previous, result);
		}

		@Test
		public void unchangedObservationReturnsSameInstance() {
			final Observation previous = new Observation(800, 1, 100_000);
			final Observation result = CurrentUnitCalibrator.observe(previous, -700, 100_000 + 1);

			assertSame(previous, result); // caller skips the persist
		}
	}

	/**
	 * {@link CurrentUnitCalibrator#isMilliAmpUnits}: the conclusion needs both enough spaced readings
	 * and a maximum still below the µA floor.
	 */
	@RunWith(Parameterized.class)
	public static class IsMilliAmpUnits {

		@Parameter(0) public String label;
		@Parameter(1) public int maxAbsRaw;
		@Parameter(2) public int observedCount;
		@Parameter(3) public boolean expected;

		@Parameters(name = "{0}")
		public static Collection<Object[]> data() {
			return Arrays.asList(new Object[][]{
					{"Kirin-like max after full observation", 2_000, SAMPLES, true},
					{"just below the floor after full observation", FLOOR - 1, SAMPLES, true},
					{"at the floor: genuine µA", FLOOR, SAMPLES, false},
					{"typical µA device (screen-on draw)", 250_000, SAMPLES, false},
					{"not enough observation yet", 2_000, SAMPLES - 1, false},
					{"nothing observed", 0, 0, false},
			});
		}

		@Test
		public void matchesExpected() {
			assertEquals(label, expected,
					CurrentUnitCalibrator.isMilliAmpUnits(new Observation(maxAbsRaw, observedCount, 0)));
		}
	}

	/**
	 * {@link CurrentUnitCalibrator#scaledMicroAmps}: &times;1000 under a mA conclusion, untouched otherwise.
	 */
	public static class ScaledMicroAmps {

		@Test
		public void milliAmpUnitsScaleUp() {
			assertEquals(-800_000, CurrentUnitCalibrator.scaledMicroAmps(-800, true));
			assertEquals(1_500_000, CurrentUnitCalibrator.scaledMicroAmps(1_500, true));
		}

		@Test
		public void microAmpUnitsPassThrough() {
			assertEquals(-800_000, CurrentUnitCalibrator.scaledMicroAmps(-800_000, false));
		}
	}

	/**
	 * {@link CurrentUnitCalibrator#observeAndScale}: the Context-backed entry point — sentinel/blank
	 * passthrough and the not-yet-concluded default. The decision/scaling math is covered by the pure
	 * tests above; this exercises the wiring under Robolectric like the other Context-backed helpers.
	 */
	@RunWith(RobolectricTestRunner.class)
	@Config(sdk = 34)
	public static class ObserveAndScale {

		private Context context;

		@Before
		public void setUp() {
			context = ApplicationProvider.getApplicationContext();
		}

		@Test
		public void unsupportedSentinelPassesThrough() {
			assertEquals(Integer.MIN_VALUE, CurrentUnitCalibrator.observeAndScale(context, Integer.MIN_VALUE));
			assertEquals(Integer.MAX_VALUE, CurrentUnitCalibrator.observeAndScale(context, Integer.MAX_VALUE));
		}

		@Test
		public void blankZeroPassesThrough() {
			assertEquals(0, CurrentUnitCalibrator.observeAndScale(context, 0));
		}

		@Test
		public void freshDeviceIsNotScaled() {
			// No conclusion without observation: the very first reading returns unscaled µA.
			assertEquals(-800, CurrentUnitCalibrator.observeAndScale(context, -800));
		}

		@Test
		public void typicalMicroAmpReadingStaysUnscaled() {
			assertEquals(-250_000, CurrentUnitCalibrator.observeAndScale(context, -250_000));
		}
	}
}
