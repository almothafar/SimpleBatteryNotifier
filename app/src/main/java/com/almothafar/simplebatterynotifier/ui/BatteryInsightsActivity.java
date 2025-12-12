package com.almothafar.simplebatterynotifier.ui;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.WindowCompat;
import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.service.BatteryHealthTracker;

/**
 * Activity displaying battery health insights including charge cycles and estimated health.
 */
public class BatteryInsightsActivity extends AppCompatActivity {

	private TextView healthPercentageText;
	private TextView healthStatusText;
	private TextView chargeCyclesText;
	private TextView daysInUseText;
	private TextView healthDescriptionText;
	private LinearLayout developerSignatureLayout;

	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Enable edge-to-edge display
		WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

		setContentView(R.layout.activity_battery_insights);

		// Set up the Toolbar with back button
		final Toolbar toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
			getSupportActionBar().setDisplayShowHomeEnabled(true);
		}

		// Initialize views
		healthPercentageText = findViewById(R.id.healthPercentageText);
		healthStatusText = findViewById(R.id.healthStatusText);
		chargeCyclesText = findViewById(R.id.chargeCyclesText);
		daysInUseText = findViewById(R.id.daysInUseText);
		healthDescriptionText = findViewById(R.id.healthDescriptionText);
		developerSignatureLayout = findViewById(R.id.developerSignatureLayout);

		// Load and display battery health data
		updateHealthData();

		// Set up developer signature click listener to open link
		setupDeveloperSignature();
	}

	@Override
	protected void onResume() {
		super.onResume();
		// Refresh data when returning to the activity
		updateHealthData();
	}

	@Override
	public boolean onOptionsItemSelected(final MenuItem item) {
		// Handle back button in toolbar
		if (item.getItemId() == android.R.id.home) {
			finish();
			return true;
		}
		return super.onOptionsItemSelected(item);
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
	 * @return Color integer for the status
	 */
	private int getHealthColor(final String healthStatus) {
		switch (healthStatus) {
			case "Excellent":
				return getColor(R.color.top_background_color); // Green
			case "Good":
				return getColor(R.color.title_bar_background_color); // Blue
			case "Fair":
				return getColor(R.color.circular_progress_default_progress_warning); // Orange
			case "Poor":
				return getColor(R.color.circular_progress_default_progress_alert); // Red
			default:
				return getColor(R.color.default_text_color); // Default
		}
	}

	/**
	 * Sets up the developer signature section with clickable link.
	 */
	private void setupDeveloperSignature() {
		final TextView developerLinkText = findViewById(R.id.developerLinkText);
		final String developerLink = getString(R.string.developer_link);

		// Make the entire signature layout clickable
		developerSignatureLayout.setOnClickListener(v -> {
			// Open the developer link in browser
			openDeveloperLink(developerLink);
		});

		// Make the link text look clickable
		developerLinkText.setTextColor(getColor(R.color.title_bar_background_color));
	}

	/**
	 * Opens the developer's website or GitHub profile in a browser.
	 *
	 * @param link The URL or GitHub username to open
	 */
	private void openDeveloperLink(final String link) {
		try {
			// If the link doesn't start with http, assume it's a GitHub path
			String url = link;
			if (!link.startsWith("http://") && !link.startsWith("https://")) {
				url = "https://" + link;
			}

			final Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
			startActivity(browserIntent);
		} catch (final Exception e) {
			// Handle case where no browser is available (rare)
			e.printStackTrace();
		}
	}
}
