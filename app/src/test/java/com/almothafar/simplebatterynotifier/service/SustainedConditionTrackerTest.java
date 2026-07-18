package com.almothafar.simplebatterynotifier.service;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.almothafar.simplebatterynotifier.service.SustainedConditionTracker.Outcome;
import com.almothafar.simplebatterynotifier.service.SustainedConditionTracker.Streak;
import com.almothafar.simplebatterynotifier.service.SustainedConditionTracker.StreakStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the shared sustained-condition engine {@link SustainedConditionTracker} (issue #163):
 * the generic decide core (sleep / re-arm / lapse / sustained window) and its two repeat policies, plus
 * the {@link StreakStore} persistence — including that it reads back state written under the detectors'
 * existing preference keys, so no in-progress episode is lost on upgrade.
 */
@RunWith(Enclosed.class)
public class SustainedConditionTrackerTest {

	private static final long SUSTAINED_MS = 3 * 60_000L;
	private static final long REMINDER_MS = 15 * 60_000L;
	private static final Streak CLEARED = new Streak(0, false, 0, 0);

	/** The generic {@link SustainedConditionTracker#decide} mechanics, independent of any detector. */
	public static class DecideCore {

		@Test
		public void unmeasurable_sleepsAndKeepsStreakUntouched() {
			final Streak state = new Streak(1000, true, 2000, 2000);
			final Outcome d = SustainedConditionTracker.decide(state, false, true, SUSTAINED_MS, 999_999,
					SustainedConditionTracker.fireOnce());

			assertFalse(d.shouldNotify());
			assertEquals(state, d.newState());
		}

		@Test
		public void conditionInactive_reArmsToCleared() {
			final Streak state = new Streak(1000, true, 2000, 0);
			final Outcome d = SustainedConditionTracker.decide(state, true, false, SUSTAINED_MS, 500_000,
					SustainedConditionTracker.fireOnce());

			assertFalse(d.shouldNotify());
			assertEquals(CLEARED, d.newState());
		}

		@Test
		public void fireOnce_firesWhenSustainedThenStaysSilent() {
			final Streak building = new Streak(1000, false, 1000, 0);
			final Outcome first = SustainedConditionTracker.decide(building, true, true, SUSTAINED_MS,
					1000 + SUSTAINED_MS, SustainedConditionTracker.fireOnce());
			assertTrue(first.shouldNotify());
			assertTrue(first.newState().alerted());

			final Outcome later = SustainedConditionTracker.decide(first.newState(), true, true, SUSTAINED_MS,
					1000 + SUSTAINED_MS + REMINDER_MS, SustainedConditionTracker.fireOnce());
			assertFalse(later.shouldNotify()); // once only — no reminders
		}

		@Test
		public void withReminders_remindsWhileLockedButNotWhileUsed() {
			final long start = 1000;
			final long now = start + SUSTAINED_MS + REMINDER_MS;
			final Streak alerted = new Streak(start, true, now - 60_000L, start);

			final Outcome used = SustainedConditionTracker.decide(alerted, true, true, SUSTAINED_MS, now,
					SustainedConditionTracker.withReminders(true, REMINDER_MS));
			assertFalse(used.shouldNotify()); // visible to the user — stay silent

			final Outcome locked = SustainedConditionTracker.decide(alerted, true, true, SUSTAINED_MS, now,
					SustainedConditionTracker.withReminders(false, REMINDER_MS));
			assertTrue(locked.shouldNotify()); // background — remind on the gap
			assertEquals(now, locked.newState().lastReminder());
		}

		@Test
		public void longObservationGap_lapsesAndRestartsTheStreak() {
			final long lastSeen = 1000;
			final long now = lastSeen + SustainedConditionTracker.MAX_OBSERVATION_GAP_MS + 1;
			final Streak state = new Streak(1000, true, lastSeen, 0);

			final Outcome d = SustainedConditionTracker.decide(state, true, true, SUSTAINED_MS, now,
					SustainedConditionTracker.fireOnce());
			assertEquals(now, d.newState().start());   // fresh episode
			assertFalse(d.newState().alerted());        // re-armed
		}
	}

	/** {@link StreakStore}: round-trip, the reminder-less variant, the churn-guarded clear, and upgrade safety. */
	@RunWith(RobolectricTestRunner.class)
	@Config(sdk = 34)
	public static class Persistence {

		// The real fast-drain keys — this test doubles as the "keys unchanged on upgrade" guard (#163).
		private static final String KEY_START = "_fast_drain_streak_start";
		private static final String KEY_ALERTED = "_fast_drain_alerted";
		private static final String KEY_LAST_SEEN = "_fast_drain_last_seen_above";
		private static final String KEY_LAST_REMINDER = "_fast_drain_last_reminder";

		private SharedPreferences prefs;

		@Before
		public void setUp() {
			final Context context = ApplicationProvider.getApplicationContext();
			prefs = PreferenceManager.getDefaultSharedPreferences(context);
			prefs.edit().clear().apply();
		}

		@Test
		public void roundTripsAllFourFields() {
			final StreakStore store = new StreakStore(KEY_START, KEY_ALERTED, KEY_LAST_SEEN, KEY_LAST_REMINDER);
			final Streak state = new Streak(111, true, 222, 333);
			store.save(prefs, state);

			assertEquals(state, store.load(prefs));
		}

		@Test
		public void reminderlessStore_ignoresAndNeverWritesTheReminderField() {
			final StreakStore store = new StreakStore(KEY_START, KEY_ALERTED, KEY_LAST_SEEN);
			store.save(prefs, new Streak(111, true, 222, 999)); // 999 must not be persisted

			assertEquals(new Streak(111, true, 222, 0), store.load(prefs));
			assertFalse(prefs.contains(KEY_LAST_REMINDER));
		}

		@Test
		public void clear_resetsToCleared() {
			final StreakStore store = new StreakStore(KEY_START, KEY_ALERTED, KEY_LAST_SEEN, KEY_LAST_REMINDER);
			store.save(prefs, new Streak(111, true, 222, 333));

			store.clear(prefs);

			assertEquals(CLEARED, store.load(prefs));
		}

		@Test
		public void loadsStateWrittenUnderTheLegacyKeys() {
			// Simulate an install upgraded mid-episode: raw values sitting under the pre-#163 keys.
			prefs.edit()
			     .putLong(KEY_START, 4242)
			     .putBoolean(KEY_ALERTED, true)
			     .putLong(KEY_LAST_SEEN, 5353)
			     .putLong(KEY_LAST_REMINDER, 6464)
			     .apply();

			final Streak loaded = new StreakStore(KEY_START, KEY_ALERTED, KEY_LAST_SEEN, KEY_LAST_REMINDER).load(prefs);

			assertEquals(new Streak(4242, true, 5353, 6464), loaded);
		}
	}
}
