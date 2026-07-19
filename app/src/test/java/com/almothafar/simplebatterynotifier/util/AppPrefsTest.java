package com.almothafar.simplebatterynotifier.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.almothafar.simplebatterynotifier.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

/**
 * Robolectric tests for the {@link AppPrefs} facade (#162): the typed level accessors fall back to the
 * single-owner defaults when nothing is stored, read back a stored value, and
 * {@link AppPrefs#setBatteryLevels} writes the pair under the exact keys the alert engine and sliders
 * read. Each test gets a fresh application (and therefore empty default SharedPreferences).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AppPrefsTest {

	private Context context;

	@Before
	public void setUp() {
		context = ApplicationProvider.getApplicationContext();
	}

	@Test
	public void levels_fallBackToTheSingleOwnedDefaults() {
		assertEquals(AppPrefs.DEFAULT_CRITICAL_LEVEL, AppPrefs.criticalLevel(context));
		assertEquals(AppPrefs.DEFAULT_WARNING_LEVEL, AppPrefs.warningLevel(context));
		// Pin the historically-duplicated 20/40 literals these constants replaced.
		assertEquals(20, AppPrefs.DEFAULT_CRITICAL_LEVEL);
		assertEquals(40, AppPrefs.DEFAULT_WARNING_LEVEL);
	}

	@Test
	public void levels_readBackStoredValues() {
		PreferenceManager.getDefaultSharedPreferences(context).edit()
		                 .putInt(context.getString(R.string._pref_key_critical_battery_level), 11)
		                 .putInt(context.getString(R.string._pref_key_warn_battery_level), 33)
		                 .apply();

		assertEquals(11, AppPrefs.criticalLevel(context));
		assertEquals(33, AppPrefs.warningLevel(context));
	}

	@Test
	public void setBatteryLevels_persistsBothThresholdsUnderTheSharedKeys() {
		AppPrefs.setBatteryLevels(context, 15, 35);

		// Round-trips through the typed getters...
		assertEquals(15, AppPrefs.criticalLevel(context));
		assertEquals(35, AppPrefs.warningLevel(context));

		// ...and lands under the exact keys the sliders and alert engine read directly.
		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		assertEquals(15, prefs.getInt(context.getString(R.string._pref_key_critical_battery_level), -1));
		assertEquals(35, prefs.getInt(context.getString(R.string._pref_key_warn_battery_level), -1));
	}
}
