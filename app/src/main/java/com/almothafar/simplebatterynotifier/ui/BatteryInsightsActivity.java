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
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.model.BatteryHealthGrade;
import com.almothafar.simplebatterynotifier.service.BatteryCapacityTracker;
import com.almothafar.simplebatterynotifier.service.BatteryHealthTracker;
import com.almothafar.simplebatterynotifier.service.SystemService;
import com.almothafar.simplebatterynotifier.util.GeneralHelper;

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
	private ImageView healthWarningIcon;
	private TextView chargeCyclesText;
	private TextView daysInUseText;
	private TextView healthDescriptionText;
	private TextView designCapacityText;
	private ImageView healthMeasuredInfoIcon;
	private TextView measuredCapacityText;
	private View measuredCapacityRange;
	private TextView measuredCapacityMinText;
	private TextView measuredCapacityMaxText;

	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.activity_battery_insights);

		// Set up the Toolbar with back button
		final Toolbar toolbar = findViewById(R.id.toolbar);
		setupToolbar(toolbar, true);

		// Keep the scrollable content (down to the developer signature) clear of the system
		// navigation bar. Android 15+ (targetSdk 35+) enforces edge-to-edge and no longer insets
		// content above the nav bar, so without this the footer draws underneath it (#102).
		applyBottomSystemBarInset(findViewById(R.id.insightsScrollContent));

		// Initialize views
		healthPercentageText = findViewById(R.id.healthPercentageText);
		healthStatusText = findViewById(R.id.healthStatusText);
		healthBasisText = findViewById(R.id.healthBasisText);
		healthWarningIcon = findViewById(R.id.healthWarningIcon);
		chargeCyclesText = findViewById(R.id.chargeCyclesText);
		daysInUseText = findViewById(R.id.daysInUseText);
		healthDescriptionText = findViewById(R.id.healthDescriptionText);
		designCapacityText = findViewById(R.id.designCapacityText);
		healthMeasuredInfoIcon = findViewById(R.id.healthMeasuredInfoIcon);
		measuredCapacityText = findViewById(R.id.measuredCapacityText);
		measuredCapacityRange = findViewById(R.id.measuredCapacityRange);
		measuredCapacityMinText = findViewById(R.id.measuredCapacityMinText);
		measuredCapacityMaxText = findViewById(R.id.measuredCapacityMaxText);

		// Tap the warning icon (shown only when the reading can't be trusted, #94) to explain why
		healthWarningIcon.setOnClickListener(v -> showUnreliableReadingDialog());

		// Tap the info icon (shown only when health is measured, #116) to explain the averaging
		healthMeasuredInfoIcon.setOnClickListener(v -> showMeasuredHealthInfoDialog());

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
		// One sticky read per refresh (#161): every cycle- and capacity-derived figure below is computed
		// from these values. The capacity figure prefers the STABLE learned average (#116) so both the
		// health % and the shown capacity stay steady across refreshes; it falls back to this tick's
		// estimate while the learner warms up (or on untrusted-counter devices, where it never forms).
		final int osCycles = SystemService.getChargeCycleCount(this);
		final int cycles = BatteryHealthTracker.getEffectiveCycleCount(this, osCycles);
		final BatteryCapacityTracker.CapacitySummary capacity = BatteryCapacityTracker.getCapacitySummary(this);
		final int capacityMah = capacity != null ? capacity.averageMah() : SystemService.getBatteryCapacity(this);

		// Always show the resolved health figure (measured, else cycle-based).
		showResolvedHealth(cycles, BatteryHealthTracker.isCycleCountFromOs(osCycles), capacityMah);

		// When the device's charge counter can't be trusted (#94) the figure may be wrong: keep showing
		// it, but flag it with a tappable warning that explains why (and the failing-battery edge case).
		final boolean unreliable = BatteryHealthTracker.isBatteryReadingUnreliable(this, capacityMah);
		healthWarningIcon.setVisibility(unreliable ? View.VISIBLE : View.GONE);

		// Averaged measured capacity with its min/max spread (#116).
		showMeasuredCapacity(capacity, unreliable);

		// Metrics and the design-capacity row are shown the same way in every state.
		chargeCyclesText.setText(String.valueOf(cycles));
		daysInUseText.setText(String.valueOf(BatteryHealthTracker.getDaysSinceFirstUse(this)));

		final int designCapacity = BatteryHealthTracker.getDesignCapacity(this);
		// Pass the number as a String so it renders in Western digits (0-9) in every locale (#96).
		designCapacityText.setText(designCapacity > 0
		                           ? getString(R.string.design_capacity_value, String.valueOf(designCapacity))
		                           : getString(R.string.set_design_capacity_action));
	}

	/**
	 * Shows the resolved health figure: the measured percentage (current capacity vs. user-entered
	 * design capacity) when available, otherwise the cycle-based estimate. See issue #32 / #7.
	 * Works entirely from the values the caller already read this refresh (#161).
	 *
	 * @param cycles       the effective charge cycle count for this refresh
	 * @param cyclesFromOs whether that count came from the OS (labels the basis honestly, #114)
	 * @param capacityMah  the current full capacity estimate in mAh (0 when unknown)
	 */
	private void showResolvedHealth(final int cycles, final boolean cyclesFromOs, final int capacityMah) {
		final int measuredHealth = BatteryHealthTracker.getMeasuredHealthPercentage(this, capacityMah);
		final boolean measured = measuredHealth >= 0;

		final int healthPercentage = measured
		                             ? measuredHealth
		                             : BatteryHealthTracker.estimatedHealthForCycles(cycles);
		final BatteryHealthGrade grade = measured
		                                 ? BatteryHealthTracker.gradeForPercentage(measuredHealth)
		                                 : BatteryHealthTracker.gradeForCycles(cycles);

		// Update health percentage and color it based on grade
		healthPercentageText.setText(healthPercentage + "%");
		healthPercentageText.setTextColor(getHealthColor(grade));

		// Update health status text
		healthStatusText.setText(BatteryHealthTracker.labelResId(grade));
		healthStatusText.setTextColor(getHealthColor(grade));

		// Tell the user what the figure is based on. The cycle-based estimate distinguishes OS-reported
		// cycles (whole battery life) from cycles this app tracked since install, which understate real
		// wear on a phone older than the app (#114).
		final int basisRes = measured
		                     ? R.string.health_basis_measured
		                     : cyclesFromOs
		                       ? R.string.health_basis_estimated_os
		                       : R.string.health_basis_estimated_tracked;
		healthBasisText.setText(basisRes);
		// The averaging explainer only applies to the measured figure (#116); hide it for the estimate.
		healthMeasuredInfoIcon.setVisibility(measured ? View.VISIBLE : View.GONE);

		healthDescriptionText.setText(BatteryHealthTracker.describeHealthGrade(this, grade));
	}

	/**
	 * Shows the averaged measured capacity with its min/max spread (#116). The learner averages many
	 * spaced, trust-gated samples, so the figure is stable across refreshes instead of tracking the
	 * live counter. Before the average forms, shows "Unknown" when the counter can't be trusted on this
	 * device (#94) and "calculating" while a trusted device is still warming up.
	 *
	 * @param capacity   the learned capacity summary, or null when none has formed yet
	 * @param unreliable whether this device's charge-counter reading can't be trusted (#94)
	 */
	private void showMeasuredCapacity(final BatteryCapacityTracker.CapacitySummary capacity, final boolean unreliable) {
		if (capacity == null) {
			measuredCapacityText.setText(unreliable ? R.string.unknown : R.string.battery_value_calculating);
			measuredCapacityRange.setVisibility(View.GONE);
			return;
		}
		// Pass the numbers as Strings so they render in Western digits (0-9) in every locale (#96).
		measuredCapacityText.setText(getString(R.string.design_capacity_value, String.valueOf(capacity.averageMah())));
		measuredCapacityMinText.setText(String.valueOf(capacity.minMah()));
		measuredCapacityMaxText.setText(String.valueOf(capacity.maxMah()));
		measuredCapacityRange.setVisibility(View.VISIBLE);
	}

	/**
	 * Explains that the measured health figure is derived from the battery's capacity averaged over
	 * many spaced readings, so it stays stable instead of tracking a single fluctuating sample (#116).
	 */
	private void showMeasuredHealthInfoDialog() {
		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.health_measured_info_dialog_title)
				.setMessage(R.string.health_measured_info_dialog_message)
				.setPositiveButton(android.R.string.ok, null)
				.show();
	}

	/**
	 * Explains why the battery reading may be unreliable on this device, including the possibility that
	 * the battery is genuinely wearing out (#94). Shares its wording with the home Capacity row.
	 */
	private void showUnreliableReadingDialog() {
		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.battery_reading_unreliable_dialog_title)
				.setMessage(R.string.battery_reading_unreliable_dialog_message)
				.setPositiveButton(android.R.string.ok, null)
				.show();
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

		// Debug-only strings: this block runs only in debuggable builds, so its literals are
		// deliberately left untranslated (no values-ar). Skip them in i18n sweeps (#165).
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
		final int prefill = stored > 0 ? stored : measuredCapacityForPrefill();
		if (prefill > 0) {
			input.setText(String.valueOf(prefill));
			input.setSelection(String.valueOf(prefill).length());
		}

		// Indent the field to match the dialog's message padding
		final int padding = GeneralHelper.dpToPixel(getResources(), 24);
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
	 * The measured capacity to prefill the design-capacity field with when the user hasn't set one:
	 * the stable learned average (#116) when available, else this tick's estimate, else 0 (no prefill).
	 *
	 * @return a measured capacity in mAh, or 0 when none is available
	 */
	private int measuredCapacityForPrefill() {
		final BatteryCapacityTracker.CapacitySummary capacity = BatteryCapacityTracker.getCapacitySummary(this);
		return capacity != null ? capacity.averageMah() : SystemService.getBatteryCapacity(this);
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
		// Reached only from the debuggable-only debug menu, so these literals are deliberately
		// left untranslated (no values-ar). Skip them in i18n sweeps (#165).
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
