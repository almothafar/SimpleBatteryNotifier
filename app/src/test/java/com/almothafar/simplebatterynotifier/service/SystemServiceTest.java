package com.almothafar.simplebatterynotifier.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Unit tests for the pure capacity-estimation helper in {@link SystemService}.
 */
public class SystemServiceTest {

	@Test
	public void estimateFullCapacity_typicalReadings() {
		// 2,000,000 µAh remaining at 50% -> 4000 mAh full
		assertEquals(4000, SystemService.estimateFullCapacityMah(2_000_000, 50));
		// 3,000,000 µAh at 75% -> 4000 mAh
		assertEquals(4000, SystemService.estimateFullCapacityMah(3_000_000, 75));
		// 4,000,000 µAh at 100% -> 4000 mAh
		assertEquals(4000, SystemService.estimateFullCapacityMah(4_000_000, 100));
	}

	@Test
	public void estimateFullCapacity_unsupportedOrInvalid_returnsZero() {
		// getIntProperty returns Integer.MIN_VALUE for unsupported properties
		assertEquals(0, SystemService.estimateFullCapacityMah(Integer.MIN_VALUE, 50));
		assertEquals(0, SystemService.estimateFullCapacityMah(0, 50));
		assertEquals(0, SystemService.estimateFullCapacityMah(2_000_000, 0));
		assertEquals(0, SystemService.estimateFullCapacityMah(2_000_000, -1));
		assertEquals(0, SystemService.estimateFullCapacityMah(2_000_000, 101));
	}

	@Test
	public void estimateFullCapacity_implausibleResult_returnsZero() {
		// Kirin/HiSilicon device reporting CHARGE_COUNTER in the wrong unit: raw 9000 at 100% -> 9 mAh,
		// far below any real battery, so it must be rejected as "unknown" (issue #69).
		assertEquals(0, SystemService.estimateFullCapacityMah(9000, 100));
		assertEquals(0, SystemService.estimateFullCapacityMah(2000, 50)); // -> 4 mAh
		// Absurdly large (wrong unit the other way) is rejected too: 200,000,000 µAh at 100% -> 200000 mAh
		assertEquals(0, SystemService.estimateFullCapacityMah(200_000_000, 100));
	}

	@Test
	public void estimateFullCapacity_plausibleBoundaries_areKept() {
		// A small-but-real 500 mAh battery (lower bound) and a large 15000 mAh (upper bound) are kept.
		assertEquals(500, SystemService.estimateFullCapacityMah(500_000, 100));
		assertEquals(15000, SystemService.estimateFullCapacityMah(15_000_000, 100));
	}

	@Test
	public void designCapacityFromMicroAmpHours_typicalReadings() {
		// charge_full_design is reported in µAh: 4,700,000 µAh -> 4700 mAh
		assertEquals(4700, SystemService.designCapacityMahFromMicroAmpHours("4700000"));
		// Surrounding whitespace / trailing newline from the sysfs node is tolerated
		assertEquals(4000, SystemService.designCapacityMahFromMicroAmpHours(" 4000000\n"));
	}

	@Test
	public void designCapacityFromMicroAmpHours_missingOrMalformed_returnsZero() {
		assertEquals(0, SystemService.designCapacityMahFromMicroAmpHours(null));
		assertEquals(0, SystemService.designCapacityMahFromMicroAmpHours(""));
		assertEquals(0, SystemService.designCapacityMahFromMicroAmpHours("N/A"));
		assertEquals(0, SystemService.designCapacityMahFromMicroAmpHours("-4700000"));
	}

	@Test
	public void designCapacityFromMicroAmpHours_implausibleResult_returnsZero() {
		// A value already in mAh (non-standard) divides down to 4 mAh -> rejected as implausible.
		assertEquals(0, SystemService.designCapacityMahFromMicroAmpHours("4700"));
		// Absurdly large (wrong unit the other way): 200,000,000 µAh -> 200000 mAh -> rejected.
		assertEquals(0, SystemService.designCapacityMahFromMicroAmpHours("200000000"));
	}

	@Test
	public void designCapacityFromMicroAmpHours_plausibleBoundaries_areKept() {
		assertEquals(500, SystemService.designCapacityMahFromMicroAmpHours("500000"));
		assertEquals(15000, SystemService.designCapacityMahFromMicroAmpHours("15000000"));
	}
}
