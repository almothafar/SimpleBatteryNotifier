package com.almothafar.simplebatterynotifier.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the pure (context-free) parts of {@link TemperatureUtils}.
 */
public class TemperatureUtilsTest {

	@Test
	public void celsiusToFahrenheit_knownValues() {
		assertEquals(32, TemperatureUtils.celsiusToFahrenheit(0));
		assertEquals(104, TemperatureUtils.celsiusToFahrenheit(40));
		assertEquals(113, TemperatureUtils.celsiusToFahrenheit(45));
		assertEquals(140, TemperatureUtils.celsiusToFahrenheit(60));
		assertEquals(212, TemperatureUtils.celsiusToFahrenheit(100));
	}

	@Test
	public void fahrenheitToCelsius_knownValues() {
		assertEquals(0, TemperatureUtils.fahrenheitToCelsius(32));
		assertEquals(40, TemperatureUtils.fahrenheitToCelsius(104));
		assertEquals(45, TemperatureUtils.fahrenheitToCelsius(113));
		assertEquals(60, TemperatureUtils.fahrenheitToCelsius(140));
		assertEquals(100, TemperatureUtils.fahrenheitToCelsius(212));
	}

	/** The canonical threshold values must survive a round-trip through Fahrenheit display. */
	@Test
	public void roundTrip_isStableForThresholdRange() {
		for (int c = TemperatureUtils.MIN_HIGH_TEMP_THRESHOLD_C; c <= TemperatureUtils.MAX_HIGH_TEMP_THRESHOLD_C; c++) {
			final int back = TemperatureUtils.fahrenheitToCelsius(TemperatureUtils.celsiusToFahrenheit(c));
			assertEquals("°C should round-trip via °F for " + c, c, back);
		}
	}

	@Test
	public void isAtOrAboveThreshold_comparesInCelsius() {
		// 45.0 °C reading vs 45 °C threshold -> trips
		assertTrue(TemperatureUtils.isAtOrAboveThreshold(450, 45));
		// 60.0 °C -> trips
		assertTrue(TemperatureUtils.isAtOrAboveThreshold(600, 45));
		// 44.9 °C -> does not trip
		assertFalse(TemperatureUtils.isAtOrAboveThreshold(449, 45));
	}

	/**
	 * The bug this guards against: a chilly 45 °F (~7.2 °C, i.e. 72 tenths) must NOT trip a
	 * 45 °C threshold. Temperature is always compared in Celsius.
	 */
	@Test
	public void isAtOrAboveThreshold_chilly45Fahrenheit_doesNotTrip() {
		final int sevenPointTwoCelsiusInTenths = 72; // == 45 °F
		assertFalse(TemperatureUtils.isAtOrAboveThreshold(sevenPointTwoCelsiusInTenths, 45));
	}

	@Test
	public void isBelowResetThreshold_appliesHysteresis() {
		// threshold 45 °C, hysteresis 3 °C -> re-arm at/below 42.0 °C (420 tenths)
		assertTrue(TemperatureUtils.isBelowResetThreshold(420, 45, 3));
		assertTrue(TemperatureUtils.isBelowResetThreshold(300, 45, 3));
		assertFalse(TemperatureUtils.isBelowResetThreshold(430, 45, 3)); // 43.0 °C still too warm
	}
}
