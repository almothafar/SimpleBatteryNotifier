package com.almothafar.simplebatterynotifier.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.model.BatteryDO;
import com.almothafar.simplebatterynotifier.model.ChargeSpeed;
import com.almothafar.simplebatterynotifier.model.ChargeSpeedTier;
import com.almothafar.simplebatterynotifier.ui.MainActivity;
import com.almothafar.simplebatterynotifier.util.AppPrefs;
import com.almothafar.simplebatterynotifier.util.TemperatureUtils;

import java.lang.ref.WeakReference;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Battery-notification dispatch (issue #166): the public façade the receivers and the foreground service
 * call to post battery alerts and the ongoing status notification.
 * <p>
 * The heavy lifting is delegated to focused collaborators in this package, each owning one job:
 * <ul>
 *   <li>{@link NotificationChannels} — channel registry and versioned-ID resolution;</li>
 *   <li>{@link QuietHours} — quiet-hours / silent-mode policy;</li>
 *   <li>{@link AlertSounds} — the alarm sound + vibration playback and its executor;</li>
 *   <li>{@link OngoingStatusContent} — the ongoing notification's title / detail text.</li>
 * </ul>
 * This class owns the notification IDs, the single shared alert-builder chain, the per-charge-style
 * delivery, and the per-level {@link NotificationConfig}.
 */
public final class NotificationService {
	// Battery thresholds
	public static final int RED_ALERT_LEVEL = 4;
	public static final int FULL_PERCENTAGE = 95;
	private static final String TAG = NotificationService.class.getSimpleName();

	// Notification IDs — each alert gets its own so one can never replace another.
	private static final int NOTIFICATION_ID = 1641987;
	// The persistent foreground-service status notification.
	private static final int ONGOING_NOTIFICATION_ID = 1641988;
	// A temperature alert doesn't replace a battery-level alert.
	private static final int TEMPERATURE_NOTIFICATION_ID = 1641989;
	// A fast-drain alert doesn't replace a level or temperature alert (#109).
	private static final int FAST_DRAIN_NOTIFICATION_ID = 1641990;
	// A slow-charge warning doesn't replace any other alert (#123).
	private static final int SLOW_CHARGE_NOTIFICATION_ID = 1641991;
	// "Charging started" doesn't replace a level alert (#155). The level alert is still dismissed at
	// plug-in, but explicitly (see clearLevelAlert), not by ID collision.
	private static final int CHARGE_CONNECTED_NOTIFICATION_ID = 1641992;

	// Charge-connected notification style (values persisted by the ListPreference in pref_behaviour.xml).
	// Toast is the default so plugging in stays low-clutter (issue #122).
	static final String CHARGE_STYLE_TOAST = "toast";
	static final String CHARGE_STYLE_NOTIFICATION = "notification";
	static final String CHARGE_STYLE_NONE = "none";

	/**
	 * Cached launcher icon bitmap (performance optimization). Uses a {@link WeakReference} so it can be
	 * garbage-collected if memory is tight; it is reloaded on next access if that happens, which avoids
	 * holding a strong reference to a potentially large bitmap for the app's lifetime.
	 */
	private static WeakReference<Bitmap> cachedLauncherIcon;

	private NotificationService() {
		// Utility class - prevent instantiation
	}

	/**
	 * Send a battery status notification
	 *
	 * @param context The application context
	 * @param type    Which battery-level alert to send
	 */
	public static void sendNotification(Context context, AlertType type) {
		if (isNull(type)) {
			Log.w(TAG, "No alert type given, notification not sent");
			return;
		}
		if (lacksNotificationPermission(context)) {
			Log.w(TAG, "Missing POST_NOTIFICATIONS permission, notification not sent");
			return;
		}

		NotificationChannels.ensureChannels(context);

		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		final NotificationConfig config = new NotificationConfig(context, prefs, type);

		final String channelId = NotificationChannels.channelFor(context, config.alertsAllowed, config.channelId);
		final AlertSpec display = new AlertSpec("level", config.channelId, NOTIFICATION_ID, config.iconRes,
				config.ticker, config.title, config.content, config.bigContent);
		final Notification.Builder builder = alertBuilder(context, channelId, display);
		if (!type.alertsEveryTime()) {
			// Non-critical alerts update quietly when re-posted; only critical alerts alert every time.
			builder.setOnlyAlertOnce(true);
		}

		final Notification notification = builder.build();
		if (config.stickyNotification) {
			notification.flags |= Notification.FLAG_NO_CLEAR | Notification.FLAG_ONGOING_EVENT;
		}
		post(context, NOTIFICATION_ID, notification);

		if (config.alertsAllowed) {
			AlertSounds.playAlarm(context, config.alarmSound, config.ignoreSilent, config.vibrate);
		}
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
	public static void notifyChargeConnected(Context context, ChargeSpeed speed, boolean wireless) {
		final String content = chargeConnectedMessage(context, speed, wireless);
		deliverPerChargeStyle(context, content, () -> postChargeNotification(context, content));
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
	public static void sendTemperatureNotification(Context context, int rawTenthsC) {
		final String temperature = TemperatureUtils.format(context, rawTenthsC);
		sendQuietHoursAwareAlert(context, new AlertSpec(
				"temperature",
				NotificationChannels.CHANNEL_ID_TEMPERATURE,
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
	public static void sendFastDrainNotification(Context context, int ratePph, int limitPph, int elapsedMinutes) {
		// Western digits in every locale (#96) via String.valueOf.
		final String rate = String.valueOf(ratePph);
		final String limit = String.valueOf(limitPph);
		final String minutes = String.valueOf(elapsedMinutes);
		final String content = context.getString(R.string.notification_fast_drain_content, rate, minutes, limit);

		sendQuietHoursAwareAlert(context, new AlertSpec(
				"fast-drain",
				NotificationChannels.CHANNEL_ID_FAST_DRAIN,
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
	public static void sendSlowChargeWarning(Context context, int watts) {
		// Western digits in every locale (#96) via String.valueOf.
		final String content = context.getString(R.string.notification_slow_charge_content, String.valueOf(watts));
		deliverPerChargeStyle(context, content, () -> sendQuietHoursAwareAlert(context, new AlertSpec(
				"slow-charge",
				NotificationChannels.CHANNEL_ID_SLOW_CHARGE,
				SLOW_CHARGE_NOTIFICATION_ID,
				R.drawable.ic_stat_device_battery_charging_20,
				context.getString(R.string.notification_slow_charge_ticker),
				context.getString(R.string.notification_slow_charge_title),
				content,
				context.getString(R.string.notification_slow_charge_content_big, String.valueOf(watts)))));
	}

	/**
	 * Clear the charge-session notifications: the level alert and the "Charging started"
	 * notification (#155). Called on charger disconnect, so neither a stale level alert nor a stale
	 * "Charging started" lingers after unplug.
	 *
	 * @param context The application context
	 */
	public static void clearNotifications(Context context) {
		final NotificationManager manager = getNotificationManager(context);
		if (nonNull(manager)) {
			manager.cancel(NOTIFICATION_ID);
			manager.cancel(CHARGE_CONNECTED_NOTIFICATION_ID);
		}
	}

	/**
	 * Dismiss a displayed critical/warning/full level alert.
	 * <p>
	 * Called when a charger is connected: charging resolves the low-battery concern, so the alert is
	 * deliberately dismissed (#155). Previously this happened only as a side effect of the
	 * charge-connected notification reusing the level alert's ID — and therefore only in the
	 * "notification" charge style; now it is an explicit action in every style.
	 *
	 * @param context The application context
	 */
	public static void clearLevelAlert(Context context) {
		final NotificationManager manager = getNotificationManager(context);
		if (nonNull(manager)) {
			manager.cancel(NOTIFICATION_ID);
		}
	}

	/**
	 * Re-create the alert channels so a changed "Vibrate" preference takes effect (issue #153).
	 * Delegates to {@link NotificationChannels#refreshAlertChannels(Context)}.
	 *
	 * @param context The application context
	 */
	public static void refreshAlertChannels(Context context) {
		NotificationChannels.refreshAlertChannels(context);
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
	public static Notification buildOngoingNotification(Context context, BatteryDO batteryDO) {
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
	public static Notification buildOngoingNotification(Context context, BatteryDO batteryDO, BatteryRateTracker.BatteryRate rate) {
		NotificationChannels.ensureChannels(context);

		final String collapsed = OngoingStatusContent.statusDetail(context, batteryDO, rate);
		final String expanded = OngoingStatusContent.statusDetailExpanded(context, batteryDO, rate);
		final Notification.Builder builder = new Notification.Builder(context, NotificationChannels.CHANNEL_ID_STATUS)
				.setSmallIcon(OngoingStatusContent.ongoingIconRes(batteryDO))
				.setContentTitle(OngoingStatusContent.statusTitle(context, batteryDO))
				.setContentText(collapsed)
				.setContentIntent(createMainActivityIntent(context))
				.setOnlyAlertOnce(true)
				.setOngoing(true)
				.setVisibility(Notification.VISIBILITY_PUBLIC)
				.setCategory(Notification.CATEGORY_STATUS);

		// Make it expandable only when the pull-down actually adds something (a multi-line breakdown);
		// a single-line expanded view (e.g. temperature only) would show a pointless expand chevron (#194).
		if (expanded.indexOf('\n') >= 0) {
			builder.setStyle(new Notification.BigTextStyle().bigText(expanded));
		}
		return builder.build();
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
	public static void updateOngoingNotification(Context context, BatteryDO batteryDO, BatteryRateTracker.BatteryRate rate) {
		if (lacksNotificationPermission(context)) {
			return;
		}
		post(context, ONGOING_NOTIFICATION_ID, buildOngoingNotification(context, batteryDO, rate));
	}

	// ========== Private Helper Methods ==========

	/**
	 * Send an alert on its own channel and notification ID, honouring quiet hours, silent-mode and
	 * vibrate preferences.
	 * <p>
	 * The single home of the quiet-hours dance shared by the temperature (#18/#111), fast-drain (#109)
	 * and slow-charge (#123) alerts: rerouting to the silent channel outside the notification window,
	 * and gating the alarm sound/vibration on the window — so a quiet-hours fix can't be applied to one
	 * alert and missed on another. All reuse the critical-alert ringtone preference, as before.
	 *
	 * @param context The application context
	 * @param spec    What to show: channel, id, icon and text content
	 */
	private static void sendQuietHoursAwareAlert(Context context, AlertSpec spec) {
		if (lacksNotificationPermission(context)) {
			Log.w(TAG, "Missing POST_NOTIFICATIONS permission, " + spec.logName() + " alert not sent");
			return;
		}

		NotificationChannels.ensureChannels(context);

		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		// These are not critical battery alerts, so they respect quiet hours (#111).
		final boolean withinWindow = QuietHours.isWithinNotificationWindow(context, prefs);
		final String channelId = NotificationChannels.channelFor(context, withinWindow, spec.audibleChannelId());

		post(context, spec.notificationId(), alertBuilder(context, channelId, spec).build());

		if (withinWindow) {
			final String sound = prefs.getString(
					context.getString(R.string._pref_key_notifications_alert_sound_ringtone),
					context.getString(R.string._default_notification_sound_uri));
			AlertSounds.playAlarm(context, sound, QuietHours.shouldIgnoreSilentMode(context, prefs), AppPrefs.vibrateEnabled(context));
		}
	}

	/**
	 * Post the charge-connected message as a system notification (the "notification" style).
	 * <p>
	 * Plugging in during quiet hours shouldn't ding: shown on the silent channel outside the window
	 * instead of the audible full-battery channel (issue #111). Posted under its own ID so it can
	 * never replace a level alert (#155) — the level alert's dismissal at plug-in is the explicit
	 * {@link #clearLevelAlert} call in {@code PowerConnectionReceiver}, not an ID collision here.
	 *
	 * @param context The application context
	 * @param content The charge message to display
	 */
	private static void postChargeNotification(Context context, String content) {
		if (lacksNotificationPermission(context)) {
			Log.w(TAG, "Missing POST_NOTIFICATIONS permission, notification not sent");
			return;
		}

		NotificationChannels.ensureChannels(context);

		final String title = context.getString(R.string.notification_charge_started_title);
		final String ticker = title.concat(", ").concat(content);
		final AlertSpec spec = new AlertSpec(
				"charge-connected",
				NotificationChannels.CHANNEL_ID_FULL,
				CHARGE_CONNECTED_NOTIFICATION_ID,
				R.drawable.ic_stat_device_battery_charging_50,
				ticker,
				title,
				content,
				content);

		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		final boolean withinWindow = QuietHours.isWithinNotificationWindow(context, prefs);
		final String channelId = NotificationChannels.channelFor(context, withinWindow, spec.audibleChannelId());

		final Notification.Builder builder = alertBuilder(context, channelId, spec);
		builder.setOnlyAlertOnce(true);
		post(context, spec.notificationId(), builder.build());
	}

	/**
	 * Apply the shared alert-notification builder chain (issue #166): the identical
	 * {@code setSmallIcon/Ticker/ContentTitle/ContentText/setWhen/setLargeIcon/setContentIntent/setVisibility/BigTextStyle}
	 * sequence that the level, own-channel and charge-connected alerts each used to spell out separately.
	 * Each caller still owns its own channel resolution, notification id, {@code setOnlyAlertOnce}, sticky
	 * flag and sound — only the identical content chain lives here.
	 *
	 * @param context   The application context
	 * @param channelId The resolved channel id to post on
	 * @param display   What to show: icon + text (an {@link AlertSpec})
	 * @return a Notification.Builder with the shared content applied
	 */
	private static Notification.Builder alertBuilder(Context context, String channelId, AlertSpec display) {
		return new Notification.Builder(context, channelId)
				.setSmallIcon(display.iconRes())
				.setTicker(display.ticker())
				.setContentTitle(display.title())
				.setContentText(display.content())
				.setWhen(System.currentTimeMillis())
				.setLargeIcon(getLauncherIcon(context))
				.setContentIntent(createMainActivityIntent(context))
				.setVisibility(Notification.VISIBILITY_PUBLIC)
				.setStyle(new Notification.BigTextStyle().bigText(display.bigContent()));
	}

	/**
	 * Deliver a charge-related message per the user's chosen charge-notification style (issue #122):
	 * nothing when they opted out, a brief toast, or the caller's own notification path. The single home
	 * of the read-pref → resolve → none/toast/notification branch shared by {@link #notifyChargeConnected}
	 * and {@link #sendSlowChargeWarning}.
	 *
	 * @param context              The application context
	 * @param content              the message to show as a toast (and that the caller shows in its notification)
	 * @param notificationDelivery the caller's notification path, run only for the "notification" style
	 */
	private static void deliverPerChargeStyle(Context context, String content, Runnable notificationDelivery) {
		final String style = chargeStyle(context);
		if (CHARGE_STYLE_NONE.equals(style)) {
			return; // User opted out of charge feedback entirely.
		}
		if (CHARGE_STYLE_TOAST.equals(style)) {
			showChargeToast(context, content);
			return;
		}
		notificationDelivery.run();
	}

	/**
	 * The user's charge-notification style preference, normalized to a known {@code CHARGE_STYLE_*} value.
	 *
	 * @param context The application context
	 * @return one of the {@code CHARGE_STYLE_*} constants
	 */
	private static String chargeStyle(Context context) {
		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		return resolveChargeStyle(prefs.getString(
				context.getString(R.string._pref_key_charge_notification_style), CHARGE_STYLE_TOAST));
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
	static String resolveChargeStyle(String stored) {
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
	private static String chargeConnectedMessage(Context context, ChargeSpeed speed, boolean wireless) {
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
	private static int tierLabelRes(ChargeSpeedTier tier) {
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
	private static void showChargeToast(Context context, String message) {
		final Context appContext = context.getApplicationContext();
		if (Looper.myLooper() == Looper.getMainLooper()) {
			Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show();
		} else {
			new Handler(Looper.getMainLooper()).post(
					() -> Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show());
		}
	}

	/**
	 * Check if app lacks POST_NOTIFICATIONS permission (required for API 33+)
	 *
	 * @param context The application context
	 * @return true if permission is missing, false if granted or not required
	 */
	private static boolean lacksNotificationPermission(Context context) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED;
		}
		return false; // Permission isn't required before API 33, so never lacking
	}

	/**
	 * Post a notification, no-op when the NotificationManager is unavailable.
	 *
	 * @param context      The application context
	 * @param id           The notification id to post under
	 * @param notification The notification to post
	 */
	private static void post(Context context, int id, Notification notification) {
		final NotificationManager manager = getNotificationManager(context);
		if (nonNull(manager)) {
			manager.notify(id, notification);
		}
	}

	/**
	 * Get NotificationManager system service
	 *
	 * @param context The application context
	 * @return NotificationManager instance, or null if unavailable
	 */
	private static NotificationManager getNotificationManager(Context context) {
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
	private static Bitmap getLauncherIcon(Context context) {
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
	private static PendingIntent createMainActivityIntent(Context context) {
		final Intent intent = new Intent(context, MainActivity.class);
		final int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
		return PendingIntent.getActivity(context, 0, intent, flags);
	}
}
