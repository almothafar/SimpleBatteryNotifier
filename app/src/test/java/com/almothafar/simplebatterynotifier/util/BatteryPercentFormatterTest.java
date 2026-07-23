package com.almothafar.simplebatterynotifier.util;

import com.almothafar.simplebatterynotifier.model.BatteryDO;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.assertEquals;

/**
 * Tests for {@link BatteryPercentFormatter} (#158/#204): the exact display formats — {@code 2.01%}
 * (no leading zero), {@code 88.88%}, {@code 100%} (never {@code 100.00%}) — the zero-fraction
 * suppression ({@code 48.00} self-heals to {@code 48%}, #204), and the whole-percent fallback used
 * when sub-percent data isn't genuine.
 */
@RunWith(Enclosed.class)
public class BatteryPercentFormatterTest {

	/** {@link BatteryPercentFormatter#formatPrecise(float)} across the issue's format table. */
	@RunWith(Parameterized.class)
	public static class FormatPrecise {

		@Parameter(0) public String label;
		@Parameter(1) public float percentage;
		@Parameter(2) public String expected;

		@Parameters(name = "{0}")
		public static Collection<Object[]> data() {
			return Arrays.asList(new Object[][]{
					{"empty battery suppresses zero fraction", 0f, "0%"},
					{"low, no leading zero", 2.01f, "2.01%"},
					{"zero fraction self-heals to whole", 48f, "48%"},
					{"real fraction keeps decimals", 40.5f, "40.50%"},
					{"mid fraction", 88.88f, "88.88%"},
					{"rounds onto a whole shows clean integer", 39.9996f, "40%"},
					{"just below full", 99.99f, "99.99%"},
					{"rounds into full shows clean 100", 99.996f, "100%"},
					{"full, no decimals", 100f, "100%"},
					{"negative clamped defensively", -1.5f, "0%"},
			});
		}

		@Test
		public void matchesExpected() {
			assertEquals(label, expected, BatteryPercentFormatter.formatPrecise(percentage));
		}
	}

	/** Whole-percent form and the live entry point's precise-vs-fallback switching. */
	public static class Behaviour {

		@Test
		public void formatWhole_plainInteger() {
			assertEquals("48%", BatteryPercentFormatter.formatWhole(48));
			assertEquals("100%", BatteryPercentFormatter.formatWhole(100));
			assertEquals("0%", BatteryPercentFormatter.formatWhole(0));
		}

		@Test
		public void formatLive_nullSnapshot_readsZero() {
			assertEquals("0%", BatteryPercentFormatter.formatLive(null));
		}

		@Test
		public void formatLive_withTrustedCounter_showsTwoDecimals() {
			// 3,525,000 µAh of a 4000 mAh stable capacity = 88.125% → rendered at two decimals.
			final BatteryDO battery = new BatteryDO().setLevel(88).setScale(100)
					.setStableCapacityMah(4000).setChargeCounterMicroAmpHours(3_525_000);
			assertEquals("88.13%", BatteryPercentFormatter.formatLive(battery));
		}

		@Test
		public void formatLive_zeroFraction_selfHealsToWhole() {
			// 3,520,000 µAh of 4000 mAh = exactly 88.00% — genuine sub-percent data, zero fraction:
			// the display degrades to the clean whole form instead of a fake "88.00%" (#204).
			final BatteryDO battery = new BatteryDO().setLevel(88).setScale(100)
					.setStableCapacityMah(4000).setChargeCounterMicroAmpHours(3_520_000);
			assertEquals("88%", BatteryPercentFormatter.formatLive(battery));
		}

		@Test
		public void formatLive_withoutCounter_fallsBackToWholePercent() {
			final BatteryDO battery = new BatteryDO().setLevel(88).setScale(100);
			assertEquals("88%", BatteryPercentFormatter.formatLive(battery));
		}

		@Test
		public void formatLive_fullBattery_readsCleanHundred() {
			final BatteryDO battery = new BatteryDO().setLevel(100).setScale(100)
					.setStableCapacityMah(4000).setChargeCounterMicroAmpHours(4_000_000);
			assertEquals("100%", BatteryPercentFormatter.formatLive(battery));
		}
	}
}
