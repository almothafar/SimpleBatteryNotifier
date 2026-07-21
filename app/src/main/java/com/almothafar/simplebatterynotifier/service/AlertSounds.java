package com.almothafar.simplebatterynotifier.service;

import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioManager;
import android.net.Uri;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.util.Objects.isNull;

/**
 * The alert sound/vibration playback path (issue #166): plays the user's alarm sound (and optionally
 * vibrates) when the phone is silenced but the user opted to override silent/DND mode. In normal ringer
 * mode the high-importance notification channel plays its own sound, so this stays out of the way.
 * <p>
 * Split out of {@code NotificationService} so it owns its own thread pool. The single-thread executor
 * runs for the app's lifetime and is reclaimed on process death (there is no {@code Application} to hook
 * an explicit shutdown to).
 */
final class AlertSounds {

	/**
	 * Thread pool for async sound playback. This single-thread executor is used throughout the app
	 * lifetime to play notification sounds asynchronously. Android reclaims it when the process
	 * terminates.
	 */
	private static final ExecutorService soundExecutor = Executors.newSingleThreadExecutor();

	private AlertSounds() {
		// Utility class - prevent instantiation
	}

	/**
	 * Play the alert sound (and optionally vibrate) when the phone is silenced but the user opted
	 * to override silent mode. In normal ringer mode the notification channel plays its own sound.
	 * <p>
	 * Callers must first check that alerts are allowed right now (quiet hours / critical override).
	 *
	 * @param context      The application context
	 * @param soundUriStr  The alarm sound URI string
	 * @param ignoreSilent Whether the user opted to override silent/DND mode
	 * @param vibrate      Whether the user enabled vibration
	 */
	static void playAlarm(Context context, String soundUriStr, boolean ignoreSilent, boolean vibrate) {
		final Uri soundUri = Uri.parse(soundUriStr);
		final AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
		if (isNull(audioManager)) {
			return;
		}

		final boolean isNotNormalRingerMode = audioManager.getRingerMode() != AudioManager.RINGER_MODE_NORMAL || isInDoNotDisturbMode(context);

		if (ignoreSilent && isNotNormalRingerMode) {
			soundExecutor.execute(() -> {
				SystemService.playSound(context, soundUri);
				if (vibrate) {
					SystemService.vibratePhone(context);
				}
			});
		}
	}

	/**
	 * Whether the device is in any Do Not Disturb mode (priority-only, alarms-only or total silence).
	 * <p>
	 * Uses the public {@link NotificationManager#getCurrentInterruptionFilter()} instead of the
	 * undocumented {@code Settings.Global "zen_mode"} (issue #167). Any filter other than
	 * {@code INTERRUPTION_FILTER_ALL} counts as "silenced": priority-only, alarms-only and total silence
	 * all suppress the app's alerts, so a user who opted to override silent/DND mode should still be
	 * alerted — a broader, more correct reading than the old priority-only check. {@code UNKNOWN} (and a
	 * null service) mean "can't tell", so they are treated as not-DND, leaving the alert on its normal path.
	 *
	 * @param context The application context
	 * @return true if the device is in any DND mode
	 */
	private static boolean isInDoNotDisturbMode(Context context) {
		final NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
		if (isNull(manager)) {
			return false;
		}
		final int filter = manager.getCurrentInterruptionFilter();
		return filter != NotificationManager.INTERRUPTION_FILTER_ALL
				&& filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
	}
}
