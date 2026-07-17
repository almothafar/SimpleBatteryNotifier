package com.almothafar.simplebatterynotifier.util;

import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;

import static org.junit.Assert.assertEquals;

/**
 * Unit tests for the pure time parsing/formatting in {@link GeneralHelper} (issue #154): a corrupt
 * stored quiet-hours value must become -1, never an exception, because parsing runs on the
 * broadcast path of every alert.
 */
@RunWith(Enclosed.class)
public class GeneralHelperTest {

	/**
	 * {@link GeneralHelper#parseTimeToMinutes(String)} across valid, boundary and malformed inputs.
	 * The malformed rows are the shapes issue #154 calls out; none may throw.
	 */
	@RunWith(Parameterized.class)
	public static class ParseTimeToMinutes {

		@Parameter(0) public String label;
		@Parameter(1) public String time;
		@Parameter(2) public int expected;

		@Parameters(name = "{0}")
		public static Collection<Object[]> data() {
			return Arrays.asList(new Object[][]{
					{"default window start", "06:30", 6 * 60 + 30},
					{"default window end", "23:30", 23 * 60 + 30},
					{"midnight", "00:00", 0},
					{"single-digit components", "0:0", 0},
					{"last minute of day", "23:59", 23 * 60 + 59},
					{"legacy Arabic-digit value kept working", "٠٦:٣٠", 6 * 60 + 30},
					{"hour 24 rejected", "24:00", -1},
					{"minute 60 rejected", "23:60", -1},
					{"out-of-range both", "25:99", -1},
					{"null rejected", null, -1},
					{"empty rejected", "", -1},
					{"no colon rejected", "12", -1},
					{"letters rejected", "ab:cd", -1},
					{"letter minute rejected", "12:ab", -1},
					{"missing hour rejected", ":30", -1},
					{"missing minute rejected", "12:", -1},
					{"second colon rejected", "1:2:3", -1},
					{"negative hour rejected", "-1:30", -1},
					{"leading space rejected", " 6:30", -1},
					{"three-digit hour rejected", "006:30", -1},
			});
		}

		@Test
		public void matchesExpected() {
			assertEquals(label, expected, GeneralHelper.parseTimeToMinutes(time));
		}
	}

	/**
	 * {@link GeneralHelper#formatTime(int, int)} — the persisted-format writer. Round-tripped
	 * through the parser so the two can't drift apart (issue #154 acceptance criterion), and pinned
	 * to Western digits under the Arabic locale (the old default-locale formatting wrote Eastern
	 * Arabic numerals there).
	 */
	public static class FormatTime {

		@Test
		public void writesZeroPaddedHhMm() {
			assertEquals("06:30", GeneralHelper.formatTime(6, 30));
			assertEquals("23:05", GeneralHelper.formatTime(23, 5));
			assertEquals("00:00", GeneralHelper.formatTime(0, 0));
		}

		@Test
		public void keepsWesternDigitsUnderArabicDefaultLocale() {
			final Locale original = Locale.getDefault();
			try {
				Locale.setDefault(Locale.forLanguageTag("ar"));
				assertEquals("06:30", GeneralHelper.formatTime(6, 30));
			} finally {
				Locale.setDefault(original);
			}
		}

		@Test
		public void roundTripsThroughTheParser() {
			assertEquals(6 * 60 + 30, GeneralHelper.parseTimeToMinutes(GeneralHelper.formatTime(6, 30)));
			assertEquals(0, GeneralHelper.parseTimeToMinutes(GeneralHelper.formatTime(0, 0)));
			assertEquals(23 * 60 + 59, GeneralHelper.parseTimeToMinutes(GeneralHelper.formatTime(23, 59)));
		}
	}
}
