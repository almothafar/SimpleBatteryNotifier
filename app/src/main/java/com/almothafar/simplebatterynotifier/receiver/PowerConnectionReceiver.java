package com.almothafar.simplebatterynotifier.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.widget.Toast;

import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.service.NotificationService;

public class PowerConnectionReceiver extends BroadcastReceiver {

    /**
     * Save stat to avoid "spam" triggers.
     */
    private static int currentStat = -1;

    @Override
    public void onReceive(Context context, Intent intent) {
        final Intent mIntent = context.getApplicationContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int plugged = mIntent != null ? mIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) : -1;
        if (currentStat == plugged) {
            return;
        }
        setCurrentStat(plugged);

        int level = mIntent != null ? mIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) : -1;
        int scale = mIntent != null ? mIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1) : -1;
        int percentage = (int) ((level / (float) scale) * 100);

        boolean usbCharge = (plugged == BatteryManager.BATTERY_PLUGGED_USB);
        boolean acCharge = (plugged == BatteryManager.BATTERY_PLUGGED_AC);
        boolean wirelessCharge = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            wirelessCharge = plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS;
        }
        String charger, chargerSource;
        if (plugged > 0) {
            if (usbCharge) {
                charger = context.getResources().getString(R.string.charger_connected_usb);
                chargerSource = context.getResources().getString(R.string.charger_usb);
            } else if (acCharge) {
                charger = context.getResources().getString(R.string.charger_connected_ac);
                chargerSource = context.getResources().getString(R.string.charger_ac);
            } else if (wirelessCharge) {
                charger = context.getResources().getString(R.string.charger_connected_wireless);
                chargerSource = context.getResources().getString(R.string.charger_wireless);
            } else {
                // default if missed one of another possible options above, this case is almost impossible.
                charger = context.getResources().getString(R.string.charger_connected);
                chargerSource = context.getResources().getString(R.string.charger);
            }
            NotificationService.setIsHealthy(percentage <= 20);
            NotificationService.sendChargeNotification(context, chargerSource);
        } else {
            charger = context.getResources().getString(R.string.charger_disconnected);
            BatteryLevelReceiver.resetVariables();
            NotificationService.clearNotifications(context);
        }
        Toast.makeText(context, charger, Toast.LENGTH_LONG).show();
    }

    public static synchronized void setCurrentStat(int currentStat) {
        PowerConnectionReceiver.currentStat = currentStat;
    }
}
