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
}
