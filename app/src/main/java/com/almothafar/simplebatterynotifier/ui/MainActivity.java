package com.almothafar.simplebatterynotifier.ui;

import android.Manifest;
import android.content.ActivityNotFoundException;
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;
import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.model.BatteryDO;
import com.almothafar.simplebatterynotifier.service.BatteryHealthTracker;
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
		if (id == R.id.action_feedback) {
			showFeedbackChooser();
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

		// Keep the bottom section (Insights button + developer signature) clear of the system
		// navigation bar. Android 15+ (targetSdk 35+) enforces edge-to-edge and no longer insets
		// content above the nav bar, so without this the signature draws underneath it (#102).
		applyBottomSystemBarInset(findViewById(R.id.homeBottomSection));

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

		// Best-effort: auto-fill the battery design capacity from the device on first run, so the
		// measured health/capacity works without the user having to look up and type it in (#104).
		// No-op on devices where the kernel node isn't readable — manual entry still applies there.
		BatteryHealthTracker.autoDetectDesignCapacityIfUnset(this);

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

		batteryPercentage = (int) batteryDO.getBatteryPercentage();
		subTitle = SystemService.getStatusLabel(this, batteryDO.getStatus());
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

	/**
	 * Show the feedback chooser: report on GitHub, or email the developer.
	 */
	private void showFeedbackChooser() {
		final CharSequence[] options = {
				getString(R.string.feedback_option_github),
				getString(R.string.feedback_option_email)
		};
		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.feedback_dialog_title)
				.setItems(options, (dialog, which) -> {
					if (which == 0) {
						openGitHubIssues();
					} else {
						emailDeveloper();
					}
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	/**
	 * Open the GitHub issue-template chooser in a browser.
	 */
	private void openGitHubIssues() {
		final Uri uri = Uri.parse(getString(R.string.feedback_github_url));
		try {
			startActivity(new Intent(Intent.ACTION_VIEW, uri));
		} catch (ActivityNotFoundException e) {
			Toast.makeText(this, R.string.no_browser_found, Toast.LENGTH_SHORT).show();
		}
	}

	/**
	 * Open the mail app to the developer with a prefilled subject and a device-info block.
	 */
	private void emailDeveloper() {
		final Intent intent = new Intent(Intent.ACTION_SENDTO,
				Uri.fromParts("mailto", getString(R.string.feedback_support_email), null));
		intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.feedback_email_subject, appVersionName()));
		intent.putExtra(Intent.EXTRA_TEXT, deviceInfoBlock());
		try {
			startActivity(intent);
		} catch (ActivityNotFoundException e) {
			Toast.makeText(this, R.string.no_email_app, Toast.LENGTH_SHORT).show();
		}
	}

	/**
	 * The app's version name (e.g. "2.0.71"), or an empty string if it can't be read.
	 */
	private String appVersionName() {
		try {
			return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
		} catch (PackageManager.NameNotFoundException e) {
			// The app can always resolve its own package, so this is effectively unreachable;
			// log it rather than swallow silently, and degrade to an empty version string.
			Log.w(TAG, "Unable to read app version name", e);
			return "";
		}
	}

	/**
	 * A short diagnostic block appended to a feedback email so reports carry the essentials.
	 */
	private String deviceInfoBlock() {
		return "\n\n---\n"
				+ "App: " + appVersionName() + "\n"
				+ "Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
				+ "Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
	}
}
