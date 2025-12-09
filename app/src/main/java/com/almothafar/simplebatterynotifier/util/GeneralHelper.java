package com.almothafar.simplebatterynotifier.util;

import android.content.res.Resources;
import android.util.TypedValue;

import static java.util.Objects.isNull;

/**
 * General utility helper methods for the application
 */
public final class GeneralHelper {

	private GeneralHelper() {
		// Utility class - prevent instantiation
	}

	/**
	 * Convert density-independent pixels (dp) to actual pixels
	 *
	 * @param res     The Resources instance
	 * @param dpValue The dp value to convert
	 * @return The pixel value
	 */
	public static int dpToPixel(final Resources res, final int dpValue) {
		if (isNull(res)) {
			return 0;
		}
		return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dpValue, res.getDisplayMetrics());
	}

	/**
	 * Get color resource with null theme (uses default theme)
	 *
	 * @param res The Resources instance
	 * @param id  The color resource ID
	 * @return The color value
	 * @throws Resources.NotFoundException if the color resource is not found
	 */
	public static int getColor(final Resources res, final int id) throws Resources.NotFoundException {
		if (isNull(res)) {
			throw new Resources.NotFoundException("Resources is null");
		}
		// Using null theme means default theme will be used
		return res.getColor(id, null);
	}

	/**
	 * Convert temperature from Celsius to Fahrenheit, rounded to one decimal place
	 *
	 * @param temperature Temperature in Celsius
	 * @return Temperature in Fahrenheit
	 */
	public static float fromCtoF(final float temperature) {
		final float fahrenheit = (9.0f / 5.0f) * temperature + 32;
		return (float) Math.ceil(fahrenheit * 10.0f) / 10.0f;
	}

	/**
	 * Extract hour from time string in format "HH:MM"
	 *
	 * @param time Time string in format "HH:MM"
	 * @return The hour component
	 * @throws IllegalArgumentException if time format is invalid
	 */
	public static int getHour(final String time) {
		if (isNull(time) || time.isEmpty()) {
			throw new IllegalArgumentException("Time string cannot be null or empty");
		}
		final String[] pieces = time.split(":");
		if (pieces.length < 2) {
			throw new IllegalArgumentException("Invalid time format. Expected HH:MM");
		}
		return Integer.parseInt(pieces[0]);
	}

	/**
	 * Extract minute from time string in format "HH:MM"
	 *
	 * @param time Time string in format "HH:MM"
	 * @return The minute component
	 * @throws IllegalArgumentException if time format is invalid
	 */
	public static int getMinute(final String time) {
		if (isNull(time) || time.isEmpty()) {
			throw new IllegalArgumentException("Time string cannot be null or empty");
		}
		final String[] pieces = time.split(":");
		if (pieces.length < 2) {
			throw new IllegalArgumentException("Invalid time format. Expected HH:MM");
		}
		return Integer.parseInt(pieces[1]);
	}
}
