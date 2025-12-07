package com.almothafar.simplebatterynotifier.service;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

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
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.ui.MainActivity;
import com.almothafar.simplebatterynotifier.util.GeneralHelper;

import java.util.Calendar;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Service for managing battery notification creation and display
 */
public final class NotificationService {
	private static final String TAG = NotificationService.class.getSimpleName();

	// Notification types
	public static final int CRITICAL_TYPE = 1;
	public static final int WARNING_TYPE = 2;
	public static final int FULL_LEVEL_TYPE = 3;

	// Battery thresholds
	public static final int RED_ALERT_LEVEL = 4;
	public static final int FULL_PERCENTAGE = 95;

	// Notification channels
	private static final String CHANNEL_ID_CRITICAL = "battery_critical";
	private static final String CHANNEL_ID_WARNING = "battery_warning";
	private static final String CHANNEL_ID_FULL = "battery_full";

	// Notification settings
	private static final long[] VIBRATION_PATTERN = {0, 500, 250, 500, 250};
	private static final int NOTIFICATION_ID = 1641987;

	// Do Not Disturb mode constants
	private static final String ZEN_MODE = "zen_mode";
	private static final int ZEN_MODE_IMPORTANT_INTERRUPTIONS = 1;

	// Thread pool for async sound playback
	private static final ExecutorService soundExecutor = Executors.newSingleThreadExecutor();

	// Cached launcher icon bitmap (performance optimization)
	private static Bitmap cachedLauncherIcon;

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
	 * Clear all battery notifications
	 */
	public static void clearNotifications(final Context context) {
		final NotificationManager manager = getNotificationManager(context);
		if (nonNull(manager)) {
			manager.cancel(NOTIFICATION_ID);
		}
	}

	/**
	 * Set healthy charge mode
	 *
	 * @param healthy true if charging in healthy mode
	 */
	public static void setIsHealthy(final boolean healthy) {
		isHealthyCharge = healthy;
	}

	// ========== Private Helper Methods ==========

	/**
	 * Check if app lacks POST_NOTIFICATIONS permission (required for API 33+)
	 *
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
	 */
	private static void createNotificationChannels(final Context context) {
		final NotificationManager manager = getNotificationManager(context);
		if (isNull(manager)) {
			return;
		}

		createChannelIfNotExists(manager, CHANNEL_ID_CRITICAL, "Battery Critical Alerts", "Critical battery level alerts", Color.RED);
		createChannelIfNotExists(manager, CHANNEL_ID_WARNING, "Battery Warnings", "Battery warning notifications", Color.rgb(0xff, 0x66, 0x00));
		createChannelIfNotExists(manager, CHANNEL_ID_FULL, "Battery Full", "Battery fully charged notifications", Color.GREEN);
	}

