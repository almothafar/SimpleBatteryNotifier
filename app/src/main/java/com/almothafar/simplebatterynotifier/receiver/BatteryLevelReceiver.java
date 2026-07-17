package com.almothafar.simplebatterynotifier.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import androidx.preference.PreferenceManager;
import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.model.BatteryDO;
import com.almothafar.simplebatterynotifier.service.BatteryHealthTracker;
import com.almothafar.simplebatterynotifier.service.BatteryRateTracker;
import com.almothafar.simplebatterynotifier.service.FastDrainDetector;
import com.almothafar.simplebatterynotifier.service.NotificationService;
import com.almothafar.simplebatterynotifier.service.SlowChargeDetector;
import com.almothafar.simplebatterynotifier.service.SystemService;
import com.almothafar.simplebatterynotifier.util.TemperatureUtils;

import static java.util.Objects.isNull;

/**
 * Broadcast receiver for monitoring battery level changes.
 * Sends notifications when battery reaches critical/warning levels or becomes full.
 * <p>
 * Alert episode state (level-alert de-dupe, full-once-per-charge, temperature hysteresis) is
 * persisted in {@link SharedPreferences} so it survives process death (#164) — doze, OEM task
 * killers and memory pressure routinely kill a background-monitoring app, and in-memory state
 * previously reset mid-episode, firing duplicate alerts. The logic follows the same
 * load → pure decide → save-on-change pattern as {@link FastDrainDetector} and
 * {@link SlowChargeDetector}.
 * <p>
 * <b>Threading:</b> there is deliberately no lock. Both this receiver and the unplug reset
 * ({@link PowerConnectionReceiver} → {@link #onChargerDisconnected}) are registered on and
 * delivered to the main thread (see {@code PowerConnectionService}), so all state access is
 * serialized by the main looper.
 */
public class BatteryLevelReceiver extends BroadcastReceiver {

	/** "No alert sent" marker for {@code prevType}; the real types (1-3) live in NotificationService. */
	static final int NO_ALERT = 0;

	/**
	 * How far (in °C) the battery must cool below the threshold before another high-temperature
	 * alert can fire. Hysteresis prevents repeated alerts during a single hot spell.
	 */
	private static final int TEMPERATURE_HYSTERESIS_C = 3;

	// Persisted alert episode state (survives process restarts, #164).
	private static final String PREF_PREV_LEVEL = "_level_alert_prev_level";
	private static final String PREF_PREV_TYPE = "_level_alert_prev_type";
	private static final String PREF_FULL_NOTIFIED = "_level_alert_full_notified";
	private static final String PREF_TEMPERATURE_ALERTED = "_temperature_alert_sent";

	/**
	 * Charger-disconnect reset: re-arms the alerts whose episode is bounded by a charge session —
	 * the full-battery alert and the critical/warning de-dupe — so the new discharge session can
	 * alert afresh. Deliberately <b>not</b> reset (#164):
	 * <ul>
	 *   <li>{@code prevLevel} — the next broadcast still compares against the real last-seen level,
	 *       so an unchanged level keeps skipping the discharge branch;</li>
	 *   <li>the temperature flag — a hot spell doesn't end at unplug; cooling below the threshold
	 *       (the hysteresis in {@link #decideTemperature}) is its only re-arm.</li>
	 * </ul>
	 *
	 * @param context The application context
	 */
	public static void onChargerDisconnected(final Context context) {
		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		final LevelAlertState state = loadLevelState(prefs);
		final LevelAlertState reset = new LevelAlertState(state.prevLevel(), NO_ALERT, false);
		if (!reset.equals(state)) {
			saveLevelState(prefs, reset);
		}
	}

