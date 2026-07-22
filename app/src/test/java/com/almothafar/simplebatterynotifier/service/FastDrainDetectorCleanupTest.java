package com.almothafar.simplebatterynotifier.service;

import android.content.Context;
import android.os.BatteryManager;

import androidx.test.core.app.ApplicationProvider;

import com.almothafar.simplebatterynotifier.model.BatteryDO;
import com.almothafar.simplebatterynotifier.service.BatteryRateTracker.BatteryRate;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

/**
 * Robolectric tests for {@link FastDrainDetector#evaluate}'s notification cleanup: a shown
 * "battery draining fast" warning must not outlive the episode it describes. The pure trigger logic is
 * covered by {@link FastDrainDetectorTest}; here we assert the {@link NotificationService} wiring —
 * dismissing the stale alert when the drain calms while still on battery, or when the session ends
 * (charging / feature disabled) — with the service mocked so we see what {@code evaluate} decides.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FastDrainDetectorCleanupTest {

	// Rate magnitudes in %/h; the default high-drain limit is AppPrefs.DEFAULT_DRAIN_LIMIT_PPH (20).
	private static final int BELOW_LIMIT_PPH = 3;
	private static final int ABOVE_LIMIT_PPH = 30;

	private Context context;

	@Before
	public void setUp() {
		context = ApplicationProvider.getApplicationContext();
	}

	@Test
	public void drainCalmedWhileDischarging_dismissesStaleWarning() {
		seedAlertedStreak();

		try (MockedStatic<NotificationService> ns = mockStatic(NotificationService.class)) {
			FastDrainDetector.evaluate(context, discharging(), rate(BELOW_LIMIT_PPH));

			// The drain dropped back below the limit, so the warning is stale — dismissed, not re-posted.
			ns.verify(() -> NotificationService.clearFastDrainAlert(any(Context.class)));
			ns.verify(() -> NotificationService.sendFastDrainNotification(any(Context.class), anyInt(), anyInt(), anyInt()), never());
		}
	}

	@Test
	public void chargingWhileWarningShown_dismissesStaleWarning() {
		seedAlertedStreak();

		try (MockedStatic<NotificationService> ns = mockStatic(NotificationService.class)) {
			FastDrainDetector.evaluate(context, charging(), rate(ABOVE_LIMIT_PPH));

			// Charging ends the discharge episode, so a warning left from it is dismissed too.
			ns.verify(() -> NotificationService.clearFastDrainAlert(any(Context.class)));
		}
	}

	@Test
	public void noWarningShown_leavesNotificationsAlone() {
		// No prior alert (default cleared streak): a calm below-limit tick has nothing to dismiss.
		try (MockedStatic<NotificationService> ns = mockStatic(NotificationService.class)) {
			FastDrainDetector.evaluate(context, discharging(), rate(BELOW_LIMIT_PPH));

			ns.verify(() -> NotificationService.clearFastDrainAlert(any(Context.class)), never());
			ns.verify(() -> NotificationService.sendFastDrainNotification(any(Context.class), anyInt(), anyInt(), anyInt()), never());
		}
	}

	// --- helpers -------------------------------------------------------------

	/**
	 * Seed the transient streak file as if a fast-drain warning has already fired (alerted, sustained
	 * well past the window). Keys mirror {@link FastDrainDetector}'s persisted streak keys — a stable
	 * contract kept unchanged across the shared-core extraction (#163).
	 */
	private void seedAlertedStreak() {
		final long now = System.currentTimeMillis();
		TransientState.prefs(context).edit()
				.putLong("_fast_drain_streak_start", now - 10 * 60_000L)
				.putBoolean("_fast_drain_alerted", true)
				.putLong("_fast_drain_last_seen_above", now)
				.putLong("_fast_drain_last_reminder", now)
				.apply();
	}

	private static BatteryDO discharging() {
		return new BatteryDO().setStatus(BatteryManager.BATTERY_STATUS_DISCHARGING).setLevel(50).setScale(100);
	}

	private static BatteryDO charging() {
		return new BatteryDO().setStatus(BatteryManager.BATTERY_STATUS_CHARGING).setLevel(50).setScale(100);
	}

	private static BatteryRate rate(final int percentPerHour) {
		return new BatteryRate(true, percentPerHour, false, false, 0, false, 0);
	}
}
