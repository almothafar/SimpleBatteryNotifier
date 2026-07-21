package com.almothafar.simplebatterynotifier.service;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import androidx.preference.PreferenceManager;

import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.util.AppPrefs;

import static java.util.Objects.isNull;

/**
 * The notification-channel registry (issue #166): creates, updates, refreshes and resolves the app's
 * notification channels. Split out of {@code NotificationService} so channel bookkeeping lives in one
 * place.
 * <p>
 * The six alert channels below are <em>base</em> IDs: the actual channel ID carries a version suffix
 * (see {@link #versionedChannelId}) because Android un-deletes a channel recreated under the same ID,
 * restoring its old settings — which made the Vibrate toggle a no-op (issue #153).
 * {@link #refreshAlertChannels} bumps the version so a changed setting really applies.
 */
final class NotificationChannels {

	// Alert (audible, high-importance) channels — base IDs, see class javadoc.
	static final String CHANNEL_ID_CRITICAL = "battery_critical";
	static final String CHANNEL_ID_WARNING = "battery_warning";
	static final String CHANNEL_ID_FULL = "battery_full";
	static final String CHANNEL_ID_TEMPERATURE = "battery_temperature";
	static final String CHANNEL_ID_FAST_DRAIN = "battery_fast_drain";
	static final String CHANNEL_ID_SLOW_CHARGE = "battery_slow_charge";
	// Silent, low-importance channels: the persistent status notification, and the quiet-hours channel
	// used to deliver an alert quietly during the user's quiet hours — still visible, no sound/vibration
	// (issue #111).
	static final String CHANNEL_ID_STATUS = "battery_status";
	static final String CHANNEL_ID_ALERTS_SILENT = "battery_alerts_quiet";

	// Current version of the alert channels' settings, stored in the default SharedPreferences.
	// Version 1 means the original unsuffixed channel IDs, so existing installs keep their channels
	// (and any per-channel tweaks) until the user first changes the Vibrate preference.
	private static final String PREF_ALERT_CHANNEL_VERSION = "alert_channel_version";

	private NotificationChannels() {
		// Utility class - prevent instantiation
	}

