package com.almothafar.simplebatterynotifier.ui;

import android.os.Bundle;
import android.view.MenuItem;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.ui.fragment.CardPreferenceFragment;

import java.util.Set;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Modern Settings Activity using AndroidX Preferences
 * Provides hierarchical settings navigation with preference fragments
 */
public class SettingsActivity extends BaseActivity implements PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {
	private static final String TITLE_TAG = "settingsActivityTitle";

	/**
	 * Save the activity state including the current title
	 *
	 * @param outState Bundle to save state into
	 */
	@Override
	public void onSaveInstanceState(@NonNull final Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putCharSequence(TITLE_TAG, getTitle());
	}

	/**
	 * Handle support navigation up action
	 *
	 * @return True if navigation was handled
	 */
	@Override
	public boolean onSupportNavigateUp() {
		if (getSupportFragmentManager().popBackStackImmediate()) {
			return true;
		}
		return super.onSupportNavigateUp();
	}

	/**
	 * Handle options menu item selection
	 *
	 * @param item The selected menu item
	 * @return True if item was handled
	 */
	@Override
	public boolean onOptionsItemSelected(final MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			getOnBackPressedDispatcher().onBackPressed();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	/**
	 * Handle preference fragment navigation
	 *
	 * @param caller The calling preference fragment
	 * @param pref   The preference that was clicked
	 * @return True if fragment navigation was handled
	 */
	@Override
	public boolean onPreferenceStartFragment(@NonNull final PreferenceFragmentCompat caller, final Preference pref) {
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

	/**
	 * Initialize the settings activity
	 *
	 * @param savedInstanceState Saved state bundle
	 */
	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.activity_settings);

		// Set up the toolbar with back button
		final Toolbar toolbar = findViewById(R.id.toolbar);
		setupToolbar(toolbar, true);

		// Keep the (potentially long) preference list clear of the system navigation bar.
		applyBottomSystemBarInset(findViewById(R.id.settings_container));

		// setStatusBarColor()/setNavigationBarColor() removed (deprecated in API 35).
		// Edge-to-edge is enforced by the platform on Android 15+; system-bar insets are handled
		// in BaseActivity. System bar colors should be set via themes (values/themes.xml).

		// Load root preferences if this is the first creation
		if (isNull(savedInstanceState)) {
			// Set the title via the app's (locale-aware) resources. Without this, the toolbar falls back
			// to the manifest android:label, which the framework resolves in the SYSTEM locale — showing an
			// English "Settings" under an Arabic app until the back stack next changes (#98).
			setTitle(R.string.title_activity_settings);
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
	public static class HeaderFragment extends CardPreferenceFragment {
		/**
		 * The About/Version footer shares the root screen's parent with the nav entries, so force it
		 * onto its own card instead of joining their group (#222).
		 *
		 * @return the About footer key, so it starts its own card
		 */
		@Override
		protected Set<String> cardBreakKeys() {
			return Set.of(getString(R.string._pref_key_about));
		}

		/**
		 * Create the preference hierarchy from XML
		 *
		 * @param savedInstanceState Saved state bundle
		 * @param rootKey            The root key for preferences
		 */
		@Override
		public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
			setPreferencesFromResource(R.xml.pref_headers_root, rootKey);

			// Version footer (#113): show the running app version at the bottom of the root
			// screen; tapping it opens the shared About dialog (same one as the main menu, #136).
			final Preference about = findPreference(getString(R.string._pref_key_about));
			if (nonNull(about)) {
				about.setTitle(getString(R.string.about_version, AboutDialog.appVersionName(requireContext())));
				about.setOnPreferenceClickListener(pref -> {
					AboutDialog.show(requireActivity());
					return true;
				});
			}
		}
	}
}