	@Override
	public void onReceive(final Context context, final Intent intent) {
		final Intent batteryStatus = context.getApplicationContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
		if (isNull(batteryStatus)) {
			return; // Cannot determine battery status, exit early
		}

		final int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
		final boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING;
		final boolean isFull = status == BatteryManager.BATTERY_STATUS_FULL;

		// Reuse the sticky intent we already read above instead of triggering a second read.
		final BatteryDO batteryDO = SystemService.getBatteryInfo(context, batteryStatus);

		// Feed the charge/drain rate window from this broadcast (no polling timer of our own) so both the
		// ongoing notification below and the details table reflect the latest reading (issue #108). The
		// fast-drain alert (#109) then evaluates the same smoothed rate.
		final BatteryRateTracker.BatteryRate rate = BatteryRateTracker.record(context, batteryDO);

		// Keep the persistent foreground-service status notification live with the latest reading,
		// reusing the rate just computed instead of re-parsing the persisted sample window.
		NotificationService.updateOngoingNotification(context, batteryDO, rate);

		if (isNull(batteryDO)) {
			// Without a real reading, don't assume a level. Previously this defaulted to 100%,
			// which silently suppressed genuine low/critical alerts on a transient read failure.
			return;
		}
		final int percentage = batteryDO.getBatteryPercentageInt();

		// Track battery health and charge cycles
		BatteryHealthTracker.recordBatteryState(context, percentage, status);

		final SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
		final LevelAlertConfig config = new LevelAlertConfig(
				sharedPref.getInt(context.getString(R.string._pref_key_critical_battery_level), 20),
				sharedPref.getInt(context.getString(R.string._pref_key_warn_battery_level), 40),
				sharedPref.getBoolean(context.getString(R.string._pref_key_notify_for_warning_level), true),
				sharedPref.getBoolean(context.getString(R.string._pref_key_notify_for_full_level), true),
				sharedPref.getBoolean(context.getString(R.string._pref_key_notify_every_tick), false));

		final LevelAlertState previous = loadLevelState(sharedPref);
		final LevelAlertDecision decision = decideLevelAlert(previous, percentage, isCharging, isFull, config);

		// Persist only on change: most broadcasts (voltage/temperature deltas) re-decide an identical
		// state, and rewriting it would churn SharedPreferences on every tick.
		if (!decision.newState().equals(previous)) {
			saveLevelState(sharedPref, decision.newState());
		}
		if (decision.notifyType() != NO_ALERT) {
			NotificationService.sendNotification(context, decision.notifyType());
		}

		handleTemperature(context, batteryDO, sharedPref);

		// #109: warn when the (smoothed #108) drain rate stays abnormally high for a sustained time.
		FastDrainDetector.evaluate(context, batteryDO, rate);

		// #123: warn when charging power stays abnormally low for a sustained time (frayed cable, dirty
		// port, or dying charger). Independent of the drain rate — it reads the estimated charge wattage.
		SlowChargeDetector.evaluate(context, batteryDO);
	}

	/**
	 * Send a high-temperature safety alert when the battery exceeds the configured threshold.
	 * <p>
	 * The hysteresis flag is persisted (#164) so a process restart mid-hot-spell cannot fire a
	 * duplicate alert; another alert can only fire once the battery has cooled at least
	 * {@link #TEMPERATURE_HYSTERESIS_C}°C below the threshold.
	 *
	 * @param context    The application context
	 * @param batteryDO  Current battery snapshot (non-null; the caller already checked)
	 * @param sharedPref The shared preferences
	 */
	private void handleTemperature(final Context context, final BatteryDO batteryDO, final SharedPreferences sharedPref) {
		final boolean enabled = sharedPref.getBoolean(context.getString(R.string._pref_key_notify_high_temperature), true);

		// The threshold is stored canonically in Celsius; the battery reading is also Celsius
		// (tenths), so a chilly 45 °F (~7 °C) never trips a 45 °C threshold. See TemperatureUtils.
		final int thresholdCelsius = sharedPref.getInt(
				context.getString(R.string._pref_key_high_temperature_threshold),
				TemperatureUtils.DEFAULT_HIGH_TEMP_THRESHOLD_C);
		final int rawTenthsC = batteryDO.getTemperature();

		final boolean previouslyAlerted = sharedPref.getBoolean(PREF_TEMPERATURE_ALERTED, false);
		final TemperatureDecision decision = decideTemperature(previouslyAlerted, enabled, rawTenthsC, thresholdCelsius);

		if (decision.alerted() != previouslyAlerted) {
			sharedPref.edit().putBoolean(PREF_TEMPERATURE_ALERTED, decision.alerted()).apply();
		}
		if (decision.shouldNotify()) {
			NotificationService.sendTemperatureNotification(context, rawTenthsC);
		}
	}

