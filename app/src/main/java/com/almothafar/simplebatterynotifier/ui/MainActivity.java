package com.almothafar.simplebatterynotifier.ui;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;
import com.almothafar.simplebatterynotifier.ui.fragment.BatteryDetailsFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;
import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.model.BatteryDO;
import com.almothafar.simplebatterynotifier.service.PowerConnectionService;
import com.almothafar.simplebatterynotifier.service.SystemService;
import com.almothafar.simplebatterynotifier.ui.widget.CircularProgressBar;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Main activity displaying battery status with circular progress bar
 */
public class MainActivity extends BaseActivity {

	private static final String TAG = "MainActivity";
	private static final long UPDATER_DELAY = 300;
	private static final long UPDATER_PERIOD = 3000;

	// Use Handler(Looper) constructor - Handler() deprecated to prevent null Looper
	private final Handler handler = new Handler(Looper.getMainLooper());

	private int batteryPercentage;
	private String subTitle;
	private BatteryDO batteryDO;
	private ActivityResultLauncher<Intent> settingsLauncher;
	private ActivityResultLauncher<String> notificationPermissionLauncher;

	// UI elements
	private MaterialButton batteryInsightsButton;

	// Self-reposting refresh loop, bound to the foreground lifecycle (started in onPostResume,
	// stopped in onPause) so it never stacks across resumes or keeps polling in the background.
	private final Runnable updateTask = new Runnable() {
		@Override
		public void run() {
			refreshBatteryUi();
			handler.postDelayed(this, UPDATER_PERIOD);
		}
	};

	/**
	 * Create the options menu
	 *
	 * @param menu The options menu
	 * @return True if menu was created successfully
	 */
	@Override
	public boolean onCreateOptionsMenu(final Menu menu) {
		getMenuInflater().inflate(R.menu.menu_main, menu);
		return true;
	}

	/**
	 * Handle options menu item selection
	 *
	 * @param item The selected menu item
	 * @return True if item was handled
	 */
	@Override
	public boolean onOptionsItemSelected(final MenuItem item) {
		final int id = item.getItemId();
		if (id == R.id.action_settings) {
			openSettings();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	/**
	 * Initialize the activity
	 *
	 * @param savedInstanceState Saved state bundle
	 */
	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.activity_main);

		// Set up the Toolbar
		final Toolbar toolbar = findViewById(R.id.toolbar);
		setupToolbar(toolbar);

		// setStatusBarColor()/setNavigationBarColor() removed (deprecated in API 35).
		// Edge-to-edge is enforced by the platform on Android 15+; system-bar insets are handled
		// in BaseActivity. System bar colors should be set via themes (values/themes.xml).

		// Register activity result launcher for settings
		// Replaces deprecated startActivityForResult() - modern approach doesn't require result handling
		settingsLauncher = registerForActivityResult(
				new ActivityResultContracts.StartActivityForResult(),
				result -> {
					// When returning from settings, refresh the UI with updated preferences
					initializeFirstValues();
				}
		);

		// Register notification permission launcher for Android 13+ (API 33+)
		notificationPermissionLauncher = registerForActivityResult(
				new ActivityResultContracts.RequestPermission(),
				isGranted -> {
					if (isGranted) {
						Log.d(TAG, "Notification permission granted");
						Toast.makeText(this, R.string.notifications_enabled_toast, Toast.LENGTH_SHORT).show();
					} else {
						Log.w(TAG, "Notification permission denied");
						showPermissionDeniedSnackbar();
					}
				}
		);

		// Request notification permission if needed (Android 13+)
		requestNotificationPermissionIfNeeded();

		// Initialize UI elements
		batteryInsightsButton = findViewById(R.id.batteryInsightsButton);

		// Set up button click listeners
		batteryInsightsButton.setOnClickListener(v -> openBatteryInsights());

		// Start the power connection service as a foreground service so monitoring
		// survives the app being closed (required on Android 8+).
		ContextCompat.startForegroundService(this, new Intent(this, PowerConnectionService.class));
	}

	/**
	 * Called when activity is resumed
	 */
	@Override
	protected void onPostResume() {
		super.onPostResume();

		initializeFirstValues();

		startUpdateTimer();
	}

	/**
	 * Stop the refresh loop while the activity is not in the foreground so it doesn't keep
	 * polling the battery (and draining it) in the background.
	 */
	@Override
	protected void onPause() {
		super.onPause();
		stopUpdateTimer();
	}

	/**
	 * Clean up resources when activity is destroyed
	 */
	@Override
	protected void onDestroy() {
		super.onDestroy();
		stopUpdateTimer();
	}

