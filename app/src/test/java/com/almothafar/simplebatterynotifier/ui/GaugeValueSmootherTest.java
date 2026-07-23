package com.almothafar.simplebatterynotifier.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Unit tests for the live-percentage smoother (issue #217): rate-based extrapolation between the
 * device's infrequent counter updates, the re-anchor on a fresh reading, the drift cap, and the
 * no-op pass-through when smoothing doesn't apply. Times are millis, rates %/h.
 */
public class GaugeValueSmootherTest {

	private static final float CAP = GaugeValueSmoother.MAX_PREDICTED_DRIFT;

	@Test
	public void notSmoothable_passesMeasuredThrough() {
		final GaugeValueSmoother smoother = new GaugeValueSmoother();
		assertEquals(72.5f, smoother.displayValue(72.5f, false, 20, false, 1_000L), 0.001f);
	}

	@Test
	public void firstSmoothableTick_showsMeasured() {
		// Nothing to extrapolate from yet — the anchor is this reading, elapsed 0.
		final GaugeValueSmoother smoother = new GaugeValueSmoother();
		assertEquals(72.0f, smoother.displayValue(72.0f, true, 24, false, 0L), 0.001f);
	}

	@Test
	public void discharging_extrapolatesDownwardOverTime() {
		final GaugeValueSmoother smoother = new GaugeValueSmoother();
		smoother.displayValue(72.0f, true, 24, false, 0L); // anchor at 72.00
		// 30 s later, same measured reading: 24%/h × 30 s = 0.2% drained.
		assertEquals(71.8f, smoother.displayValue(72.0f, true, 24, false, 30_000L), 0.001f);
	}

	@Test
	public void charging_extrapolatesUpwardOverTime() {
		final GaugeValueSmoother smoother = new GaugeValueSmoother();
		smoother.displayValue(40.0f, true, 36, true, 0L); // anchor at 40.00
		// 10 s later: 36%/h × 10 s = 0.1% gained.
		assertEquals(40.1f, smoother.displayValue(40.0f, true, 36, true, 10_000L), 0.001f);
	}

	@Test
	public void extrapolationIsCappedSoAStalledCounterCantRunAway() {
		final GaugeValueSmoother smoother = new GaugeValueSmoother();
		smoother.displayValue(50.0f, true, 60, false, 0L); // anchor at 50.00
		// A full hour with no fresh reading would be 60% of drift; capped to MAX_PREDICTED_DRIFT.
		assertEquals(50.0f - CAP, smoother.displayValue(50.0f, true, 60, false, 3_600_000L), 0.001f);
	}

	@Test
	public void newMeasuredReading_reAnchorsToTruth() {
		final GaugeValueSmoother smoother = new GaugeValueSmoother();
		smoother.displayValue(72.0f, true, 24, false, 0L);                       // anchor 72.00
		assertEquals(71.8f, smoother.displayValue(72.0f, true, 24, false, 30_000L), 0.001f); // predicted
		// The device's real counter update lands (72.0 -> 71.8): snap to it, elapsed resets.
		assertEquals(71.8f, smoother.displayValue(71.8f, true, 24, false, 30_000L), 0.001f);
		// ...and extrapolation continues from the new anchor.
		assertEquals(71.6f, smoother.displayValue(71.8f, true, 24, false, 60_000L), 0.001f);
	}

	@Test
	public void becomingUnsmoothable_dropsAnchorAndPassesThrough() {
		final GaugeValueSmoother smoother = new GaugeValueSmoother();
		smoother.displayValue(72.0f, true, 24, false, 0L);
		// Smoothing no longer applies (e.g. rate lost): show the measured value, forget the anchor.
		assertEquals(80.0f, smoother.displayValue(80.0f, false, 0, false, 30_000L), 0.001f);
		// Re-engaging starts fresh from the new measured value, not the stale 72 anchor.
		assertEquals(80.0f, smoother.displayValue(80.0f, true, 24, false, 30_000L), 0.001f);
	}

	@Test
	public void clampsToZeroWhenDrainingPastEmpty() {
		final GaugeValueSmoother smoother = new GaugeValueSmoother();
		smoother.displayValue(0.3f, true, 60, false, 0L);
		// 0.3 minus the capped 1.0 drift would be negative; clamped to 0.
		assertEquals(0.0f, smoother.displayValue(0.3f, true, 60, false, 3_600_000L), 0.001f);
	}

	@Test
	public void clampsToHundredWhenChargingPastFull() {
		final GaugeValueSmoother smoother = new GaugeValueSmoother();
		smoother.displayValue(99.8f, true, 60, true, 0L);
		// 99.8 plus the capped 1.0 drift would exceed 100; clamped to 100.
		assertEquals(100.0f, smoother.displayValue(99.8f, true, 60, true, 3_600_000L), 0.001f);
	}
}
