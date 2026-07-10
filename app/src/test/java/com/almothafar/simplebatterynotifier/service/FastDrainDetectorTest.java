package com.almothafar.simplebatterynotifier.service;

import com.almothafar.simplebatterynotifier.service.FastDrainDetector.FastDrainDecision;
import com.almothafar.simplebatterynotifier.service.FastDrainDetector.FastDrainState;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the pure fast-drain decision core {@link FastDrainDetector#decide} (issue #109):
 * the sustained-streak trigger, the once-vs-reminder split by screen state, and the re-arm hysteresis.
 * Times in millis; limit in %/h.
 */
public class FastDrainDetectorTest {

	private static final int LIMIT = 20;
	private static final long SUSTAINED_MS = 5 * 60_000L;   // 5 min
	private static final long REMINDER_MS = 15 * 60_000L;   // 15 min
	private static final boolean USED = true;
	private static final boolean LOCKED = false; // "not actively used" == screen off/locked

	private static final FastDrainState CLEARED = new FastDrainState(0, false, 0);

	@Test
	public void rateUnavailable_sleepsAndKeepsStreak() {
		final FastDrainState state = new FastDrainState(1000, true, 2000);
		final FastDrainDecision d = FastDrainDetector.decide(state, false, 30, LIMIT, SUSTAINED_MS, REMINDER_MS, LOCKED, 999_999);

		assertFalse(d.shouldNotify());
		assertEquals(state, d.newState()); // untouched
	}

	@Test
	public void rateBelowLimit_reArmsEpisode() {
		final FastDrainState state = new FastDrainState(1000, true, 2000);
		final FastDrainDecision d = FastDrainDetector.decide(state, true, 10, LIMIT, SUSTAINED_MS, REMINDER_MS, LOCKED, 500_000);

		assertFalse(d.shouldNotify());
		assertEquals(CLEARED, d.newState());
	}

	@Test
	public void rateAtLimit_startsStreakButDoesNotFireYet() {
		final FastDrainDecision d = FastDrainDetector.decide(CLEARED, true, 20, LIMIT, SUSTAINED_MS, REMINDER_MS, LOCKED, 1000);

		assertFalse(d.shouldNotify());
		assertEquals(1000, d.newState().streakStart());
		assertFalse(d.newState().alerted());
	}

	@Test
	public void streakNotYetSustained_doesNotFire() {
		final FastDrainState state = new FastDrainState(1000, false, 0);
		final long now = 1000 + 2 * 60_000L; // only 2 min in
		final FastDrainDecision d = FastDrainDetector.decide(state, true, 30, LIMIT, SUSTAINED_MS, REMINDER_MS, LOCKED, now);

		assertFalse(d.shouldNotify());
		assertEquals(1000, d.newState().streakStart()); // start preserved
		assertFalse(d.newState().alerted());
	}

	@Test
	public void sustained_firesFirstAlertEvenWhileActivelyUsed() {
		final FastDrainState state = new FastDrainState(1000, false, 0);
		final long now = 1000 + SUSTAINED_MS;
		final FastDrainDecision d = FastDrainDetector.decide(state, true, 30, LIMIT, SUSTAINED_MS, REMINDER_MS, USED, now);

		assertTrue(d.shouldNotify());              // warn at least once per episode
		assertTrue(d.newState().alerted());
		assertEquals(now, d.newState().lastReminder());
		assertEquals(SUSTAINED_MS, d.elapsedMs());
	}

	@Test
	public void afterAlert_activelyUsed_staysSilent() {
		final long start = 1000;
		final FastDrainState state = new FastDrainState(start, true, start);
		final long now = start + REMINDER_MS + 60_000L; // well past a reminder gap
		final FastDrainDecision d = FastDrainDetector.decide(state, true, 30, LIMIT, SUSTAINED_MS, REMINDER_MS, USED, now);

		assertFalse(d.shouldNotify());                       // once per episode while the user can see it
		assertEquals(start, d.newState().lastReminder()); // reminder time untouched
	}

	@Test
	public void afterAlert_lockedAndGapElapsed_reminds() {
		final long start = 1000;
		final FastDrainState state = new FastDrainState(start, true, start);
		final long now = start + REMINDER_MS;
		final FastDrainDecision d = FastDrainDetector.decide(state, true, 30, LIMIT, SUSTAINED_MS, REMINDER_MS, LOCKED, now);

		assertTrue(d.shouldNotify());
		assertEquals(now, d.newState().lastReminder());
		assertEquals(REMINDER_MS, d.elapsedMs()); // "for the last 15 minutes"
	}

	@Test
	public void afterAlert_lockedButGapNotElapsed_staysSilent() {
		final long start = 1000;
		final FastDrainState state = new FastDrainState(start, true, start);
		final long now = start + REMINDER_MS - 60_000L; // one minute short of the gap
		final FastDrainDecision d = FastDrainDetector.decide(state, true, 30, LIMIT, SUSTAINED_MS, REMINDER_MS, LOCKED, now);

		assertFalse(d.shouldNotify());
		assertEquals(start, d.newState().lastReminder());
	}

	@Test
	public void reArmedEpisode_canAlertAgainLater() {
		// A calmed episode clears, then a fresh flare re-starts the streak and alerts once sustained.
		final FastDrainDecision calmed = FastDrainDetector.decide(
				new FastDrainState(1000, true, 1000), true, 5, LIMIT, SUSTAINED_MS, REMINDER_MS, LOCKED, 400_000);
		assertEquals(CLEARED, calmed.newState());

		final FastDrainDecision restart = FastDrainDetector.decide(
				calmed.newState(), true, 30, LIMIT, SUSTAINED_MS, REMINDER_MS, LOCKED, 500_000);
		assertFalse(restart.shouldNotify());
		assertEquals(500_000, restart.newState().streakStart());

		final FastDrainDecision reAlert = FastDrainDetector.decide(
				restart.newState(), true, 30, LIMIT, SUSTAINED_MS, REMINDER_MS, LOCKED, 500_000 + SUSTAINED_MS);
		assertTrue(reAlert.shouldNotify());
	}
}
