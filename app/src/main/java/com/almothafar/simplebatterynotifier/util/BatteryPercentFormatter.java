package com.almothafar.simplebatterynotifier.util;

import com.almothafar.simplebatterynotifier.model.BatteryDO;

import java.util.Locale;

import static java.util.Objects.isNull;

/**
 * Formats battery percentages for display (#158).
 * <p>
 * One home for the two display forms so the gauge and the ongoing status notification can't drift:
 * <ul>
 *   <li><b>Precise</b> — two decimals ({@code 2.01%}, {@code 48.00%}, {@code 88.88%}), no leading
 *       zero, except a full battery which reads {@code 100%} (never {@code 100.00%}).</li>
 *   <li><b>Whole</b> — the plain integer form ({@code 48%}) used when the device provides no
 *       genuine sub-percent resolution, and by every integer surface (alerts, details table).</li>
 * </ul>
 * All formatting goes through {@link Locale#ROOT}, keeping Western digits in every locale (#96).
 */
public final class BatteryPercentFormatter {

	// At/above this the two-decimal form would render "100.00%"; show the clean "100%" instead.
	private static final float FULL_DISPLAY_THRESHOLD = 99.995f;

	private BatteryPercentFormatter() {
		// Utility class - prevent instantiation
	}

	/**
	 * The live percentage for the home gauge and the ongoing status notification: two decimals when
	 * the snapshot carries genuine sub-percent resolution, the whole percent otherwise — never fake
	 * precision on unreliable-counter devices (#69/#94).
	 *
	 * @param batteryDO Current battery snapshot, or null when unavailable (reads "0%")
	 *
	 * @return the formatted percentage, including the trailing '%'
	 */
	public static String formatLive(final BatteryDO batteryDO) {
		if (isNull(batteryDO)) {
			return formatWhole(0);
		}
		return batteryDO.hasPrecisePercentage()
		       ? formatPrecise(batteryDO.getPrecisePercentage())
		       : formatWhole(batteryDO.getBatteryPercentageInt());
	}

	/**
	 * The two-decimal form, e.g. {@code "2.01%"} / {@code "48.00%"} — except full, which reads
	 * {@code "100%"}. Negative input is clamped to 0 defensively.
	 *
	 * @param percentage the fractional percentage (0-100)
	 *
	 * @return the formatted percentage, including the trailing '%'
	 */
	public static String formatPrecise(final float percentage) {
		if (percentage >= FULL_DISPLAY_THRESHOLD) {
			return formatWhole(100);
		}
		return String.format(Locale.ROOT, "%.2f%%", Math.max(0f, percentage));
	}

	/**
	 * The whole-percent form, e.g. {@code "48%"}.
	 *
	 * @param percentage the whole percentage (0-100)
	 *
	 * @return the formatted percentage, including the trailing '%'
	 */
	public static String formatWhole(final int percentage) {
		return String.format(Locale.ROOT, "%d%%", percentage);
	}
}
