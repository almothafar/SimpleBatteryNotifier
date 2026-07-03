package com.almothafar.simplebatterynotifier.ui;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.model.BatteryHealthGrade;
import com.almothafar.simplebatterynotifier.service.BatteryHealthTracker;
import com.almothafar.simplebatterynotifier.service.SystemService;

/**
 * Activity displaying battery health insights including charge cycles and estimated health.
 */
public class BatteryInsightsActivity extends BaseActivity {

	// Digit cap for the design-capacity field; MAX_DESIGN_CAPACITY_MAH (15000) is 5 digits, so this
	// bounds the input and guarantees Integer.parseInt can't overflow.
	private static final int MAX_DESIGN_CAPACITY_DIGITS = 5;

	private TextView healthPercentageText;
	private TextView healthStatusText;
	private TextView healthBasisText;
	private TextView chargeCyclesText;
	private TextView daysInUseText;
	private TextView healthDescriptionText;
	private TextView designCapacityText;

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
		healthBasisText = findViewById(R.id.healthBasisText);
		chargeCyclesText = findViewById(R.id.chargeCyclesText);
		daysInUseText = findViewById(R.id.daysInUseText);
		healthDescriptionText = findViewById(R.id.healthDescriptionText);
		designCapacityText = findViewById(R.id.designCapacityText);

		// Tap the design-capacity card to set/edit the rated capacity
		findViewById(R.id.designCapacityCard).setOnClickListener(v -> showDesignCapacityDialog());

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
		// Prefer the measured health figure (current capacity vs. user-entered design capacity) when
		// available; otherwise fall back to the cycle-based estimate. See issue #32 / #7.
		final int measuredHealth = BatteryHealthTracker.getMeasuredHealthPercentage(this);
		final boolean measured = measuredHealth >= 0;

		final int healthPercentage = measured
		                             ? measuredHealth
		                             : BatteryHealthTracker.getEstimatedHealthPercentage(this);
		final BatteryHealthGrade grade = measured
		                                 ? BatteryHealthTracker.gradeForPercentage(measuredHealth)
		                                 : BatteryHealthTracker.getHealthGrade(this);
		final String healthDescription = BatteryHealthTracker.describeHealthGrade(this, grade);
		final int chargeCycles = BatteryHealthTracker.getEffectiveCycleCount(this);
		final int daysInUse = BatteryHealthTracker.getDaysSinceFirstUse(this);

		// Update health percentage and color it based on grade
		healthPercentageText.setText(healthPercentage + "%");
		healthPercentageText.setTextColor(getHealthColor(grade));

		// Update health status text
		healthStatusText.setText(BatteryHealthTracker.labelResId(grade));
		healthStatusText.setTextColor(getHealthColor(grade));

		// Tell the user whether the figure is measured (honest) or a cycle-based estimate
		healthBasisText.setText(measured ? R.string.health_basis_measured : R.string.health_basis_estimated);

		// Update metrics
		chargeCyclesText.setText(String.valueOf(chargeCycles));
		daysInUseText.setText(String.valueOf(daysInUse));

		// Update description
		healthDescriptionText.setText(healthDescription);

