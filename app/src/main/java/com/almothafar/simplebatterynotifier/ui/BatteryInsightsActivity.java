package com.almothafar.simplebatterynotifier.ui;

import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.widget.Toolbar;
import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.service.BatteryHealthTracker;

/**
 * Activity displaying battery health insights including charge cycles and estimated health.
 */
public class BatteryInsightsActivity extends BaseActivity {

	private TextView healthPercentageText;
	private TextView healthStatusText;
	private TextView chargeCyclesText;
	private TextView daysInUseText;
	private TextView healthDescriptionText;

	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.activity_battery_insights);

		// Set up the Toolbar with back button
		final Toolbar toolbar = findViewById(R.id.toolbar);
		setupToolbar(toolbar, true);

		// Initialize views
		healthPercentageText = findViewById(R.id.healthPercentageText);
		healthStatusText = findViewById(R.id.healthStatusText);
		chargeCyclesText = findViewById(R.id.chargeCyclesText);
		daysInUseText = findViewById(R.id.daysInUseText);
		healthDescriptionText = findViewById(R.id.healthDescriptionText);

		// Add debug menu (long-press on health percentage)
		setupDebugMenu();

		// Load and display battery health data
		updateHealthData();
	}

	@Override
	protected void onResume() {
		super.onResume();
		// Refresh data when returning to the activity
		updateHealthData();
	}

	/**
	 * Updates all health data displays with current values from BatteryHealthTracker.
	 */
	private void updateHealthData() {
		// Get health metrics
		final int healthPercentage = BatteryHealthTracker.getEstimatedHealthPercentage(this);
		final String healthStatus = BatteryHealthTracker.getHealthStatus(this);
		final int chargeCycles = BatteryHealthTracker.getChargeCycles(this);
		final int daysInUse = BatteryHealthTracker.getDaysSinceFirstUse(this);
		final String healthDescription = BatteryHealthTracker.getHealthDescription(this);

		// Update health percentage and color it based on status
		healthPercentageText.setText(healthPercentage + "%");
		healthPercentageText.setTextColor(getHealthColor(healthStatus));

		// Update health status text
		healthStatusText.setText(healthStatus);
		healthStatusText.setTextColor(getHealthColor(healthStatus));

		// Update metrics
		chargeCyclesText.setText(String.valueOf(chargeCycles));
		daysInUseText.setText(String.valueOf(daysInUse));

		// Update description
		healthDescriptionText.setText(healthDescription);
	}

	/**
	 * Gets the appropriate color for the health status.
	 *
	 * @param healthStatus The health status string (Excellent, Good, Fair, Poor)
	 *
	 * @return Color integer for the status
	 */
	private int getHealthColor(final String healthStatus) {
		return switch (healthStatus) {
			case "Excellent" -> getColor(R.color.top_background_color); // Green
			case "Good" -> getColor(R.color.title_bar_background_color); // Blue
			case "Fair" -> getColor(R.color.circular_progress_default_progress_warning); // Orange
			case "Poor" -> getColor(R.color.circular_progress_default_progress_alert); // Red
			default -> getColor(R.color.default_text_color); // Default
		};
	}

	/**
	 * Sets up the hidden debug menu (long-press on health percentage).
	 * DEBUG feature for testing battery health tracking.
	 */
	private void setupDebugMenu() {
		healthPercentageText.setOnLongClickListener(v -> {
			showDebugMenu();
			return true;
		});
	}

	/**
	 * Shows debug menu with options to test battery health tracking.
	 */
	private void showDebugMenu() {
		final String[] options = {
				"Show Debug Info",
				"Add 50 Test Cycles",
				"Add 300 Test Cycles",
				"Add 600 Test Cycles",
				"Reset All Data"
		};

		new AlertDialog.Builder(this)
				.setTitle("Battery Health Debug")
				.setItems(options, (dialog, which) -> {
					switch (which) {
						case 0 -> showDebugInfo();
						case 1 -> addTestCycles(50);
						case 2 -> addTestCycles(300);
						case 3 -> addTestCycles(600);
						case 4 -> resetHealthData();
					}
				})
				.setNegativeButton("Cancel", null)
				.show();
	}

	/**
	 * Shows detailed debug information about tracking state.
	 */
	private void showDebugInfo() {
		final String debugInfo = BatteryHealthTracker.getDebugInfo(this);
		new AlertDialog.Builder(this)
				.setTitle("Tracking Debug Info")
				.setMessage(debugInfo)
				.setPositiveButton("OK", null)
				.show();
	}

	/**
	 * Adds test charge cycles and refreshes display.
	 */
	private void addTestCycles(final int cycles) {
		BatteryHealthTracker.addTestChargeCycles(this, cycles);
		updateHealthData();
		Toast.makeText(this, "Added " + cycles + " test cycles", Toast.LENGTH_SHORT).show();
	}

	/**
	 * Resets all health tracking data.
	 */
	private void resetHealthData() {
		new AlertDialog.Builder(this)
				.setTitle("Reset Health Data?")
				.setMessage("This will delete all tracked battery health data. Are you sure?")
				.setPositiveButton("Reset", (dialog, which) -> {
					BatteryHealthTracker.resetHealthData(this);
					updateHealthData();
					Toast.makeText(this, "Health data reset", Toast.LENGTH_SHORT).show();
				})
				.setNegativeButton("Cancel", null)
				.show();
	}
}
