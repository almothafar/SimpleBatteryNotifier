package com.almothafar.simplebatterynotifier.service;

import com.almothafar.simplebatterynotifier.service.SustainedConditionTracker.Outcome;
import com.almothafar.simplebatterynotifier.service.SustainedConditionTracker.Streak;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the pure slow-charge decision core {@link SlowChargeDetector#decide} (issue #123),
 * unchanged as the behaviour contract after the shared engine was extracted (#163): the
 * sustained-below-floor trigger, the once-per-session guarantee, the re-arm-on-recovery hysteresis, and
 * the observation-gap lapse rule. Power in mW; times in millis. The streak's {@code lastSeen} sits close
 * to "now" where the scenario implies continuous observation — in production every below-floor tick
 * refreshes it.
 */
public class SlowChargeDetectorTest {

	private static final int FLOOR_MW = SlowChargeDetector.FLOOR_MILLIWATTS; // 2500
	private static final int SLOW_MW = 1_500;   // below the floor
	private static final int HEALTHY_MW = 8_000; // well above the floor
	private static final long SUSTAINED_MS = SlowChargeDetector.SUSTAINED_MS; // 3 min
	private static final long MINUTE_MS = 60_000L;

	// Streak(start, alerted, lastSeen) — no reminder concept for slow charge.
	private static final Streak CLEARED = new Streak(0, false, 0);

	@Test
	public void powerUnknown_sleepsAndKeepsStreak() {
		final Streak state = new Streak(1000, true, 2000);
		final Outcome d = SlowChargeDetector.decide(state, false, 0, FLOOR_MW, SUSTAINED_MS, 999_999);

		assertFalse(d.shouldNotify());
		assertEquals(state, d.newState()); // untouched
	}

	@Test
	public void powerAtOrAboveFloor_reArmsSession() {
		final Streak state = new Streak(1000, true, 2000);
		final Outcome d = SlowChargeDetector.decide(state, true, HEALTHY_MW, FLOOR_MW, SUSTAINED_MS, 500_000);

		assertFalse(d.shouldNotify());
		assertEquals(CLEARED, d.newState());
	}

	@Test
	public void floorIsInclusiveHealthy_exactlyAtFloorReArms() {
		// At the floor counts as healthy (re-arm); one milliwatt below starts a slow streak.
		assertEquals(CLEARED, SlowChargeDetector.decide(CLEARED, true, FLOOR_MW, FLOOR_MW, SUSTAINED_MS, 1000).newState());
		assertEquals(1000, SlowChargeDetector.decide(CLEARED, true, FLOOR_MW - 1, FLOOR_MW, SUSTAINED_MS, 1000).newState().start());
	}

	@Test
	public void belowFloor_startsStreakButDoesNotFireYet() {
		final Outcome d = SlowChargeDetector.decide(CLEARED, true, SLOW_MW, FLOOR_MW, SUSTAINED_MS, 1000);

		assertFalse(d.shouldNotify());
		assertEquals(1000, d.newState().start());
		assertEquals(1000, d.newState().lastSeen());
		assertFalse(d.newState().alerted());
	}

	@Test
	public void streakNotYetSustained_doesNotFire() {
		final Streak state = new Streak(1000, false, 1000);
		final long now = 1000 + 2 * MINUTE_MS; // 2 min in, sustained window is 3
		final Outcome d = SlowChargeDetector.decide(state, true, SLOW_MW, FLOOR_MW, SUSTAINED_MS, now);

		assertFalse(d.shouldNotify());
		assertEquals(1000, d.newState().start());     // start preserved
		assertEquals(now, d.newState().lastSeen());   // observation refreshed
		assertFalse(d.newState().alerted());
	}

	@Test
	public void sustained_firesTheOneWarning() {
		final Streak state = new Streak(1000, false, 1000);
		final long now = 1000 + SUSTAINED_MS;
		final Outcome d = SlowChargeDetector.decide(state, true, SLOW_MW, FLOOR_MW, SUSTAINED_MS, now);

		assertTrue(d.shouldNotify());
		assertTrue(d.newState().alerted());
		assertEquals(1000, d.newState().start());
	}

	@Test
	public void afterWarning_staysSilentForTheRestOfTheSession() {
		final long start = 1000;
		final long now = start + SUSTAINED_MS + 20 * MINUTE_MS; // long after the warning
		final Streak state = new Streak(start, true, now - MINUTE_MS);
		final Outcome d = SlowChargeDetector.decide(state, true, SLOW_MW, FLOOR_MW, SUSTAINED_MS, now);

		assertFalse(d.shouldNotify()); // once per charge session — no reminders
		assertTrue(d.newState().alerted());
	}

	@Test
	public void reArmedSession_canWarnAgainLater() {
		// Power recovers (charge fixed), then collapses again in a later session and warns once more.
		final Outcome recovered = SlowChargeDetector.decide(
				new Streak(1000, true, 1000), true, HEALTHY_MW, FLOOR_MW, SUSTAINED_MS, 400_000);
		assertEquals(CLEARED, recovered.newState());

		final Outcome restart = SlowChargeDetector.decide(
				recovered.newState(), true, SLOW_MW, FLOOR_MW, SUSTAINED_MS, 500_000);
		assertFalse(restart.shouldNotify());
		assertEquals(500_000, restart.newState().start());

		final Outcome reAlert = SlowChargeDetector.decide(
				restart.newState(), true, SLOW_MW, FLOOR_MW, SUSTAINED_MS, 500_000 + SUSTAINED_MS);
		assertTrue(reAlert.shouldNotify());
	}

	// --- observation-gap lapse rule ------------------------------------------------------------

	@Test
	public void lapsedGap_restartsStreakInsteadOfWarning() {
		// Below-floor for a bit, then no usable reading for longer than the window (process death / doze).
		// The next below-floor reading must start a fresh episode, not warn on unobserved time.
		final long lastSeen = 1000 + MINUTE_MS;
		final Streak state = new Streak(1000, false, lastSeen);
		final long now = lastSeen + SustainedConditionTracker.MAX_OBSERVATION_GAP_MS + 1;
		final Outcome d = SlowChargeDetector.decide(state, true, SLOW_MW, FLOOR_MW, SUSTAINED_MS, now);

		assertFalse(d.shouldNotify());
		assertEquals(now, d.newState().start());   // fresh episode
		assertEquals(now, d.newState().lastSeen());
	}

	@Test
	public void lapsedGapAfterWarning_startsFreshEpisodeThatCanWarnAgain() {
		final long alertedAt = 1000 + SUSTAINED_MS;
		final Streak state = new Streak(1000, true, alertedAt);
		final long now = alertedAt + SustainedConditionTracker.MAX_OBSERVATION_GAP_MS + 1;
		final Outcome lapsed = SlowChargeDetector.decide(state, true, SLOW_MW, FLOOR_MW, SUSTAINED_MS, now);

		assertFalse(lapsed.shouldNotify());
		assertEquals(now, lapsed.newState().start());
		assertFalse(lapsed.newState().alerted()); // new episode: the warning is re-armed

		final Outcome reAlert = SlowChargeDetector.decide(
				lapsed.newState(), true, SLOW_MW, FLOOR_MW, SUSTAINED_MS, now + SUSTAINED_MS);
		assertTrue(reAlert.shouldNotify());
	}

	@Test
	public void gapAtBoundary_continuesStreak() {
		// A gap of exactly MAX_OBSERVATION_GAP_MS is still continuous; one millisecond more lapses it.
		final long lastSeen = 1000;
		final long atBoundary = lastSeen + SustainedConditionTracker.MAX_OBSERVATION_GAP_MS;
		final Streak state = new Streak(1000, false, lastSeen);

		final Outcome continued = SlowChargeDetector.decide(state, true, SLOW_MW, FLOOR_MW, SUSTAINED_MS, atBoundary);
		assertEquals(1000, continued.newState().start()); // preserved

		final Outcome lapsed = SlowChargeDetector.decide(state, true, SLOW_MW, FLOOR_MW, SUSTAINED_MS, atBoundary + 1);
		assertEquals(atBoundary + 1, lapsed.newState().start()); // restarted
	}
}
