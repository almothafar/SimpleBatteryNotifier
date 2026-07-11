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
import com.almothafar.simplebatterynotifier.model.BatteryHealthStatus;
import com.almothafar.simplebatterynotifier.model.ChargeSpeed;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

import static java.util.Objects.isNull;

/**
 * System service utilities for battery monitoring, sound, and vibration
 */
public final class SystemService {
	private static final String TAG = "com.almothafar";

	// A plausible phone-battery full-capacity range (mAh). Some devices (e.g. certain Kirin/HiSilicon)
	// report BATTERY_PROPERTY_CHARGE_COUNTER in a non-standard unit, yielding absurd single/double-digit
	// mAh estimates; anything outside this range is treated as "unknown". See issue #69.
	private static final int MIN_PLAUSIBLE_CAPACITY_MAH = 500;
	private static final int MAX_PLAUSIBLE_CAPACITY_MAH = 15000;

	// Linux power-supply sysfs directory; a supply's charge_full_design node holds the rated capacity
	// in µAh. Readable on some devices, blocked by SELinux on many newer ones (issue #104).
	private static final String POWER_SUPPLY_DIR = "/sys/class/power_supply";
	private static final String CHARGE_FULL_DESIGN_NODE = "charge_full_design";

	private SystemService() {
		// Utility class - prevent instantiation
	}

	/**
	 * Get battery information from the system
	 *
	 * @param context The application context
	 *
	 * @return BatteryDO object containing battery information, or null if battery status is unavailable
	 */
	public static BatteryDO getBatteryInfo(final Context context) {
		return getBatteryInfo(context, getBatteryStatusIntent(context));
	}

	/**
	 * Build battery information from an already-obtained {@code ACTION_BATTERY_CHANGED} intent.
	 * <p>
	 * Lets a caller that already holds the sticky battery intent (e.g. a receiver that just read it)
	 * reuse it instead of triggering a second sticky-broadcast read.
	 *
	 * @param context       The application context
	 * @param batteryStatus A sticky {@code ACTION_BATTERY_CHANGED} intent, or null if unavailable
	 *
	 * @return BatteryDO built from the intent, or null when {@code batteryStatus} is null
	 */
	public static BatteryDO getBatteryInfo(final Context context, final Intent batteryStatus) {
		if (isNull(batteryStatus)) {
			return null;
		}

		final Resources resources = context.getResources();
		final BatteryExtras extras = extractBatteryExtras(batteryStatus);
		final String chargerType = determineChargerType(extras.plugged, resources);
		final int batteryCapacity = getBatteryCapacity(context);
		final int currentMicroAmps = getInstantaneousCurrentMicroAmps(context);

		return buildBatteryDataObject(extras, chargerType, batteryCapacity, currentMicroAmps, resources);
	}

	/**
	 * Map a {@link BatteryManager} status constant to its localized label
	 * (e.g. "Charging", "Discharging", "Charged").
	 * <p>
	 * Single source of truth shared by the main screen's subtitle and the ongoing status
	 * notification, so the two can't drift.
	 *
	 * @param context The application context
	 * @param status  A {@code BatteryManager.BATTERY_STATUS_*} constant
	 *
	 * @return The localized status label ("Unknown" for unrecognized values)
	 */
	public static String getStatusLabel(final Context context, final int status) {
		final Resources resources = context.getResources();
		return switch (status) {
			case BatteryManager.BATTERY_STATUS_FULL -> resources.getString(R.string.charged);
			case BatteryManager.BATTERY_STATUS_CHARGING -> resources.getString(R.string.charging);
			case BatteryManager.BATTERY_STATUS_NOT_CHARGING -> resources.getString(R.string.not_charging);
			case BatteryManager.BATTERY_STATUS_DISCHARGING -> resources.getString(R.string.discharging);
			default -> resources.getString(R.string.unknown);
		};
	}

