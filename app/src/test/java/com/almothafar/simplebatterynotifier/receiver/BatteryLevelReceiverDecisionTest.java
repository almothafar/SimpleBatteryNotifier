package com.almothafar.simplebatterynotifier.receiver;

import com.almothafar.simplebatterynotifier.receiver.BatteryLevelReceiver.LevelAlertConfig;
import com.almothafar.simplebatterynotifier.receiver.BatteryLevelReceiver.LevelAlertDecision;
import com.almothafar.simplebatterynotifier.receiver.BatteryLevelReceiver.LevelAlertState;
import com.almothafar.simplebatterynotifier.receiver.BatteryLevelReceiver.TemperatureDecision;
import com.almothafar.simplebatterynotifier.service.AlertType;
import com.almothafar.simplebatterynotifier.service.NotificationService;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link BatteryLevelReceiver}'s pure decision cores (#164), in the
 * {@code FastDrainDetectorTest} style: the critical/warning de-dupe, the red-alert override, the
 * full-once-per-charge episode with its re-arm band, and the temperature hysteresis. Because the
 * state is now a value passed in and returned, every test doubles as a process-restart test: the
 * decision depends only on what was persisted, not on in-memory history.
 */
public class BatteryLevelReceiverDecisionTest {

	private static final int CRITICAL = 20;
	private static final int WARNING = 40;
	private static final int THRESHOLD_C = 45;

	private static final LevelAlertConfig DEFAULTS = new LevelAlertConfig(CRITICAL, WARNING, true, true, false);
	private static final LevelAlertState FRESH = new LevelAlertState(0, null, false);

	private static final boolean DISCHARGING = false;
	private static final boolean NOT_FULL = false;

	// --- discharging: critical/warning thresholds and de-dupe -----------------------------------

	@Test
	public void discharging_belowCritical_firesCriticalOnce() {
		final LevelAlertDecision first = BatteryLevelReceiver.decideLevelAlert(
				new LevelAlertState(16, null, false), 15, DISCHARGING, NOT_FULL, DEFAULTS);

		assertEquals(AlertType.CRITICAL, first.notifyType());
		assertEquals(new LevelAlertState(15, AlertType.CRITICAL, false), first.newState());

		// Next tick, still below critical: the persisted prevType suppresses the duplicate.
		final LevelAlertDecision second = BatteryLevelReceiver.decideLevelAlert(
				first.newState(), 14, DISCHARGING, NOT_FULL, DEFAULTS);
		assertNull(second.notifyType());
		assertEquals(14, second.newState().prevLevel());
	}

	@Test
	public void discharging_inWarningBand_firesWarningOnce() {
		final LevelAlertDecision first = BatteryLevelReceiver.decideLevelAlert(
				new LevelAlertState(41, null, false), 38, DISCHARGING, NOT_FULL, DEFAULTS);

		assertEquals(AlertType.WARNING, first.notifyType());

		final LevelAlertDecision second = BatteryLevelReceiver.decideLevelAlert(
				first.newState(), 35, DISCHARGING, NOT_FULL, DEFAULTS);
		assertNull(second.notifyType());
	}

	@Test
	public void discharging_aboveWarning_noAlert() {
		final LevelAlertDecision d = BatteryLevelReceiver.decideLevelAlert(
				new LevelAlertState(81, null, false), 80, DISCHARGING, NOT_FULL, DEFAULTS);

		assertNull(d.notifyType());
		assertEquals(80, d.newState().prevLevel());
	}

	@Test
	public void discharging_warningDisabled_staysSilentInWarningBand() {
		final LevelAlertConfig noWarning = new LevelAlertConfig(CRITICAL, WARNING, false, true, false);
		final LevelAlertDecision d = BatteryLevelReceiver.decideLevelAlert(
				new LevelAlertState(41, null, false), 38, DISCHARGING, NOT_FULL, noWarning);

		assertNull(d.notifyType());
	}

	@Test
	public void discharging_warningThenCritical_escalates() {
		final LevelAlertState afterWarning = new LevelAlertState(35, AlertType.WARNING, false);
		final LevelAlertDecision d = BatteryLevelReceiver.decideLevelAlert(
				afterWarning, 20, DISCHARGING, NOT_FULL, DEFAULTS);

		assertEquals(AlertType.CRITICAL, d.notifyType());
	}

	@Test
	public void discharging_alertEveryTick_repeatsCritical() {
		final LevelAlertConfig everyTick = new LevelAlertConfig(CRITICAL, WARNING, true, true, true);
		final LevelAlertState alreadyCritical = new LevelAlertState(15, AlertType.CRITICAL, false);
		final LevelAlertDecision d = BatteryLevelReceiver.decideLevelAlert(
				alreadyCritical, 14, DISCHARGING, NOT_FULL, everyTick);

		assertEquals(AlertType.CRITICAL, d.notifyType());
	}

	@Test
	public void discharging_atRedAlertFloor_overridesDeDupe() {
		// Already alerted critical this episode, but at/below the red-alert level it must re-fire.
		final LevelAlertState alreadyCritical = new LevelAlertState(5, AlertType.CRITICAL, false);
		final LevelAlertDecision d = BatteryLevelReceiver.decideLevelAlert(
				alreadyCritical, NotificationService.RED_ALERT_LEVEL, DISCHARGING, NOT_FULL, DEFAULTS);

		assertEquals(AlertType.CRITICAL, d.notifyType());
	}

