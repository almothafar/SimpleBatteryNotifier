package com.almothafar.simplebatterynotifier.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.almothafar.simplebatterynotifier.model.BatteryDO;
import com.almothafar.simplebatterynotifier.model.ChargeSpeed;
import com.almothafar.simplebatterynotifier.model.ChargeSpeedTier;
import com.almothafar.simplebatterynotifier.service.NotificationService;
import com.almothafar.simplebatterynotifier.service.SystemService;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Broadcast receiver for power connection/disconnection events.
 * <p>
 * When the device is plugged in, this reports what's actually useful — the estimated charging speed
 * and whether it's wired or wireless — rather than the old, often-misleading "AC charger connected"
 * message (issue #122). The AC/USB distinction was dropped because {@code EXTRA_PLUGGED} reports
 * {@code BATTERY_PLUGGED_AC} for many power banks and fast chargers, so it couldn't be trusted; the
 * wired/wireless split, by contrast, is reliable.
 * <p>
 * Charging current reads 0 or noisy for a moment right at plug-in, so the speed is sampled a short
 * delay after connection (see {@link #CHARGE_SAMPLE_DELAY_MS}) rather than synchronously here. The
 * foreground {@code PowerConnectionService} keeps the process alive across that delay.
 */
public class PowerConnectionReceiver extends BroadcastReceiver {

	private static final String TAG = PowerConnectionReceiver.class.getSimpleName();

	/**
	 * Delay between charge-current samples, giving it time to stabilise after plug-in.
	 * Package-visible so tests can advance the main looper by exactly this amount.
	 */
	static final long CHARGE_SAMPLE_DELAY_MS = 2000L;

	/**
	 * How many times to sample the charging current before settling on a speed estimate.
	 * <p>
	 * Two things ramp after plug-in: the current starts at 0/noisy, and a fast charger then spends the
	 * first seconds <em>negotiating</em> — drawing at the safe ~2 W USB default before it jumps to full
	 * power (#227). A single early sample catches either the blank or that handshake trickle, freezing
	 * the one-shot message at "Wired charging" or a bogus "Slow charging ~2 W". Re-sampling across a
	 * wider window (6 &times; {@link #CHARGE_SAMPLE_DELAY_MS} &asymp; 12 s) and reporting the best speed
	 * seen lets the real tier surface. Package-visible so the test can idle the looper across the whole
	 * retry window.
	 */
	static final int MAX_CHARGE_SAMPLE_ATTEMPTS = 6;

	/**
	 * Previous plugged state to prevent duplicate notifications for the same state.
	 * <p>
	 * Atomic because BroadcastReceivers can run concurrently: the dedupe must read, compare and
	 * update in one operation ({@link AtomicInteger#getAndSet}), or two quick broadcasts could both
	 * see the stale state and both pass (issue #156). -1 means "unknown", so the first broadcast
	 * after process start always processes.
	 */
	private static final AtomicInteger currentState = new AtomicInteger(-1);

	// Main-thread handler used to sample the charging speed a short delay after connection. Static so a
	// stale pending sample can be cancelled if the charger is unplugged (or re-plugged) during the delay.
	private static final Handler sampleHandler = new Handler(Looper.getMainLooper());
	private static Runnable pendingSample;

	/**
	 * Update the current plugged state without triggering a notification. Used by
	 * {@code PowerConnectionService} to seed the state at service start, so the first broadcast
	 * after startup doesn't announce a charger that was already plugged in.
	 *
	 * @param state The new plugged state
	 */
	public static void setCurrentState(final int state) {
		currentState.set(state);
	}

	/**
	 * Called when a battery-changed broadcast is received
	 * <p>
	 * This method determines the current battery state, detects whether charging is wired or
	 * wireless, and schedules the charge-connected notification for the user.
	 *
	 * @param context The context in which the receiver is running
	 * @param intent  The delivered {@code ACTION_BATTERY_CHANGED} intent
	 */
	@Override
	public void onReceive(final Context context, final Intent intent) {
		// The receiver is registered for ACTION_BATTERY_CHANGED (see PowerConnectionService), so the
		// delivered intent already carries the battery state — no need to re-query the sticky
		// broadcast (#159). The action check guards against unexpected/spoofed intents.
		if (intent == null || !Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
			Log.w(TAG, "Ignoring unexpected broadcast: " + (intent == null ? "null intent" : intent.getAction()));
			return;
		}

		final int pluggedState = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
		// Read-compare-update in one atomic step: a check-then-act here let two quick broadcasts
		// both pass the dedupe (issue #156).
		if (currentState.getAndSet(pluggedState) == pluggedState) {
			return; // Same state as before, avoid duplicate notifications
		}

		final int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
		final int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
		// Through the single rounding policy (#158) — also guards the scale=-1 default the raw division didn't.
		final int percentage = new BatteryDO().setLevel(level).setScale(scale).getBatteryPercentageInt();

		if (pluggedState > 0) {
			// Charger connected
			handleChargerConnected(context, pluggedState, percentage);
		} else {
			// Charger disconnected
			handleChargerDisconnected(context);
		}
	}

	/**
	 * Handle charger connected event.
	 * <p>
	 * Determines wired vs wireless and schedules the speed sample + notification for a short delay
	 * later. (The old "healthy charge" flag — plugged in at low battery — was retired in #114:
	 * starting low isn't a virtue under modern 20-80% guidance, so the titles no longer flip on it.)
	 *
	 * @param context      The application context
	 * @param pluggedState The type of charger plugged in
	 * @param percentage   Current battery percentage
	 */
	private void handleChargerConnected(final Context context, final int pluggedState, final int percentage) {
		final boolean wireless = pluggedState == BatteryManager.BATTERY_PLUGGED_WIRELESS;
		final Context appContext = context.getApplicationContext();

		// Charging resolves a shown low-battery (or stale full) alert, so dismiss it deliberately —
		// in every charge-notification style, and immediately rather than after the sample delay (#155).
		NotificationService.clearLevelAlert(appContext);
		// A "battery draining fast" warning is a discharge fact; charging makes it stale, so clear it now
		// rather than leaving it to linger until the next drain episode re-posts (it never would).
		NotificationService.clearFastDrainAlert(appContext);

		// Sample the charging speed after a short delay (the current is 0/noisy right at plug-in), then
		// notify. A fast charger keeps ramping through its handshake, so re-sample a few times and report
		// the best speed seen rather than freezing on the first low reading (#227). No best reading yet.
		scheduleChargeSample(appContext, wireless, 1, ChargeSpeed.unknown());

		Log.i(TAG, String.format("Charger connected (Battery: %d%%, Wireless: %s)", percentage, wireless));
	}

	/**
	 * Handle charger disconnected event
	 * <p>
	 * Cancels any pending speed sample, re-arms the charge-session alerts (full-battery + level
	 * de-dupe — see {@link BatteryLevelReceiver#onChargerDisconnected}) and clears active notifications.
	 *
	 * @param context The application context
	 */
	private void handleChargerDisconnected(final Context context) {
		cancelPendingSample();
		BatteryLevelReceiver.onChargerDisconnected(context);
		NotificationService.clearNotifications(context);

		Log.i(TAG, "Charger disconnected");
	}

	/**
	 * Whether a charger is still connected. Used to abort a pending speed sample if the charger was
	 * unplugged during the sampling delay.
	 *
	 * @param context The application context
	 *
	 * @return true when still plugged into a power source
	 */
	private static boolean isStillPlugged(final Context context) {
		final Intent batteryStatus = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
		final int plugged = batteryStatus == null ? 0 : batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
		return plugged > 0;
	}

	/**
	 * Schedule the next charge-speed sample attempt after {@link #CHARGE_SAMPLE_DELAY_MS}.
	 *
	 * @param appContext The application context
	 * @param wireless   Whether charging over a wireless charger
	 * @param attempt    1-based attempt number (see {@link #MAX_CHARGE_SAMPLE_ATTEMPTS})
	 * @param bestSoFar  the highest-power speed seen across the earlier attempts (carried forward)
	 */
	private static void scheduleChargeSample(Context appContext, boolean wireless, int attempt, ChargeSpeed bestSoFar) {
		scheduleSample(() -> sampleChargeSpeed(appContext, wireless, attempt, bestSoFar));
	}

	/**
	 * Read the charging speed and notify — re-sampling while it hasn't clearly ramped yet.
	 * <p>
	 * The current is 0/noisy right at plug-in, and a fast charger then draws at the ~2 W USB default
	 * while it negotiates, so an early sample reads either unknown or a misleading trickle (#227).
	 * Rather than freeze on that, carry the best (highest-power) reading forward and keep re-sampling up
	 * to {@link #MAX_CHARGE_SAMPLE_ATTEMPTS} times: stop early once a reading has clearly ramped (fast+),
	 * otherwise notify with the best speed once the window ends — a genuine slow charger reports trickle,
	 * a device without a current reading falls back to the plain "Wired charging" message. Each attempt
	 * re-checks we're still plugged in, in case the charger was pulled during the delay.
	 *
	 * @param appContext The application context
	 * @param wireless   Whether charging over a wireless charger
	 * @param attempt    1-based attempt number
	 * @param bestSoFar  the highest-power speed seen across the earlier attempts
	 */
	private static void sampleChargeSpeed(Context appContext, boolean wireless, int attempt, ChargeSpeed bestSoFar) {
		if (!isStillPlugged(appContext)) {
			return;
		}
		final ChargeSpeed best = higherPowerOf(bestSoFar, SystemService.getChargeSpeed(appContext));
		if (!isRamped(best) && attempt < MAX_CHARGE_SAMPLE_ATTEMPTS) {
			scheduleChargeSample(appContext, wireless, attempt + 1, best);
			return;
		}
		NotificationService.notifyChargeConnected(appContext, best, wireless);
	}

	/**
	 * The higher-power of two charge-speed estimates. {@link ChargeSpeed#unknown()} reports
	 * {@link ChargeSpeed#UNKNOWN_POWER_MW} (−1&nbsp;mW), so it loses to any real reading and the seed
	 * unknown is replaced by the first usable sample. Pure so it is unit-testable.
	 *
	 * @param a one estimate (typically the best carried forward)
	 * @param b the other (typically this attempt's fresh reading)
	 *
	 * @return whichever estimate reports the higher power (ties keep {@code b}, the fresher reading)
	 */
	static ChargeSpeed higherPowerOf(ChargeSpeed a, ChargeSpeed b) {
		return b.getMilliwatts() >= a.getMilliwatts() ? b : a;
	}

	/**
	 * Whether a speed has clearly ramped past the plug-in negotiation trickle — a fast tier or above.
	 * Sub-fast readings (unknown / trickle / normal) keep the sampler going so a still-ramping charger
	 * isn't settled too early, while a fast+ reading is unambiguous enough to stop on. Pure so it is
	 * unit-testable.
	 *
	 * @param speed the speed estimate
	 *
	 * @return true when the tier is {@code FAST}, {@code SUPER_FAST}, or {@code SUPER_FAST_PLUS}
	 */
	static boolean isRamped(ChargeSpeed speed) {
		final ChargeSpeedTier tier = speed.getTier();
		return tier == ChargeSpeedTier.FAST || tier == ChargeSpeedTier.SUPER_FAST
				|| tier == ChargeSpeedTier.SUPER_FAST_PLUS;
	}

	/**
	 * Schedule the delayed charge sample, cancelling any previously scheduled one so a quick
	 * unplug/replug doesn't fire twice.
	 *
	 * @param sample The sampling task to run after {@link #CHARGE_SAMPLE_DELAY_MS}
	 */
	private static synchronized void scheduleSample(final Runnable sample) {
		cancelPendingSample();
		pendingSample = sample;
		sampleHandler.postDelayed(sample, CHARGE_SAMPLE_DELAY_MS);
	}

	/**
	 * Cancel any pending delayed charge sample.
	 */
	static synchronized void cancelPendingSample() {
		if (pendingSample != null) {
			sampleHandler.removeCallbacks(pendingSample);
			pendingSample = null;
		}
	}
}
