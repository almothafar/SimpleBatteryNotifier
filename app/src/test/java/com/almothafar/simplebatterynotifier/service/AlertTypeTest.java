package com.almothafar.simplebatterynotifier.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link AlertType} (issue #160). The persisted ids are a compatibility contract:
 * the level-alert de-dupe state (#164) was written by older app versions as the int constants
 * 1/2/3 (0 = none), so the mapping must stay exactly that — otherwise an upgrade would misread an
 * in-progress alert episode.
 */
public class AlertTypeTest {

	@Test
	public void persistedIds_keepTheLegacyIntValues() {
		assertEquals(1, AlertType.persistedId(AlertType.CRITICAL));
		assertEquals(2, AlertType.persistedId(AlertType.WARNING));
		assertEquals(3, AlertType.persistedId(AlertType.FULL));
		assertEquals(0, AlertType.persistedId(null));
	}

	@Test
	public void fromPersistedId_roundTripsEveryType() {
		for (final AlertType type : AlertType.values()) {
			assertEquals(type, AlertType.fromPersistedId(AlertType.persistedId(type)));
		}
	}

	@Test
	public void fromPersistedId_zeroAndUnknownReadAsNone() {
		assertNull(AlertType.fromPersistedId(0));
		assertNull(AlertType.fromPersistedId(-1));
		assertNull(AlertType.fromPersistedId(99));
	}

	@Test
	public void onlyCriticalAlertsEveryTime() {
		assertTrue(AlertType.CRITICAL.alertsEveryTime());
		assertFalse(AlertType.WARNING.alertsEveryTime());
		assertFalse(AlertType.FULL.alertsEveryTime());
	}
}
