package com.almothafar.simplebatterynotifier.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.preference.PreferenceManager;
import android.provider.Settings;

import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.model.BatteryDO;
import com.almothafar.simplebatterynotifier.receiver.BatteryLevelReceiver;
import com.almothafar.simplebatterynotifier.ui.MainActivity;
import com.almothafar.simplebatterynotifier.util.GeneralHelper;

import java.util.Calendar;
import java.util.Date;

/**
 * Created by Al-Mothafar on 24/08/2015.
 */
public class NotificationService {
    private static final String TAG = "com.almothafar";
    private static final long[] VIBRATION_PATTERN = {0, 500, 250, 500, 250};
    private static final int UNIQUE_ID = 1641987;
    private static final int UNIQUE_ID_STICKY = 1648719;
    private static boolean isHealthyCharge;

    public static final int RED_ALERT_LEVEL = 4; // 4% is so low.
    public static final int CRITICAL_TYPE = 1;
    public static final int WARNING_TYPE = 2;
    public static final int FULL_LEVEL_TYPE = 3;
    public static final int FULL_PERCENTAGE = 95;

    public static final String ZEN_MODE = "zen_mode";
    public static final int ZEN_MODE_IMPORTANT_INTERRUPTIONS = 1;

    public static void sendNotification(Context context, int type) {
        Notification.Builder notification;
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
        boolean vibrationEnabled = sharedPref.getBoolean(context.getString(R.string._pref_key_notifications_vibrate), true);
        boolean stickyNotification = sharedPref.getBoolean(context.getString(R.string._pref_key_notifications_sticky), false);
        Integer warningLevel = sharedPref.getInt(context.getString(R.string._pref_key_warn_battery_level), 40);
        Integer criticalLevel = sharedPref.getInt(context.getString(R.string._pref_key_critical_battery_level), 20);
        boolean limitedTime = sharedPref.getBoolean(context.getString(R.string._pref_key_notifications_time_range), false);

        String startTime = sharedPref.getString(context.getString(R.string._pref_key_notifications_time_range_start), context.getString(R.string._pref_value_notifications_time_range_start));
        String endTime = sharedPref.getString(context.getString(R.string._pref_key_notifications_time_range_end), context.getString(R.string._pref_value_notifications_time_range_end));

        boolean withInTime = (isWithinTime(startTime, endTime) && limitedTime) || !limitedTime;
        boolean ignoreSilent = !sharedPref.getBoolean(context.getString(R.string._pref_key_notifications_apply_silent_mode), false);

        String ticker = "";
        String title = "";
        String content = "";
        String bigContent = "";

        Thread playSoundThread = null;

        String alarmSound = "content://settings/system/notification_sound"; // initial with default value
        notification = new Notification.Builder(context);

        switch (type) {
            case CRITICAL_TYPE:
                alarmSound = sharedPref.getString(context.getString(R.string._pref_key_notifications_alert_sound_ringtone), alarmSound);
                notification
                        .setLights(Color.RED, 1000, 1000)
                        .setSmallIcon(R.drawable.ic_stat_device_battery_charging_20);
                ticker = context.getString(R.string.notification_critical_ticker, criticalLevel);
                title = context.getString(R.string.notification_critical_title);
                content = context.getString(R.string.notification_critical_content, criticalLevel);
                bigContent = context.getString(R.string.notification_critical_content_big, criticalLevel);
                break;
            case WARNING_TYPE:
                alarmSound = sharedPref.getString(context.getString(R.string._pref_key_notifications_warning_sound_ringtone), alarmSound);
                notification
                        .setLights(Color.rgb(0xff, 0x66, 0x00), 1000, 1000)
                        .setSmallIcon(R.drawable.ic_stat_device_battery_charging_50)
                        .setOnlyAlertOnce(true);
                ticker = context.getString(R.string.notification_warning_ticker, warningLevel);
                title = context.getString(R.string.notification_warning_title);
                content = context.getString(R.string.notification_warning_content, warningLevel);
                bigContent = context.getString(R.string.notification_warning_content_big, warningLevel);
                break;
            case FULL_LEVEL_TYPE:
                alarmSound = sharedPref.getString(context.getString(R.string._pref_key_notifications_full_sound_ringtone), alarmSound);
                notification
                        .setLights(Color.GREEN, 1000, 1000)
                        .setSmallIcon(R.drawable.ic_stat_device_battery_charging_full)
                        .setOnlyAlertOnce(true);
                ticker = context.getString(R.string.notification_full_level_ticker);
                if (isHealthyCharge) {
                    title = context.getString(R.string.notification_full_level_title_healthy);
                } else {
                    title = context.getString(R.string.notification_full_level_title_regular);
                }
                content = context.getString(R.string.notification_full_level_content);
                bigContent = context.getString(R.string.notification_full_level_content_big);
                break;
        }

        // Add sound only if it is between user alert time in case it is limited.
        if (withInTime) {
            Uri uri = Uri.parse(alarmSound);

            AudioManager mobileMode = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            boolean isNotNormalRingerMode = mobileMode.getRingerMode() != AudioManager.RINGER_MODE_NORMAL;
            // The following is a workaround for Lollipop priority mode.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                try {
                    // If 0 means in sound, if 1 means in priority , if 2 means silent.
                    isNotNormalRingerMode = isNotNormalRingerMode
                            || ZEN_MODE_IMPORTANT_INTERRUPTIONS == Settings.Global.getInt(context.getContentResolver(), ZEN_MODE);
                } catch (Settings.SettingNotFoundException e) {
                    isNotNormalRingerMode = true;
                }
            }
            // if silent must ignored then play it as media
            if (ignoreSilent && isNotNormalRingerMode) {
                class RunnableWithContextUri implements Runnable {
                    private Context context;
                    private Uri uri;

                    public RunnableWithContextUri(Context context, Uri uri) {
                        this.context = context;
                        this.uri = uri;
                    }

                    @Override
                    public void run() {
                        SystemService.playSound(context, uri);
                        SystemService.vibratePhone(context);
                    }
                }
                playSoundThread = new Thread(new RunnableWithContextUri(context, uri));
            } else {
                // else just follow normal behaviour
                notification.setSound(uri);
                if (vibrationEnabled) {
                    notification.setVibrate(VIBRATION_PATTERN);
                }
            }
        }
        Bitmap bm = BitmapFactory.decodeResource(context.getResources(), R.mipmap.ic_launcher);
        // Last touches on notification before build it.
        notification
                .setTicker(ticker)
                .setContentTitle(title)
                .setContentText(content)
                .setWhen(System.currentTimeMillis())
                .setLargeIcon(bm);


        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        notification.setContentIntent(pendingIntent);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            notification.setVisibility(Notification.VISIBILITY_PUBLIC);
        }

        Notification n;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            notification
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setStyle(new Notification.BigTextStyle().bigText(bigContent));
            n = notification.build();
        } else {
            //noinspection deprecation
            n = notification.getNotification();
        }

        if (stickyNotification) {
            n.flags |= Notification.FLAG_NO_CLEAR | Notification.FLAG_ONGOING_EVENT;
        }

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(UNIQUE_ID, n);
        if (playSoundThread != null) {
            playSoundThread.start();
        }
    }

    public static void sendChargeNotification(Context context, String chargeSource) {
        Notification.Builder notification = new Notification.Builder(context);

        String ticker, title, content;

        notification.setOnlyAlertOnce(true);

        if (isHealthyCharge) {
            title = context.getString(R.string.notification_charge_started_title_healthy);
            notification.setSmallIcon(R.drawable.ic_stat_device_battery_charging_20);
        } else {
            title = context.getString(R.string.notification_charge_started_title_regular);
            notification.setSmallIcon(R.drawable.ic_stat_device_battery_charging_50);
        }
        ticker = title.concat(", ").concat(context.getString(R.string.notification_charge_started_content, chargeSource));
        content = context.getString(R.string.notification_charge_started_content, chargeSource);

        Bitmap bm = BitmapFactory.decodeResource(context.getResources(), R.mipmap.ic_launcher);
        // Last touches on notification before build it.
        notification
                .setTicker(ticker)
                .setContentTitle(title)
                .setContentText(content)
                .setWhen(System.currentTimeMillis())
                .setLargeIcon(bm);

        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        notification.setContentIntent(pendingIntent);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            notification.setVisibility(Notification.VISIBILITY_PUBLIC);
        }

        Notification n;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            notification
                    .setStyle(new Notification.BigTextStyle().bigText(content));
            n = notification.build();
        } else {
            //noinspection deprecation
            n = notification.getNotification();
        }

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(UNIQUE_ID, n);

    }

    public static void updateSmallIcon(Context context) {
        // TODO for add icon and battery status on notifications
    }

    public static void clearNotifications(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.cancel(UNIQUE_ID);
    }

    public static void setIsHealthy(boolean isHealthyCharge) {
        NotificationService.isHealthyCharge = isHealthyCharge;
    }

    private static boolean isWithinTime(String startTime, String endTime) {
        int sHour, sMinute, eHour, eMinute;
        sHour = GeneralHelper.getHour(startTime);
        sMinute = GeneralHelper.getMinute(startTime);
        eHour = GeneralHelper.getHour(endTime);
        eMinute = GeneralHelper.getMinute(endTime);
        Date currentTime = new Date();

        Calendar startCal = Calendar.getInstance();
        startCal.setTime(currentTime);
        startCal.set(Calendar.HOUR_OF_DAY, sHour);
        startCal.set(Calendar.MINUTE, sMinute);


        Calendar endCal = Calendar.getInstance();
        endCal.setTime(currentTime);
        endCal.set(Calendar.HOUR_OF_DAY, eHour);
        endCal.set(Calendar.MINUTE, eMinute);
        // This because maybe user think for end time is in next day, ex: from 8:00 AM to 00:00 AM
        // so its from 8:00 am morning to midnight.
        endCal.add(Calendar.DATE, (eHour <= sHour) ? 1 : 0);

        return currentTime.after(startCal.getTime()) && currentTime.before(endCal.getTime());
    }


}
