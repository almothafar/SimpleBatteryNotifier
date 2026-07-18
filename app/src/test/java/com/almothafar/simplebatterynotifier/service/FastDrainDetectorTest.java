package com.almothafar.simplebatterynotifier.service;

import com.almothafar.simplebatterynotifier.service.SustainedConditionTracker.Outcome;
import com.almothafar.simplebatterynotifier.service.SustainedConditionTracker.Streak;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the pure fast-drain decision core {@link FastDrainDetector#decide} (issue #109),
 * unchanged as the behaviour contract after the shared engine was extracted (#163): the
 * sustained-streak trigger, the once-vs-reminder split by screen state, the re-arm hysteresis, the
 * observation-gap lapse rule, and the timing-preference clamp.
 * Times in millis; limit in %/h. The streak's {@code lastSeen} sits close to "now" where the scenario
 * implies continuous observation — in production every above-limit tick refreshes it.
 */
public class FastDrainDetectorTest {

	private static final int LIMIT = 20;
	private static final long SUSTAINED_MS = 5 * 60_000L;   // 5 min
	private static final long REMINDER_MS = 15 * 60_000L;   // 15 min
	private static final long MINUTE_MS = 60_000L;
	private static final boolean USED = true;
	private static final boolean LOCKED = false; // "not actively used" == screen off/locked

	// Streak(start, alerted, lastSeen, lastReminder).
	private static final Streak CLEARED = new Streak(0, false, 0, 0);

	@Test
	public void rateUnavailable_sleepsAndKeepsStreak() {
		final Streak state = new Streak(1000, true, 2000, 2000);
		final Outcome d = FastDrainDetector.decide(state, false, 30, LIMIT, SUSTAINED_MS, REMINDER_MS, LOCKED, 999_999);

		assertFalse(d.shouldNotify());
		assertEquals(state, d.newState()); // untouched
	}

	@Test
	public void rateBelowLimit_reArmsEpisode() {
		final Streak state = new Streak(1000, true, 2000, 2000);
		final Outcome d = FastDrainDetector.decide(state, true, 10, LIMIT, SUSTAINED_MS, REMINDER_MS, LOCKED, 500_000);

		assertFalse(d.shouldNotify());
		assertEquals(CLEARED, d.newState());
	}

	@Test
	public void rateAtLimit_startsStreakButDoesNotFireYet() {
		final Outcome d = FastDrainDetector.decide(CLEARED, true, 20, LIMIT, SUSTAINED_MS, REMINDER_MS, LOCKED, 1000);

		assertFalse(d.shouldNotify());
		assertEquals(1000, d.newState().start());
		assertEquals(1000, d.newState().lastSeen());
		assertFalse(d.newState().alerted());
	}

	@Test
	public void streakNotYetSustained_doesNotFire() {
		final Streak state = new Streak(1000, false, 1000, 0);
		final long now = 1000 + 2 * MINUTE_MS; // only 2 min in
		final Outcome d = FastDrainDetector.decide(state, true, 30, LIMIT, SUSTAINED_MS, REMINDER_MS, LOCKED, now);

		assertFalse(d.shouldNotify());
		assertEquals(1000, d.newState().start());     // start preserved
		assertEquals(now, d.newState().lastSeen());   // observation refreshed
		assertFalse(d.newState().alerted());
	}

	@Test
	public void sustained_firesFirstAlertEvenWhileActivelyUsed() {
		final Streak state = new Streak(1000, false, 1000, 0);
		final long now = 1000 + SUSTAINED_MS;
		final Outcome d = FastDrainDetector.decide(state, true, 30, LIMIT, SUSTAINED_MS, REMINDER_MS, USED, now);

		assertTrue(d.shouldNotify());              // warn at least once per episode
		assertTrue(d.newState().alerted());
		assertEquals(now, d.newState().lastReminder());
		assertEquals(SUSTAINED_MS, d.elapsedMs());
	}

	@Test
	public void afterAlert_activelyUsed_staysSilent() {
		final long start = 1000;
		final long now = start + REMINDER_MS + MINUTE_MS; // well past a reminder gap
		final Streak state = new Streak(start, true, now - MINUTE_MS, start);
		final Outcome d = FastDrainDetector.decide(state, true, 30, LIMIT, SUSTAINED_MS, REMINDER_MS, USED, now);

		assertFalse(d.shouldNotify());                    // once per episode while the user can see it
		assertEquals(start, d.newState().lastReminder()); // reminder time untouched
	}

	@Test
	public void afterAlert_lockedAndGapElapsed_reminds() {
		final long start = 1000;
		final long now = start + REMINDER_MS;
		final Streak state = new Streak(start, true, now - MINUTE_MS, start);
		final Outcome d = FastDrainDetector.decide(state, true, 30, LIMIT, SUSTAINED_MS, REMINDER_MS, LOCKED, now);

		assertTrue(d.shouldNotify());
		assertEquals(now, d.newState().lastReminder());
		assertEquals(REMINDER_MS, d.elapsedMs()); // "for the last 15 minutes"
	}

	@Test
	public void afterAlert_lockedButGapNotElapsed_staysSilent() {
		final long start = 1000;
		final long now = start + REMINDER_MS - MINUTE_MS; // one minute short of the gap
		final Streak state = new Streak(start, true, now - MINUTE_MS, start);
		final Outcome d = FastDrainDetector.decide(state, true, 30, LIMIT, SUSTAINED_MS, REMINDER_MS, LOCKED, now);

		assertFalse(d.shouldNotify());
		assertEquals(start, d.newState().lastReminder());
	}

	@Test
	public void reArmedEpisode_canAlertAgainLater() {
		// A calmed episode clears, then a fresh flare re-starts the streak and alerts once sustained.
		final Outcome calmed = FastDrainDetector.decide(
				new Streak(1000, true, 1000, 1000), true, 5, LIMIT, SUSTAINED_MS, REMINDER_MS, LOCKED, 400_000);
		assertEquals(CLEARED, calmed.newState());

		final Outcome restart = FastDrainDetector.decide(
				calmed.newState(), true, 30, LIMIT, SUSTAINED_MS, REMINDER_MS, LOCKED, 500_000);
		assertFalse(restart.shouldNotify());
		assertEquals(500_000, restart.newState().start());

		final Outcome reAlert = FastDrainDetector.decide(
				restart.newState(), true, 30, LIMIT, SUSTAINED_MS, REMINDER_MS, LOCKED, 500_000 + SUSTAINED_MS);
		assertTrue(reAlert.shouldNotify());
	}

	// --- observation-gap lapse rule ------------------------------------------------------------

	@Test
	public void lapsedGap_restartsStreakInsteadOfAlerting() {
		// Streak observed for 4 min, then no usable rate for ~86 min (process death / doze). The next
		// above-limit reading must start a fresh episode, not alert "for the last 90 minutes".
		final long lastSeen = 1000 + 4 * MINUTE_MS;
		final Streak state = new Streak(1000, false, lastSeen, 0);
		final long now = 1000 + 90 * MINUTE_MS;
		final Outcome d = FastDrainDetector.decide(state, true, 22, LIMIT, SUSTAINED_MS, REMINDER_MS, LOCKED, now);

		assertFalse(d.shouldNotify());
		assertEquals(now, d.newState().start());   // fresh episode
		assertEquals(now, d.newState().lastSeen());
	}

	@Test
	public void lapsedGapAfterAlert_startsFreshEpisodeThatCanAlertAgain() {
		final long alertedAt = 1000 + SUSTAINED_MS;
		final Streak state = new Streak(1000, true, alertedAt, alertedAt);
		final long now = 1000 + 120 * MINUTE_MS; // hours later
		final Outcome lapsed = FastDrainDetector.decide(state, true, 30, LIMIT, SUSTAINED_MS, REMINDER_MS, LOCKED, now);

		assertFalse(lapsed.shouldNotify());
		assertEquals(now, lapsed.newState().start());
		assertFalse(lapsed.newState().alerted()); // new episode: the first-alert is re-armed

		final Outcome reAlert = FastDrainDetector.decide(
				lapsed.newState(), true, 30, LIMIT, SUSTAINED_MS, REMINDER_MS, LOCKED, now + SUSTAINED_MS);
		assertTrue(reAlert.shouldNotify());
	}

	@Test
	public void gapAtBoundary_continuesStreak() {
		// A gap of exactly MAX_OBSERVATION_GAP_MS is still continuous; one millisecond more lapses it.
		final long lastSeen = 1000;
		final long atBoundary = lastSeen + SustainedConditionTracker.MAX_OBSERVATION_GAP_MS;
		final Streak state = new Streak(1000, false, lastSeen, 0);

		final Outcome continued = FastDrainDetector.decide(
				state, true, 30, LIMIT, SUSTAINED_MS, REMINDER_MS, LOCKED, atBoundary);
		assertEquals(1000, continued.newState().start()); // preserved

		final Outcome lapsed = FastDrainDetector.decide(
				state, true, 30, LIMIT, SUSTAINED_MS, REMINDER_MS, LOCKED, atBoundary + 1);
		assertEquals(atBoundary + 1, lapsed.newState().start()); // restarted
	}

	// --- timing-preference clamp ----------------------------------------------------------------

	@Test
	public void clampMinutes_inRangeUnchanged() {
		assertEquals(5 * MINUTE_MS, FastDrainDetector.clampMinutesToMs(5, 1, 30));
		assertEquals(1 * MINUTE_MS, FastDrainDetector.clampMinutesToMs(1, 1, 30));   // boundaries kept
		assertEquals(60 * MINUTE_MS, FastDrainDetector.clampMinutesToMs(60, 5, 60));
	}

	@Test
	public void clampMinutes_outOfRangeClamped() {
		// 0 sustained minutes would fire on the first above-limit tick — the spike alarm the design forbids.
		assertEquals(1 * MINUTE_MS, FastDrainDetector.clampMinutesToMs(0, 1, 30));
		assertEquals(1 * MINUTE_MS, FastDrainDetector.clampMinutesToMs(-7, 1, 30));
		assertEquals(30 * MINUTE_MS, FastDrainDetector.clampMinutesToMs(999, 1, 30));
	}
}
