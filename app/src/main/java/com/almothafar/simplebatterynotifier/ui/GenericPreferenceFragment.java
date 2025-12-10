package com.almothafar.simplebatterynotifier.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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

import java.util.Set;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Modern preference fragment using AndroidX Preferences
 */
public class GenericPreferenceFragment extends PreferenceFragmentCompat
		implements SharedPreferences.OnSharedPreferenceChangeListener {

	private RingtonePreference currentRingtonePreference;
	private ActivityResultLauncher<Intent> ringtonePickerLauncher;

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
							// Use type-safe getParcelableExtra for API 33+, fallback for older versions
							final Uri uri;
							if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
								uri = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri.class);
							} else {
								// Suppress deprecation warning - this is required for API < 33 compatibility (minSdk is 26)
								@SuppressWarnings("deprecation")
								final Uri fallbackUri = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
								uri = fallbackUri;
							}
							final String uriString = nonNull(uri) ? uri.toString() : "";
							currentRingtonePreference.setRingtoneUri(uriString);
							currentRingtonePreference = null;
						}
					}
				}
		);
	}

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

	@Override
	public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences, final String key) {
		// Update summary when preference changes
		final Preference pref = findPreference(key);
		updatePreferencesSummary(sharedPreferences, pref);
	}

	/**
	 * Update a preference summary based on its type and value
	 */
	protected void updatePreferencesSummary(final SharedPreferences sharedPreferences, final Preference pref) {
		switch (pref) {
			case null -> {
			}
			case ListPreference listPref -> listPref.setSummary(listPref.getEntry());
			case EditTextPreference editTextPref -> {
				// Only set summary if no SummaryProvider is already set
				if (isNull(editTextPref.getSummaryProvider())) {
					editTextPref.setSummary(editTextPref.getText());
				}
			}
			case SeekBarPreference seekBarPref -> {
				// Add percentage suffix for battery level preferences
				final String key = pref.getKey();
				if (nonNull(key) && (key.equals(getString(R.string._pref_key_warn_battery_level)) || key.equals(getString(R.string._pref_key_critical_battery_level)))) {
					seekBarPref.setSummary(seekBarPref.getValue() + "%");
				}
			}
			case MultiSelectListPreference mlistPref -> {
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
			default -> {
				// Handle custom preferences by class name (they use old android.preference classes)
				final String className = pref.getClass().getSimpleName();

				switch (className) {
					case "RingtonePreference" -> {
						// Handle ringtone preference
						final String uri = sharedPreferences.getString(pref.getKey(), null);
						if (nonNull(uri) && !uri.isEmpty()) {
							try {
								final Ringtone ringtone = RingtoneManager.getRingtone(pref.getContext(), Uri.parse(uri));
								if (nonNull(ringtone)) {
									pref.setSummary(ringtone.getTitle(pref.getContext()));
								}
							} catch (Exception e) {
								// If ringtone not found, just show URI
								pref.setSummary(uri);
							}
						}
					}
					case "NumberPickerPreference" -> {
						// Handle number picker preference
						final int value = sharedPreferences.getInt(pref.getKey(), 0);
						pref.setSummary(String.valueOf(value));
					}
					case "TimePickerPreference" -> {
						// Handle time picker preference
						final String value = sharedPreferences.getString(pref.getKey(), "");
						if (nonNull(value) && !value.isEmpty()) {
							pref.setSummary(value);
						}
					}
				}
			}
		}
	}

	/**
	 * Initialize summaries for all preferences
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
	 */
	protected void initPreferencesSummary(final SharedPreferences sharedPreferences, final Preference p) {
		if (p instanceof final PreferenceCategory pCat) {
			for (int i = 0; i < pCat.getPreferenceCount(); i++) {
				initPreferencesSummary(sharedPreferences, pCat.getPreference(i));
			}
		} else {
			updatePreferencesSummary(sharedPreferences, p);

			// Add click listener for ringtone preferences
			if (p instanceof final RingtonePreference ringtonePref) {
				ringtonePref.setOnPreferenceClickListener(pref -> {
					currentRingtonePreference = (RingtonePreference) pref;
					final Intent intent = currentRingtonePreference.createRingtonePickerIntent();
					ringtonePickerLauncher.launch(intent);
					return true;
				});
			}

			// Add a change listener for battery level SeekBarPreferences
			if (p instanceof SeekBarPreference) {
				final String key = p.getKey();
				if (nonNull(key) && (key.equals(getString(R.string._pref_key_warn_battery_level)) ||
						key.equals(getString(R.string._pref_key_critical_battery_level)))) {
					p.setOnPreferenceChangeListener((pref, newValue) -> {
						if (newValue instanceof Integer) {
							final int value = (Integer) newValue;

							// Validate warning and critical levels don't overlap
							if (key.equals(getString(R.string._pref_key_warn_battery_level))) {
								// Changing warning level - ensure it's higher than critical
								final int criticalLevel = sharedPreferences.getInt(
										getString(R.string._pref_key_critical_battery_level), 20);
								if (value <= criticalLevel) {
									// Show a toast or message to the user
									android.widget.Toast.makeText(pref.getContext(),
											"Warning level must be higher than critical level (" + criticalLevel + "%)",
											android.widget.Toast.LENGTH_SHORT).show();
									return false; // Reject the change
								}
							} else if (key.equals(getString(R.string._pref_key_critical_battery_level))) {
								// Changing critical level - ensure it's lower than warning
								final int warningLevel = sharedPreferences.getInt(
										getString(R.string._pref_key_warn_battery_level), 40);
								if (value >= warningLevel) {
									// Show a toast or message to the user
									android.widget.Toast.makeText(pref.getContext(),
											"Critical level must be lower than warning level (" + warningLevel + "%)",
											android.widget.Toast.LENGTH_SHORT).show();
									return false; // Reject the change
								}
							}

							pref.setSummary(value + "%");
						}
						return true;
					});
				}
			}
		}
	}
}
