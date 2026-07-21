package com.almothafar.simplebatterynotifier.service;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.assertEquals;

/**
 * {@link NotificationChannels#versionedChannelId(String, int)} — versioned alert-channel IDs so a
 * Vibrate change creates genuinely new channels instead of un-deleting old ones (issue #153).
 * Version 1 must stay the original unsuffixed ID so existing installs keep their channels.
 */
@RunWith(Parameterized.class)
public class NotificationChannelsTest {

	@Parameter(0) public String label;
	@Parameter(1) public String baseId;
	@Parameter(2) public int version;
	@Parameter(3) public String expected;

	@Parameters(name = "{0}")
	public static Collection<Object[]> data() {
		return Arrays.asList(new Object[][]{
				{"version 1 keeps the legacy unsuffixed ID", "battery_critical", 1, "battery_critical"},
				{"version 2 appends _v2", "battery_critical", 2, "battery_critical_v2"},
				{"later versions keep counting", "battery_warning", 7, "battery_warning_v7"},
				{"different base IDs stay distinct", "battery_full", 2, "battery_full_v2"},
				{"defensive: version 0 treated as legacy", "battery_critical", 0, "battery_critical"},
				{"defensive: negative version treated as legacy", "battery_critical", -3, "battery_critical"},
		});
	}

	@Test
	public void matchesExpected() {
		assertEquals(label, expected, NotificationChannels.versionedChannelId(baseId, version));
	}
}
