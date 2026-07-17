package com.almothafar.simplebatterynotifier.util;

import android.content.res.Resources;
import android.util.TypedValue;

import java.util.Locale;

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
	 * Parse a persisted "HH:MM" time into minutes since midnight, without ever throwing.
	 * <p>
	 * The stored value can be corrupt (backup/restore across versions, prefs file damage — issue
	 * #154) and this runs on the broadcast path of every alert, so malformed input must be a value,
	 * not an exception: the caller decides the fallback. Digits are matched via
	 * {@link Character#digit(char, int)}, which also accepts Eastern Arabic numerals — values written
	 * by older versions under the Arabic locale keep working (see {@link #formatTime(int, int)}).
	 *
	 * @param time Time string in "HH:MM" form (1-2 digit components)
	 * @return minutes since midnight (0-1439), or -1 if {@code time} is not a valid time
	 */
	public static int parseTimeToMinutes(final String time) {
		if (isNull(time)) {
			return -1;
		}
		final int colon = time.indexOf(':');
		if (colon < 0 || colon != time.lastIndexOf(':')) {
			return -1;
		}
		final int hour = parseTimeComponent(time.substring(0, colon), 23);
		final int minute = parseTimeComponent(time.substring(colon + 1), 59);
		if (hour < 0 || minute < 0) {
			return -1;
		}
		return hour * 60 + minute;
	}

	/**
	 * Parse a 1-2 digit hour/minute component, rejecting instead of throwing.
	 *
	 * @param part candidate hour/minute component
	 * @param max  inclusive upper bound (23 for hours, 59 for minutes)
	 * @return the component's value, or -1 when {@code part} is not a valid component
	 */
	private static int parseTimeComponent(final String part, final int max) {
		if (part.isEmpty() || part.length() > 2) {
			return -1;
		}
		int value = 0;
		for (int i = 0; i < part.length(); i++) {
			final int digit = Character.digit(part.charAt(i), 10);
			if (digit < 0) {
				return -1;
			}
			value = value * 10 + digit;
		}
		return value <= max ? value : -1;
	}

	/**
	 * Format a time as the canonical persisted "HH:MM" form.
	 * <p>
	 * {@link Locale#ROOT} keeps the digits Western regardless of the app language: the default
	 * locale under Arabic renders Eastern Arabic numerals, which silently corrupted the stored
	 * value for the parser (issue #154). Writer and parser are round-trip tested together so the
	 * two can't drift apart.
	 *
	 * @param hour   hour of day (0-23)
	 * @param minute minute of hour (0-59)
	 * @return the "HH:MM" string, zero-padded, Western digits
	 */
	public static String formatTime(final int hour, final int minute) {
		return String.format(Locale.ROOT, "%02d:%02d", hour, minute);
	}
}