	@Test
	public void discharging_unchangedLevel_doesNotAlert() {
		// Same level as last tick routes to the charging-or-full branch (the receiver's historical
		// split), so a repeated broadcast at the same percentage can't duplicate a level alert.
		final LevelAlertDecision d = BatteryLevelReceiver.decideLevelAlert(
				new LevelAlertState(15, AlertType.CRITICAL, false), 15, DISCHARGING, NOT_FULL, DEFAULTS);

		assertNull(d.notifyType());
	}

	// --- charging / full: once per charge session ------------------------------------------------

	@Test
	public void charging_full_firesOnceThenHolds() {
		final LevelAlertState atHundred = new LevelAlertState(100, null, false);
		final LevelAlertDecision first = BatteryLevelReceiver.decideLevelAlert(atHundred, 100, false, true, DEFAULTS);

		assertEquals(AlertType.FULL, first.notifyType());
		assertTrue(first.newState().fullNotified());

		final LevelAlertDecision second = BatteryLevelReceiver.decideLevelAlert(first.newState(), 100, false, true, DEFAULTS);
		assertNull(second.notifyType());
	}

	@Test
	public void charging_fullDisabled_staysSilent() {
		final LevelAlertConfig noFull = new LevelAlertConfig(CRITICAL, WARNING, true, false, false);
		final LevelAlertDecision d = BatteryLevelReceiver.decideLevelAlert(
				new LevelAlertState(100, null, false), 100, false, true, noFull);

		assertNull(d.notifyType());
		assertFalse(d.newState().fullNotified());
	}

	@Test
	public void charging_levelLeavesFullBand_reArmsFullAlert() {
		// Notified at full, then the level drops to 90 (≤ FULL_PERCENTAGE, above warning): re-armed.
		final LevelAlertState notified = new LevelAlertState(100, null, true);
		final LevelAlertDecision d = BatteryLevelReceiver.decideLevelAlert(notified, 90, true, NOT_FULL, DEFAULTS);

		assertNull(d.notifyType());
		assertFalse(d.newState().fullNotified());
	}

	@Test
	public void charging_belowWarningBand_doesNotReArmFullAlert() {
		// The re-arm band is (warning, FULL_PERCENTAGE]: charging low keeps the flag as-is.
		final LevelAlertState notified = new LevelAlertState(100, null, true);
		final LevelAlertDecision d = BatteryLevelReceiver.decideLevelAlert(notified, 30, true, NOT_FULL, DEFAULTS);

		assertTrue(d.newState().fullNotified());
	}

	// --- charger-disconnect reset semantics (via the pure state) ---------------------------------

	@Test
	public void restartMidEpisode_persistedStateSuppressesDuplicates() {
		// Process death loses nothing: the decision on the persisted state after a "restart" is the
		// same as it would have been in-process — no duplicate critical while still below threshold.
		final LevelAlertState persisted = new LevelAlertState(15, AlertType.CRITICAL, false);
		final LevelAlertDecision d = BatteryLevelReceiver.decideLevelAlert(
				persisted, 13, DISCHARGING, NOT_FULL, DEFAULTS);

		assertNull(d.notifyType());
	}

	// --- temperature hysteresis -------------------------------------------------------------------

	@Test
	public void temperature_aboveThreshold_firesOnce() {
		final TemperatureDecision first = BatteryLevelReceiver.decideTemperature(false, true, 460, THRESHOLD_C);
		assertTrue(first.shouldNotify());
		assertTrue(first.alerted());

		final TemperatureDecision second = BatteryLevelReceiver.decideTemperature(first.alerted(), true, 470, THRESHOLD_C);
		assertFalse(second.shouldNotify());
		assertTrue(second.alerted());
	}

	@Test
	public void temperature_inHysteresisBand_holdsState() {
		// 43.0 °C: below the 45° threshold but not yet 3° cooler — the alerted flag must hold, so a
		// process restart in this band can't re-fire when the temperature ticks back up.
		final TemperatureDecision d = BatteryLevelReceiver.decideTemperature(true, true, 430, THRESHOLD_C);
		assertFalse(d.shouldNotify());
		assertTrue(d.alerted());
	}

	@Test
	public void temperature_cooledBelowHysteresis_reArms() {
		final TemperatureDecision cooled = BatteryLevelReceiver.decideTemperature(true, true, 420, THRESHOLD_C);
		assertFalse(cooled.shouldNotify());
		assertFalse(cooled.alerted());

		// The next spell alerts again.
		final TemperatureDecision reAlert = BatteryLevelReceiver.decideTemperature(cooled.alerted(), true, 455, THRESHOLD_C);
		assertTrue(reAlert.shouldNotify());
	}

	@Test
	public void temperature_disabled_neverNotifiesAndReArms() {
		final TemperatureDecision d = BatteryLevelReceiver.decideTemperature(true, false, 470, THRESHOLD_C);
		assertFalse(d.shouldNotify());
		assertFalse(d.alerted());
	}
}
