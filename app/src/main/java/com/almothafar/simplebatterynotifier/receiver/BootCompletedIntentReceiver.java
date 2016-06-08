package com.almothafar.simplebatterynotifier.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.almothafar.simplebatterynotifier.service.PowerConnectionService;

/**
 * Created by Al-Mothafar on 21/08/2015.
 */
public class BootCompletedIntentReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        context.startService(new Intent(context, PowerConnectionService.class));
        context.startService(new Intent(context, BatteryLevelReceiver.class));
    }
}
