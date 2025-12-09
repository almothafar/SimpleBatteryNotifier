package com.almothafar.simplebatterynotifier.service;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.model.BatteryDO;

import java.io.IOException;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * System service utilities for battery monitoring, sound, and vibration
 */
public final class SystemService {
	private static final String TAG = "com.almothafar";

	private SystemService() {
		// Utility class - prevent instantiation
	}

	/**
	 * Get battery information from the system
	 *
	 * @param context The application context
	 * @return BatteryDO object containing battery information, or null if battery status is unavailable
	 */
	public static BatteryDO getBatteryInfo(final Context context) {
		final Intent batteryStatus = getBatteryStatusIntent(context);
		if (isNull(batteryStatus)) {
			return null;
		}

		final Resources resources = context.getResources();
		final BatteryExtras extras = extractBatteryExtras(batteryStatus);
		final String chargerType = determineChargerType(extras.plugged, resources);
		final int batteryCapacity = getBatteryCapacity(context);

		return buildBatteryDataObject(extras, chargerType, batteryCapacity, resources);
	}

	/**
	 * Get battery status intent from system
	 *
	 * @param context The application context
	 * @return Battery status intent, or null if unavailable
	 */
	private static Intent getBatteryStatusIntent(final Context context) {
		final IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
		final Intent batteryStatus = context.registerReceiver(null, filter);

		if (isNull(batteryStatus)) {
			Log.w(TAG, "Unable to retrieve battery status");
		}

		return batteryStatus;
	}

	/**
	 * Extract all battery-related data from the intent
	 *
	 * @param batteryStatus The battery status intent
	 * @return BatteryExtras object containing all extracted data
	 */
	private static BatteryExtras extractBatteryExtras(final Intent batteryStatus) {
		final int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
		final int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
		final int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
		final int health = batteryStatus.getIntExtra(BatteryManager.EXTRA_HEALTH, 0);
		final int plugged = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
		final int temperature = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
		final int voltage = batteryStatus.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);

		// CRITICAL: Check for null extras before accessing
		final Bundle extras = batteryStatus.getExtras();
		final boolean present = nonNull(extras) && extras.getBoolean(BatteryManager.EXTRA_PRESENT);
		final String technology = nonNull(extras) ? extras.getString(BatteryManager.EXTRA_TECHNOLOGY) : "";

