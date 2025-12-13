package com.almothafar.simplebatterynotifier.ui.fragment;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.SeekBarPreference;

import com.almothafar.simplebatterynotifier.R;
import com.google.android.material.snackbar.Snackbar;

import java.util.Set;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Modern preference fragment using AndroidX Preferences
 * <p>
 * Handles preference UI, validation, and summary updates for all app settings.
 * Uses modern Activity Result API for ringtone picker and provides accessible
 * error feedback via Snackbar.
 */
public class GenericPreferenceFragment extends PreferenceFragmentCompat
		implements SharedPreferences.OnSharedPreferenceChangeListener {

	// Default battery level values (from pref_general.xml)
	private static final int DEFAULT_WARNING_BATTERY_LEVEL = 40;
	private static final int DEFAULT_CRITICAL_BATTERY_LEVEL = 20;

	private RingtonePreference currentRingtonePreference;
	private ActivityResultLauncher<Intent> ringtonePickerLauncher;

	/**
	 * Called when the fragment is first created
	 * <p>
	 * Registers the ActivityResultLauncher for handling ringtone picker results
	 * using modern Activity Result API instead of deprecated startActivityForResult.
	 *
	 * @param savedInstanceState Saved state from previous instance
	 */
	@Override
	public void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Register the activity result launcher for the ringtone picker
		ringtonePickerLauncher = registerForActivityResult(
				new ActivityResultContracts.StartActivityForResult(),
				result -> {
					if (result.getResultCode() == Activity.RESULT_OK && nonNull(currentRingtonePreference)) {
						final Intent data = result.getData();
						if (nonNull(data)) {
							final Uri uri = extractRingtoneUri(data);
							final String uriString = nonNull(uri) ? uri.toString() : "";
							currentRingtonePreference.setRingtoneUri(uriString);
							currentRingtonePreference = null;
						}
					}
				}
		);
	}

	/**
	 * Extract ringtone URI from intent data using type-safe API for API 33+
	 * <p>
	 * Uses modern getParcelableExtra(String, Class) for API 33+ with fallback
	 * to deprecated method for API 26-32 (minSdk is 26).
	 *
	 * @param data Intent containing ringtone picker result
	 * @return Selected ringtone URI, or null if not available
	 */
	private Uri extractRingtoneUri(final Intent data) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			return data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri.class);
		} else {
			// Suppress deprecation warning - this is required for API < 33 compatibility (minSdk is 26)
			@SuppressWarnings("deprecation")
			final Uri fallbackUri = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
			return fallbackUri;
		}
	}

	/**
	 * Called when preferences should be created from XML resource
	 * <p>
	 * Loads the appropriate preference screen based on the category argument.
	 *
	 * @param savedInstanceState Saved state from previous instance
	 * @param rootKey            The root key of the preference hierarchy, or null
	 */
	@Override
	public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
		// Get category from arguments
		final Bundle args = getArguments();
		if (nonNull(args)) {
			final String category = args.getString("category");
			if (nonNull(category)) {
				if (category.equals(getString(R.string.pref_category_general))) {
					setPreferencesFromResource(R.xml.pref_general, rootKey);
				} else if (category.equals(getString(R.string.pref_category_notifications))) {
					setPreferencesFromResource(R.xml.pref_notification, rootKey);
				} else if (category.equals(getString(R.string.pref_category_time_settings))) {
					setPreferencesFromResource(R.xml.pref_time_settings, rootKey);
				}
			}
		}
	}

	/**
	 * Display custom dialog for TimePickerPreference
	 * <p>
	 * Note: setTargetFragment is deprecated but still required by PreferenceDialogFragmentCompat.
	 * The AndroidX Preference library has not yet provided an alternative.
	 *
	 * @param preference The preference requesting the dialog
	 */
	@Override
	public void onDisplayPreferenceDialog(@NonNull final Preference preference) {
		// Handle custom TimePickerPreference dialog
		if (preference instanceof TimePickerPreference) {
			final DialogFragment dialogFragment = TimePickerPreferenceDialogFragmentCompat
					.newInstance(preference.getKey());

			// Set target fragment (required for PreferenceDialogFragmentCompat)
			// Note: setTargetFragment is deprecated but still required by PreferenceDialogFragmentCompat
			//noinspection deprecation
			dialogFragment.setTargetFragment(this, 0);
			dialogFragment.show(getParentFragmentManager(),
			                    "androidx.preference.PreferenceFragment.DIALOG");
		} else {
			super.onDisplayPreferenceDialog(preference);
		}
	}

	/**
	 * Register preference change listener and initialize summaries
	 */
	@Override
	public void onResume() {
		super.onResume();
		// Register preference change listener
		final PreferenceScreen preferenceScreen = getPreferenceScreen();
		if (nonNull(preferenceScreen)) {
			final SharedPreferences sharedPreferences = preferenceScreen.getSharedPreferences();
			if (nonNull(sharedPreferences)) {
				sharedPreferences.registerOnSharedPreferenceChangeListener(this);
			}
		}
		initSummary();
	}

	/**
	 * Unregister preference change listener to prevent memory leaks
	 */
	@Override
	public void onPause() {
		super.onPause();
		// Unregister preference change listener
		final PreferenceScreen preferenceScreen = getPreferenceScreen();
		if (nonNull(preferenceScreen)) {
			final SharedPreferences sharedPreferences = preferenceScreen.getSharedPreferences();
			if (nonNull(sharedPreferences)) {
				sharedPreferences.unregisterOnSharedPreferenceChangeListener(this);
			}
		}
	}

	/**
	 * Called when a shared preference is changed
	 * <p>
	 * Updates the preference summary to reflect the new value.
	 *
	 * @param sharedPreferences The SharedPreferences that received the change
	 * @param key               The key of the preference that was changed
	 */
	@Override
	public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences, final String key) {
		// Update summary when preference changes
		final Preference pref = findPreference(key);
		updatePreferencesSummary(sharedPreferences, pref);
	}

	/**
	 * Update a preference summary based on its type and value
	 * <p>
	 * Delegates to specialized methods for each preference type to keep
	 * the method focused and maintainable.
	 *
	 * @param sharedPreferences The SharedPreferences containing preference values
	 * @param pref              The preference to update
	 */
	protected void updatePreferencesSummary(final SharedPreferences sharedPreferences, final Preference pref) {
		switch (pref) {
			case null -> {
			}
			case ListPreference listPref -> updateListPreferenceSummary(listPref);
			case EditTextPreference editTextPref -> updateEditTextPreferenceSummary(editTextPref);
			case SeekBarPreference seekBarPref -> updateSeekBarPreferenceSummary(seekBarPref);
			case MultiSelectListPreference mlistPref -> updateMultiSelectListPreferenceSummary(mlistPref);
			case RingtonePreference ringtonePref -> updateRingtonePreferenceSummary(sharedPreferences, ringtonePref);
			case TimePickerPreference timePickerPref -> updateTimePickerPreferenceSummary(sharedPreferences, timePickerPref);
			default -> {
				// No summary update needed for other preference types
			}
		}
	}

	/**
	 * Update summary for ListPreference
	 */
	private void updateListPreferenceSummary(final ListPreference listPref) {
		listPref.setSummary(listPref.getEntry());
	}

	/**
	 * Update summary for EditTextPreference
	 * <p>
	 * Only updates if no custom SummaryProvider is already set.
	 */
	private void updateEditTextPreferenceSummary(final EditTextPreference editTextPref) {
		if (isNull(editTextPref.getSummaryProvider())) {
			editTextPref.setSummary(editTextPref.getText());
		}
	}

	/**
	 * Update summary for SeekBarPreference
	 * <p>
	 * Adds percentage suffix for battery level preferences.
	 */
	private void updateSeekBarPreferenceSummary(final SeekBarPreference seekBarPref) {
		final String key = seekBarPref.getKey();
		if (nonNull(key) && isBatteryLevelPreference(key)) {
			seekBarPref.setSummary(seekBarPref.getValue() + "%");
		}
	}

	/**
	 * Update summary for MultiSelectListPreference
	 * <p>
	 * Shows selected items separated by semicolons.
	 */
	private void updateMultiSelectListPreferenceSummary(final MultiSelectListPreference mlistPref) {
		final StringBuilder summaryBuilder = new StringBuilder();
		final Set<String> values = mlistPref.getValues();

		int count = 0;
		for (final String value : values) {
			final int index = mlistPref.findIndexOfValue(value);
			if (index >= 0 && nonNull(mlistPref.getEntries())) {
				if (count > 0) {
					summaryBuilder.append("; ");
				}
				summaryBuilder.append(mlistPref.getEntries()[index]);
				count++;
			}
		}
		mlistPref.setSummary(summaryBuilder.toString());
	}

	/**
	 * Update summary for RingtonePreference
	 * <p>
	 * Shows the ringtone title, or the URI if title cannot be retrieved.
	 */
	private void updateRingtonePreferenceSummary(final SharedPreferences sharedPreferences,
	                                              final RingtonePreference ringtonePref) {
		final String uri = sharedPreferences.getString(ringtonePref.getKey(), null);
		if (nonNull(uri) && !uri.isEmpty()) {
			try {
				final Ringtone ringtone = RingtoneManager.getRingtone(ringtonePref.getContext(), Uri.parse(uri));
				if (nonNull(ringtone)) {
					ringtonePref.setSummary(ringtone.getTitle(ringtonePref.getContext()));
				}
			} catch (Exception e) {
				// If ringtone not found, just show URI
				ringtonePref.setSummary(uri);
			}
		}
	}

	/**
	 * Update summary for TimePickerPreference
	 * <p>
	 * Shows the selected time value.
	 */
	private void updateTimePickerPreferenceSummary(final SharedPreferences sharedPreferences,
	                                                final TimePickerPreference timePickerPref) {
		final String value = sharedPreferences.getString(timePickerPref.getKey(), "");
		if (nonNull(value) && !value.isEmpty()) {
			timePickerPref.setSummary(value);
		}
	}

	/**
	 * Check if a preference key is for battery level configuration
	 *
	 * @param key The preference key to check
	 * @return true if the key is for warning or critical battery level
	 */
	private boolean isBatteryLevelPreference(final String key) {
		return key.equals(getString(R.string._pref_key_warn_battery_level)) ||
		       key.equals(getString(R.string._pref_key_critical_battery_level));
	}

	/**
	 * Initialize summaries for all preferences
	 * <p>
	 * Called when the fragment is resumed to ensure all preference summaries
	 * are up to date.
	 */
	protected void initSummary() {
		final PreferenceScreen screen = getPreferenceScreen();
		if (nonNull(screen)) {
			final SharedPreferences sharedPrefs = screen.getSharedPreferences();
			for (int i = 0; i < screen.getPreferenceCount(); i++) {
				initPreferencesSummary(sharedPrefs, screen.getPreference(i));
			}
		}
	}

	/**
	 * Initialize the summary for a single preference (recursively for categories)
	 * <p>
	 * Sets up click listeners for custom preferences and validation for battery levels.
	 *
	 * @param sharedPreferences The SharedPreferences containing preference values
	 * @param p                 The preference to initialize
	 */
	protected void initPreferencesSummary(final SharedPreferences sharedPreferences, final Preference p) {
		if (p instanceof final PreferenceCategory pCat) {
			// Recursively initialize category children
			for (int i = 0; i < pCat.getPreferenceCount(); i++) {
				initPreferencesSummary(sharedPreferences, pCat.getPreference(i));
			}
		} else {
			updatePreferencesSummary(sharedPreferences, p);

			// Set up click listener for ringtone preferences
			if (p instanceof final RingtonePreference ringtonePref) {
				setupRingtonePreferenceListener(ringtonePref);
			}

			// Set up validation for battery level preferences
			if (p instanceof SeekBarPreference && isBatteryLevelPreference(p.getKey())) {
				setupBatteryLevelValidation(sharedPreferences, p);
			}
		}
	}

	/**
	 * Set up click listener for ringtone preference
	 * <p>
	 * Launches the ringtone picker when the preference is clicked.
	 *
	 * @param ringtonePref The ringtone preference to configure
	 */
	private void setupRingtonePreferenceListener(final RingtonePreference ringtonePref) {
		ringtonePref.setOnPreferenceClickListener(pref -> {
			currentRingtonePreference = (RingtonePreference) pref;
			final Intent intent = currentRingtonePreference.createRingtonePickerIntent();
			ringtonePickerLauncher.launch(intent);
			return true;
		});
	}

	/**
	 * Set up validation for battery level SeekBarPreferences
	 * <p>
	 * Ensures warning level is always higher than critical level and vice versa.
	 * Shows accessible error message via Snackbar if validation fails.
	 *
	 * @param sharedPreferences The SharedPreferences containing current values
	 * @param pref              The battery level preference to validate
	 */
	private void setupBatteryLevelValidation(final SharedPreferences sharedPreferences, final Preference pref) {
		pref.setOnPreferenceChangeListener((preference, newValue) -> {
			if (newValue instanceof Integer) {
				final int value = (Integer) newValue;
				final String key = preference.getKey();

				// Validate the new value
				final String errorMessage = validateBatteryLevel(key, value, sharedPreferences);
				if (nonNull(errorMessage)) {
					showValidationError(errorMessage);
					return false; // Reject the change
				}

				// Update summary with new value
				preference.setSummary(value + "%");
			}
			return true;
		});
	}

	/**
	 * Validate battery level change
	 * <p>
	 * Ensures warning level is always higher than critical level.
	 *
	 * @param key               The preference key being changed
	 * @param newValue          The new value to validate
	 * @param sharedPreferences The SharedPreferences containing current values
	 * @return Error message if validation fails, null if valid
	 */
	private String validateBatteryLevel(final String key, final int newValue, final SharedPreferences sharedPreferences) {
		if (key.equals(getString(R.string._pref_key_warn_battery_level))) {
			// Changing warning level - ensure it's higher than critical
			final int criticalLevel = sharedPreferences.getInt(
					getString(R.string._pref_key_critical_battery_level),
					DEFAULT_CRITICAL_BATTERY_LEVEL);
			if (newValue <= criticalLevel) {
				return getString(R.string.error_warning_level_too_low, criticalLevel);
			}
		} else if (key.equals(getString(R.string._pref_key_critical_battery_level))) {
			// Changing critical level - ensure it's lower than warning
			final int warningLevel = sharedPreferences.getInt(
					getString(R.string._pref_key_warn_battery_level),
					DEFAULT_WARNING_BATTERY_LEVEL);
			if (newValue >= warningLevel) {
				return getString(R.string.error_critical_level_too_high, warningLevel);
			}
		}
		return null; // Valid
	}

	/**
	 * Show validation error message using Snackbar for accessibility
	 * <p>
	 * Snackbar is preferred over Toast because:
	 * - It's accessible to TalkBack users
	 * - It's more visible and can contain actions
	 * - It follows Material Design guidelines
	 *
	 * @param message The error message to display
	 */
	private void showValidationError(final String message) {
		final View view = getView();
		if (nonNull(view)) {
			Snackbar.make(view, message, Snackbar.LENGTH_SHORT).show();
		}
	}
}
