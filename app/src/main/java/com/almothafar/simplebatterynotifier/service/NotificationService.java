package com.almothafar.simplebatterynotifier.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.model.BatteryDO;
import com.almothafar.simplebatterynotifier.ui.MainActivity;
import com.almothafar.simplebatterynotifier.util.GeneralHelper;
import com.almothafar.simplebatterynotifier.util.TemperatureUtils;

import java.lang.ref.WeakReference;
import java.time.LocalTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Service for managing battery notification creation and display
 * <p>
 * This is a utility class that handles all notification-related functionality including:
 * - Creating notification channels
 * - Building and sending battery status notifications
 * - Playing notification sounds
 * - Managing notification lifecycle
 * <p>
 * Thread Safety: This class uses a single-thread executor for async sound playback.
 * The executor is managed statically and should be shutdown when the app terminates
 * by calling {@link #shutdown()}.
 */
public final class NotificationService {
	// Notification types
	public static final int CRITICAL_TYPE = 1;
	public static final int WARNING_TYPE = 2;
	public static final int FULL_LEVEL_TYPE = 3;
	// Battery thresholds
	public static final int RED_ALERT_LEVEL = 4;
	public static final int FULL_PERCENTAGE = 95;
	private static final String TAG = NotificationService.class.getSimpleName();
	// Notification channels
	private static final String CHANNEL_ID_CRITICAL = "battery_critical";
	private static final String CHANNEL_ID_WARNING = "battery_warning";
	private static final String CHANNEL_ID_FULL = "battery_full";
	private static final String CHANNEL_ID_STATUS = "battery_status";
	private static final String CHANNEL_ID_TEMPERATURE = "battery_temperature";
	// Silent, low-importance channel used to deliver an alert quietly during the user's quiet hours:
	// the alert is still visible but makes no sound or vibration (issue #111).
	private static final String CHANNEL_ID_ALERTS_SILENT = "battery_alerts_quiet";

	// Notification settings
	private static final long[] VIBRATION_PATTERN = {0, 500, 250, 500, 250};
	private static final int NOTIFICATION_ID = 1641987;
	// Separate ID for the persistent foreground-service status notification
	private static final int ONGOING_NOTIFICATION_ID = 1641988;
	// Separate ID so a temperature alert doesn't replace a battery-level alert
	private static final int TEMPERATURE_NOTIFICATION_ID = 1641989;

	// Do Not Disturb mode constants
	private static final String ZEN_MODE = "zen_mode";
	private static final int ZEN_MODE_IMPORTANT_INTERRUPTIONS = 1;

	/**
	 * Thread pool for async sound playback
	 * <p>
	 * This single-thread executor is used throughout the app lifetime to play
	 * notification sounds asynchronously. It should be shutdown when the app
	 * terminates by calling {@link #shutdown()}.
	 * <p>
	 * Note: In practice, Android will clean up this executor when the process
	 * terminates, but explicit cleanup is provided for proper resource management.
	 */
	private static final ExecutorService soundExecutor = Executors.newSingleThreadExecutor();

	/**
	 * Cached launcher icon bitmap (performance optimization)
	 * <p>
	 * Uses WeakReference to allow garbage collection if memory is tight.
	 * The icon will be reloaded on next access if garbage collected.
	 * This prevents holding a strong reference to a potentially large bitmap
	 * throughout the app lifetime.
	 */
	private static WeakReference<Bitmap> cachedLauncherIcon;

	// Thread-safe healthy charge state
	private static volatile boolean isHealthyCharge;

	private NotificationService() {
		// Utility class - prevent instantiation
	}

	/**
	 * Send a battery status notification
	 *
	 * @param context The application context
	 * @param type    Notification type (CRITICAL_TYPE, WARNING_TYPE, or FULL_LEVEL_TYPE)
	 */
	public static void sendNotification(final Context context, final int type) {
		if (lacksNotificationPermission(context)) {
			Log.w(TAG, "Missing POST_NOTIFICATIONS permission, notification not sent");
			return;
		}

		createNotificationChannels(context);

		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		final NotificationConfig config = new NotificationConfig(context, prefs, type);

		final Notification.Builder builder = createNotificationBuilder(context, config);
		if (isNull(builder)) {
			return;
		}

		configureNotificationContent(context, builder, config);
		final Notification notification = buildNotification(builder, config);

		sendNotificationToSystem(context, notification);
		playSoundIfNeeded(context, config);
	}

	/**
	 * Send a charge-started notification
	 *
	 * @param context      The application context
	 * @param chargeSource The charging source description
	 */
	public static void sendChargeNotification(final Context context, final String chargeSource) {
		if (lacksNotificationPermission(context)) {
			Log.w(TAG, "Missing POST_NOTIFICATIONS permission, notification not sent");
			return;
		}

		createNotificationChannels(context);

		final String title = isHealthyCharge
		                     ? context.getString(R.string.notification_charge_started_title_healthy)
		                     : context.getString(R.string.notification_charge_started_title_regular);

		final String content = context.getString(R.string.notification_charge_started_content, chargeSource);
		final String ticker = title.concat(", ").concat(content);

		final int iconRes = isHealthyCharge
		                    ? R.drawable.ic_stat_device_battery_charging_20
		                    : R.drawable.ic_stat_device_battery_charging_50;

		final Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID_FULL)
				.setSmallIcon(iconRes)
				.setOnlyAlertOnce(true)
				.setTicker(ticker)
				.setContentTitle(title)
				.setContentText(content)
				.setWhen(System.currentTimeMillis())
				.setLargeIcon(getLauncherIcon(context))
				.setContentIntent(createMainActivityIntent(context))
				.setVisibility(Notification.VISIBILITY_PUBLIC)
				.setStyle(new Notification.BigTextStyle().bigText(content));

		sendNotificationToSystem(context, builder.build());
	}

	/**
	 * Send a high-temperature safety alert.
	 * <p>
	 * Uses a dedicated channel and notification ID so it does not replace battery-level alerts.
	 * Honors the same quiet-hours and silent-mode preferences as the other alerts, reusing the
	 * critical alert sound.
	 *
	 * @param context    The application context
	 * @param rawTenthsC Battery temperature in tenths of a degree Celsius (as reported by BatteryManager)
	 */
	public static void sendTemperatureNotification(final Context context, final int rawTenthsC) {
		if (lacksNotificationPermission(context)) {
			Log.w(TAG, "Missing POST_NOTIFICATIONS permission, temperature alert not sent");
			return;
		}

		createNotificationChannels(context);

		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		final String temperature = TemperatureUtils.format(context, rawTenthsC);

		// A high-temperature warning is not a critical battery alert, so it respects quiet hours: shown
		// on the silent channel outside the window (issue #111).
		final boolean withinWindow = isWithinNotificationWindow(context, prefs);
		final String channelId = withinWindow ? CHANNEL_ID_TEMPERATURE : CHANNEL_ID_ALERTS_SILENT;

		final Notification.Builder builder = new Notification.Builder(context, channelId)
				.setSmallIcon(R.drawable.ic_stat_temperature_hot)
				.setTicker(context.getString(R.string.notification_temperature_ticker))
				.setContentTitle(context.getString(R.string.notification_temperature_title))
				.setContentText(context.getString(R.string.notification_temperature_content, temperature))
				.setWhen(System.currentTimeMillis())
				.setLargeIcon(getLauncherIcon(context))
				.setContentIntent(createMainActivityIntent(context))
				.setVisibility(Notification.VISIBILITY_PUBLIC)
				.setStyle(new Notification.BigTextStyle()
						          .bigText(context.getString(R.string.notification_temperature_content_big, temperature)));

		final NotificationManager manager = getNotificationManager(context);
		if (nonNull(manager)) {
			manager.notify(TEMPERATURE_NOTIFICATION_ID, builder.build());
		}

		final String sound = prefs.getString(
				context.getString(R.string._pref_key_notifications_alert_sound_ringtone),
				context.getString(R.string._default_notification_sound_uri));
		if (withinWindow) {
			playAlarm(context, sound, shouldIgnoreSilentMode(context, prefs), isVibrationEnabled(context, prefs));
		}
	}

	/**
	 * Clear all battery notifications
	 *
	 * @param context The application context
	 */
	public static void clearNotifications(final Context context) {
		final NotificationManager manager = getNotificationManager(context);
		if (nonNull(manager)) {
			manager.cancel(NOTIFICATION_ID);
		}
	}

	/**
	 * ID of the persistent status notification (used by the foreground service).
	 *
	 * @return The ongoing notification ID
	 */
	public static int getOngoingNotificationId() {
		return ONGOING_NOTIFICATION_ID;
	}

	/**
	 * Build the persistent (ongoing) battery-status notification shown by the foreground service.
	 * <p>
	 * This is a low-importance, silent, non-dismissible notification that displays the live
	 * battery percentage, charging state and temperature (AccuBattery-style). It keeps the
	 * monitoring service alive so alerts are delivered even when the app is closed.
	 *
	 * @param context   The application context
	 * @param batteryDO Current battery snapshot, or null if unavailable
	 * @return The built ongoing notification
	 */
	public static Notification buildOngoingNotification(final Context context, final BatteryDO batteryDO) {
		createNotificationChannels(context);

		return new Notification.Builder(context, CHANNEL_ID_STATUS)
				.setSmallIcon(ongoingIconRes(batteryDO))
				.setContentTitle(context.getString(R.string.app_name))
				.setContentText(statusText(context, batteryDO))
				.setContentIntent(createMainActivityIntent(context))
				.setOnlyAlertOnce(true)
				.setOngoing(true)
				.setVisibility(Notification.VISIBILITY_PUBLIC)
				.setCategory(Notification.CATEGORY_STATUS)
				.build();
	}

	/**
	 * Refresh the persistent status notification with the latest battery data.
	 * <p>
	 * Called whenever the battery state changes so the ongoing notification stays live.
	 * Uses the same notification ID as the foreground service, so it updates in place.
	 *
	 * @param context   The application context
	 * @param batteryDO Current battery snapshot, or null if unavailable
	 */
	public static void updateOngoingNotification(final Context context, final BatteryDO batteryDO) {
		if (lacksNotificationPermission(context)) {
			return;
		}
		final NotificationManager manager = getNotificationManager(context);
		if (nonNull(manager)) {
			manager.notify(ONGOING_NOTIFICATION_ID, buildOngoingNotification(context, batteryDO));
		}
	}

	/**
	 * Set healthy charge mode
	 * <p>
	 * Thread-safe setter for the healthy charge state flag.
	 *
	 * @param healthy true if charging in healthy mode
	 */
	public static void setIsHealthy(final boolean healthy) {
		isHealthyCharge = healthy;
	}

	/**
	 * Shutdown the sound executor service
	 * <p>
	 * This method should be called when the application is terminating to ensure
	 * proper cleanup of the background thread pool. In practice, Android will clean
	 * up the executor when the process terminates, but explicit shutdown is good
	 * practice for resource management.
	 * <p>
	 * Note: This is typically called from Application.onTerminate(), though that
	 * method is rarely invoked on production devices.
	 */
	public static void shutdown() {
		soundExecutor.shutdown();
		try {
			if (!soundExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
				soundExecutor.shutdownNow();
			}
		} catch (InterruptedException e) {
			soundExecutor.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Release the cached launcher icon bitmap
	 * <p>
	 * This method clears the cached bitmap reference to help with memory management.
	 * The bitmap will be reloaded on the next access if needed.
	 */
	public static void releaseCachedBitmap() {
		if (nonNull(cachedLauncherIcon)) {
			cachedLauncherIcon.clear();
			cachedLauncherIcon = null;
		}
	}

	// ========== Private Helper Methods ==========

	/**
	 * Check if app lacks POST_NOTIFICATIONS permission (required for API 33+)
	 *
	 * @param context The application context
	 * @return true if permission is missing, false if granted or not required
	 */
	private static boolean lacksNotificationPermission(final Context context) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED;
		}
		return false; // Permission isn't required before API 33, so never lacking
	}

	/**
	 * Create notification channels if they don't exist
	 *
	 * @param context The application context
	 */
	private static void createNotificationChannels(final Context context) {
		final NotificationManager manager = getNotificationManager(context);
		if (isNull(manager)) {
			return;
		}

		final boolean vibrate = PreferenceManager.getDefaultSharedPreferences(context)
		                                          .getBoolean(context.getString(R.string._pref_key_notifications_vibrate), true);

		createChannelIfNotExists(manager, CHANNEL_ID_CRITICAL, "Battery Critical Alerts", "Critical battery level alerts", Color.RED, vibrate);
		createChannelIfNotExists(manager, CHANNEL_ID_WARNING, "Battery Warnings", "Battery warning notifications", Color.rgb(0xff, 0x66, 0x00), vibrate);
		createChannelIfNotExists(manager, CHANNEL_ID_FULL, "Battery Full", "Battery fully charged notifications", Color.GREEN, vibrate);
		createChannelIfNotExists(manager, CHANNEL_ID_TEMPERATURE,
				context.getString(R.string.notification_temperature_channel_name),
				context.getString(R.string.notification_temperature_channel_description), Color.RED, vibrate);
		createSilentChannelIfNotExists(manager, CHANNEL_ID_STATUS,
				context.getString(R.string.notification_status_channel_name),
				context.getString(R.string.notification_status_channel_description));
		createSilentChannelIfNotExists(manager, CHANNEL_ID_ALERTS_SILENT,
				context.getString(R.string.notification_quiet_channel_name),
				context.getString(R.string.notification_quiet_channel_description));
	}

	/**
	 * Create a low-importance, fully silent channel (no sound, vibration, lights or badge).
	 * <p>
	 * Used both by the persistent status notification and, during the user's quiet hours, to deliver
	 * an alert quietly so it stays visible without disturbing the user (issue #111).
	 *
	 * @param manager     The NotificationManager
	 * @param channelId   The channel ID to create
	 * @param name        The channel name
	 * @param description The channel description
	 */
	private static void createSilentChannelIfNotExists(final NotificationManager manager, final String channelId,
	                                                   final String name, final String description) {
		if (nonNull(manager.getNotificationChannel(channelId))) {
			return;
		}

		final NotificationChannel channel = new NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_LOW);
		channel.setDescription(description);
		channel.enableLights(false);
		channel.enableVibration(false);
		channel.setSound(null, null);
		channel.setShowBadge(false);
		manager.createNotificationChannel(channel);
	}

	/**
	 * Create a notification channel if it doesn't already exist
	 *
	 * @param manager   The NotificationManager
	 * @param channelId The channel ID
	 * @param name      The channel name
	 * @param description The channel description
	 * @param ledColor  The LED color for notifications
	 * @param vibrate   Whether the channel should vibrate (from the user's Vibrate preference)
	 */
	private static void createChannelIfNotExists(final NotificationManager manager, final String channelId, final String name,
	                                             final String description, final int ledColor, final boolean vibrate) {
		if (nonNull(manager.getNotificationChannel(channelId))) {
			return;
		}

		final NotificationChannel channel = new NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_HIGH);
		channel.setDescription(description);
		channel.enableLights(true);
		channel.setLightColor(ledColor);
		channel.enableVibration(vibrate);
		if (vibrate) {
			channel.setVibrationPattern(VIBRATION_PATTERN);
		}
		manager.createNotificationChannel(channel);
	}

	/**
	 * Re-create the alert channels so a changed "Vibrate" preference takes effect.
	 * <p>
	 * Android caches a channel's settings after first creation, so the channels must be deleted
	 * and recreated for a new vibration setting to apply. The silent status channel is untouched.
	 *
	 * @param context The application context
	 */
	public static void refreshAlertChannels(final Context context) {
		final NotificationManager manager = getNotificationManager(context);
		if (isNull(manager)) {
			return;
		}
		manager.deleteNotificationChannel(CHANNEL_ID_CRITICAL);
		manager.deleteNotificationChannel(CHANNEL_ID_WARNING);
		manager.deleteNotificationChannel(CHANNEL_ID_FULL);
		manager.deleteNotificationChannel(CHANNEL_ID_TEMPERATURE);
		createNotificationChannels(context);
	}

	/**
	 * Create notification builder with channel and icon
	 *
	 * @param context The application context
	 * @param config  The notification configuration
	 * @return Notification.Builder instance
	 */
	private static Notification.Builder createNotificationBuilder(final Context context, final NotificationConfig config) {
		// During quiet hours the alert is still shown, but on the silent channel so it makes no sound or
		// vibration (issue #111). Alerts allowed to sound keep their high-importance channel.
		final String channelId = config.alertsAllowed ? config.channelId : CHANNEL_ID_ALERTS_SILENT;
		final Notification.Builder builder = new Notification.Builder(context, channelId).setSmallIcon(config.iconRes);

		if (config.type != CRITICAL_TYPE) {
			builder.setOnlyAlertOnce(true);
		}

		return builder;
	}

	/**
	 * Configure notification content (title, text, ticker)
	 *
	 * @param context The application context
	 * @param builder The notification builder
	 * @param config  The notification configuration
	 */
	private static void configureNotificationContent(final Context context, final Notification.Builder builder, final NotificationConfig config) {
		builder.setTicker(config.ticker)
		       .setContentTitle(config.title)
		       .setContentText(config.content)
		       .setWhen(System.currentTimeMillis())
		       .setLargeIcon(getLauncherIcon(context))
		       .setContentIntent(createMainActivityIntent(context))
		       .setVisibility(Notification.VISIBILITY_PUBLIC)
		       .setStyle(new Notification.BigTextStyle().bigText(config.bigContent));
	}

	/**
	 * Build the final notification
	 *
	 * @param builder The notification builder
	 * @param config  The notification configuration
	 * @return The built Notification
	 */
	private static Notification buildNotification(final Notification.Builder builder, final NotificationConfig config) {
		final Notification notification = builder.build();

		if (config.stickyNotification) {
			notification.flags |= Notification.FLAG_NO_CLEAR | Notification.FLAG_ONGOING_EVENT;
		}

		return notification;
	}

	/**
	 * Send notification to system
	 *
	 * @param context      The application context
	 * @param notification The notification to send
	 */
	private static void sendNotificationToSystem(final Context context, final Notification notification) {
		final NotificationManager manager = getNotificationManager(context);
		if (nonNull(manager)) {
			manager.notify(NOTIFICATION_ID, notification);
		}
	}

	/**
	 * Play notification sound if conditions are met
	 *
	 * @param context The application context
	 * @param config  The notification configuration
	 */
	private static void playSoundIfNeeded(final Context context, final NotificationConfig config) {
		if (config.alertsAllowed) {
			playAlarm(context, config.alarmSound, config.ignoreSilent, config.vibrate);
		}
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
	private static void playAlarm(
			final Context context,
			final String soundUriStr,
			final boolean ignoreSilent,
			final boolean vibrate) {
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
	 * Read the user's "Vibrate" preference (defaults to enabled).
	 *
	 * @param context The application context
	 * @param prefs   SharedPreferences containing user settings
	 * @return true if vibration is enabled
	 */
	private static boolean isVibrationEnabled(final Context context, final SharedPreferences prefs) {
		return prefs.getBoolean(context.getString(R.string._pref_key_notifications_vibrate), true);
	}

	/**
	 * Whether the current time falls inside the user's allowed notification window
	 * (always true when the time-range limit is disabled).
	 *
	 * @param context The application context
	 * @param prefs   SharedPreferences containing user settings
	 * @return true if alerts are allowed at the current time
	 */
	private static boolean isWithinNotificationWindow(final Context context, final SharedPreferences prefs) {
		// Default ON to match the toggle's XML default (pref_time_settings.xml). Reading false here let a
		// fresh install that never opened Time Settings alert around the clock, so quiet hours silently
		// did nothing (issue #111). Now quiet hours apply by default (06:30–23:30).
		final boolean limitedTime = prefs.getBoolean(context.getString(R.string._pref_key_notifications_time_range), true);
		final String startTime = prefs.getString(
				context.getString(R.string._pref_key_notifications_time_range_start),
				context.getString(R.string._pref_value_notifications_time_range_start));
		final String endTime = prefs.getString(
				context.getString(R.string._pref_key_notifications_time_range_end),
				context.getString(R.string._pref_value_notifications_time_range_end));
		return isWithinTime(startTime, endTime) || !limitedTime;
	}

	/**
	 * Whether an alert may sound/vibrate right now, given the quiet-hours window and the critical-alert
	 * override. Alerts are allowed inside the window; a critical alert may additionally be allowed to
	 * break through quiet hours when the user has left that option on (default), so a genuinely low
	 * battery still wakes them (issue #111). Pure and Android-free so it is unit-testable.
	 *
	 * @param withinWindow             whether now falls inside the allowed notification window
	 * @param isCritical               whether this is a critical (about-to-die) alert
	 * @param criticalIgnoresQuietHours whether critical alerts are allowed to break through quiet hours
	 * @return true when the alert may sound/vibrate now
	 */
	static boolean alertsAllowedNow(final boolean withinWindow, final boolean isCritical,
	                                final boolean criticalIgnoresQuietHours) {
		return withinWindow || (isCritical && criticalIgnoresQuietHours);
	}

	/**
	 * Whether the user opted to override silent/DND mode for alerts.
	 *
	 * @param context The application context
	 * @param prefs   SharedPreferences containing user settings
	 * @return true if silent mode should be overridden
	 */
	private static boolean shouldIgnoreSilentMode(final Context context, final SharedPreferences prefs) {
		return !prefs.getBoolean(context.getString(R.string._pref_key_notifications_apply_silent_mode), false);
	}

	/**
	 * Check if device is in Do Not Disturb mode
	 *
	 * @param context The application context
	 * @return true if in DND mode, false otherwise
	 */
	private static boolean isInDoNotDisturbMode(final Context context) {
		try {
			return ZEN_MODE_IMPORTANT_INTERRUPTIONS == Settings.Global.getInt(context.getContentResolver(), ZEN_MODE);
		} catch (Settings.SettingNotFoundException e) {
			return false;
		}
	}

	/**
	 * Check if current time is within notification time range
	 *
	 * @param startTime Start time string (HH:mm format)
	 * @param endTime   End time string (HH:mm format)
	 * @return true if current time is within range
	 */
	private static boolean isWithinTime(final String startTime, final String endTime) {
		final LocalTime now = LocalTime.now();
		final int nowMinutes = now.getHour() * 60 + now.getMinute();
		final int startMinutes = GeneralHelper.getHour(startTime) * 60 + GeneralHelper.getMinute(startTime);
		final int endMinutes = GeneralHelper.getHour(endTime) * 60 + GeneralHelper.getMinute(endTime);
		return isWithinTimeRange(nowMinutes, startMinutes, endMinutes);
	}

	/**
	 * Pure minute-of-day range check. Handles same-day, overnight and equal-times windows
	 * (the previous implementation ignored minutes and mishandled overnight ranges that shared
	 * the same hour bucket).
	 * <ul>
	 *   <li>start &lt; end: inside when {@code start <= now < end} (e.g. 08:00–23:00).</li>
	 *   <li>start &gt; end: overnight window, inside when {@code now >= start || now < end} (e.g. 22:00–06:00).</li>
	 *   <li>start == end: treated as a 24-hour window (always inside).</li>
	 * </ul>
	 * Start is inclusive, end is exclusive.
	 *
	 * @param nowMinutes   Current time as minutes since midnight
	 * @param startMinutes Window start as minutes since midnight
	 * @param endMinutes   Window end as minutes since midnight
	 * @return true if now falls inside the window
	 */
	static boolean isWithinTimeRange(final int nowMinutes, final int startMinutes, final int endMinutes) {
		if (startMinutes == endMinutes) {
			return true; // Whole day
		}
		if (startMinutes < endMinutes) {
			return nowMinutes >= startMinutes && nowMinutes < endMinutes;
		}
		// Overnight window wraps past midnight
		return nowMinutes >= startMinutes || nowMinutes < endMinutes;
	}

	/**
	 * Get NotificationManager system service
	 *
	 * @param context The application context
	 * @return NotificationManager instance, or null if unavailable
	 */
	private static NotificationManager getNotificationManager(final Context context) {
		return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
	}

	/**
	 * Get cached launcher icon bitmap (performance optimization)
	 * <p>
	 * Uses WeakReference to allow garbage collection if memory is needed.
	 * The bitmap will be automatically reloaded if it was garbage collected.
	 *
	 * @param context The application context
	 * @return Launcher icon bitmap
	 */
	private static Bitmap getLauncherIcon(final Context context) {
		Bitmap bitmap = null;
		if (nonNull(cachedLauncherIcon)) {
			bitmap = cachedLauncherIcon.get();
		}

		if (isNull(bitmap)) {
			bitmap = BitmapFactory.decodeResource(context.getResources(), R.mipmap.ic_launcher);
			cachedLauncherIcon = new WeakReference<>(bitmap);
		}

		return bitmap;
	}

	/**
	 * Create PendingIntent for MainActivity
	 *
	 * @param context The application context
	 * @return PendingIntent for MainActivity
	 */
	private static PendingIntent createMainActivityIntent(final Context context) {
		final Intent intent = new Intent(context, MainActivity.class);
		final int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
		return PendingIntent.getActivity(context, 0, intent, flags);
	}

	/**
	 * Build the content line of the ongoing status notification, e.g. "85% · Discharging · 32.0 °C".
	 *
	 * @param context   The application context
	 * @param batteryDO Current battery snapshot, or null if unavailable
	 * @return Formatted status text
	 */
	private static String statusText(final Context context, final BatteryDO batteryDO) {
		final int percentage = isNull(batteryDO) ? 0 : Math.round(batteryDO.getBatteryPercentage());
		final String statusLabel = SystemService.getStatusLabel(context, isNull(batteryDO) ? -1 : batteryDO.getStatus());
		final String temperature = isNull(batteryDO) ? "" : TemperatureUtils.format(context, batteryDO.getTemperature());
		return context.getString(R.string.notification_status_content, percentage, statusLabel, temperature);
	}

	/**
	 * Choose a small icon for the ongoing notification that reflects the actual battery state: a
	 * charging bolt only while actively charging, otherwise a plain battery whose fill matches the
	 * level. (A charged-but-still-plugged battery reads as full, without a bolt.)
	 *
	 * @param batteryDO Current battery snapshot, or null if unavailable
	 * @return Drawable resource id
	 */
	private static int ongoingIconRes(final BatteryDO batteryDO) {
		if (isNull(batteryDO)) {
			return R.drawable.ic_stat_battery_full;
		}
		if (batteryDO.getStatus() == BatteryManager.BATTERY_STATUS_CHARGING) {
			return R.drawable.ic_stat_battery_charging;
		}
		return Math.round(batteryDO.getBatteryPercentage()) <= 50
		       ? R.drawable.ic_stat_battery_low
		       : R.drawable.ic_stat_battery_full;
	}

	/**
	 * Configuration object for notification creation (reduces parameter count)
	 * <p>
	 * This class encapsulates all configuration needed to build a notification,
	 * extracted from SharedPreferences and notification type.
	 */
	private static class NotificationConfig {
		final int type;
		final String channelId;
		final int iconRes;
		final String ticker;
		final String title;
		final String content;
		final String bigContent;
		final String alarmSound;
		final boolean alertsAllowed;
		final boolean ignoreSilent;
		final boolean vibrate;
		final boolean stickyNotification;

		/**
		 * Create a NotificationConfig from preferences and type
		 *
		 * @param context The application context
		 * @param prefs   SharedPreferences containing user settings
		 * @param type    Notification type (CRITICAL_TYPE, WARNING_TYPE, or FULL_LEVEL_TYPE)
		 */
		NotificationConfig(final Context context, final SharedPreferences prefs, final int type) {
			this.type = type;

			// Load common preferences
			final int warningLevel = prefs.getInt(context.getString(R.string._pref_key_warn_battery_level), 40);
			final int criticalLevel = prefs.getInt(context.getString(R.string._pref_key_critical_battery_level), 20);

			this.stickyNotification = prefs.getBoolean(context.getString(R.string._pref_key_notifications_sticky), false);
			final boolean withinWindow = isWithinNotificationWindow(context, prefs);
			final boolean criticalIgnoresQuietHours = prefs.getBoolean(context.getString(R.string._pref_key_critical_ignore_quiet_hours), true);
			this.alertsAllowed = alertsAllowedNow(withinWindow, type == CRITICAL_TYPE, criticalIgnoresQuietHours);
			this.ignoreSilent = shouldIgnoreSilentMode(context, prefs);
			this.vibrate = isVibrationEnabled(context, prefs);

			final String defaultSound = context.getString(R.string._default_notification_sound_uri);

			// Configure based on notification type
			switch (type) {
				case CRITICAL_TYPE -> {
					this.channelId = CHANNEL_ID_CRITICAL;
					this.iconRes = R.drawable.ic_stat_device_battery_charging_20;
					this.alarmSound = prefs.getString(context.getString(R.string._pref_key_notifications_alert_sound_ringtone), defaultSound);
					this.ticker = context.getString(R.string.notification_critical_ticker, criticalLevel);
					this.title = context.getString(R.string.notification_critical_title);
					this.content = context.getString(R.string.notification_critical_content, criticalLevel);
					this.bigContent = context.getString(R.string.notification_critical_content_big, criticalLevel);
				}
				case WARNING_TYPE -> {
					this.channelId = CHANNEL_ID_WARNING;
					this.iconRes = R.drawable.ic_stat_device_battery_charging_50;
					this.alarmSound = prefs.getString(context.getString(R.string._pref_key_notifications_warning_sound_ringtone), defaultSound);
					this.ticker = context.getString(R.string.notification_warning_ticker, warningLevel);
					this.title = context.getString(R.string.notification_warning_title);
					this.content = context.getString(R.string.notification_warning_content, warningLevel);
					this.bigContent = context.getString(R.string.notification_warning_content_big, warningLevel);
				}
				case FULL_LEVEL_TYPE -> {
					this.channelId = CHANNEL_ID_FULL;
					this.iconRes = R.drawable.ic_stat_device_battery_charging_full;
					this.alarmSound = prefs.getString(context.getString(R.string._pref_key_notifications_full_sound_ringtone), defaultSound);
					this.ticker = context.getString(R.string.notification_full_level_ticker);
					this.title = isHealthyCharge
					             ? context.getString(R.string.notification_full_level_title_healthy)
					             : context.getString(R.string.notification_full_level_title_regular);
					this.content = context.getString(R.string.notification_full_level_content);
					this.bigContent = context.getString(R.string.notification_full_level_content_big);
				}
				default -> {
					// Fallback to FULL_LEVEL_TYPE configuration
					this.channelId = CHANNEL_ID_FULL;
					this.iconRes = R.drawable.ic_stat_device_battery_charging_full;
					this.alarmSound = defaultSound;
					this.ticker = "";
					this.title = "";
					this.content = "";
					this.bigContent = "";
				}
			}
		}
	}
}