	/**
	 * Create the notification channels if they don't exist, or refresh their name/description if they
	 * do (so translated names reach upgraded installs — issue #165).
	 *
	 * @param context The application context
	 */
	static void ensureChannels(Context context) {
		final NotificationManager manager = getManager(context);
		if (isNull(manager)) {
			return;
		}

		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		final boolean vibrate = AppPrefs.vibrateEnabled(context);
		final int version = alertChannelVersion(prefs);

		createOrUpdateAlertChannel(manager, versionedChannelId(CHANNEL_ID_CRITICAL, version),
				context.getString(R.string.notification_critical_channel_name),
				context.getString(R.string.notification_critical_channel_description), Color.RED, vibrate);
		createOrUpdateAlertChannel(manager, versionedChannelId(CHANNEL_ID_WARNING, version),
				context.getString(R.string.notification_warning_channel_name),
				context.getString(R.string.notification_warning_channel_description), Color.rgb(0xff, 0x66, 0x00), vibrate);
		createOrUpdateAlertChannel(manager, versionedChannelId(CHANNEL_ID_FULL, version),
				context.getString(R.string.notification_full_channel_name),
				context.getString(R.string.notification_full_channel_description), Color.GREEN, vibrate);
		createOrUpdateAlertChannel(manager, versionedChannelId(CHANNEL_ID_TEMPERATURE, version),
				context.getString(R.string.notification_temperature_channel_name),
				context.getString(R.string.notification_temperature_channel_description), Color.RED, vibrate);
		createOrUpdateAlertChannel(manager, versionedChannelId(CHANNEL_ID_FAST_DRAIN, version),
				context.getString(R.string.notification_fast_drain_channel_name),
				context.getString(R.string.notification_fast_drain_channel_description), Color.rgb(0xff, 0x66, 0x00), vibrate);
		createOrUpdateAlertChannel(manager, versionedChannelId(CHANNEL_ID_SLOW_CHARGE, version),
				context.getString(R.string.notification_slow_charge_channel_name),
				context.getString(R.string.notification_slow_charge_channel_description), Color.rgb(0xff, 0x66, 0x00), vibrate);
		createOrUpdateSilentChannel(manager, CHANNEL_ID_STATUS,
				context.getString(R.string.notification_status_channel_name),
				context.getString(R.string.notification_status_channel_description));
		createOrUpdateSilentChannel(manager, CHANNEL_ID_ALERTS_SILENT,
				context.getString(R.string.notification_quiet_channel_name),
				context.getString(R.string.notification_quiet_channel_description));
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
	static void refreshAlertChannels(Context context) {
		final NotificationManager manager = getManager(context);
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
		ensureChannels(context);
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
	static String channelFor(Context context, boolean alertsAllowed, String audibleBaseChannelId) {
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
	static String versionedChannelId(String baseChannelId, int version) {
		return version <= 1 ? baseChannelId : baseChannelId + "_v" + version;
	}

	/**
	 * The current alert-channel settings version, bumped by {@link #refreshAlertChannels(Context)}
	 * whenever the Vibrate preference changes.
	 *
	 * @param prefs the default SharedPreferences
	 * @return the current version, 1 for installs that never changed the Vibrate preference
	 */
	private static int alertChannelVersion(SharedPreferences prefs) {
		return prefs.getInt(PREF_ALERT_CHANNEL_VERSION, 1);
	}

	/**
	 * Create a low-importance, fully silent channel (no sound, vibration, lights or badge), or update
	 * its name/description if it already exists.
	 * <p>
	 * Used both by the persistent status notification and, during the user's quiet hours, to deliver
	 * an alert quietly so it stays visible without disturbing the user (issue #111).
	 * <p>
	 * As with the alert channels, re-calling for an existing ID updates only name/description, so
	 * translated names reach upgraded installs (#165) without disturbing the silent behaviour.
	 *
	 * @param manager     The NotificationManager
	 * @param channelId   The channel ID to create
	 * @param name        The channel name
	 * @param description The channel description
	 */
	private static void createOrUpdateSilentChannel(NotificationManager manager, String channelId, String name, String description) {
		final NotificationChannel channel = new NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_LOW);
		channel.setDescription(description);
		channel.enableLights(false);
		channel.enableVibration(false);
		channel.setSound(null, null);
		channel.setShowBadge(false);
		manager.createNotificationChannel(channel);
	}

	/**
	 * Create an alert channel, or update its name/description if it already exists.
	 * <p>
	 * Re-calling {@code createNotificationChannel} with an existing ID updates only the name,
	 * description and group — Android ignores importance, vibration, lights and sound so the user's
	 * (and the versioned #153) settings are preserved. This is how translated channel names reach
	 * <em>upgraded</em> installs, not just fresh ones (#165): the channel already exists, so we still
	 * re-apply the current locale's name and description.
	 *
	 * @param manager     The NotificationManager
	 * @param channelId   The channel ID
	 * @param name        The channel name
	 * @param description The channel description
	 * @param ledColor    The LED color for notifications
	 * @param vibrate     Whether the channel should vibrate (from the user's Vibrate preference)
	 */
	private static void createOrUpdateAlertChannel(NotificationManager manager, String channelId, String name, String description,
	                                               int ledColor, boolean vibrate) {
		final NotificationChannel channel = new NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_HIGH);
		channel.setDescription(description);
		channel.enableLights(true);
		channel.setLightColor(ledColor);
		channel.enableVibration(vibrate);
		if (vibrate) {
			channel.setVibrationPattern(SystemService.VIBRATION_PATTERN);
		}
		manager.createNotificationChannel(channel);
	}

	private static NotificationManager getManager(Context context) {
		return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
	}
}