	/**
	 * Get battery status intent from system
	 *
	 * @param context The application context
	 *
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
	 *
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
		final boolean present = extras != null && extras.getBoolean(BatteryManager.EXTRA_PRESENT);
		final String technology = extras != null ? extras.getString(BatteryManager.EXTRA_TECHNOLOGY) : "";

		return new BatteryExtras(level, scale, status, health, plugged, temperature, voltage, present, technology);
	}

	/**
	 * Determine the charger type string based on plugged status
	 *
	 * @param plugged   The plugged status from BatteryManager
	 * @param resources Resources for string lookup
	 *
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
	 *
	 * @return Populated BatteryDO object
	 */
	private static BatteryDO buildBatteryDataObject(final BatteryExtras extras,
	                                                final String chargerType,
	                                                final int batteryCapacity,
	                                                final int currentMicroAmps,
	                                                final Resources resources) {
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
		         .setCurrentMicroAmps(currentMicroAmps)
		         .setIntHealth(extras.health);

		// Determine health status and set it on the battery object
		final BatteryHealthStatus healthStatus = determineHealthStatus(extras.health);
		batteryDO.setHealthStatus(healthStatus);

		// Get the human-readable health string
		final String healthAsString = getHealthString(extras.health, resources);
		batteryDO.setHealth(healthAsString);

		return batteryDO;
	}

	/**
	 * Determine battery health status from BatteryManager health constant
	 * <p>
	 * This method has a single responsibility: converting the health constant
	 * to a BatteryHealthStatus enum value.
	 *
	 * @param health Battery health constant from BatteryManager
	 *
	 * @return BatteryHealthStatus enum representing the health level
	 */
	private static BatteryHealthStatus determineHealthStatus(final int health) {
		return switch (health) {
			case BatteryManager.BATTERY_HEALTH_GOOD -> BatteryHealthStatus.GOOD;
			case BatteryManager.BATTERY_HEALTH_DEAD,
			     BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> BatteryHealthStatus.CRITICAL;
			case BatteryManager.BATTERY_HEALTH_COLD,
			     BatteryManager.BATTERY_HEALTH_OVERHEAT,
			     BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> BatteryHealthStatus.WARNING;
			default -> BatteryHealthStatus.UNKNOWN;
		};
	}

	/**
	 * Get human-readable battery health string from BatteryManager health constant
	 * <p>
	 * This method has a single responsibility: converting the health constant
	 * to a localized string resource.
	 *
	 * @param health    Battery health constant from BatteryManager
	 * @param resources Resource instance for string lookup
	 *
	 * @return Human-readable health string
	 */
	private static String getHealthString(final int health, final Resources resources) {
		return switch (health) {
			case BatteryManager.BATTERY_HEALTH_GOOD -> resources.getString(R.string.battery_health_good);
			case BatteryManager.BATTERY_HEALTH_DEAD -> resources.getString(R.string.battery_health_dead);
			case BatteryManager.BATTERY_HEALTH_COLD -> resources.getString(R.string.battery_health_cold);
			case BatteryManager.BATTERY_HEALTH_OVERHEAT -> resources.getString(R.string.battery_health_overheat);
			case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> resources.getString(R.string.battery_health_over_voltage);
			case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> resources.getString(R.string.battery_health_unspecified_failure);
			default -> "";
		};
	}

	/**
	 * Estimate the battery's full capacity (mAh) using public {@link BatteryManager} properties.
	 * <p>
	 * Android does not expose the battery's design capacity, so this divides the instantaneous
	 * remaining charge ({@link BatteryManager#BATTERY_PROPERTY_CHARGE_COUNTER}, in µAh) by the
	 * remaining percentage ({@link BatteryManager#BATTERY_PROPERTY_CAPACITY}) to estimate the full
	 * capacity. This replaces a previous approach that reflected into the private
	 * {@code com.android.internal.os.PowerProfile} class, which is blocked by non-SDK restrictions
	 * on modern Android and returned 0 on most devices.
	 *
	 * @param context The application context
	 *
	 * @return Estimated full battery capacity in mAh, or 0 if it cannot be determined
	 */
	public static int getBatteryCapacity(final Context context) {
		final BatteryManager batteryManager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
		if (isNull(batteryManager)) {
			Log.w(TAG, "BatteryManager service unavailable");
			return 0;
		}

		final int chargeCounterUah = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
		final int capacityPercent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
		return estimateFullCapacityMah(chargeCounterUah, capacityPercent);
	}

