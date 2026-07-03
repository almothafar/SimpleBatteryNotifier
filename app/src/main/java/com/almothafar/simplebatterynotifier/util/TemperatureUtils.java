package com.almothafar.simplebatterynotifier.util;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import com.almothafar.simplebatterynotifier.R;

/**
 * Single source of truth for battery-temperature handling.
 * <p>
 * IMPORTANT: Android's {@code BatteryManager} always reports the battery temperature in tenths of a
 * degree <b>Celsius</b> (e.g. {@code 320} == 32.0&nbsp;°C), regardless of the device locale or the
 * user's display settings. There is no "native" Fahrenheit reading. Fahrenheit is therefore purely a
 * <i>display</i> preference.
 * <p>
 * To keep the alert comparison trivial and immune to unit changes, the high-temperature alert
 * threshold is also stored canonically in <b>Celsius</b>. The settings slider converts to/from the
 * user's unit for display only (see {@code GenericPreferenceFragment}); the stored value and the
 * comparison are always Celsius. As a result a chilly 45&nbsp;°F (~7&nbsp;°C) can never trip a
 * 45&nbsp;°C threshold.
 */
public final class TemperatureUtils {

	/** Default high-temperature alert threshold, in Celsius. */
	public static final int DEFAULT_HIGH_TEMP_THRESHOLD_C = 45;
	/** Minimum selectable threshold, in Celsius. */
	public static final int MIN_HIGH_TEMP_THRESHOLD_C = 40;
	/** Maximum selectable threshold, in Celsius. */
	public static final int MAX_HIGH_TEMP_THRESHOLD_C = 60;

	private TemperatureUtils() {
		// Utility class - prevent instantiation
	}

	/**
	 * @param context the application context
	 * @return true if the user's display-unit preference is Fahrenheit (Celsius otherwise)
	 */
	public static boolean isFahrenheit(final Context context) {
		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		final String unit = prefs.getString(
				context.getString(R.string._pref_key_temperatures_unit),
				context.getString(R.string._pref_value_temperatures_unit_c));
		return context.getString(R.string._pref_value_temperatures_unit_f).equalsIgnoreCase(unit);
	}

	/** Convert whole degrees Celsius to whole degrees Fahrenheit (rounded). */
	public static int celsiusToFahrenheit(final int celsius) {
		return Math.round(celsius * 9f / 5f + 32f);
	}

	/** Convert whole degrees Fahrenheit to whole degrees Celsius (rounded). */
	public static int fahrenheitToCelsius(final int fahrenheit) {
		return Math.round((fahrenheit - 32) * 5f / 9f);
	}

	/**
	 * Convert degrees Celsius to degrees Fahrenheit (one-decimal precision), rounded to the nearest
	 * tenth. Uses {@code Math.round} so the display path matches the whole-degree overload above
	 * (the previous implementation rounded up via {@code Math.ceil}, biasing displayed °F upward).
	 */
	public static float celsiusToFahrenheit(final float celsius) {
		final float fahrenheit = celsius * 9f / 5f + 32f;
		return Math.round(fahrenheit * 10f) / 10f;
	}

	/**
	 * Whether a raw {@code BatteryManager} reading is at or above the alert threshold.
	 * <p>
	 * Both operands are Celsius: {@code rawTenthsC} is tenths of a degree Celsius and
	 * {@code thresholdCelsius} is whole degrees Celsius. Compared as tenths to avoid floating point.
	 *
	 * @param rawTenthsC       battery temperature in tenths of a degree Celsius
	 * @param thresholdCelsius alert threshold in whole degrees Celsius
	 * @return true if the battery is at or above the threshold
	 */
	public static boolean isAtOrAboveThreshold(final int rawTenthsC, final int thresholdCelsius) {
		return rawTenthsC >= thresholdCelsius * 10;
	}

	/**
	 * Whether the battery has cooled far enough below the threshold to re-arm the alert.
	 *
	 * @param rawTenthsC       battery temperature in tenths of a degree Celsius
	 * @param thresholdCelsius alert threshold in whole degrees Celsius
	 * @param hysteresisC      how many degrees Celsius below the threshold counts as "cooled down"
	 * @return true if the battery is at or below {@code threshold - hysteresis}
	 */
	public static boolean isBelowResetThreshold(final int rawTenthsC, final int thresholdCelsius, final int hysteresisC) {
		return rawTenthsC <= (thresholdCelsius - hysteresisC) * 10;
	}

	/**
	 * Format a raw {@code BatteryManager} temperature (tenths of °C) in the user's display unit,
	 * e.g. {@code "32.0 °C"} or {@code "89.6 °F"}.
	 *
	 * @param context    the application context
	 * @param rawTenthsC battery temperature in tenths of a degree Celsius
	 * @return formatted, unit-suffixed temperature string
	 */
	public static String format(final Context context, final int rawTenthsC) {
		final float celsius = rawTenthsC / 10f;
		if (isFahrenheit(context)) {
			return celsiusToFahrenheit(celsius) + " " + context.getString(R.string.fahrenheit_short);
		}
		return celsius + " " + context.getString(R.string.celsius_short);
	}
}
