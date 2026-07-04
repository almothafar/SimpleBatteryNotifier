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
}
