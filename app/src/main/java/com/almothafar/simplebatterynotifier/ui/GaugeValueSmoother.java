package com.almothafar.simplebatterynotifier.ui;

/**
 * Glides the live battery percentage between the device's infrequent charge-counter updates (#217).
 * <p>
 * A phone's fuel gauge only refreshes the charge counter every so often (measured ~10–20 s on the
 * S23), so the synthesized two-decimal percentage (#204) sits still and then jumps. This extrapolates
 * from the current charge/drain rate — like a GPS sliding the map dot between real fixes — so the
 * displayed value moves every tick, then <b>snaps to truth</b> whenever a fresh reading arrives.
 * <p>
 * Deliberately honest and bounded: the extrapolation is capped at {@link #MAX_PREDICTED_DRIFT} so a
 * delayed update can't let the estimate run away, and it engages only when the caller confirms both
 * genuine sub-percent data and a trustworthy rate — otherwise the measured value passes through
 * untouched (a no-op on untrusted-counter devices like the Mate 10 Pro). The in-between values are
 * estimated, so the gauge carries an info affordance explaining this to the user.
 * <p>
 * Pure and deterministic given {@code nowMs} (the only state is the last anchor), so it is
 * unit-testable without Android.
 */
public final class GaugeValueSmoother {

	// How far the estimate may run from the last real reading before it waits for the next one. One
	// percent: enough to bridge a normal ~10-20 s gap smoothly, small enough that a stalled counter
	// can't drift the display away from reality.
	static final float MAX_PREDICTED_DRIFT = 1.0f;

	private static final float SECONDS_PER_HOUR = 3600f;
	// A measured value that moves by more than this counts as a fresh device reading (re-anchor);
	// an unchanged counter yields a bit-identical value, so this only needs to clear float noise.
	private static final float RE_ANCHOR_EPSILON = 0.001f;

	private boolean hasAnchor;
	private float anchorValue;
	private long anchorTimeMs;

	/**
	 * The percentage to display right now: the last real reading, extrapolated toward where the
	 * current rate says the battery is heading. Re-anchors (snaps to truth) whenever {@code measured}
	 * changes, and caps the extrapolation at {@link #MAX_PREDICTED_DRIFT}.
	 *
	 * @param measured  the measured precise percentage this tick ({@code BatteryDO.getPrecisePercentage})
	 * @param canSmooth whether smoothing applies (genuine sub-percent data AND a trustworthy rate)
	 * @param ratePph   the charge/drain rate magnitude in %/h (used only when {@code canSmooth})
	 * @param charging  true when charging (estimate rises), false when draining (estimate falls)
	 * @param nowMs     current time in milliseconds
	 *
	 * @return the percentage to display, clamped to {@code [0, 100]}
	 */
	public float displayValue(float measured, boolean canSmooth, int ratePph, boolean charging, long nowMs) {
		if (!canSmooth) {
			hasAnchor = false; // drop the anchor so smoothing restarts cleanly when it next applies
			return measured;
		}
		if (!hasAnchor || Math.abs(measured - anchorValue) > RE_ANCHOR_EPSILON) {
			anchorValue = measured;
			anchorTimeMs = nowMs;
			hasAnchor = true;
		}
		final float elapsedSeconds = Math.max(0f, (nowMs - anchorTimeMs) / 1000f);
		final float direction = charging ? 1f : -1f;
		final float rawDrift = direction * (ratePph / SECONDS_PER_HOUR) * elapsedSeconds;
		final float drift = Math.max(-MAX_PREDICTED_DRIFT, Math.min(MAX_PREDICTED_DRIFT, rawDrift));
		return Math.max(0f, Math.min(100f, anchorValue + drift));
	}

	/**
	 * Drops the anchor so the next smoothed value starts fresh from the measured reading. Called when
	 * smoothing stops applying (e.g. the snapshot is unavailable or loses sub-percent resolution).
	 */
	public void reset() {
		hasAnchor = false;
	}
}