		return new BatteryExtras(level, scale, status, health, plugged, temperature, voltage, present, technology);
	}

	/**
	 * Determine the charger type string based on plugged status
	 *
	 * @param plugged   The plugged status from BatteryManager
	 * @param resources Resources for string lookup
	 * @return Charger type string
	 */
	@SuppressLint("InlinedApi") // BATTERY_PLUGGED_WIRELESS added in API 17
	private static String determineChargerType(final int plugged, final Resources resources) {
		if (plugged == 0) {
			return resources.getString(R.string.battery);
		}

		// Check charger types in priority order
		if (plugged == BatteryManager.BATTERY_PLUGGED_USB) {
			return resources.getString(R.string.charger_usb);
		} else if (plugged == BatteryManager.BATTERY_PLUGGED_AC) {
			return resources.getString(R.string.charger_ac);
		} else if (plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS) {
			return resources.getString(R.string.charger_wireless);
		} else {
			return resources.getString(R.string.battery);
		}
	}

	/**
	 * Build the BatteryDO object from extracted data
	 *
	 * @param extras          Extracted battery extras
	 * @param chargerType     Charger type string
	 * @param batteryCapacity Battery capacity in mAh
	 * @param resources       Resources for string lookup
	 * @return Populated BatteryDO object
	 */
	private static BatteryDO buildBatteryDataObject(final BatteryExtras extras, final String chargerType,
	                                                 final int batteryCapacity, final Resources resources) {
		final BatteryDO batteryDO = new BatteryDO();
		batteryDO.setLevel(extras.level)
		         .setScale(extras.scale)
		         .setStatus(extras.status)
		         .setPowerSource(chargerType)
		         .setPlugged(extras.plugged)
		         .setPresent(extras.present)
		         .setTechnology(extras.technology)
		         .setTemperature(extras.temperature)
		         .setVoltage(extras.voltage)
		         .setCapacity(batteryCapacity)
		         .setIntHealth(extras.health);

		final String healthAsString = determineHealthString(extras.health, batteryDO, resources);
		batteryDO.setHealth(healthAsString);

		return batteryDO;
	}

	/**
	 * Internal data class to hold extracted battery extras
	 */
	private static final class BatteryExtras {
		final int level;
		final int scale;
		final int status;
		final int health;
		final int plugged;
		final int temperature;
		final int voltage;
		final boolean present;
		final String technology;

		BatteryExtras(final int level, final int scale, final int status, final int health,
		              final int plugged, final int temperature, final int voltage,
		              final boolean present, final String technology) {
			this.level = level;
			this.scale = scale;
			this.status = status;
			this.health = health;
			this.plugged = plugged;
			this.temperature = temperature;
			this.voltage = voltage;
			this.present = present;
			this.technology = technology;
		}
	}

	/**
	 * Determine battery health string and set warning/critical flags using switch expression
	 *
	 * @param health    Battery health constant from BatteryManager
	 * @param batteryDO The BatteryDO object to update with warning/critical flags
	 * @param resources Resource instance for string lookup
	 * @return Human-readable health string
	 */
	private static String determineHealthString(final int health, final BatteryDO batteryDO, final Resources resources) {
		// Use switch expression for cleaner code (Java 14+)
		return switch (health) {
			case BatteryManager.BATTERY_HEALTH_GOOD -> resources.getString(R.string.battery_health_good);
			case BatteryManager.BATTERY_HEALTH_DEAD -> {
				batteryDO.setCriticalHealth(true);
				yield resources.getString(R.string.battery_health_dead);
			}
			case BatteryManager.BATTERY_HEALTH_COLD -> {
				batteryDO.setWarningHealth(true);
				yield resources.getString(R.string.battery_health_cold);
			}
			case BatteryManager.BATTERY_HEALTH_OVERHEAT -> {
				batteryDO.setWarningHealth(true);
				yield resources.getString(R.string.battery_health_overheat);
			}
			case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> {
				batteryDO.setCriticalHealth(true);
				yield resources.getString(R.string.battery_health_over_voltage);
			}
			case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> {
				batteryDO.setWarningHealth(true);
				yield resources.getString(R.string.battery_health_unspecified_failure);
			}
			default -> "";
		};
	}

	/**
	 * Get battery capacity using reflection (internal API)
	 * <p>
	 * WARNING: This method accesses internal Android APIs via reflection.
	 * Android does not provide a public API to retrieve battery capacity (mAh).
	 * This implementation may not work on all devices or future Android versions.
	 * <p>
	 * The method gracefully handles failures and returns 0 if capacity cannot be determined.
	 * This is acceptable as capacity is informational only and not critical for app functionality.
	 *
	 * @param context The application context
	 * @return Battery capacity in mAh, or 0 if unavailable or unsupported
	 */
	@SuppressLint("DiscouragedPrivateApi")
	public static synchronized int getBatteryCapacity(final Context context) {
		// Power profile class name - internal API, not guaranteed to be available
		final String POWER_PROFILE_CLASS = "com.android.internal.os.PowerProfile";

		Object powerProfile = null;
		int batteryCapacity = 0;

		try {
			// Access internal PowerProfile class via reflection
			// This is the only way to get battery capacity as Android doesn't provide a public API
			powerProfile = Class.forName(POWER_PROFILE_CLASS)
			                    .getConstructor(Context.class)
			                    .newInstance(context);
		} catch (Exception e) {
			// Silently fail - this is expected on some devices or Android versions
			final String errorMsg = e.getMessage();
			if (nonNull(errorMsg)) {
				Log.w(TAG, "Unable to access PowerProfile class: " + errorMsg);
			}
		}

		if (nonNull(powerProfile)) {
			try {
				final Object result = Class.forName(POWER_PROFILE_CLASS)
				                           .getMethod("getAveragePower", java.lang.String.class)
				                           .invoke(powerProfile, "battery.capacity");
				if (result instanceof Double) {
					batteryCapacity = ((Double) result).intValue();
				}
			} catch (Exception e) {
				// Silently fail - graceful degradation
				final String errorMsg = e.getMessage();
				if (nonNull(errorMsg)) {
					Log.w(TAG, "Unable to retrieve battery capacity: " + errorMsg);
				}
			}
		}

		return batteryCapacity;
	}

	/**
	 * Vibrate the phone with a predefined pattern
	 *
	 * @param context The application context
	 */
	public static void vibratePhone(final Context context) {
		// Get Vibrator using modern API for S+ or legacy for older versions
		final Vibrator vibrator;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			final VibratorManager vibratorManager = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
			if (isNull(vibratorManager)) {
				Log.w(TAG, "VibratorManager service unavailable");
				return;
			}
			vibrator = vibratorManager.getDefaultVibrator();
		} else {
			// VIBRATOR_SERVICE deprecated in API 31, but required for API 26-30 (minSdk is 26)
			@SuppressWarnings("deprecation")
			final Vibrator systemService = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
			vibrator = systemService;
		}

		if (isNull(vibrator)) {
			Log.w(TAG, "Vibrator service unavailable");
			return;
		}

		if (vibrator.hasVibrator()) {
			final long[] pattern = {0, 500, 250, 500, 250};
			// Use VibrationEffect for O+ (API 26 is minSdk, so this is always available)
			vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
		} else {
			Log.w(TAG, "Device cannot vibrate");
		}
	}

	/**
	 * Play a sound alert using MediaPlayer
	 *
	 * @param context The application context
	 * @param alert   The URI of the sound to play
	 */
	public static void playSound(final Context context, final Uri alert) {
		final MediaPlayer mediaPlayer = new MediaPlayer();
		try {
			mediaPlayer.setDataSource(context, alert);
			final AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

			if (isNull(audioManager)) {
				Log.w(TAG, "AudioManager service unavailable");
				return;
			}

			if (audioManager.getStreamVolume(AudioManager.STREAM_ALARM) != 0) {
				// setAudioStreamType() deprecated - use AudioAttributes instead
				final AudioAttributes audioAttributes = new AudioAttributes.Builder()
						.setUsage(AudioAttributes.USAGE_ALARM)
						.setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
						.build();
				mediaPlayer.setAudioAttributes(audioAttributes);
				mediaPlayer.prepare();
				mediaPlayer.start();
			}
		} catch (IOException e) {
			final String errorMsg = e.getMessage();
			if (nonNull(errorMsg)) {
				Log.e(TAG, errorMsg);
			}
		}
	}
}
