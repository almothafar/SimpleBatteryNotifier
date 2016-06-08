package com.almothafar.simplebatterynotifier.service;

import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.IBinder;

import com.almothafar.simplebatterynotifier.receiver.BatteryLevelReceiver;
import com.almothafar.simplebatterynotifier.receiver.PowerConnectionReceiver;

public class PowerConnectionService extends Service {
    private static final String TAG = "com.almothafar.service";

    private static PowerConnectionReceiver powerConnectionReceiver;
    private static BatteryLevelReceiver batteryLevelReceiver;

    public PowerConnectionService() {
    }

    @Override
    public void onCreate() {
        registerPowerConnectionReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void registerPowerConnectionReceiver() {
        final Intent mIntent = getApplicationContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int plugged = mIntent != null ? mIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) : -1;
        powerConnectionReceiver = new PowerConnectionReceiver();
        // First call must pass the current value, so avoid unnecessary triggers, its handled inside onRecieve() method.
        PowerConnectionReceiver.setCurrentStat(plugged);
        batteryLevelReceiver = new BatteryLevelReceiver();
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(powerConnectionReceiver, filter);
        registerReceiver(batteryLevelReceiver, filter);
    }

}
