package com.almothafar.simplebatterynotifier.ui;

import android.os.Bundle;
import android.view.MenuItem;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.WindowCompat;
import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import com.almothafar.simplebatterynotifier.R;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Modern Settings Activity using AndroidX Preferences
 */
public class SettingsActivity extends AppCompatActivity implements PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {
	private static final String TITLE_TAG = "settingsActivityTitle";

	@Override
	public void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putCharSequence(TITLE_TAG, getTitle());
	}

	@Override
	public boolean onSupportNavigateUp() {
		if (getSupportFragmentManager().popBackStackImmediate()) {
			return true;
		}
		return super.onSupportNavigateUp();
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			getOnBackPressedDispatcher().onBackPressed();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	public boolean onPreferenceStartFragment(@NonNull PreferenceFragmentCompat caller, Preference pref) {
		// Instantiate the new Fragment
		final Bundle args = pref.getExtras();
		final Fragment fragment = getSupportFragmentManager().getFragmentFactory()
		                                                     .instantiate(getClassLoader(), pref.getFragment());
		fragment.setArguments(args);
		// setTargetFragment() removed - deprecated API, no longer needed for fragment navigation

		// Replace the existing Fragment with the new Fragment
		getSupportFragmentManager().beginTransaction()
		                           .replace(R.id.settings_container, fragment)
		                           .addToBackStack(null)
		                           .commit();
		setTitle(pref.getTitle());
		return true;
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Enable edge-to-edge display
		WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

		setContentView(R.layout.activity_settings);

		// Set up the toolbar
		Toolbar toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		final ActionBar supportActionBar = getSupportActionBar();
		if (nonNull(supportActionBar)) {
			supportActionBar.setDisplayHomeAsUpEnabled(true);
			supportActionBar.setDisplayShowHomeEnabled(true);
		}

		// setStatusBarColor() and setNavigationBarColor() removed - deprecated in API 35
		// Edge-to-edge is already enabled via WindowCompat.setDecorFitsSystemWindows()
		// System bar colors should be set in themes (values/themes.xml) instead

		// Load root preferences if this is the first creation
		if (isNull(savedInstanceState)) {
			getSupportFragmentManager()
					.beginTransaction()
					.replace(R.id.settings_container, new HeaderFragment())
					.commit();
		} else {
			setTitle(savedInstanceState.getCharSequence(TITLE_TAG));
		}

		getSupportFragmentManager().addOnBackStackChangedListener(() -> {
			if (getSupportFragmentManager().getBackStackEntryCount() == 0) {
				setTitle(R.string.title_activity_settings);
			}
		});
	}

	/**
	 * Root settings fragment showing preference categories
	 */
	public static class HeaderFragment extends PreferenceFragmentCompat {
		@Override
		public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
			setPreferencesFromResource(R.xml.pref_headers_root, rootKey);
		}
	}
}
