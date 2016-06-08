package com.almothafar.simplebatterynotifier.ui;

import android.annotation.TargetApi;
import android.content.SharedPreferences;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.MultiSelectListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.RingtonePreference;

import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.ui.widgets.NumberPickerPreference;
import com.almothafar.simplebatterynotifier.ui.widgets.TimePickerPreference;

import java.util.Set;

/**
 * Created by Al-Mothafar on 25/08/2015.
 */
@TargetApi(Build.VERSION_CODES.HONEYCOMB)
public class GenericPreferenceFragment extends PreferenceFragment implements SharedPreferences.OnSharedPreferenceChangeListener {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String category = getArguments().getString("category");
        if (category != null) {
            if (category.equals(getString(R.string.pref_category_general))) {
                addPreferencesFromResource(R.xml.pref_general);
            } else if (category.equals(getString(R.string.pref_category_notifications))) {
                addPreferencesFromResource(R.xml.pref_notification);
            } else if (category.equals(getString(R.string.pref_category_time_settings))) {
                addPreferencesFromResource(R.xml.pref_time_settings);
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        // Set up a listener whenever a key changes
        getPreferenceScreen().getSharedPreferences()
                .registerOnSharedPreferenceChangeListener(this);

        initSummary();
    }

    @Override
    public void onPause() {
        super.onPause();

        // Unregister the listener whenever a key changes
        getPreferenceScreen().getSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
                                          String key) {

        //update summary
        updatePreferencesSummary(sharedPreferences, findPreference(key));
    }

    /**
     * Update summary
     *
     * @param sharedPreferences
     * @param pref
     */
    protected void updatePreferencesSummary(SharedPreferences sharedPreferences,
                                            Preference pref) {

        if (pref == null)
            return;

        if (pref instanceof ListPreference) {
            // List Preference
            ListPreference listPref = (ListPreference) pref;
            listPref.setSummary(listPref.getEntry());
        } else if (pref instanceof EditTextPreference) {
            // EditPreference
            EditTextPreference editTextPref = (EditTextPreference) pref;
            editTextPref.setSummary(editTextPref.getText());
        } else if (pref instanceof MultiSelectListPreference) {
            // MultiSelectList Preference
            MultiSelectListPreference mlistPref = (MultiSelectListPreference) pref;
            String summaryMListPref = "";
            String and = "";

            // Retrieve values
            Set<String> values = mlistPref.getValues();
            for (String value : values) {
                // For each value retrieve index
                int index = mlistPref.findIndexOfValue(value);
                // Retrieve entry from index
                CharSequence mEntry = index >= 0
                        && mlistPref.getEntries() != null ? mlistPref
                        .getEntries()[index] : null;
                if (mEntry != null) {
                    // add summary
                    summaryMListPref = summaryMListPref + and + mEntry;
                    and = ";";
                }
            }
            // set summary
            mlistPref.setSummary(summaryMListPref);

        } else if (pref instanceof RingtonePreference) {
            // RingtonePreference
            RingtonePreference rtPref = (RingtonePreference) pref;
            String uri;
            if (rtPref != null) {
                uri = sharedPreferences.getString(rtPref.getKey(), null);
                if (uri != null) {
                    Ringtone ringtone = RingtoneManager.getRingtone(
                            pref.getContext(), Uri.parse(uri));
                    pref.setSummary(ringtone.getTitle(pref.getContext()));
                }
            }

        } else if (pref instanceof NumberPickerPreference) {
            // My NumberPicker Preference
            NumberPickerPreference nPickerPref = (NumberPickerPreference) pref;
            nPickerPref.setSummary(nPickerPref.getValue());
        } else if (pref instanceof TimePickerPreference) {
            // My NumberPicker Preference
            TimePickerPreference tPickerPref = (TimePickerPreference) pref;
            tPickerPref.setSummary(tPickerPref.getSummary());
        }
    }

    /*
     * Init summary
	 */
    protected void initSummary() {
        for (int i = 0; i < getPreferenceScreen().getPreferenceCount(); i++) {
            initPreferencesSummary(getPreferenceManager().getSharedPreferences(),
                    getPreferenceScreen().getPreference(i));
        }
    }

    /*
     * Init single Preference
     */
    protected void initPreferencesSummary(SharedPreferences sharedPreferences,
                                          Preference p) {
        if (p instanceof PreferenceCategory) {
            PreferenceCategory pCat = (PreferenceCategory) p;
            for (int i = 0; i < pCat.getPreferenceCount(); i++) {
                initPreferencesSummary(sharedPreferences, pCat.getPreference(i));
            }
        } else {
            updatePreferencesSummary(sharedPreferences, p);
            if (p instanceof RingtonePreference)
                p.setOnPreferenceChangeListener(
                        new RingToneOnPreferenceChangeListener());
        }
    }

    class RingToneOnPreferenceChangeListener implements Preference.OnPreferenceChangeListener {

        @Override
        public boolean onPreferenceChange(Preference pref, Object newValue) {
            if (newValue != null && newValue instanceof String) {
                String uri = (String) newValue;
                Ringtone ringtone = RingtoneManager.getRingtone(pref.getContext(), Uri.parse(uri));
                pref.setSummary(ringtone.getTitle(pref.getContext()));
            }
            return true;
        }
    }


        /* //Bind the summaries of EditText/List/Dialog/Ringtone preferences
        // to their values. When their values change, their summaries are
        // updated to reflect the new value, per the Android Design
        // guidelines.
       bindPreferenceSummaryToValue(findPreference("example_text"));
        bindPreferenceSummaryToValue(findPreference("example_list"));*/

}