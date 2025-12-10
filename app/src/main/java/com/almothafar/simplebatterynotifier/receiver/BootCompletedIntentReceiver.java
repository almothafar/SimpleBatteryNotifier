package com.almothafar.simplebatterynotifier.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.almothafar.simplebatterynotifier.service.PowerConnectionService;

import static java.util.Objects.nonNull;

/**
 * Receiver that starts the PowerConnectionService when the device boots
 * <p>
 * This receiver is triggered by the ACTION_BOOT_COMPLETED broadcast and ensures
 * that battery monitoring resumes after device restart. The PowerConnectionService
 * will handle registering the necessary battery level and power connection receivers.
 */
public class BootCompletedIntentReceiver extends BroadcastReceiver {

	/**
	 * Called when the BOOT_COMPLETED broadcast is received
	 * <p>
	 * Starts the PowerConnectionService which will register all necessary
	 * battery monitoring receivers (BatteryLevelReceiver, PowerConnectionReceiver).
	 *
	 * @param context The context in which the receiver is running
	 * @param intent  The intent being received (should be ACTION_BOOT_COMPLETED)
	 */
	@Override
	public void onReceive(final Context context, final Intent intent) {
		// Verify the intent action to prevent spoofed intents
		if (nonNull(intent) && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
			// Start the PowerConnectionService which will register battery monitoring receivers
			context.startService(new Intent(context, PowerConnectionService.class));
		}
	}
}
