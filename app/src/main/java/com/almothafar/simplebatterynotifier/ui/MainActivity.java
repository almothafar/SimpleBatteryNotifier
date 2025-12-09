package com.almothafar.simplebatterynotifier.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.WindowCompat;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;
import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.model.BatteryDO;
import com.almothafar.simplebatterynotifier.service.PowerConnectionService;
import com.almothafar.simplebatterynotifier.service.SystemService;
import com.almothafar.simplebatterynotifier.ui.widgets.CircularProgressBar;

import java.util.Timer;
import java.util.TimerTask;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Main activity displaying battery status with circular progress bar
 */
public class MainActivity extends AppCompatActivity {

	private static final String TAG = "MainActivity";
	private static final long UPDATER_DELAY = 300;
	private static final long UPDATER_PERIOD = 3000;

	// Use Handler(Looper) constructor - Handler() deprecated to prevent null Looper
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Timer timer = new Timer();

	private int batteryPercentage;
	private String subTitle;
	private BatteryDO batteryDO;
	private TimerTask updateTask;
	private ActivityResultLauncher<Intent> settingsLauncher;

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

		// Enable edge-to-edge display
		WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

		setContentView(R.layout.activity_main);

		// Set up the Toolbar
		final Toolbar toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);

		// setStatusBarColor() and setNavigationBarColor() removed - deprecated in API 35
		// Edge-to-edge is already enabled via WindowCompat.setDecorFitsSystemWindows()
		// System bar colors should be set in themes (values/themes.xml) instead

		// Register activity result launcher for settings
		// Replaces deprecated startActivityForResult() - modern approach doesn't require result handling
		settingsLauncher = registerForActivityResult(
				new ActivityResultContracts.StartActivityForResult(),
				result -> {
					// When returning from settings, refresh the UI with updated preferences
					initializeFirstValues();
				}
		);

		// Start the power connection service
		startService(new Intent(this, PowerConnectionService.class));
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
	 * Clean up resources when activity is destroyed
	 */
	@Override
	protected void onDestroy() {
		super.onDestroy();
		timer.cancel();
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
			subTitle = getResources().getString(R.string.discharging);
			return;
		}

		final boolean isCharging = batteryDO.getStatus() == BatteryManager.BATTERY_STATUS_CHARGING;
		final boolean isFull = batteryDO.getStatus() == BatteryManager.BATTERY_STATUS_FULL;

		batteryPercentage = (int) batteryDO.getBatteryPercentage();

		if (isFull) {
			subTitle = getResources().getString(R.string.charged);
		} else if (isCharging) {
			subTitle = getResources().getString(R.string.charging);
		} else {
			subTitle = getResources().getString(R.string.discharging);
		}
	}

	/**
	 * Start the timer to periodically update battery information
	 */
	private void startUpdateTimer() {
		updateTask = new TimerTask() {
			@Override
			public void run() {
				handler.post(() -> {
					final CircularProgressBar progressBar = findViewById(R.id.batteryPercentage);
					fillBatteryInfo();
					progressBar.setProgress(batteryPercentage);
					progressBar.setTitle(batteryPercentage + "%");
					progressBar.setSubTitle(subTitle);

					final FragmentManager fragmentManager = getSupportFragmentManager();
					final BatteryDetailsFragment batteryDetailsFragment =
							(BatteryDetailsFragment) fragmentManager.findFragmentById(R.id.detailsFragmentLayout);

					if (nonNull(batteryDetailsFragment) && nonNull(batteryDO)) {
						batteryDetailsFragment.updateBatteryDetails(batteryDO);
					}
				});
			}
		};
		timer.schedule(updateTask, UPDATER_DELAY, UPDATER_PERIOD);
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
	 * Open the settings activity
	 */
	private void openSettings() {
		final Intent intent = new Intent(this, SettingsActivity.class);
		// Use modern ActivityResultLauncher instead of deprecated startActivityForResult()
		settingsLauncher.launch(intent);
	}
}
