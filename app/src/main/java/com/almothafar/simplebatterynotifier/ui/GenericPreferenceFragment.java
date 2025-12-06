package com.almothafar.simplebatterynotifier.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
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

/**
 * Modern preference fragment using AndroidX Preferences
 */
public class GenericPreferenceFragment extends PreferenceFragmentCompat
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private RingtonePreference currentRingtonePreference;
    private ActivityResultLauncher<Intent> ringtonePickerLauncher;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Register activity result launcher for ringtone picker
        ringtonePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && currentRingtonePreference != null) {
                        Intent data = result.getData();
                        if (data != null) {
                            Uri uri = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
                            String uriString = uri != null ? uri.toString() : "";
                            currentRingtonePreference.setRingtoneUri(uriString);
                            currentRingtonePreference = null;
                        }
                    }
                }
        );
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        // Get category from arguments
        Bundle args = getArguments();
        if (args != null) {
            String category = args.getString("category");
            if (category != null) {
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
    public void onDisplayPreferenceDialog(@NonNull Preference preference) {
        // Handle TimePickerPreference dialog
        if (preference instanceof TimePickerPreference) {
            DialogFragment dialogFragment = TimePickerPreferenceDialogFragmentCompat
                    .newInstance(preference.getKey());
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
        getPreferenceScreen().getSharedPreferences()
                .registerOnSharedPreferenceChangeListener(this);
        initSummary();
    }

    @Override
    public void onPause() {
        super.onPause();
        // Unregister preference change listener
        getPreferenceScreen().getSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        // Update summary when preference changes
        Preference pref = findPreference(key);
        updatePreferencesSummary(sharedPreferences, pref);
    }

    /**
     * Update preference summary based on its type and value
     */
    protected void updatePreferencesSummary(SharedPreferences sharedPreferences, Preference pref) {
	    switch (pref) {
		    case null -> {
		    }
		    case ListPreference listPref -> listPref.setSummary(listPref.getEntry());
		    case EditTextPreference editTextPref -> {
			    // Only set summary if no SummaryProvider is already set
			    if (editTextPref.getSummaryProvider() == null) {
				    editTextPref.setSummary(editTextPref.getText());
			    }
		    }
		    case SeekBarPreference seekBarPref -> {
			    // Add percentage suffix for battery level preferences
			    String key = pref.getKey();
			    if (key != null && (key.equals(getString(R.string._pref_key_warn_battery_level)) ||
					    key.equals(getString(R.string._pref_key_critical_battery_level)))) {
				    seekBarPref.setSummary(seekBarPref.getValue() + "%");
			    }
		    }
		    case MultiSelectListPreference mlistPref -> {
			    StringBuilder summaryBuilder = new StringBuilder();
			    Set<String> values = mlistPref.getValues();

			    int count = 0;
			    for (String value : values) {
				    int index = mlistPref.findIndexOfValue(value);
				    if (index >= 0 && mlistPref.getEntries() != null) {
					    if (count > 0) summaryBuilder.append("; ");
					    summaryBuilder.append(mlistPref.getEntries()[index]);
					    count++;
				    }
			    }
			    mlistPref.setSummary(summaryBuilder.toString());
		    }
		    default -> {
			    // Handle custom preferences by class name (they use old android.preference classes)
			    String className = pref.getClass().getSimpleName();

			    switch (className) {
				    case "RingtonePreference" -> {
					    // Handle ringtone preference
					    String uri = sharedPreferences.getString(pref.getKey(), null);
					    if (uri != null && !uri.isEmpty()) {
						    try {
							    Ringtone ringtone = RingtoneManager.getRingtone(pref.getContext(), Uri.parse(uri));
							    if (ringtone != null) {
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
					    int value = sharedPreferences.getInt(pref.getKey(), 0);
					    pref.setSummary(String.valueOf(value));

				    }
				    case "TimePickerPreference" -> {
					    // Handle time picker preference
					    String value = sharedPreferences.getString(pref.getKey(), "");
					    if (!value.isEmpty()) {
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
        PreferenceScreen screen = getPreferenceScreen();
        if (screen != null) {
            SharedPreferences sharedPrefs = screen.getSharedPreferences();
            for (int i = 0; i < screen.getPreferenceCount(); i++) {
                initPreferencesSummary(sharedPrefs, screen.getPreference(i));
            }
        }
    }

    /**
     * Initialize summary for a single preference (recursively for categories)
     */
    protected void initPreferencesSummary(SharedPreferences sharedPreferences, Preference p) {
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
                    Intent intent = currentRingtonePreference.createRingtonePickerIntent();
                    ringtonePickerLauncher.launch(intent);
                    return true;
                });
            }

            // Add change listener for SeekBarPreference to show percentage
            if (p instanceof SeekBarPreference) {
                String key = p.getKey();
                if (key != null && (key.equals(getString(R.string._pref_key_warn_battery_level)) ||
                                   key.equals(getString(R.string._pref_key_critical_battery_level)))) {
                    p.setOnPreferenceChangeListener((pref, newValue) -> {
                        if (newValue instanceof Integer) {
                            pref.setSummary(newValue + "%");
                        }
                        return true;
                    });
                }
            }
        }
    }
}
