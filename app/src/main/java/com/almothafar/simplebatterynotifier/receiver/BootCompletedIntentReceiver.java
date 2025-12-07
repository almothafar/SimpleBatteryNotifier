package com.almothafar.simplebatterynotifier.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.almothafar.simplebatterynotifier.service.PowerConnectionService;

import static java.util.Objects.nonNull;

/**
 * Created by Al-Mothafar on 21/08/2015.
 */
public class BootCompletedIntentReceiver extends BroadcastReceiver {
	@Override
	public void onReceive(Context context, Intent intent) {
		// Verify the intent action to prevent spoofed intents
		if (nonNull(intent) && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
			context.startService(new Intent(context, PowerConnectionService.class));
			context.startService(new Intent(context, BatteryLevelReceiver.class));
		}
	}
}