	/**
	 * Create a notification channel if it doesn't already exist
	 */
	private static void createChannelIfNotExists(final NotificationManager manager, final String channelId, final String name,
	                                              final String description, final int ledColor) {
		if (nonNull(manager.getNotificationChannel(channelId))) {
			return;
		}

		final NotificationChannel channel = new NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_HIGH);
		channel.setDescription(description);
		channel.enableLights(true);
		channel.setLightColor(ledColor);
		channel.enableVibration(true);
		channel.setVibrationPattern(VIBRATION_PATTERN);
		manager.createNotificationChannel(channel);
	}

	/**
	 * Create notification builder with channel and icon
	 */
	private static Notification.Builder createNotificationBuilder(final Context context, final NotificationConfig config) {
		final Notification.Builder builder = new Notification.Builder(context, config.channelId).setSmallIcon(config.iconRes);

		if (config.type != CRITICAL_TYPE) {
			builder.setOnlyAlertOnce(true);
		}

		return builder;
	}

	/**
	 * Configure notification content (title, text, ticker)
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
	 */
	private static void sendNotificationToSystem(final Context context, final Notification notification) {
		final NotificationManager manager = getNotificationManager(context);
		if (nonNull(manager)) {
			manager.notify(NOTIFICATION_ID, notification);
		}
	}

	/**
	 * Play notification sound if conditions are met
	 */
	private static void playSoundIfNeeded(final Context context, final NotificationConfig config) {
		if (!config.withinTime) return;

		final Uri soundUri = Uri.parse(config.alarmSound);
		final AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
		if (isNull(audioManager)) return;

		final boolean isNotNormalRingerMode = audioManager.getRingerMode() != AudioManager.RINGER_MODE_NORMAL || isInDoNotDisturbMode(context);

		if (config.ignoreSilent && isNotNormalRingerMode) {
			soundExecutor.execute(() -> {
				SystemService.playSound(context, soundUri);
				SystemService.vibratePhone(context);
			});
		}
	}

	/**
	 * Check if device is in Do Not Disturb mode
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
	 */
	private static boolean isWithinTime(final String startTime, final String endTime) {
		final int startHour = GeneralHelper.getHour(startTime);
		final int startMinute = GeneralHelper.getMinute(startTime);
		final int endHour = GeneralHelper.getHour(endTime);
		final int endMinute = GeneralHelper.getMinute(endTime);

		final Date currentTime = new Date();

		final Calendar startCal = Calendar.getInstance();
		startCal.setTime(currentTime);
		startCal.set(Calendar.HOUR_OF_DAY, startHour);
		startCal.set(Calendar.MINUTE, startMinute);

		final Calendar endCal = Calendar.getInstance();
		endCal.setTime(currentTime);
		endCal.set(Calendar.HOUR_OF_DAY, endHour);
		endCal.set(Calendar.MINUTE, endMinute);

		// Handle overnight time ranges (e.g., 8:00 PM to 6:00 AM)
		if (endHour <= startHour) {
			endCal.add(Calendar.DATE, 1);
		}

		return currentTime.after(startCal.getTime()) && currentTime.before(endCal.getTime());
	}

	/**
	 * Get NotificationManager system service
	 */
	private static NotificationManager getNotificationManager(final Context context) {
		return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
	}

	/**
	 * Get cached launcher icon bitmap (performance optimization)
	 */
	private static Bitmap getLauncherIcon(final Context context) {
		if (isNull(cachedLauncherIcon)) {
			cachedLauncherIcon = BitmapFactory.decodeResource(context.getResources(), R.mipmap.ic_launcher);
		}
		return cachedLauncherIcon;
	}

	/**
	 * Create PendingIntent for MainActivity
	 */
	private static PendingIntent createMainActivityIntent(final Context context) {
		final Intent intent = new Intent(context, MainActivity.class);
		final int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
		return PendingIntent.getActivity(context, 0, intent, flags);
	}

	/**
	 * Configuration object for notification creation (reduces parameter count)
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
		final boolean withinTime;
		final boolean ignoreSilent;
		final boolean stickyNotification;

		NotificationConfig(final Context context, final SharedPreferences prefs, final int type) {
			this.type = type;

			final int warningLevel = prefs.getInt(context.getString(R.string._pref_key_warn_battery_level), 40);
			final int criticalLevel = prefs.getInt(context.getString(R.string._pref_key_critical_battery_level), 20);
			final boolean limitedTime = prefs.getBoolean(context.getString(R.string._pref_key_notifications_time_range), false);
			final String startTime = prefs.getString(
					context.getString(R.string._pref_key_notifications_time_range_start),
					context.getString(R.string._pref_value_notifications_time_range_start)
			);
			final String endTime = prefs.getString(
					context.getString(R.string._pref_key_notifications_time_range_end),
					context.getString(R.string._pref_value_notifications_time_range_end)
			);

			this.stickyNotification = prefs.getBoolean(context.getString(R.string._pref_key_notifications_sticky), false);
			this.withinTime = isWithinTime(startTime, endTime) || !limitedTime;
			this.ignoreSilent = !prefs.getBoolean(context.getString(R.string._pref_key_notifications_apply_silent_mode), false);

			final String defaultSound = "content://settings/system/notification_sound";

			switch (type) {
				case CRITICAL_TYPE:
					this.channelId = CHANNEL_ID_CRITICAL;
					this.iconRes = R.drawable.ic_stat_device_battery_charging_20;
					this.alarmSound = prefs.getString(context.getString(R.string._pref_key_notifications_alert_sound_ringtone), defaultSound);
					this.ticker = context.getString(R.string.notification_critical_ticker, criticalLevel);
					this.title = context.getString(R.string.notification_critical_title);
					this.content = context.getString(R.string.notification_critical_content, criticalLevel);
					this.bigContent = context.getString(R.string.notification_critical_content_big, criticalLevel);
					break;

				case WARNING_TYPE:
					this.channelId = CHANNEL_ID_WARNING;
					this.iconRes = R.drawable.ic_stat_device_battery_charging_50;
					this.alarmSound = prefs.getString(context.getString(R.string._pref_key_notifications_warning_sound_ringtone), defaultSound);
					this.ticker = context.getString(R.string.notification_warning_ticker, warningLevel);
					this.title = context.getString(R.string.notification_warning_title);
					this.content = context.getString(R.string.notification_warning_content, warningLevel);
					this.bigContent = context.getString(R.string.notification_warning_content_big, warningLevel);
					break;

				case FULL_LEVEL_TYPE:
				default:
					this.channelId = CHANNEL_ID_FULL;
					this.iconRes = R.drawable.ic_stat_device_battery_charging_full;
					this.alarmSound = prefs.getString(context.getString(R.string._pref_key_notifications_full_sound_ringtone), defaultSound);
					this.ticker = context.getString(R.string.notification_full_level_ticker);
					this.title = isHealthyCharge
							? context.getString(R.string.notification_full_level_title_healthy)
							: context.getString(R.string.notification_full_level_title_regular);
					this.content = context.getString(R.string.notification_full_level_content);
					this.bigContent = context.getString(R.string.notification_full_level_content_big);
					break;
			}
		}
	}
}
