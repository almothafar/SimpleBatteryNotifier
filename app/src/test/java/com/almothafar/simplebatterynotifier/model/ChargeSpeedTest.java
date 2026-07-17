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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the pure charging-speed helpers in {@link ChargeSpeed}: power estimation from
 * current/voltage, tier classification, and the {@link ChargeSpeed#fromMeasurements(int, int)}
 * factory (including the wattage rounding used for display).
 */
@RunWith(Enclosed.class)
public class ChargeSpeedTest {

	/**
	 * {@link ChargeSpeed#powerMilliwatts(int, int)} — estimates power (mW) from current (µA) and
	 * voltage (mV), handling unsupported readings, the discharge-positive sign convention, and
	 * implausibly large results.
	 */
	@RunWith(Parameterized.class)
	public static class PowerMilliwatts {

		@Parameter(0) public String label;
		@Parameter(1) public int currentMicroAmps;
		@Parameter(2) public int voltageMilliVolts;
		@Parameter(3) public int expectedMilliwatts;

		@Parameters(name = "{0}")
		public static Collection<Object[]> data() {
			return Arrays.asList(new Object[][]{
					// Typical charging readings: mW = µA × mV / 1e6.
					{"2 A @ 5 V = 10 W", 2_000_000, 5_000, 10_000},
					{"1.5 A @ 5 V = 7.5 W", 1_500_000, 5_000, 7_500},
					{"3 A @ 9 V = 27 W", 3_000_000, 9_000, 27_000},
					// Discharge-positive devices report a negative current while charging; magnitude is used.
					{"negative sign convention", -3_000_000, 5_000, 15_000},
					// Unusable inputs -> unknown (-1). getIntProperty returns Integer.MIN_VALUE when unsupported.
					{"unsupported current", Integer.MIN_VALUE, 5_000, ChargeSpeed.UNKNOWN_POWER_MW},
					{"zero current", 0, 5_000, ChargeSpeed.UNKNOWN_POWER_MW},
					{"zero voltage", 2_000_000, 0, ChargeSpeed.UNKNOWN_POWER_MW},
					{"negative voltage", 2_000_000, -1, ChargeSpeed.UNKNOWN_POWER_MW},
					// Implausibly large (e.g. wrong unit / garbage) -> unknown.
					{"implausibly high", 50_000_000, 5_000, ChargeSpeed.UNKNOWN_POWER_MW},
					// Boundary around the plausibility cap (200 W): equal is kept, just above is rejected.
					{"at plausibility cap", 40_000_000, 5_000, 200_000},
					{"just over plausibility cap", 41_000_000, 5_000, ChargeSpeed.UNKNOWN_POWER_MW},
					// Implausibly small (#152): a charging phone never takes in under ~0.1 W — such a
					// result is a wrong-unit misread (Kirin reports CURRENT_NOW in mA, so a real fast
					// charge computes to ~3-4 mW), not a real trickle.
					{"Kirin mA-unit misread (~3.8 mW)", 1_000, 3_800, ChargeSpeed.UNKNOWN_POWER_MW},
					{"just under plausibility floor", 24_750, 4_000, ChargeSpeed.UNKNOWN_POWER_MW},
					{"at plausibility floor", 25_000, 4_000, 100},
			});
		}

		@Test
		public void matchesExpected() {
			assertEquals(label, expectedMilliwatts,
					ChargeSpeed.powerMilliwatts(currentMicroAmps, voltageMilliVolts));
		}
	}

	/**
	 * {@link ChargeSpeed#classify(int)} — buckets an estimated power (mW) into a
	 * {@link ChargeSpeedTier}, with the thresholds applied inclusively at the lower bound.
	 */
	@RunWith(Parameterized.class)
	public static class Classify {

		@Parameter(0) public String label;
		@Parameter(1) public int milliwatts;
		@Parameter(2) public ChargeSpeedTier expectedTier;

		@Parameters(name = "{0}")
		public static Collection<Object[]> data() {
			return Arrays.asList(new Object[][]{
					{"unknown (-1)", ChargeSpeed.UNKNOWN_POWER_MW, ChargeSpeedTier.UNKNOWN},
					{"zero -> trickle", 0, ChargeSpeedTier.TRICKLE},
					{"just below trickle max", ChargeSpeed.TRICKLE_MAX_MW - 1, ChargeSpeedTier.TRICKLE},
					{"trickle max -> normal", ChargeSpeed.TRICKLE_MAX_MW, ChargeSpeedTier.NORMAL},
					{"just below normal max", ChargeSpeed.NORMAL_MAX_MW - 1, ChargeSpeedTier.NORMAL},
					{"normal max -> fast", ChargeSpeed.NORMAL_MAX_MW, ChargeSpeedTier.FAST},
					{"just below fast max", ChargeSpeed.FAST_MAX_MW - 1, ChargeSpeedTier.FAST},
					{"fast max -> super fast", ChargeSpeed.FAST_MAX_MW, ChargeSpeedTier.SUPER_FAST},
					{"just below super-fast max", ChargeSpeed.SUPER_FAST_MAX_MW - 1, ChargeSpeedTier.SUPER_FAST},
					{"super-fast max -> super fast+", ChargeSpeed.SUPER_FAST_MAX_MW, ChargeSpeedTier.SUPER_FAST_PLUS},
					{"very high -> super fast+", 100_000, ChargeSpeedTier.SUPER_FAST_PLUS},
			});
		}

		@Test
		public void matchesExpected() {
			assertEquals(label, expectedTier, ChargeSpeed.classify(milliwatts));
		}
	}

	/**
	 * {@link ChargeSpeed#fromMeasurements(int, int)} and the display accessors ({@link
	 * ChargeSpeed#isKnown()}, {@link ChargeSpeed#getWatts()}, {@link ChargeSpeed#getTier()}).
	 */
	public static class FromMeasurements {

		@Test
		public void typicalReading_isKnownWithTierAndRoundedWatts() {
			final ChargeSpeed speed = ChargeSpeed.fromMeasurements(2_000_000, 5_000); // 10 W
			assertTrue(speed.isKnown());
			assertEquals(ChargeSpeedTier.FAST, speed.getTier());
			assertEquals(10, speed.getWatts());
			assertEquals(10_000, speed.getMilliwatts());
		}

		@Test
		public void unsupportedReading_isUnknown() {
			final ChargeSpeed speed = ChargeSpeed.fromMeasurements(Integer.MIN_VALUE, 5_000);
			assertFalse(speed.isKnown());
			assertEquals(ChargeSpeedTier.UNKNOWN, speed.getTier());
			assertEquals(0, speed.getWatts());
			assertEquals(ChargeSpeed.UNKNOWN_POWER_MW, speed.getMilliwatts());
		}

		@Test
		public void subWattTrickle_isKnownButRoundsToZeroWatts() {
			final ChargeSpeed speed = ChargeSpeed.fromMeasurements(100_000, 4_000); // 0.4 W
			assertTrue(speed.isKnown());
			assertEquals(ChargeSpeedTier.TRICKLE, speed.getTier());
			assertEquals(0, speed.getWatts());
		}

		@Test
		public void kirinMisreadCurrent_isUnknownNotTrickle() {
			// The end-to-end #152 case: Kirin's mA-unit raw (~1000 while fast charging) used to classify
			// as a known Trickle (~3 mW), mislabeling the tier everywhere and firing the slow-charge
			// warning (#123) on every charge. It must read as unknown, which keeps both quiet.
			final ChargeSpeed speed = ChargeSpeed.fromMeasurements(1_000, 3_800);
			assertFalse(speed.isKnown());
			assertEquals(ChargeSpeedTier.UNKNOWN, speed.getTier());
		}

		@Test
		public void unknownFactory_isUnknown() {
			final ChargeSpeed speed = ChargeSpeed.unknown();
			assertFalse(speed.isKnown());
			assertEquals(ChargeSpeedTier.UNKNOWN, speed.getTier());
		}
	}
}
