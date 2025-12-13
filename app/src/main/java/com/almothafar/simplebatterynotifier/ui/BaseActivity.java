package com.almothafar.simplebatterynotifier.ui;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

/**
 * Base activity providing common functionality for all activities
 * <p>
 * Features:
 * - Edge-to-edge display support
 * - Toolbar setup with consistent styling
 * - System window insets handling
 * <p>
 * Usage: All activities should extend this class instead of AppCompatActivity
 */
public abstract class BaseActivity extends AppCompatActivity {

	/**
	 * Handle back button in toolbar
	 * Subclasses can override this for custom back behavior
	 *
	 * @return true if back was handled, false otherwise
	 */
	@Override
	public boolean onSupportNavigateUp() {
		getOnBackPressedDispatcher().onBackPressed();
		return true;
	}

	/**
	 * Initialize the activity with common setup
	 * Subclasses should call super.onCreate() first
	 *
	 * @param savedInstanceState Saved a state bundle
	 */
	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Edge-to-edge display disabled - causes too many layout issues
		// Using standard window decorations instead
	}

	/**
	 * Set up the toolbar after the content view is set
	 * Call this method from subclass onCreate() after setContentView()
	 *
	 * @param toolbar The toolbar to set up
	 */
	protected void setupToolbar(final Toolbar toolbar) {
		if (toolbar != null) {
			setSupportActionBar(toolbar);
		}
	}

	/**
	 * Set up the toolbar with back button
	 * Call this method from subclass onCreate() after setContentView()
	 * Useful for secondary activities that need a back button
	 *
	 * @param toolbar        The toolbar to set up
	 * @param showBackButton Whether to show the back button
	 */
	protected void setupToolbar(final Toolbar toolbar, final boolean showBackButton) {
		setupToolbar(toolbar);
		if (showBackButton && getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
			getSupportActionBar().setDisplayShowHomeEnabled(true);
		}
	}
}