	/**
	 * Fill battery information from system
	 * Updates batteryDO, batteryPercentage, and subTitle fields
	 */
	protected void fillBatteryInfo() {
		batteryDO = SystemService.getBatteryInfo(this);

		// CRITICAL: Check for null batteryDO
		if (isNull(batteryDO)) {
			Log.w(TAG, "Unable to retrieve battery information");
			batteryPercentage = 0;
			subTitle = getResources().getString(R.string.unknown);
			return;
		}

		final int status = batteryDO.getStatus();
		batteryPercentage = (int) batteryDO.getBatteryPercentage();

		// Map battery status to appropriate string resource
		if (status == BatteryManager.BATTERY_STATUS_FULL) {
			subTitle = getResources().getString(R.string.charged);
		} else if (status == BatteryManager.BATTERY_STATUS_CHARGING) {
			subTitle = getResources().getString(R.string.charging);
		} else if (status == BatteryManager.BATTERY_STATUS_NOT_CHARGING) {
			subTitle = getResources().getString(R.string.not_charging);
		} else if (status == BatteryManager.BATTERY_STATUS_DISCHARGING) {
			subTitle = getResources().getString(R.string.discharging);
		} else {
			// BATTERY_STATUS_UNKNOWN or any other status
			subTitle = getResources().getString(R.string.unknown);
		}
	}

	/**
	 * Start the periodic battery refresh loop.
	 * <p>
	 * Removes any pending run first so repeated resume cycles can't stack multiple loops.
	 */
	private void startUpdateTimer() {
		handler.removeCallbacks(updateTask);
		handler.postDelayed(updateTask, UPDATER_DELAY);
	}

	/**
	 * Stop the periodic battery refresh loop and drop any pending run.
	 */
	private void stopUpdateTimer() {
		handler.removeCallbacks(updateTask);
	}

	/**
	 * Refresh the battery UI (circular gauge + details fragment) with the latest reading.
	 * Runs on the main thread via {@link #handler}.
	 */
	private void refreshBatteryUi() {
		final CircularProgressBar progressBar = findViewById(R.id.batteryPercentage);
		fillBatteryInfo();
		progressBar.setProgress(batteryPercentage);
		progressBar.setTitle(batteryPercentage + "%");
		progressBar.setSubTitle(subTitle);

		// Update charging animation based on battery status
		if (nonNull(batteryDO)) {
			final boolean isCharging = batteryDO.getStatus() == BatteryManager.BATTERY_STATUS_CHARGING;
			progressBar.setCharging(isCharging);
		}

		final FragmentManager fragmentManager = getSupportFragmentManager();
		final BatteryDetailsFragment batteryDetailsFragment =
				(BatteryDetailsFragment) fragmentManager.findFragmentById(R.id.detailsFragmentLayout);

		if (nonNull(batteryDetailsFragment) && nonNull(batteryDO)) {
			batteryDetailsFragment.updateBatteryDetails(batteryDO);
		}
	}

	/**
	 * Initialize first values and animate the progress bar
	 */
	private void initializeFirstValues() {
		fillBatteryInfo();
		final SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);

		final CircularProgressBar progressBar = findViewById(R.id.batteryPercentage);
		progressBar.setWarningLevel(sharedPref.getInt(getString(R.string._pref_key_warn_battery_level), 40));
		progressBar.setCriticalLevel(sharedPref.getInt(getString(R.string._pref_key_critical_battery_level), 20));
		progressBar.animateProgressTo(0, batteryPercentage, new CircularProgressBar.ProgressAnimationListener() {

			@Override
			public void onAnimationStart() {
				// Animation started
			}

			@Override
			public void onAnimationFinish() {
				// Animation finished
			}

			@Override
			public void onAnimationProgress(final int progress) {
				progressBar.setTitle(progress + "%");
				progressBar.setSubTitle(subTitle);
			}
		});
	}

	/**
	 * Request notification permission if needed (Android 13+)
	 */
	private void requestNotificationPermissionIfNeeded() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
					!= PackageManager.PERMISSION_GRANTED) {
				Log.d(TAG, "Requesting notification permission");
				notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
			}
		}
	}

	/**
	 * Shows a Snackbar when permission is denied, with action to open notification settings
	 */
	private void showPermissionDeniedSnackbar() {
		Snackbar.make(
				findViewById(R.id.containerLayout),
				R.string.notifications_permission_rationale,
				Snackbar.LENGTH_LONG
		).setAction(R.string.open_settings, v -> openNotificationSettings()).show();
	}

	/**
	 * Opens the app's notification settings page directly
	 */
	private void openNotificationSettings() {
		final Intent intent = new Intent();

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			// Android 8.0+: Open app notification settings directly
			intent.setAction(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
			intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
		} else {
			// Older Android: Open app details settings
			intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
			intent.setData(Uri.fromParts("package", getPackageName(), null));
		}

		startActivity(intent);
	}

	/**
	 * Open the settings activity
	 */
	private void openSettings() {
		final Intent intent = new Intent(this, SettingsActivity.class);
		// Use modern ActivityResultLauncher instead of deprecated startActivityForResult()
		settingsLauncher.launch(intent);
	}

	/**
	 * Open the battery insights activity
	 */
	private void openBatteryInsights() {
		final Intent intent = new Intent(this, BatteryInsightsActivity.class);
		startActivity(intent);
	}
}
