package com.almothafar.simplebatterynotifier.util;

import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.assertEquals;

/**
 * Unit tests for the pure (context-free) parts of {@link TemperatureUtils}.
 */
@RunWith(Enclosed.class)
public class TemperatureUtilsTest {

	/**
	 * {@link TemperatureUtils#celsiusToFahrenheit(int)} and its inverse
	 * {@link TemperatureUtils#fahrenheitToCelsius(int)} across known integer values.
	 */
	@RunWith(Parameterized.class)
	public static class IntConversions {

		@Parameter(0) public int celsius;
		@Parameter(1) public int fahrenheit;

		@Parameters(name = "{0}C = {1}F")
		public static Collection<Object[]> data() {
			return Arrays.asList(new Object[][]{
					{0, 32},
					{40, 104},
					{45, 113},
					{60, 140},
					{100, 212},
			});
		}

		@Test
		public void celsiusToFahrenheit() {
			assertEquals(fahrenheit, TemperatureUtils.celsiusToFahrenheit(celsius));
		}

		@Test
		public void fahrenheitToCelsius() {
			assertEquals(celsius, TemperatureUtils.fahrenheitToCelsius(fahrenheit));
		}
	}

	/**
	 * {@link TemperatureUtils#isAtOrAboveThreshold(int, int)} — reading (tenths of °C) vs threshold
	 * (°C), always compared in Celsius.
	 */
	@RunWith(Parameterized.class)
	public static class AtOrAboveThreshold {

		@Parameter(0) public String label;
		@Parameter(1) public int readingTenthsC;
		@Parameter(2) public int thresholdC;
		@Parameter(3) public boolean expected;

		@Parameters(name = "{0}")
		public static Collection<Object[]> data() {
			return Arrays.asList(new Object[][]{
					{"45.0C at 45 trips", 450, 45, true},
					{"60.0C at 45 trips", 600, 45, true},
					{"44.9C at 45 does not trip", 449, 45, false},
					// The bug this guards: a chilly 45°F (~7.2°C = 72 tenths) must NOT trip a 45°C threshold.
					{"chilly 45F (7.2C) does not trip", 72, 45, false},
			});
		}

		@Test
		public void matchesExpected() {
			assertEquals(label, expected, TemperatureUtils.isAtOrAboveThreshold(readingTenthsC, thresholdC));
		}
	}

	/**
	 * {@link TemperatureUtils#isBelowResetThreshold(int, int, int)} — hysteresis re-arm: true once the
	 * reading (tenths of °C) drops to/below threshold − hysteresis.
	 */
	@RunWith(Parameterized.class)
	public static class BelowResetThreshold {

		@Parameter(0) public String label;
		@Parameter(1) public int readingTenthsC;
		@Parameter(2) public int thresholdC;
		@Parameter(3) public int hysteresisC;
		@Parameter(4) public boolean expected;

		@Parameters(name = "{0}")
		public static Collection<Object[]> data() {
			// threshold 45°C, hysteresis 3°C -> re-arm at/below 42.0°C (420 tenths).
			return Arrays.asList(new Object[][]{
					{"42.0C re-arms", 420, 45, 3, true},
					{"30.0C re-arms", 300, 45, 3, true},
					{"43.0C still too warm", 430, 45, 3, false},
			});
		}

		@Test
		public void matchesExpected() {
			assertEquals(label, expected,
					TemperatureUtils.isBelowResetThreshold(readingTenthsC, thresholdC, hysteresisC));
		}
	}

	/**
	 * Float rounding and the °C↔°F round-trip stay as named tests — the behaviour, not a data row, is
	 * the point.
	 */
	public static class FloatAndRoundTrip {

		@Test
		public void celsiusToFahrenheit_float_roundsToNearestTenth() {
			assertEquals(89.6f, TemperatureUtils.celsiusToFahrenheit(32.0f), 0.001f);
			assertEquals(98.6f, TemperatureUtils.celsiusToFahrenheit(37.0f), 0.001f);
			// 20.02 °C -> 68.036 °F: rounds DOWN to 68.0 (the old Math.ceil path returned 68.1).
			assertEquals(68.0f, TemperatureUtils.celsiusToFahrenheit(20.02f), 0.001f);
		}

		/** The canonical threshold values must survive a round-trip through Fahrenheit display. */
		@Test
		public void roundTrip_isStableForThresholdRange() {
			for (int c = TemperatureUtils.MIN_HIGH_TEMP_THRESHOLD_C; c <= TemperatureUtils.MAX_HIGH_TEMP_THRESHOLD_C; c++) {
				final int back = TemperatureUtils.fahrenheitToCelsius(TemperatureUtils.celsiusToFahrenheit(c));
				assertEquals("°C should round-trip via °F for " + c, c, back);
			}
		}
	}
}