	/**
	 * Pure decision core for the critical/warning/full level alerts, unit-testable with no Android
	 * dependencies (#164). A changed level while discharging is judged against the thresholds; an
	 * unchanged level or a charging/full state runs the full-battery logic instead — the same split
	 * the receiver has always used, now explicit.
	 *
	 * @param state      current persisted episode state
	 * @param percentage current battery percentage (whole, via the single rounding policy #158)
	 * @param charging   whether the battery status is {@code BATTERY_STATUS_CHARGING}
	 * @param full       whether the battery status is {@code BATTERY_STATUS_FULL}
	 * @param config     the user's alert thresholds and toggles
	 *
	 * @return which alert to send now ({@link #NO_ALERT} for none) and the new state to persist
	 */
	static LevelAlertDecision decideLevelAlert(final LevelAlertState state, final int percentage,
	                                           final boolean charging, final boolean full,
	                                           final LevelAlertConfig config) {
		final boolean levelChanged = state.prevLevel() != percentage;
		if (levelChanged && !charging) {
			return decideDischarging(state, percentage, config);
		}
		return decideChargingOrFull(state, percentage, full, config);
	}

	/**
	 * The discharging side: critical first, then warning, de-duplicated via {@code prevType} —
	 * except at/below the red-alert floor, where the critical alert always re-fires.
	 */
	private static LevelAlertDecision decideDischarging(final LevelAlertState state, final int percentage,
	                                                    final LevelAlertConfig config) {
		// Force the critical alert through the de-dupe at the red-alert floor: about to die trumps "already told you".
		int prevType = percentage <= NotificationService.RED_ALERT_LEVEL ? NO_ALERT : state.prevType();
		int notifyType = NO_ALERT;

		if (percentage <= config.criticalLevel()) {
			if (prevType != NotificationService.CRITICAL_TYPE || config.alertEveryTick()) {
				notifyType = NotificationService.CRITICAL_TYPE;
			}
			prevType = NotificationService.CRITICAL_TYPE;
		} else if (percentage <= config.warningLevel() && config.warningEnabled()) {
			if (prevType != NotificationService.WARNING_TYPE) {
				notifyType = NotificationService.WARNING_TYPE;
			}
			prevType = NotificationService.WARNING_TYPE;
		}
		return new LevelAlertDecision(notifyType, new LevelAlertState(percentage, prevType, state.fullNotified()));
	}

	/**
	 * The charging-or-unchanged side: the full alert fires once per charge session, re-armed once
	 * the level has genuinely dropped out of the full band while staying above the warning band.
	 */
	private static LevelAlertDecision decideChargingOrFull(final LevelAlertState state, final int percentage,
	                                                       final boolean full, final LevelAlertConfig config) {
		boolean fullNotified = state.fullNotified();
		int notifyType = NO_ALERT;

		if (!fullNotified && full && config.fullNotifyEnabled()) {
			notifyType = NotificationService.FULL_LEVEL_TYPE;
			fullNotified = true;
		}
		if (percentage <= NotificationService.FULL_PERCENTAGE && percentage > config.warningLevel()) {
			fullNotified = false;
		}
		return new LevelAlertDecision(notifyType, new LevelAlertState(percentage, state.prevType(), fullNotified));
	}