		// Update the design-capacity row (value when set, call-to-action when unset)
		final int designCapacity = BatteryHealthTracker.getDesignCapacity(this);
		designCapacityText.setText(designCapacity > 0
		                           ? getString(R.string.design_capacity_value, designCapacity)
		                           : getString(R.string.set_design_capacity_action));
	}

	/**
	 * Gets the appropriate color for a battery wear grade.
	 *
	 * @param grade The battery wear grade
	 *
	 * @return Color integer for the grade
	 */
	private int getHealthColor(final BatteryHealthGrade grade) {
		return switch (grade) {
			case EXCELLENT -> getColor(R.color.top_background_color); // Green
			case GOOD -> getColor(R.color.title_bar_background_color); // Blue
			case FAIR -> getColor(R.color.circular_progress_default_progress_warning); // Orange
			case POOR -> getColor(R.color.circular_progress_default_progress_alert); // Red
		};
	}

	/**
	 * Sets up the hidden debug menu (long-press on health percentage).
	 * <p>
	 * The long-press entry point is always registered: in release builds it surfaces only the
	 * read-only "Show Debug Info" dump (harmless, useful for diagnosing user reports), while the
	 * data-mutating actions are gated to debuggable builds by {@link #showDebugMenu}.
	 */
	private void setupDebugMenu() {
		healthPercentageText.setOnLongClickListener(v -> {
			showDebugMenu();
			return true;
		});
	}

	/**
	 * Shows the debug menu. Read-only "Show Debug Info" is always available; the data-mutating
	 * actions (inject/reset cycles) are only offered in debuggable builds, where a long-press can't
	 * let a normal user silently corrupt their tracked health data.
	 */
	private void showDebugMenu() {
		final boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;

		if (!debuggable) {
			// Release: read-only tracking dump only
			showDebugInfo();
			return;
		}

		final String[] options = {
				"Show Debug Info",
				"Add 50 Test Cycles",
				"Add 300 Test Cycles",
				"Add 600 Test Cycles",
				"Reset debug data",
				"Reset ALL (incl. real data)"
		};

		new MaterialAlertDialogBuilder(this)
				.setTitle("Battery Health Debug")
				.setItems(options, (dialog, which) -> {
					switch (which) {
						case 0 -> showDebugInfo();
						case 1 -> addTestCycles(50);
						case 2 -> addTestCycles(300);
						case 3 -> addTestCycles(600);
						case 4 -> resetDebugData();
						case 5 -> resetHealthData();
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
		new MaterialAlertDialogBuilder(this)
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
	 * Clears only the debug-injected cycles, leaving the first-use date and real cycles intact.
	 */
	private void resetDebugData() {
		BatteryHealthTracker.resetDebugData(this);
		updateHealthData();
		Toast.makeText(this, "Debug data cleared", Toast.LENGTH_SHORT).show();
	}

	/**
	 * Shows the dialog for setting, editing, or clearing the battery design (rated) capacity.
	 * <p>
	 * The input is prefilled with the current stored value, or — when unset — with the measured
	 * current full-capacity estimate, so the user only has to confirm or correct it. A "look up my
	 * model" action opens a web search for the device's rated capacity. Saving an empty field clears
	 * the value and reverts to the cycle-based estimate.
	 */
	private void showDesignCapacityDialog() {
		final TextInputLayout inputLayout = new TextInputLayout(this);
		inputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
		inputLayout.setHint(getString(R.string.design_capacity_hint));
		inputLayout.setSuffixText(getString(R.string.capacity_unit_mah));

		final TextInputEditText input = new TextInputEditText(inputLayout.getContext());
		input.setInputType(InputType.TYPE_CLASS_NUMBER);
		// Max valid capacity is 15000 (5 digits); cap the length so the field can't overflow an int.
		input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_DESIGN_CAPACITY_DIGITS)});
		inputLayout.addView(input);

		final int stored = BatteryHealthTracker.getDesignCapacity(this);
		final int prefill = stored > 0 ? stored : SystemService.getBatteryCapacity(this);
		if (prefill > 0) {
			input.setText(String.valueOf(prefill));
			input.setSelection(String.valueOf(prefill).length());
		}

		// Indent the field to match the dialog's message padding
		final int padding = (int) (24 * getResources().getDisplayMetrics().density);
		final FrameLayout container = new FrameLayout(this);
		final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
		params.leftMargin = padding;
		params.rightMargin = padding;
		container.addView(inputLayout, params);

		final AlertDialog dialog = new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.design_capacity_dialog_title)
				.setMessage(R.string.design_capacity_dialog_message)
				.setView(container)
				.setPositiveButton(R.string.save, null) // overridden below to validate without dismissing
				.setNeutralButton(R.string.look_up_my_model, null)
				.setNegativeButton(android.R.string.cancel, null)
				.create();

		dialog.show();

		// Override click handlers so invalid input (Save) and the lookup action (Neutral) don't dismiss.
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
			final CharSequence text = input.getText();
			if (saveDesignCapacity(text == null ? "" : text.toString())) {
				dialog.dismiss();
			}
		});
		dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> lookUpBatteryModel());
	}

	/**
	 * Validates and stores the entered design capacity.
	 *
	 * @param rawInput The raw text from the input field
	 *
	 * @return true when the value was saved (or cleared) and the dialog may close; false when the
	 * input was invalid and the dialog should stay open
	 */
	private boolean saveDesignCapacity(final String rawInput) {
		final String trimmed = rawInput.trim();

		// Empty input clears the design capacity and reverts to the cycle-based estimate
		if (TextUtils.isEmpty(trimmed)) {
			BatteryHealthTracker.setDesignCapacity(this, 0);
			updateHealthData();
			return true;
		}

		// Reject non-numeric / oversized input as a normal validation branch. Bounding it to 1–5
		// digits here means the parse below provably can't overflow, so no exception handling needed.
		if (!trimmed.matches("\\d{1," + MAX_DESIGN_CAPACITY_DIGITS + "}")) {
			showCapacityRangeError();
			return false;
		}

		final int value = Integer.parseInt(trimmed); // safe: matched \d{1,5}, fits in an int
		if (!BatteryHealthTracker.isValidDesignCapacity(value)) {
			showCapacityRangeError();
			return false;
		}

		BatteryHealthTracker.setDesignCapacity(this, value);
		updateHealthData();
		return true;
	}

	/**
	 * Shows the accepted design-capacity range as a Toast.
	 */
	private void showCapacityRangeError() {
		Toast.makeText(this, getString(R.string.error_design_capacity_range,
				BatteryHealthTracker.MIN_DESIGN_CAPACITY_MAH,
				BatteryHealthTracker.MAX_DESIGN_CAPACITY_MAH), Toast.LENGTH_LONG).show();
	}

	/**
	 * Opens a web search for the current device's battery capacity so the user can find their rated
	 * (design) capacity.
	 */
	private void lookUpBatteryModel() {
		final String query = (Build.MANUFACTURER + " " + Build.MODEL + " battery capacity mAh").trim();
		final Uri uri = Uri.parse("https://www.google.com/search?q=" + Uri.encode(query));
		try {
			startActivity(new Intent(Intent.ACTION_VIEW, uri));
		} catch (ActivityNotFoundException e) {
			Toast.makeText(this, R.string.no_browser_found, Toast.LENGTH_SHORT).show();
		}
	}

	/**
	 * Resets all health tracking data, including the first-use date and real charge cycles.
	 */
	private void resetHealthData() {
		new MaterialAlertDialogBuilder(this)
				.setTitle("Reset ALL health data?")
				.setMessage("This deletes ALL tracked battery health data, including the first-use date "
						+ "and real charge cycles. Are you sure?")
				.setPositiveButton("Reset", (dialog, which) -> {
					BatteryHealthTracker.resetHealthData(this);
					updateHealthData();
					Toast.makeText(this, "All health data reset", Toast.LENGTH_SHORT).show();
				})
				.setNegativeButton("Cancel", null)
				.show();
	}
}
