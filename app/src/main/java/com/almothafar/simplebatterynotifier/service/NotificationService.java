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
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.model.BatteryDO;
import com.almothafar.simplebatterynotifier.model.ChargeSpeed;
import com.almothafar.simplebatterynotifier.model.ChargeSpeedTier;
import com.almothafar.simplebatterynotifier.ui.MainActivity;
import com.almothafar.simplebatterynotifier.util.BatteryPercentFormatter;
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
	// Notification channels. The six alert channels below are *base* IDs: the actual channel ID
	// carries a version suffix (see versionedChannelId) because Android un-deletes a channel that is
	// recreated with the same ID, restoring its old settings — which made the Vibrate toggle a no-op
	// (issue #153). refreshAlertChannels() bumps the version so a changed setting really applies.
	private static final String CHANNEL_ID_CRITICAL = "battery_critical";
	private static final String CHANNEL_ID_WARNING = "battery_warning";
	private static final String CHANNEL_ID_FULL = "battery_full";
	private static final String CHANNEL_ID_STATUS = "battery_status";
	private static final String CHANNEL_ID_TEMPERATURE = "battery_temperature";
	private static final String CHANNEL_ID_FAST_DRAIN = "battery_fast_drain";
	private static final String CHANNEL_ID_SLOW_CHARGE = "battery_slow_charge";
	// Silent, low-importance channel used to deliver an alert quietly during the user's quiet hours:
	// the alert is still visible but makes no sound or vibration (issue #111).
	private static final String CHANNEL_ID_ALERTS_SILENT = "battery_alerts_quiet";

	// Current version of the alert channels' settings, stored in the default SharedPreferences.
	// Version 1 means the original unsuffixed channel IDs, so existing installs keep their channels
	// (and any per-channel tweaks) until the user first changes the Vibrate preference.
	private static final String PREF_ALERT_CHANNEL_VERSION = "alert_channel_version";

	// Notification settings
	private static final long[] VIBRATION_PATTERN = {0, 500, 250, 500, 250};
	private static final int NOTIFICATION_ID = 1641987;
	// Separate ID for the persistent foreground-service status notification
	private static final int ONGOING_NOTIFICATION_ID = 1641988;
	// Separate ID so a temperature alert doesn't replace a battery-level alert
	private static final int TEMPERATURE_NOTIFICATION_ID = 1641989;
	// Separate ID so a fast-drain alert doesn't replace a level or temperature alert (#109)
	private static final int FAST_DRAIN_NOTIFICATION_ID = 1641990;
	// Separate ID so a slow-charge warning doesn't replace any other alert (#123)
	private static final int SLOW_CHARGE_NOTIFICATION_ID = 1641991;

	// Do Not Disturb mode constants
	private static final String ZEN_MODE = "zen_mode";
	private static final int ZEN_MODE_IMPORTANT_INTERRUPTIONS = 1;

	// Charge-connected notification style (values persisted by the ListPreference in pref_alerts.xml).
	// Toast is the default so plugging in stays low-clutter (issue #122).
	static final String CHARGE_STYLE_TOAST = "toast";
	static final String CHARGE_STYLE_NOTIFICATION = "notification";
	static final String CHARGE_STYLE_NONE = "none";

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
	 * Announce that charging has started, honoring the user's chosen style.
	 * <p>
	 * Instead of the old, often-misleading "AC charger connected" message, this reports what's
	 * actually useful: the estimated charging speed (tier + wattage) and whether it's wired or
	 * wireless (issue #122). The user picks how it's surfaced via the "Charge notification style"
	 * preference:
	 * <ul>
	 *   <li>{@link #CHARGE_STYLE_TOAST} (default) — a brief, low-clutter toast;</li>
	 *   <li>{@link #CHARGE_STYLE_NOTIFICATION} — a full system notification (quiet-hours aware);</li>
	 *   <li>{@link #CHARGE_STYLE_NONE} — nothing at all.</li>
	 * </ul>
	 *
	 * @param context  The application context
	 * @param speed    The estimated charging speed (may be {@link ChargeSpeed#unknown()})
	 * @param wireless true when charging over a wireless charger, false when wired
	 */
	public static void notifyChargeConnected(final Context context, final ChargeSpeed speed,
	                                         final boolean wireless) {
		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		final String style = resolveChargeStyle(prefs.getString(
				context.getString(R.string._pref_key_charge_notification_style), CHARGE_STYLE_TOAST));

		if (CHARGE_STYLE_NONE.equals(style)) {
			return; // User opted out of charge-connected feedback entirely.
		}

		final String content = chargeConnectedMessage(context, speed, wireless);

		if (CHARGE_STYLE_TOAST.equals(style)) {
			showChargeToast(context, content);
			return;
		}

		postChargeNotification(context, content);
	}

	/**
	 * Normalize a stored charge-style preference value to a known style, defaulting to
	 * {@link #CHARGE_STYLE_TOAST} for a null/blank/unrecognized value. Pure and Android-free so it is
	 * unit-testable.
	 *
	 * @param stored the raw persisted value
	 *
	 * @return one of the {@code CHARGE_STYLE_*} constants
	 */
	static String resolveChargeStyle(final String stored) {
		if (CHARGE_STYLE_NOTIFICATION.equals(stored)) {
			return CHARGE_STYLE_NOTIFICATION;
		}
		if (CHARGE_STYLE_NONE.equals(stored)) {
			return CHARGE_STYLE_NONE;
		}
		return CHARGE_STYLE_TOAST;
	}

	/**
	 * Build the charge-connected message, e.g. "Wireless · Fast charging · ~18 W", or
	 * "Wired charging" when the speed can't be estimated.
	 *
	 * @param context  The application context
	 * @param speed    The estimated charging speed
	 * @param wireless true for wireless charging, false for wired
	 *
	 * @return the localized message shown in the toast or notification
	 */
	private static String chargeConnectedMessage(final Context context, final ChargeSpeed speed, final boolean wireless) {
		final String source = context.getString(
				wireless ? R.string.charge_source_wireless : R.string.charge_source_wired);

		if (!speed.isKnown()) {
			return context.getString(R.string.charge_connected_plain, source);
		}

		final String tierLabel = context.getString(tierLabelRes(speed.getTier()));
		final int watts = speed.getWatts();
		if (watts < 1) {
			// Known tier but sub-watt (e.g. trickle): showing "~0 W" would read as an error.
			return context.getString(R.string.charge_connected_tier, source, tierLabel);
		}
		return context.getString(R.string.charge_connected_power, source, tierLabel, watts);
	}

	/**
	 * Map a {@link ChargeSpeedTier} to its localized label resource.
	 *
	 * @param tier the charging-speed tier
	 *
	 * @return a string resource id for the tier's label
	 */
	private static int tierLabelRes(final ChargeSpeedTier tier) {
		return switch (tier) {
			case TRICKLE -> R.string.charge_tier_trickle;
			case NORMAL -> R.string.charge_tier_normal;
			case FAST -> R.string.charge_tier_fast;
			case SUPER_FAST -> R.string.charge_tier_super_fast;
			case SUPER_FAST_PLUS -> R.string.charge_tier_super_fast_plus;
			case UNKNOWN -> R.string.charging;
		};
	}

	/**
	 * Show the charge message as a toast. Toasts must be posted from a thread with a Looper, so this
	 * hops to the main thread when called from a background context. Uses the application context to
	 * avoid leaking the caller.
	 *
	 * @param context The context
	 * @param message The message to show
	 */
	private static void showChargeToast(final Context context, final String message) {
		final Context appContext = context.getApplicationContext();
		if (Looper.myLooper() == Looper.getMainLooper()) {
			Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show();
		} else {
			new Handler(Looper.getMainLooper()).post(
					() -> Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show());
		}
	}

	/**
	 * Post the charge-connected message as a system notification (the "notification" style).
	 * <p>
	 * Plugging in during quiet hours shouldn't ding: shown on the silent channel outside the window
	 * instead of the audible full-battery channel (issue #111).
	 *
	 * @param context The application context
	 * @param content The charge message to display
	 */
	private static void postChargeNotification(final Context context, final String content) {
		if (lacksNotificationPermission(context)) {
			Log.w(TAG, "Missing POST_NOTIFICATIONS permission, notification not sent");
			return;
		}

		createNotificationChannels(context);

		final String title = context.getString(R.string.notification_charge_started_title);
		final String ticker = title.concat(", ").concat(content);
		final int iconRes = R.drawable.ic_stat_device_battery_charging_50;

		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		final boolean withinWindow = isWithinNotificationWindow(context, prefs);

		final Notification.Builder builder = new Notification.Builder(context, channelFor(context, withinWindow, CHANNEL_ID_FULL))
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
		final String temperature = TemperatureUtils.format(context, rawTenthsC);
		sendQuietHoursAwareAlert(context, new AlertSpec(
				"temperature",
				CHANNEL_ID_TEMPERATURE,
				TEMPERATURE_NOTIFICATION_ID,
				R.drawable.ic_stat_temperature_hot,
				context.getString(R.string.notification_temperature_ticker),
				context.getString(R.string.notification_temperature_title),
				context.getString(R.string.notification_temperature_content, temperature),
				context.getString(R.string.notification_temperature_content_big, temperature)));
	}

	/**
	 * Send a "battery draining fast" alert (issue #109).
	 * <p>
	 * Uses a dedicated channel and notification ID so it never replaces a level or temperature alert, and
	 * honours the same quiet-hours / silent-mode / vibrate preferences as the other alerts. The message is
	 * transparent: it states the real measured rate, how long it has been sustained, and the user's own
	 * limit, so it explains why it fired and that the user controls it. It deliberately cannot name the
	 * culprit app (that needs a privileged permission — see the issue).
	 *
	 * @param context        The application context
	 * @param ratePph        The measured drain rate in %/h
	 * @param limitPph       The user's high-drain limit in %/h
	 * @param elapsedMinutes How long the rate has been sustained at/above the limit, in minutes
	 */
	public static void sendFastDrainNotification(final Context context, final int ratePph,
	                                             final int limitPph, final int elapsedMinutes) {
		// Western digits in every locale (#96) via String.valueOf.
		final String rate = String.valueOf(ratePph);
		final String limit = String.valueOf(limitPph);
		final String minutes = String.valueOf(elapsedMinutes);
		final String content = context.getString(R.string.notification_fast_drain_content, rate, minutes, limit);

		sendQuietHoursAwareAlert(context, new AlertSpec(
				"fast-drain",
				CHANNEL_ID_FAST_DRAIN,
				FAST_DRAIN_NOTIFICATION_ID,
				R.drawable.ic_stat_device_battery_charging_20,
				context.getString(R.string.notification_fast_drain_ticker),
				context.getString(R.string.notification_fast_drain_title),
				content,
				content));
	}

	/**
	 * Warn that charging power has stayed abnormally low — a likely frayed cable, dirty/loose port, or
	 * dying charger (issue #123).
	 * <p>
	 * Surfaced per the user's charge-notification style (#122): a brief toast, a full quiet-hours-aware
	 * notification on its own channel/id, or nothing when they opted out of charge feedback. The message
	 * states the measured wattage so it explains itself; it deliberately can't tell cable from port from
	 * brick (indistinguishable via public APIs), so it advises checking all three.
	 *
	 * @param context The application context
	 * @param watts   The estimated charging power in watts
	 */
	public static void sendSlowChargeWarning(final Context context, final int watts) {
		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		final String style = resolveChargeStyle(prefs.getString(
				context.getString(R.string._pref_key_charge_notification_style), CHARGE_STYLE_TOAST));
		if (CHARGE_STYLE_NONE.equals(style)) {
			return; // the user opted out of charge feedback entirely
		}

		// Western digits in every locale (#96) via String.valueOf.
		final String content = context.getString(R.string.notification_slow_charge_content, String.valueOf(watts));
		if (CHARGE_STYLE_TOAST.equals(style)) {
			showChargeToast(context, content);
			return;
		}
		sendQuietHoursAwareAlert(context, new AlertSpec(
				"slow-charge",
				CHANNEL_ID_SLOW_CHARGE,
				SLOW_CHARGE_NOTIFICATION_ID,
				R.drawable.ic_stat_device_battery_charging_20,
				context.getString(R.string.notification_slow_charge_ticker),
				context.getString(R.string.notification_slow_charge_title),
				content,
				context.getString(R.string.notification_slow_charge_content_big, String.valueOf(watts))));
	}

	/**
	 * Send an alert on its own channel and notification ID, honouring quiet hours, silent-mode and
	 * vibrate preferences.
	 * <p>
	 * The single home of the quiet-hours dance shared by the temperature (#18/#111) and fast-drain
	 * (#109) alerts: rerouting to the silent channel outside the notification window, and gating the
	 * alarm sound/vibration on the window — so a quiet-hours fix can't be applied to one alert and
	 * missed on another. Both alerts reuse the critical-alert ringtone preference, as before.
	 *
	 * @param context The application context
	 * @param spec    What to show: channel, id, icon and text content
	 */
	private static void sendQuietHoursAwareAlert(final Context context, final AlertSpec spec) {
		if (lacksNotificationPermission(context)) {
			Log.w(TAG, "Missing POST_NOTIFICATIONS permission, " + spec.logName() + " alert not sent");
			return;
		}

		createNotificationChannels(context);

		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		// These are not critical battery alerts, so they respect quiet hours (#111).
		final boolean withinWindow = isWithinNotificationWindow(context, prefs);
		final String channelId = channelFor(context, withinWindow, spec.audibleChannelId());

		final Notification.Builder builder = new Notification.Builder(context, channelId)
				.setSmallIcon(spec.iconRes())
				.setTicker(spec.ticker())
				.setContentTitle(spec.title())
				.setContentText(spec.content())
				.setWhen(System.currentTimeMillis())
				.setLargeIcon(getLauncherIcon(context))
				.setContentIntent(createMainActivityIntent(context))
				.setVisibility(Notification.VISIBILITY_PUBLIC)
				.setStyle(new Notification.BigTextStyle().bigText(spec.bigContent()));

		final NotificationManager manager = getNotificationManager(context);
		if (nonNull(manager)) {
			manager.notify(spec.notificationId(), builder.build());
		}

		final String sound = prefs.getString(
				context.getString(R.string._pref_key_notifications_alert_sound_ringtone),
				context.getString(R.string._default_notification_sound_uri));
		if (withinWindow) {
			playAlarm(context, sound, shouldIgnoreSilentMode(context, prefs), isVibrationEnabled(context, prefs));
		}
	}

	/**
	 * Everything an own-channel alert needs to display (reduces parameter count, like
	 * {@link NotificationConfig}).
	 *
	 * @param logName          short name used in log messages (e.g. "temperature")
	 * @param audibleChannelId the alert's audible channel; rerouted to the silent channel in quiet hours
	 * @param notificationId   the alert's own notification id, so it never replaces another alert
	 * @param iconRes          small icon resource
	 * @param ticker           ticker text
	 * @param title            content title
	 * @param content          collapsed content text
	 * @param bigContent       expanded (BigTextStyle) content text
	 */
	private record AlertSpec(String logName, String audibleChannelId, int notificationId, int iconRes,
	                         String ticker, String title, String content, String bigContent) {
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
		return buildOngoingNotification(context, batteryDO, BatteryRateTracker.getRate(context, batteryDO));
	}

	/**
	 * Build the ongoing notification from an already-computed rate, so a caller that just fed the rate
	 * window (see {@link BatteryRateTracker#record}) doesn't trigger a second read-and-parse of the
	 * persisted samples (issue #108).
	 *
	 * @param context   The application context
	 * @param batteryDO Current battery snapshot, or null if unavailable
	 * @param rate      The precomputed charge/drain rate
	 * @return The built ongoing notification
	 */
	public static Notification buildOngoingNotification(final Context context, final BatteryDO batteryDO,
	                                                    final BatteryRateTracker.BatteryRate rate) {
		createNotificationChannels(context);

		return new Notification.Builder(context, CHANNEL_ID_STATUS)
				.setSmallIcon(ongoingIconRes(batteryDO))
				.setContentTitle(context.getString(R.string.app_name))
				.setContentText(statusText(context, batteryDO, rate))
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
	 * @param rate      The rate already computed while feeding the sample window
	 */
	public static void updateOngoingNotification(final Context context, final BatteryDO batteryDO,
	                                             final BatteryRateTracker.BatteryRate rate) {
		if (lacksNotificationPermission(context)) {
			return;
		}
		final NotificationManager manager = getNotificationManager(context);
		if (nonNull(manager)) {
			manager.notify(ONGOING_NOTIFICATION_ID, buildOngoingNotification(context, batteryDO, rate));
		}
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

		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		final boolean vibrate = prefs.getBoolean(context.getString(R.string._pref_key_notifications_vibrate), true);
		final int version = alertChannelVersion(prefs);

		createChannelIfNotExists(manager, versionedChannelId(CHANNEL_ID_CRITICAL, version),
				"Battery Critical Alerts", "Critical battery level alerts", Color.RED, vibrate);
		createChannelIfNotExists(manager, versionedChannelId(CHANNEL_ID_WARNING, version),
				"Battery Warnings", "Battery warning notifications", Color.rgb(0xff, 0x66, 0x00), vibrate);
		createChannelIfNotExists(manager, versionedChannelId(CHANNEL_ID_FULL, version),
				"Battery Full", "Battery fully charged notifications", Color.GREEN, vibrate);
		createChannelIfNotExists(manager, versionedChannelId(CHANNEL_ID_TEMPERATURE, version),
				context.getString(R.string.notification_temperature_channel_name),
				context.getString(R.string.notification_temperature_channel_description), Color.RED, vibrate);
		createChannelIfNotExists(manager, versionedChannelId(CHANNEL_ID_FAST_DRAIN, version),
				context.getString(R.string.notification_fast_drain_channel_name),
				context.getString(R.string.notification_fast_drain_channel_description), Color.rgb(0xff, 0x66, 0x00), vibrate);
		createChannelIfNotExists(manager, versionedChannelId(CHANNEL_ID_SLOW_CHARGE, version),
				context.getString(R.string.notification_slow_charge_channel_name),
				context.getString(R.string.notification_slow_charge_channel_description), Color.rgb(0xff, 0x66, 0x00), vibrate);
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
	private static void createSilentChannelIfNotExists(
			final NotificationManager manager,
			final String channelId,
			final String name,
			final String description) {
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
	private static void createChannelIfNotExists(
			final NotificationManager manager,
			final String channelId,
			final String name,
			final String description,
			final int ledColor,
			final boolean vibrate) {
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
	 * Deleting and recreating a channel under the same ID is not enough: Android un-deletes it with
	 * its old settings (issue #153). So the old-version channels are deleted (keeping system
	 * settings free of orphans) and the channels are recreated under the next version's IDs, which
	 * the system treats as brand-new channels with the new vibration setting. The silent channels
	 * are untouched.
	 *
	 * @param context The application context
	 */
	public static void refreshAlertChannels(final Context context) {
		final NotificationManager manager = getNotificationManager(context);
		if (isNull(manager)) {
			return;
		}

		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		final int oldVersion = alertChannelVersion(prefs);
		manager.deleteNotificationChannel(versionedChannelId(CHANNEL_ID_CRITICAL, oldVersion));
		manager.deleteNotificationChannel(versionedChannelId(CHANNEL_ID_WARNING, oldVersion));
		manager.deleteNotificationChannel(versionedChannelId(CHANNEL_ID_FULL, oldVersion));
		manager.deleteNotificationChannel(versionedChannelId(CHANNEL_ID_TEMPERATURE, oldVersion));
		manager.deleteNotificationChannel(versionedChannelId(CHANNEL_ID_FAST_DRAIN, oldVersion));
		manager.deleteNotificationChannel(versionedChannelId(CHANNEL_ID_SLOW_CHARGE, oldVersion));

		prefs.edit().putInt(PREF_ALERT_CHANNEL_VERSION, oldVersion + 1).apply();
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
		final String channelId = channelFor(context, config.alertsAllowed, config.channelId);
		final Notification.Builder builder = new Notification.Builder(context, channelId).setSmallIcon(config.iconRes);

		if (config.type != CRITICAL_TYPE) {
			builder.setOnlyAlertOnce(true);
		}

		return builder;
	}

	/**
	 * The channel an alerting notification should post to: its normal audible (high-importance) channel
	 * when alerts may sound now, or the shared silent channel during quiet hours so the alert is still
	 * shown but makes no sound or vibration (issue #111). The audible channel's base ID is resolved to
	 * its current versioned ID (issue #153), so every posting site tracks version bumps automatically.
	 *
	 * @param context              The application context
	 * @param alertsAllowed        whether alerts may sound now (inside the window, or a critical override)
	 * @param audibleBaseChannelId the base channel ID to use when alerts are allowed to sound
	 * @return the channel id to post the notification on
	 */
	private static String channelFor(final Context context, final boolean alertsAllowed, final String audibleBaseChannelId) {
		if (!alertsAllowed) {
			return CHANNEL_ID_ALERTS_SILENT;
		}
		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		return versionedChannelId(audibleBaseChannelId, alertChannelVersion(prefs));
	}

	/**
	 * The alert-channel ID for a given settings version. Version 1 is the original unsuffixed ID;
	 * later versions append a suffix ("battery_critical_v2", ...) so Android sees a brand-new channel
	 * instead of un-deleting the old one with its stale settings (issue #153).
	 *
	 * @param baseChannelId the unversioned channel ID
	 * @param version       the alert-channel settings version (1-based)
	 * @return the channel ID to create and post on for that version
	 */
	static String versionedChannelId(final String baseChannelId, final int version) {
		return version <= 1 ? baseChannelId : baseChannelId + "_v" + version;
	}

	/**
	 * The current alert-channel settings version, bumped by {@link #refreshAlertChannels(Context)}
	 * whenever the Vibrate preference changes.
	 *
	 * @param prefs the default SharedPreferences
	 * @return the current version, 1 for installs that never changed the Vibrate preference
	 */
	private static int alertChannelVersion(final SharedPreferences prefs) {
		return prefs.getInt(PREF_ALERT_CHANNEL_VERSION, 1);
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
		// Default ON to match the toggle's XML default (pref_behaviour.xml). Reading false here let a
		// fresh install that never opened Time Settings alert around the clock, so quiet hours silently
		// did nothing (issue #111). Now quiet hours apply by default (06:30–23:30).
		final boolean limitedTime = prefs.getBoolean(context.getString(R.string._pref_key_notifications_time_range), true);
		final String defaultStartTime = context.getString(R.string._pref_value_notifications_time_range_start);
		final String defaultEndTime = context.getString(R.string._pref_value_notifications_time_range_end);
		final String startTime = prefs.getString(context.getString(R.string._pref_key_notifications_time_range_start), defaultStartTime);
		final String endTime = prefs.getString(context.getString(R.string._pref_key_notifications_time_range_end), defaultEndTime);
		return isWithinTime(startTime, endTime, defaultStartTime, defaultEndTime) || !limitedTime;
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
	 * Check if current time is within notification time range. Never throws: this runs on the
	 * broadcast path of every alert, and a corrupt stored bound must not turn alerting into a crash
	 * loop (issue #154) — a malformed bound falls back to that bound's default instead.
	 *
	 * @param startTime        Start time string (HH:mm format)
	 * @param endTime          End time string (HH:mm format)
	 * @param defaultStartTime Default window start, used when {@code startTime} is malformed
	 * @param defaultEndTime   Default window end, used when {@code endTime} is malformed
	 * @return true if current time is within range
	 */
	private static boolean isWithinTime(
			final String startTime,
			final String endTime,
			final String defaultStartTime,
			final String defaultEndTime) {
		final LocalTime now = LocalTime.now();
		final int nowMinutes = now.getHour() * 60 + now.getMinute();
		final int startMinutes = boundOrDefaultMinutes(startTime, defaultStartTime);
		final int endMinutes = boundOrDefaultMinutes(endTime, defaultEndTime);
		return isWithinTimeRange(nowMinutes, startMinutes, endMinutes);
	}

	/**
	 * A quiet-hours window bound as minutes since midnight, falling back to the bound's default when
	 * the stored value is malformed (backup/restore corruption, prefs damage — issue #154). The
	 * fallback is logged: a corrupt pref is unexpected and should be visible, just never fatal.
	 *
	 * @param storedTime  the persisted "HH:MM" bound
	 * @param defaultTime the bound's default from resources, expected to always parse
	 * @return the bound in minutes since midnight
	 */
	static int boundOrDefaultMinutes(final String storedTime, final String defaultTime) {
		final int minutes = GeneralHelper.parseTimeToMinutes(storedTime);
		if (minutes >= 0) {
			return minutes;
		}
		Log.w(TAG, "Malformed quiet-hours time \"" + storedTime + "\"; using default " + defaultTime);
		// The default is a compile-time resource constant, so this parse can't realistically fail;
		// floor at midnight rather than propagate -1 into the range check just in case.
		return Math.max(0, GeneralHelper.parseTimeToMinutes(defaultTime));
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
	 * Build the content line of the ongoing status notification, e.g. "85.12% · Discharging 9%/h · 32.0 °C".
	 * <p>
	 * The percentage is the same live value the home gauge shows — two decimals when the device
	 * genuinely resolves below one percent, whole otherwise (#158); only the level <em>alerts</em>
	 * (critical/warning) stay integer. The middle segment appends the charge/drain rate to the status
	 * label when available, falling back to the raw mA, then to the plain label — always showing the
	 * best number on hand (issue #108). The appended rate is gated by a user setting (default on); the
	 * plain label always shows.
	 *
	 * @param context   The application context
	 * @param batteryDO Current battery snapshot, or null if unavailable
	 * @param rate      The precomputed charge/drain rate
	 * @return Formatted status text
	 */
	private static String statusText(final Context context, final BatteryDO batteryDO,
	                                 final BatteryRateTracker.BatteryRate rate) {
		final String percentage = BatteryPercentFormatter.formatLive(batteryDO);
		final String statusLabel = SystemService.getStatusLabel(context, isNull(batteryDO) ? -1 : batteryDO.getStatus());
		final String temperature = isNull(batteryDO) ? "" : TemperatureUtils.format(context, batteryDO.getTemperature());
		return context.getString(R.string.notification_status_content, percentage, statusWithRate(context, batteryDO, statusLabel, rate), temperature);
	}

	/**
	 * The status segment with the rate (or raw mA) appended, e.g. "Discharging 9%/h" — or the plain
	 * label when no reading is available or the user turned the appended rate off (issue #108).
	 *
	 * @param context     The application context
	 * @param batteryDO   Current battery snapshot, or null if unavailable
	 * @param statusLabel The plain localized status label
	 * @param rate        The precomputed charge/drain rate
	 * @return The status label, optionally with the rate/current appended
	 */
	private static String statusWithRate(final Context context, final BatteryDO batteryDO,
	                                     final String statusLabel, final BatteryRateTracker.BatteryRate rate) {
		if (isNull(batteryDO) || isNull(rate)) {
			return statusLabel;
		}
		final boolean showRate = PreferenceManager.getDefaultSharedPreferences(context)
		                                           .getBoolean(context.getString(R.string._pref_key_show_rate_in_notification), true);
		if (!showRate) {
			return statusLabel;
		}
		// While charging, humans think in charger speed, not %/h — show the estimated tier + wattage when we
		// can (#125). The ChargeSpeed read (CURRENT_NOW × voltage) is charging-only, so the discharging path
		// below is untouched; it falls through to the %/h → mA → plain chain when the speed can't be estimated.
		if (rate.charging()) {
			final String chargingSpeed = chargingSpeedSegment(context, batteryDO);
			if (nonNull(chargingSpeed)) {
				return chargingSpeed;
			}
		}
		if (rate.hasRate()) {
			return statusLabel + " " + BatteryRateTracker.formatRateValue(context, rate.percentPerHour());
		}
		if (rate.hasCurrent()) {
			return statusLabel + " " + BatteryRateTracker.formatCurrentValue(context, rate.currentMilliAmps());
		}
		return statusLabel;
	}

	/**
	 * The charging-speed segment for the ongoing status notification — the estimated tier and wattage, e.g.
	 * "Fast charging · ~18 W", or just the tier ("Slow charging") when the wattage rounds below 1 W (#125).
	 * The tier label already reads as "… charging", so it replaces the plain "Charging" status word rather
	 * than being appended to it. Returns null when the speed can't be estimated (e.g. a device that doesn't
	 * report {@code CURRENT_NOW}), so the caller falls back to the %/h → mA → plain chain.
	 * <p>
	 * The speed is derived from the {@code batteryDO} snapshot, not a fresh hardware read, so this segment
	 * judges the same reading as the details table and {@link SlowChargeDetector} within one tick (#157).
	 *
	 * @param context   The application context
	 * @param batteryDO Current battery snapshot (non-null; the caller already checked)
	 * @return the formatted charging-speed segment, or null when the charge speed is unknown
	 */
	private static String chargingSpeedSegment(final Context context, final BatteryDO batteryDO) {
		final ChargeSpeed speed = ChargeSpeed.fromMeasurements(batteryDO.getCurrentMicroAmps(), batteryDO.getVoltage());
		if (!speed.isKnown()) {
			return null;
		}
		final String tierLabel = context.getString(tierLabelRes(speed.getTier()));
		final int watts = speed.getWatts();
		if (watts < 1) {
			return tierLabel; // sub-watt (e.g. trickle): "~0 W" would read as an error
		}
		// %2$s via String.valueOf keeps the wattage in Western digits (0-9) in every locale (#96).
		return context.getString(R.string.notification_status_charge_power, tierLabel, String.valueOf(watts));
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
		return batteryDO.getBatteryPercentageInt() <= 50
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
					this.title = context.getString(R.string.notification_full_level_title);
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