	/**
	 * Estimate the current charging speed from the instantaneous current and battery voltage.
	 * <p>
	 * Combines {@link #getInstantaneousCurrentMicroAmps(Context)} with the battery voltage
	 * ({@link BatteryManager#EXTRA_VOLTAGE}, mV) into a {@link ChargeSpeed}. Best sampled a moment
	 * after connection, once the current has stabilised — right at plug-in the current reads 0 or
	 * noisy. Returns {@link ChargeSpeed#unknown()} when the device doesn't report instantaneous
	 * current, so callers fall back to a plain "Charging" message.
	 *
	 * @param context The application context
	 *
	 * @return the estimated {@link ChargeSpeed}; never null
	 */
	public static ChargeSpeed getChargeSpeed(final Context context) {
		final int currentMicroAmps = getInstantaneousCurrentMicroAmps(context);

		final Intent batteryStatus = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
		final int voltageMilliVolts = isNull(batteryStatus) ? 0 : batteryStatus.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);

		return ChargeSpeed.fromMeasurements(currentMicroAmps, voltageMilliVolts);
	}

	/**
	 * Read the live instantaneous battery current from {@link BatteryManager#BATTERY_PROPERTY_CURRENT_NOW}
	 * (µA), used to derive the charge/drain rate and the signed "Current" row (issue #108).
	 * <p>
	 * The raw value is returned unfiltered: {@code getIntProperty} yields {@link Integer#MIN_VALUE} when
	 * the property is unsupported, and the sign convention varies by OEM. Callers gate plausibility and
	 * derive direction from the charging status (see {@link BatteryRateTracker}).
	 *
	 * @param context The application context
	 *
	 * @return instantaneous current in µA, or {@link Integer#MIN_VALUE} when unavailable
	 */
	public static int getInstantaneousCurrentMicroAmps(final Context context) {
		final BatteryManager batteryManager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
		if (isNull(batteryManager)) {
			Log.w(TAG, "BatteryManager service unavailable");
			return Integer.MIN_VALUE;
		}
		return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
	}

	/**
	 * Estimate the full battery capacity (mAh) from the remaining charge and remaining percentage.
	 * <p>
	 * Pure helper with no Android dependencies, so it is unit-testable. {@code getIntProperty}
	 * returns {@link Integer#MIN_VALUE} for unsupported properties; any non-positive or
	 * out-of-range input, or a result outside a plausible battery range, yields 0 ("unknown").
	 *
	 * @param chargeCounterUah remaining charge in microampere-hours (µAh)
	 * @param capacityPercent  remaining charge as a percentage (1-100)
	 *
	 * @return estimated full capacity in mAh, or 0 when the inputs are unusable or the result is implausible
	 */
	static int estimateFullCapacityMah(final int chargeCounterUah, final int capacityPercent) {
		if (chargeCounterUah <= 0 || capacityPercent <= 0 || capacityPercent > 100) {
			return 0; // Unknown / unsupported on this device
		}
		// full µAh = chargeCounter / (percent / 100); mAh = full / 1000  ==>  chargeCounter / (percent * 10)
		final int estimateMah = Math.round(chargeCounterUah / (capacityPercent * 10f));
		// Some devices report CHARGE_COUNTER in the wrong unit, producing absurd values (e.g. 9 mAh from a
		// raw counter of 9000). Reject anything outside a plausible battery range so callers show "Unknown"
		// and fall back to the cycle-based estimate rather than garbage. See issue #69.
		if (estimateMah < MIN_PLAUSIBLE_CAPACITY_MAH || estimateMah > MAX_PLAUSIBLE_CAPACITY_MAH) {
			return 0;
		}
		return estimateMah;
	}

	// --- Battery design (rated) capacity, read best-effort from the kernel (issue #104) ---

	/**
	 * Best-effort read of the battery's design (rated) capacity from the kernel power-supply nodes.
	 * <p>
	 * Android exposes no public API for design capacity, but many devices surface it via the Linux
	 * power-supply class at {@code /sys/class/power_supply/<supply>/charge_full_design} (in µAh). This
	 * scans the available supplies for that node and returns the first plausible value in mAh. When the
	 * node is missing or blocked — common under SELinux on newer devices — it returns 0 so callers fall
	 * back to manual entry.
	 *
	 * @return design capacity in mAh, or 0 when it cannot be read
	 */
	public static int getDesignCapacityFromSystem() {
		final File[] supplies = new File(POWER_SUPPLY_DIR).listFiles();
		if (isNull(supplies)) {
			return 0; // Directory not listable (e.g. SELinux) — fall back to manual entry.
		}
		for (final File supply : supplies) {
			final int mah = readDesignCapacityMah(new File(supply, CHARGE_FULL_DESIGN_NODE));
			if (mah > 0) {
				return mah;
			}
		}
		return 0;
	}

	/**
	 * Reads and validates a single {@code charge_full_design} node.
	 * <p>
	 * The expected "missing" cases (node absent or not readable) are validated up front rather than
	 * caught. Only the genuinely-exceptional case — the node passes those checks but the read still
	 * fails — is caught, logged (not swallowed) and treated as "unavailable".
	 *
	 * @param node the candidate {@code charge_full_design} file
	 *
	 * @return design capacity in mAh, or 0 when unavailable or implausible
	 */
	private static int readDesignCapacityMah(final File node) {
		if (!node.exists() || !node.canRead()) {
			return 0;
		}
		try (BufferedReader reader = new BufferedReader(new FileReader(node))) {
			return designCapacityMahFromMicroAmpHours(reader.readLine());
		} catch (IOException e) {
			Log.d(TAG, "Unable to read " + node + ": " + e.getMessage());
			return 0;
		}
	}

	/**
	 * Converts a raw {@code charge_full_design} value (microampere-hours) to a plausible mAh capacity.
	 * <p>
	 * Pure and Android-free so it is unit-testable. Validates the text before parsing (so malformed
	 * content is a no-op, not an exception) and rejects results outside the plausible battery range,
	 * mirroring {@link #estimateFullCapacityMah}.
	 *
	 * @param rawMicroAmpHours raw node contents (µAh as text), or null
	 *
	 * @return design capacity in mAh, or 0 when the value is missing, malformed or implausible
	 */
	static int designCapacityMahFromMicroAmpHours(final String rawMicroAmpHours) {
		if (isNull(rawMicroAmpHours)) {
			return 0;
		}
		final String trimmed = rawMicroAmpHours.trim();
		// µAh for a phone battery is at most ~8 digits; bound the length so the parse can't overflow.
		if (!trimmed.matches("\\d{1,9}")) {
			return 0;
		}
		final int mah = (int) (Long.parseLong(trimmed) / 1000L);
		if (mah < MIN_PLAUSIBLE_CAPACITY_MAH || mah > MAX_PLAUSIBLE_CAPACITY_MAH) {
			return 0;
		}
		return mah;
	}

	/**
	 * Read the OS-reported charge cycle count, where available.
	 * <p>
	 * Exposed to apps via the public {@link BatteryManager#EXTRA_CYCLE_COUNT} extra on
	 * {@code ACTION_BATTERY_CHANGED} (Android 14 / API 34+, populated on devices whose battery
	 * fuel gauge reports it). Returns -1 when unavailable, so callers can fall back to the
	 * app's own estimate. Note: the per-app battery State-of-Health <em>percentage</em> is a
	 * privileged/system API and is not available here.
	 *
	 * @param context The application context
	 *
	 * @return Charge cycle count, or -1 if the device doesn't report it
	 */
	public static int getChargeCycleCount(final Context context) {
		final Intent batteryStatus = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
		if (isNull(batteryStatus)) {
			return -1;
		}
		final int cycles = batteryStatus.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1);
		return cycles > 0 ? cycles : -1;
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
			@SuppressWarnings("deprecation") final Vibrator systemService = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
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
		// Release the native player once playback finishes so repeated alerts don't leak
		// MediaPlayer instances. Paths where nothing plays release in the finally block below.
		mediaPlayer.setOnCompletionListener(MediaPlayer::release);
		boolean playbackStarted = false;
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
				playbackStarted = true; // OnCompletionListener now owns the release
			}
		} catch (IOException e) {
			final String errorMsg = e.getMessage();
			if (errorMsg != null) {
				Log.e(TAG, errorMsg);
			}
		} finally {
			// Nothing is playing (alarm silenced, no AudioManager, or setup failed): release now.
			// When playback started, the OnCompletionListener releases on completion instead.
			if (!playbackStarted) {
				mediaPlayer.release();
			}
		}
	}

	/**
	 * Internal data class to hold extracted battery extras
	 */
	private record BatteryExtras(int level,
	                             int scale,
	                             int status,
	                             int health,
	                             int plugged,
	                             int temperature,
	                             int voltage,
	                             boolean present,
	                             String technology) {
	}
}
