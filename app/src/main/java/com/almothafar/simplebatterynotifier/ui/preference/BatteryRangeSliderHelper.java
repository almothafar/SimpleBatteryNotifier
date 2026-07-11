package com.almothafar.simplebatterynotifier.ui.preference;

import com.almothafar.simplebatterynotifier.R;
import com.google.android.material.slider.RangeSlider;

/**
 * Shared configuration for the combined critical/warning battery-level {@link RangeSlider}.
 * <p>
 * The same two-thumb control appears in two places — the settings screen
 * ({@link BatteryRangeSliderPreference}) and the in-fly control on the home screen — so the
 * bounds, step, minimum thumb separation, and the "{@code N%}" label formatting live here as the
 * single source of truth. The left thumb is the critical level, the right thumb is the warning
 * level, and {@link #MIN_SEPARATION} guarantees critical always stays below warning.
 */
public final class BatteryRangeSliderHelper {

	/** Lowest value the critical thumb can reach (the combined track's start). */
	public static final int LEVEL_FROM = 10;
	/** Highest value the warning thumb can reach (the combined track's end). */
	public static final int LEVEL_TO = 50;
	/** Minimum gap kept between the critical and warning thumbs, in percent. */
	public static final int MIN_SEPARATION = 5;
	/** Default critical level, matching the historical {@code SeekBarPreference} default. */
	public static final int DEFAULT_CRITICAL = 20;
	/** Default warning level, matching the historical {@code SeekBarPreference} default. */
	public static final int DEFAULT_WARNING = 40;

	private BatteryRangeSliderHelper() {
		// Utility class
	}

	/**
	 * Apply the shared bounds, integer step, minimum thumb separation, and a "{@code N%}" label
	 * formatter to a slider. Does not set the thumb values — callers set those after clamping via
	 * {@link #clampPair(int, int, int, int, int)}.
	 *
	 * @param slider        the range slider to configure
	 * @param from          combined track start (critical floor)
	 * @param to            combined track end (warning ceiling)
	 * @param minSeparation minimum gap between the two thumbs, in value units
	 */
	public static void configure(final RangeSlider slider, final int from, final int to, final int minSeparation) {
		slider.setValueFrom(from);
		slider.setValueTo(to);
		slider.setStepSize(1f);
		slider.setMinSeparationValue(minSeparation);
		// Show the dragged thumb's value as a whole percentage (e.g. "20%") instead of "20.0".
		slider.setLabelFormatter(value ->
				slider.getContext().getString(R.string.battery_level_percent, Math.round(value)));
	}

	/**
	 * Clamp a (critical, warning) pair into {@code [from, to]} while keeping them at least
	 * {@code minSeparation} apart, so persisted or corrupted values can always be shown on the
	 * slider without tripping its validation.
	 *
	 * @return a two-element array {@code {critical, warning}}
	 */
	public static int[] clampPair(final int critical, final int warning,
	                              final int from, final int to, final int minSeparation) {
		int low = clamp(critical, from, to);
		int high = clamp(warning, from, to);
		if (high - low < minSeparation) {
			// Push the warning thumb up first; if that hits the ceiling, pull critical back down.
			high = Math.min(to, low + minSeparation);
			low = Math.max(from, high - minSeparation);
		}
		return new int[]{low, high};
	}

	private static int clamp(final int value, final int lo, final int hi) {
		return Math.max(lo, Math.min(hi, value));
	}
}
