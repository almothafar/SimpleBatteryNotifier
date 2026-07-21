package com.almothafar.simplebatterynotifier.service;

import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Unit tests for the pure capacity-estimation helpers in {@link SystemService}, and the snapshot's
 * cycle-count extraction (#161).
 */
@RunWith(Enclosed.class)
public class SystemServiceTest {

	/**
	 * The OS cycle count rides on the {@code BatteryDO} snapshot (#161), extracted from the same
	 * {@code ACTION_BATTERY_CHANGED} intent as everything else — normalized to -1 when absent or
	 * non-positive, matching {@link SystemService#getChargeCycleCount}, so per-tick consumers never
	 * need a second sticky-broadcast read.
	 */
	@RunWith(RobolectricTestRunner.class)
	@Config(sdk = 34)
	public static class SnapshotCycleCount {

		private Context context;

		@Before
		public void setUp() {
			context = ApplicationProvider.getApplicationContext();
		}

		@Test
		public void reportedCycleCount_carriesOntoTheSnapshot() {
			assertEquals(287, SystemService.getBatteryInfo(context, batteryIntent(287)).getCycleCount());
		}

		@Test
		public void absentCycleCount_readsAsMinusOne() {
			final Intent battery = new Intent(Intent.ACTION_BATTERY_CHANGED);
			battery.putExtra(BatteryManager.EXTRA_LEVEL, 50);
			battery.putExtra(BatteryManager.EXTRA_SCALE, 100);
			assertEquals(-1, SystemService.getBatteryInfo(context, battery).getCycleCount());
		}

		@Test
		public void nonPositiveCycleCount_normalizedToMinusOne() {
			assertEquals(-1, SystemService.getBatteryInfo(context, batteryIntent(0)).getCycleCount());
		}

		private static Intent batteryIntent(final int cycleCount) {
			final Intent battery = new Intent(Intent.ACTION_BATTERY_CHANGED);
			battery.putExtra(BatteryManager.EXTRA_LEVEL, 50);
			battery.putExtra(BatteryManager.EXTRA_SCALE, 100);
			battery.putExtra(BatteryManager.EXTRA_CYCLE_COUNT, cycleCount);
			return battery;
		}
	}

	/**
	 * {@link SystemService#estimateFullCapacityMah(int, int)} — derives full capacity (mAh) from a
	 * CHARGE_COUNTER reading (µAh) at a given charge level, rejecting implausible results as 0.
	 */
	@RunWith(Parameterized.class)
	public static class EstimateFullCapacity {

		@Parameter(0) public String label;
		@Parameter(1) public int chargeCounterMicroAmpHours;
		@Parameter(2) public int level;
		@Parameter(3) public int expectedMah;

		@Parameters(name = "{0}")
		public static Collection<Object[]> data() {
			return Arrays.asList(new Object[][]{
					// Typical readings: 2,000,000 µAh at 50% / 3,000,000 at 75% / 4,000,000 at 100% -> 4000 mAh.
					{"typical 50%", 2_000_000, 50, 4000},
					{"typical 75%", 3_000_000, 75, 4000},
					{"typical 100%", 4_000_000, 100, 4000},
					// Unsupported / invalid inputs -> 0. getIntProperty returns Integer.MIN_VALUE when unsupported.
					{"unsupported property", Integer.MIN_VALUE, 50, 0},
					{"zero counter", 0, 50, 0},
					{"zero level", 2_000_000, 0, 0},
					{"negative level", 2_000_000, -1, 0},
					{"level over 100", 2_000_000, 101, 0},
					// Implausible results are rejected (issue #69). Kirin/HiSilicon reports the wrong unit:
					// raw 9000 at 100% -> 9 mAh, far below any real battery.
					{"implausibly low (wrong unit)", 9000, 100, 0},
					{"implausibly low 4 mAh", 2000, 50, 0},
					// Absurdly large (wrong unit the other way): 200,000,000 µAh at 100% -> 200000 mAh.
					{"implausibly high (wrong unit)", 200_000_000, 100, 0},
					// Plausible boundaries are kept: a small-but-real 500 mAh and a large 15000 mAh.
					{"lower plausible boundary", 500_000, 100, 500},
					{"upper plausible boundary", 15_000_000, 100, 15000},
			});
		}

		@Test
		public void matchesExpected() {
			assertEquals(label, expectedMah,
					SystemService.estimateFullCapacityMah(chargeCounterMicroAmpHours, level));
		}
	}

	/**
	 * {@link SystemService#designCapacityMahFromMicroAmpHours(String)} — parses the sysfs
	 * charge_full_design node (µAh, possibly with surrounding whitespace) into mAh, rejecting junk and
	 * implausible values.
	 */
	@RunWith(Parameterized.class)
	public static class DesignCapacityFromMicroAmpHours {

		@Parameter(0) public String label;
		@Parameter(1) public String raw;
		@Parameter(2) public int expectedMah;

		@Parameters(name = "{0}")
		public static Collection<Object[]> data() {
			return Arrays.asList(new Object[][]{
					// Typical: 4,700,000 µAh -> 4700 mAh; surrounding whitespace / trailing newline is tolerated.
					{"typical 4700000", "4700000", 4700},
					{"whitespace tolerated", " 4000000\n", 4000},
					// Missing / malformed -> 0.
					{"null", null, 0},
					{"empty", "", 0},
					{"non-numeric", "N/A", 0},
					{"negative", "-4700000", 0},
					// Implausible -> 0. A value already in mAh divides down to 4 mAh; absurdly large is rejected too.
					{"implausibly low (already mAh)", "4700", 0},
					{"implausibly high", "200000000", 0},
					// Plausible boundaries kept.
					{"lower plausible boundary", "500000", 500},
					{"upper plausible boundary", "15000000", 15000},
			});
		}

		@Test
		public void matchesExpected() {
			assertEquals(label, expectedMah, SystemService.designCapacityMahFromMicroAmpHours(raw));
		}
	}

	/**
	 * The pure SoC-label formatters (#168): combine manufacturer + model on API 31+, fall back to the raw
	 * hardware string below that, and return null for anything blank or the {@code Build.UNKNOWN}
	 * ("unknown") placeholder so the caller hides the row. Plain JVM — no Android runtime needed.
	 */
	public static class SocFormatting {

		@Test
		public void modern_combinesManufacturerAndModel() {
			assertEquals("Qualcomm SM8550", SystemService.formatSocModern("Qualcomm", "SM8550"));
			assertEquals("HiSilicon Kirin 970", SystemService.formatSocModern("HiSilicon", "Kirin 970"));
		}

		@Test
		public void modern_dropsTheUnusablePart() {
			assertEquals("Qualcomm", SystemService.formatSocModern("Qualcomm", "unknown"));
			assertEquals("SM8550", SystemService.formatSocModern("", "SM8550"));
			assertEquals("SM8550", SystemService.formatSocModern(null, "SM8550"));
		}

		@Test
		public void modern_trimsWhitespace() {
			assertEquals("Qualcomm SM8550", SystemService.formatSocModern("  Qualcomm ", " SM8550 "));
		}

		@Test
		public void modern_nullWhenNeitherUsable() {
			assertNull(SystemService.formatSocModern("unknown", "unknown"));
			assertNull(SystemService.formatSocModern("", "  "));
			assertNull(SystemService.formatSocModern(null, null));
		}

		@Test
		public void legacy_usesHardwareOrNull() {
			assertEquals("kirin970", SystemService.formatSocLegacy("kirin970"));
			assertEquals("qcom", SystemService.formatSocLegacy("  qcom "));
			assertNull(SystemService.formatSocLegacy("unknown"));
			assertNull(SystemService.formatSocLegacy(""));
			assertNull(SystemService.formatSocLegacy(null));
		}
	}
}
