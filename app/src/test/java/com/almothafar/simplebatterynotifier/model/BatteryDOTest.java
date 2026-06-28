package com.almothafar.simplebatterynotifier.model;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Integration tests for BatteryDO
 * Tests actual calculation logic and edge cases
 */
public class BatteryDOTest {

	private BatteryDO battery;

	@Before
	public void setUp() {
		battery = new BatteryDO();
	}

	// ===== REAL TESTS FOR getBatteryPercentage() CALCULATION LOGIC =====

	@Test
	public void getBatteryPercentage_normalCase_calculatesCorrectly() {
		battery.setLevel(50).setScale(100);
		assertEquals(50.0f, battery.getBatteryPercentage(), 0.01f);
	}

	@Test
	public void getBatteryPercentage_differentScale_calculatesCorrectly() {
		battery.setLevel(80).setScale(200);
		assertEquals(40.0f, battery.getBatteryPercentage(), 0.01f);
	}

	@Test
	public void getBatteryPercentage_fullBattery_returns100() {
		battery.setLevel(100).setScale(100);
		assertEquals(100.0f, battery.getBatteryPercentage(), 0.01f);
	}

	@Test
	public void getBatteryPercentage_emptyBattery_returns0() {
		battery.setLevel(0).setScale(100);
		assertEquals(0.0f, battery.getBatteryPercentage(), 0.01f);
	}

	/**
	 * CRITICAL EDGE CASE: Division by zero / invalid scale
	 * A zero (or negative) scale is malformed battery data. It must be guarded so it never
	 * produces Infinity/NaN, which would become Integer.MAX_VALUE when cast to int downstream.
	 */
	@Test
	public void getBatteryPercentage_scaleZero_returnsZero() {
		battery.setLevel(50).setScale(0);
		final float result = battery.getBatteryPercentage();
		assertEquals("Invalid scale must be guarded, not produce Infinity", 0.0f, result, 0.01f);
	}

	@Test
	public void getBatteryPercentage_negativeScale_returnsZero() {
		battery.setLevel(50).setScale(-1);
		assertEquals("Negative scale must be guarded", 0.0f, battery.getBatteryPercentage(), 0.01f);
	}

	/**
	 * EDGE CASE: Negative level (shouldn't happen but test defensive code)
	 */
	@Test
	public void getBatteryPercentage_negativeLevel_calculatesCorrectly() {
		battery.setLevel(-10).setScale(100);
		assertEquals(-10.0f, battery.getBatteryPercentage(), 0.01f);
	}

	/**
	 * EDGE CASE: Level exceeds scale (malformed data)
	 */
	@Test
	public void getBatteryPercentage_levelExceedsScale_calculatesOver100() {
		battery.setLevel(150).setScale(100);
		assertEquals(150.0f, battery.getBatteryPercentage(), 0.01f);
	}

	/**
	 * PRECISION TEST: Decimal percentages
	 */
	@Test
	public void getBatteryPercentage_decimalResult_maintainsPrecision() {
		battery.setLevel(33).setScale(100);
		assertEquals(33.0f, battery.getBatteryPercentage(), 0.01f);

		battery.setLevel(1).setScale(3);
		assertEquals(33.333f, battery.getBatteryPercentage(), 0.001f);
	}

	// ===== BUILDER PATTERN TESTS (minimal, only testing method chaining works) =====

	@Test
	public void setters_returnThis_forMethodChaining() {
		final BatteryDO result = battery
				.setLevel(50)
				.setScale(100)
				.setStatus(3)
				.setPlugged(1);

		assertSame("Builder pattern should return same instance", battery, result);
	}

	// ===== HEALTH STATUS ENUM TEST =====

	@Test
	public void healthStatus_defaultsToUnknown() {
		assertEquals(BatteryHealthStatus.UNKNOWN, battery.getHealthStatus());
	}

	@Test
	public void setHealthStatus_storesCorrectValue() {
		battery.setHealthStatus(BatteryHealthStatus.CRITICAL);
		assertEquals(BatteryHealthStatus.CRITICAL, battery.getHealthStatus());
	}
}
