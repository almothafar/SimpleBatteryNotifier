package com.almothafar.simplebatterynotifier.service;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.almothafar.simplebatterynotifier.service.BatteryCapacityTracker.CapacityStats;

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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * Unit tests for the stable-capacity learner (issue #204). The pure helpers — the sample fold with
 * its gates, and the warm-up rule — carry the feature's correctness; capacities are mAh, times in
 * millis.
 */
@RunWith(Enclosed.class)
public class BatteryCapacityTrackerTest {

	private static final long SPACING = BatteryCapacityTracker.SAMPLE_SPACING_MS;
	private static final int MIN_LEVEL = BatteryCapacityTracker.MIN_SAMPLE_LEVEL_PERCENT;
	private static final int MIN_SAMPLES = BatteryCapacityTracker.MIN_STABLE_SAMPLES;
	private static final int CAP = BatteryCapacityTracker.SAMPLE_COUNT_CAP;

	private static final CapacityStats NOTHING_LEARNED = new CapacityStats(0f, 0, 0, 0, 0L);

	/**
	 * {@link BatteryCapacityTracker#learn}: the sample gates (estimate, level, spacing), the
	 * incremental average with its capped count, the min/max tracking, and the unchanged-instance
	 * contract the persist-on-change relies on.
	 */
	public static class Learn {

		@Test
		public void firstSampleSeedsStats() {
			final CapacityStats result = BatteryCapacityTracker.learn(NOTHING_LEARNED, 4400, 50, SPACING);

			assertEquals(new CapacityStats(4400f, 1, 4400, 4400, SPACING), result);
		}

		@Test
		public void nonPositiveEstimateIsRejected() {
			assertSame(NOTHING_LEARNED, BatteryCapacityTracker.learn(NOTHING_LEARNED, 0, 50, SPACING));
			assertSame(NOTHING_LEARNED, BatteryCapacityTracker.learn(NOTHING_LEARNED, -100, 50, SPACING));
		}

		@Test
		public void lowLevelIsRejected() {
			// Below the gate the whole-percent rounding noise dominates the estimate.
			assertSame(NOTHING_LEARNED, BatteryCapacityTracker.learn(NOTHING_LEARNED, 4400, MIN_LEVEL - 1, SPACING));
		}

		@Test
		public void levelAtTheGateIsAccepted() {
			assertEquals(1, BatteryCapacityTracker.learn(NOTHING_LEARNED, 4400, MIN_LEVEL, SPACING).sampleCount());
		}

		@Test
		public void closelySpacedSampleIsRejected() {
			// A burst of battery broadcasts at the same charge state must contribute one sample.
			final CapacityStats previous = new CapacityStats(4000f, 1, 4000, 4000, 1_000_000L);
			final CapacityStats result = BatteryCapacityTracker.learn(previous, 4400, 50, 1_000_000L + SPACING - 1);

			assertSame(previous, result); // caller skips the persist
		}

		@Test
		public void spacedSampleFoldsIntoAverage() {
			final CapacityStats previous = new CapacityStats(4000f, 1, 4000, 4000, 1_000_000L);
			final CapacityStats result = BatteryCapacityTracker.learn(previous, 4400, 50, 1_000_000L + SPACING);

			assertEquals(new CapacityStats(4200f, 2, 4000, 4400, 1_000_000L + SPACING), result);
		}

		@Test
		public void minAndMaxTrackTheExtremes() {
			final CapacityStats previous = new CapacityStats(4400f, 2, 4300, 4500, 1_000_000L);
			final CapacityStats result = BatteryCapacityTracker.learn(previous, 4200, 50, 1_000_000L + SPACING);

			assertEquals(4200, result.minMah());
			assertEquals(4500, result.maxMah());
			assertEquals(4333.33f, result.averageMah(), 0.01f);
		}

		@Test
		public void countCapsAndLateSamplesKeepWeight() {
			// At the cap the count stops growing, which floors every later sample's weight at 1/CAP —
			// the average keeps adapting to battery aging instead of freezing.
			final CapacityStats previous = new CapacityStats(4000f, CAP, 3900, 4100, 1_000_000L);
			final CapacityStats result = BatteryCapacityTracker.learn(previous, 4600, 50, 1_000_000L + SPACING);

			assertEquals(CAP, result.sampleCount());
			assertEquals(4000f + 600f / CAP, result.averageMah(), 0.001f);
		}
	}

	/**
	 * {@link BatteryCapacityTracker#stableCapacityMah(CapacityStats)}: 0 until enough spaced samples
	 * are in, then the average rounded to whole mAh.
	 */
	@RunWith(Parameterized.class)
	public static class StableCapacityMah {

		@Parameter(0) public String label;
		@Parameter(1) public float averageMah;
		@Parameter(2) public int sampleCount;
		@Parameter(3) public int expected;

		@Parameters(name = "{0}")
		public static Collection<Object[]> data() {
			return Arrays.asList(new Object[][]{
					{"nothing learned yet", 0f, 0, 0},
					{"first sample shows immediately", 4390.4f, MIN_SAMPLES, 4390},
					{"rounds to nearest mAh", 4390.6f, MIN_SAMPLES, 4391},
					{"keeps showing as the average settles", 4400f, 3, 4400},
					{"full window", 4444.4f, CAP, 4444},
			});
		}

		@Test
		public void matchesExpected() {
			final CapacityStats stats = new CapacityStats(averageMah, sampleCount, 0, 0, 0L);
			assertEquals(label, expected, BatteryCapacityTracker.stableCapacityMah(stats));
		}
	}

	/**
	 * {@link BatteryCapacityTracker#summarize(CapacityStats)}: the display summary (#116) — null until
	 * enough spaced samples are in, then the average (rounded) with its min/max carried through.
	 */
	public static class Summarize {

		@Test
		public void nullBeforeFirstSample() {
			assertNull(BatteryCapacityTracker.summarize(NOTHING_LEARNED));
		}

		@Test
		public void carriesRoundedAverageWithMinMax() {
			final CapacityStats stats = new CapacityStats(4390.6f, MIN_SAMPLES, 4300, 4480, 1_000_000L);
			final BatteryCapacityTracker.CapacitySummary summary = BatteryCapacityTracker.summarize(stats);

			assertEquals(4391, summary.averageMah());
			assertEquals(4300, summary.minMah());
			assertEquals(4480, summary.maxMah());
		}
	}

	/**
	 * {@link BatteryCapacityTracker#observeAndAverage}: the Context-backed entry point — persistence
	 * into the transient file, the spacing rule across calls, and the warm-up return. The fold math
	 * is covered by the pure tests above; this exercises the wiring under Robolectric like the other
	 * Context-backed helpers.
	 */
	@RunWith(RobolectricTestRunner.class)
	@Config(sdk = 34)
	public static class ObserveAndAverage {

		private Context context;
		private SharedPreferences prefs;

		@Before
		public void setUp() {
			context = ApplicationProvider.getApplicationContext();
			prefs = TransientState.prefs(context);
		}

		@Test
		public void firstSampleShowsImmediately() {
			// One trusted reading is enough — no blank warm-up before the decimals go live (#204).
			assertEquals(4400, BatteryCapacityTracker.observeAndAverage(context, 4400, 50));

			final CapacityStats stored = BatteryCapacityTracker.loadStats(prefs);
			assertEquals(4400f, stored.averageMah(), 0f);
			assertEquals(1, stored.sampleCount());
			assertEquals(4400, stored.minMah());
			assertEquals(4400, stored.maxMah());
		}

		@Test
		public void burstOfTicksFoldsOnlyOneSample() {
			BatteryCapacityTracker.observeAndAverage(context, 4400, 50);
			BatteryCapacityTracker.observeAndAverage(context, 4600, 50);

			final CapacityStats stored = BatteryCapacityTracker.loadStats(prefs);
			assertEquals(1, stored.sampleCount());
			assertEquals(4400f, stored.averageMah(), 0f);
		}

		@Test
		public void warmLearnerReturnsStableEvenWhenTheSampleIsRejected() {
			// Seeded warm with a fresh last-sample time: the incoming tick is inside the spacing
			// window, so nothing is learned — but the stable value must still come back.
			BatteryCapacityTracker.saveStats(prefs, new CapacityStats(4390.4f, MIN_SAMPLES, 4300, 4500, System.currentTimeMillis()));

			assertEquals(4390, BatteryCapacityTracker.observeAndAverage(context, 9999, 50));
			assertEquals(MIN_SAMPLES, BatteryCapacityTracker.loadStats(prefs).sampleCount());
		}
	}
}