	/**
	 * Pure decision core for the high-temperature alert's hysteresis (#18, #164):
	 * <ul>
	 *   <li><b>Disabled</b> — never notify, and re-arm so re-enabling starts fresh;</li>
	 *   <li><b>At/above the threshold</b> — notify only if not already alerted this hot spell;</li>
	 *   <li><b>Cooled below threshold − hysteresis</b> — re-arm for the next spell;</li>
	 *   <li><b>In the hysteresis band between them</b> — hold the current state.</li>
	 * </ul>
	 *
	 * @param alreadyAlerted   whether this hot spell's alert has already fired
	 * @param enabled          whether the high-temperature alert is enabled
	 * @param rawTenthsC       battery temperature in tenths of a degree Celsius
	 * @param thresholdCelsius alert threshold in whole degrees Celsius
	 *
	 * @return whether to notify now, and the new alerted flag to persist
	 */
	static TemperatureDecision decideTemperature(final boolean alreadyAlerted, final boolean enabled,
	                                             final int rawTenthsC, final int thresholdCelsius) {
		if (!enabled) {
			return new TemperatureDecision(false, false);
		}
		if (TemperatureUtils.isAtOrAboveThreshold(rawTenthsC, thresholdCelsius)) {
			return new TemperatureDecision(!alreadyAlerted, true);
		}
		if (TemperatureUtils.isBelowResetThreshold(rawTenthsC, thresholdCelsius, TEMPERATURE_HYSTERESIS_C)) {
			return new TemperatureDecision(false, false);
		}
		return new TemperatureDecision(false, alreadyAlerted);
	}

	static LevelAlertState loadLevelState(final SharedPreferences prefs) {
		return new LevelAlertState(
				prefs.getInt(PREF_PREV_LEVEL, 0),
				prefs.getInt(PREF_PREV_TYPE, NO_ALERT),
				prefs.getBoolean(PREF_FULL_NOTIFIED, false));
	}

	static void saveLevelState(final SharedPreferences prefs, final LevelAlertState state) {
		prefs.edit()
		     .putInt(PREF_PREV_LEVEL, state.prevLevel())
		     .putInt(PREF_PREV_TYPE, state.prevType())
		     .putBoolean(PREF_FULL_NOTIFIED, state.fullNotified())
		     .apply();
	}

	/**
	 * Persisted level-alert episode state (#164).
	 *
	 * @param prevLevel    the percentage seen on the previous broadcast (gates the discharge branch)
	 * @param prevType     the last level alert sent while discharging ({@link #NO_ALERT} when none)
	 * @param fullNotified whether the full-battery alert has fired this charge session
	 */
	record LevelAlertState(int prevLevel, int prevType, boolean fullNotified) {
	}

	/**
	 * The user's alert thresholds and toggles (reduces parameter count, like
	 * {@code NotificationService.NotificationConfig}).
	 *
	 * @param criticalLevel     critical alert threshold in percent
	 * @param warningLevel      warning alert threshold in percent
	 * @param warningEnabled    whether the warning alert is enabled
	 * @param fullNotifyEnabled whether the full-battery alert is enabled
	 * @param alertEveryTick    whether the critical alert repeats on every level tick
	 */
	record LevelAlertConfig(int criticalLevel, int warningLevel, boolean warningEnabled,
	                        boolean fullNotifyEnabled, boolean alertEveryTick) {
	}

	/**
	 * Result of {@link #decideLevelAlert}: which alert to send now, and the state to persist.
	 *
	 * @param notifyType a {@code NotificationService} type, or {@link #NO_ALERT} for none
	 * @param newState   the state to persist
	 */
	record LevelAlertDecision(int notifyType, LevelAlertState newState) {
	}

	/**
	 * Result of {@link #decideTemperature}: whether to notify now, and the flag to persist.
	 *
	 * @param shouldNotify whether to send the temperature alert now
	 * @param alerted      whether the current hot spell counts as alerted
	 */
	record TemperatureDecision(boolean shouldNotify, boolean alerted) {
	}
}
