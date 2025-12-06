package com.almothafar.simplebatterynotifier.service;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Vibrator;
import android.util.Log;

import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.model.BatteryDO;

import java.io.IOException;

/**
 * Created by Al-Mothafar on 24/08/2015.
 */
public class SystemService {
    private static final String TAG = "com.almothafar";

    public static BatteryDO getBatteryInfo(Context context) {
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, filter);

        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int health = batteryStatus.getIntExtra(BatteryManager.EXTRA_HEALTH, 0);
        int plugged = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        boolean present = batteryStatus.getExtras().getBoolean(BatteryManager.EXTRA_PRESENT);
        String technology = batteryStatus.getExtras().getString(BatteryManager.EXTRA_TECHNOLOGY);
        int temperature = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
        int voltage = batteryStatus.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        int batteryCapacity = getBatteryCapacity(context);

        boolean usbCharge = (plugged == BatteryManager.BATTERY_PLUGGED_USB);
        boolean acCharge = (plugged == BatteryManager.BATTERY_PLUGGED_AC);
        @SuppressLint("InlinedApi")
        boolean wirelessCharge = plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS;
        String charger = "";
        if (plugged > 0) {
            if (usbCharge) {
                charger = context.getResources().getString(R.string.charger_usb);
            } else if (acCharge) {
                charger = context.getResources().getString(R.string.charger_ac);
            } else if (wirelessCharge) {
                charger = context.getResources().getString(R.string.charger_wireless);
            }
        } else {
            charger = context.getResources().getString(R.string.battery);
        }

        BatteryDO batteryDO = new BatteryDO();
        batteryDO.setLevel(level)
                .setScale(scale)
                .setStatus(status)
                .setPowerSource(charger)
                .setPlugged(plugged)
                .setPresent(present)
                .setTechnology(technology)
                .setTemperature(temperature)
                .setVoltage(voltage)
                .setCapacity(batteryCapacity)
                .setIntHealth(health);

        String healthAsString = "";

        if (health == BatteryManager.BATTERY_HEALTH_GOOD) {
            healthAsString = context.getResources().getString(R.string.battery_health_good);
        } else if (health == BatteryManager.BATTERY_HEALTH_DEAD) {
            healthAsString = context.getResources().getString(R.string.battery_health_dead);
            batteryDO.setCriticalHealth(true);
        } else if (health == BatteryManager.BATTERY_HEALTH_COLD) {
            healthAsString = context.getResources().getString(R.string.battery_health_cold);
            batteryDO.setWarningHealth(true);
        } else if (health == BatteryManager.BATTERY_HEALTH_OVERHEAT) {
            healthAsString = context.getResources().getString(R.string.battery_health_overheat);
            batteryDO.setWarningHealth(true);
        } else if (health == BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE) {
            healthAsString = context.getResources().getString(R.string.battery_health_over_voltage);
            batteryDO.setCriticalHealth(true);
        } else if (health == BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE) {
            healthAsString = context.getResources().getString(R.string.battery_health_unspecified_failure);
            batteryDO.setWarningHealth(true);
        }

        batteryDO.setHealth(healthAsString);

        return batteryDO;
    }

    /**
     *
     * @param context
     * @return
     */
    public static synchronized int getBatteryCapacity(Context context) {
        // Power profile class instance
        Object mPowerProfile_ = null;
        // Reset variable for battery capacity
        int batteryCapacity = 0;
        // Power profile class name
        final String POWER_PROFILE_CLASS = "com.android.internal.os.PowerProfile";
        try {
            // Get power profile class and create instance. We have to do this
            // dynamically because android.internal package is not part of public API
            mPowerProfile_ = Class.forName(POWER_PROFILE_CLASS)
                    .getConstructor(Context.class).newInstance(context);
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
        }

        try {
            batteryCapacity = ((Double) Class
                    .forName(POWER_PROFILE_CLASS)
                    .getMethod("getAveragePower", java.lang.String.class)
                    .invoke(mPowerProfile_, "battery.capacity")).intValue();

        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
        }

        return batteryCapacity;
    }

    /**
     *
     * @param context
     */
    public static void vibratePhone(Context context) {
        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator.hasVibrator()) {
            long[] pattern = {0, 500, 250, 500, 250};
            vibrator.vibrate(pattern, -1);
        } else {
            Log.w(TAG, "Device can not vibrate");
        }
    }

    /**
     *
     * @param context
     * @param alert
     */
    public static void playSound(Context context, Uri alert) {
        MediaPlayer mMediaPlayer = new MediaPlayer();
        try {
            mMediaPlayer.setDataSource(context, alert);
            final AudioManager audioManager = (AudioManager) context
                    .getSystemService(Context.AUDIO_SERVICE);
            if (audioManager.getStreamVolume(AudioManager.STREAM_ALARM) != 0) {
                mMediaPlayer.setAudioStreamType(AudioManager.STREAM_ALARM);
                mMediaPlayer.prepare();
                mMediaPlayer.start();
            }
        } catch (IOException e) {
            Log.e(TAG, e.getMessage());
        }
    }
}
