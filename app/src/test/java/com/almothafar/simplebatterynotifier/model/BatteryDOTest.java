package com.almothafar.simplebatterynotifier.model;

import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * Tests for {@link BatteryDO} calculation logic and edge cases.
 */
@RunWith(Enclosed.class)
public class BatteryDOTest {

	/**
	 * {@link BatteryDO#getBatteryPercentage()} across normal, boundary and malformed inputs. A zero or
	 * negative scale must be guarded so it never yields Infinity/NaN, which would become
	 * Integer.MAX_VALUE when cast to int downstream.
	 */
	@RunWith(Parameterized.class)
	public static class GetBatteryPercentage {

		@Parameter(0) public String label;
		@Parameter(1) public int level;
		@Parameter(2) public int scale;
		@Parameter(3) public float expected;

		@Parameters(name = "{0}")
		public static Collection<Object[]> data() {
			return Arrays.asList(new Object[][]{
					{"normal 50/100", 50, 100, 50.0f},
					{"different scale 80/200", 80, 200, 40.0f},
					{"full battery", 100, 100, 100.0f},
					{"empty battery", 0, 100, 0.0f},
					{"decimal 33/100", 33, 100, 33.0f},
					// Malformed data must be guarded, not produce Infinity.
					{"zero scale guarded", 50, 0, 0.0f},
					{"negative scale guarded", 50, -1, 0.0f},
					// Defensive: out-of-range inputs still compute rather than crash.
					{"negative level", -10, 100, -10.0f},
					{"level exceeds scale", 150, 100, 150.0f},
			});
		}

		@Test
		public void matchesExpected() {
			final float result = new BatteryDO().setLevel(level).setScale(scale).getBatteryPercentage();
			assertEquals(label, expected, result, 0.01f);
		}
	}

	/**
	 * Builder chaining, decimal precision and the health-status enum are distinct concerns, kept as
	 * named tests.
	 */
	public static class Behaviour {

		@Test
		public void getBatteryPercentage_oneThird_maintainsPrecision() {
			assertEquals(33.333f, new BatteryDO().setLevel(1).setScale(3).getBatteryPercentage(), 0.001f);
		}

		@Test
		public void setters_returnThis_forMethodChaining() {
			final BatteryDO battery = new BatteryDO();
			final BatteryDO result = battery
					.setLevel(50)
					.setScale(100)
					.setStatus(3)
					.setPlugged(1);
			assertSame("Builder pattern should return same instance", battery, result);
		}

		@Test
		public void healthStatus_defaultsToUnknown() {
			assertEquals(BatteryHealthStatus.UNKNOWN, new BatteryDO().getHealthStatus());
		}

		@Test
		public void setHealthStatus_storesCorrectValue() {
			final BatteryDO battery = new BatteryDO();
			battery.setHealthStatus(BatteryHealthStatus.CRITICAL);
			assertEquals(BatteryHealthStatus.CRITICAL, battery.getHealthStatus());
		}
	}
}
