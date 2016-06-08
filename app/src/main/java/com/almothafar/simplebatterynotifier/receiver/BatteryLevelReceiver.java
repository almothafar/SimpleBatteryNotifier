package com.almothafar.simplebatterynotifier.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.preference.PreferenceManager;

import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.service.NotificationService;
import com.almothafar.simplebatterynotifier.model.BatteryDO;
import com.almothafar.simplebatterynotifier.service.SystemService;

public class BatteryLevelReceiver extends BroadcastReceiver {
    private static final String TAG = "com.almothafar";

    private static int prevLevel = 0;
    private static int prevType = 0;
    private static boolean fullNotificationCalled = false;


    @Override
    public void onReceive(Context context, Intent intent) {
        final Intent mIntent = context.getApplicationContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int status = mIntent != null ? mIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) : -1;

        boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING;
        boolean isFull = status == BatteryManager.BATTERY_STATUS_FULL;

        BatteryDO batteryDO = SystemService.getBatteryInfo(context);
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
        int warningLevel = sharedPref.getInt(context.getString(R.string._pref_key_warn_battery_level), 40);
        int criticalLevel = sharedPref.getInt(context.getString(R.string._pref_key_critical_battery_level), 20);
        boolean warningEnabled = sharedPref.getBoolean(context.getString(R.string._pref_key_notify_for_warning_level), true);
        boolean fullNotifyEnabled = sharedPref.getBoolean(context.getString(R.string._pref_key_notify_for_full_level), true);
        boolean alertEveryTick = sharedPref.getBoolean(context.getString(R.string._pref_key_notify_every_tick), false);

        int percentage = (int) batteryDO.getBatteryPercentage();

        boolean isChanged = prevLevel != percentage;

        synchronized (this) {
            // If its charging no need to make any notification except if it is full.
            if (isChanged && !isCharging) {
                // When its less than alert level (4%) then its must send notification.
                if (percentage <= NotificationService.RED_ALERT_LEVEL) {
                    prevType = 0;
                }

                // Lets handle critical first, then warning.
                if (percentage <= criticalLevel) {
                    if (prevType != NotificationService.CRITICAL_TYPE || alertEveryTick) {
                        NotificationService.sendNotification(context, NotificationService.CRITICAL_TYPE);
                        prevType = NotificationService.CRITICAL_TYPE;
                    }
                } else if (percentage <= warningLevel && warningEnabled) {
                    if (prevType != NotificationService.WARNING_TYPE) {
                        NotificationService.sendNotification(context, NotificationService.WARNING_TYPE);
                        prevType = NotificationService.WARNING_TYPE;
                    }
                }
            } else {
                if (!fullNotificationCalled) {
                    if (isFull && fullNotifyEnabled) {
                        NotificationService.sendNotification(context, NotificationService.FULL_LEVEL_TYPE);
                        fullNotificationCalled = true;
                    }
                }

                if (percentage <= NotificationService.FULL_PERCENTAGE && percentage > warningLevel) {
                    fullNotificationCalled = false;
                }
            }
            // Store the current level, so no need to duplicate the notification as a spam !
            prevLevel = percentage;
        }
    }

    public static void resetVariables() {
        fullNotificationCalled = false;
        prevType = 0;
    }

    public static void setPreviousType(int type) {
        prevType = type;
    }
}
