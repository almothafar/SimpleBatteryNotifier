package com.almothafar.simplebatterynotifier.ui;

import android.os.Bundle;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

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

	/**
	 * Pad the bottom of a scrolling container by the system navigation-bar inset so its content
	 * is not hidden behind the navigation bar. Android 15+ (targetSdk 35+) enforces edge-to-edge,
	 * so without this a long list draws underneath the nav bar.
	 * <p>
	 * Any padding already set on the view is preserved (the inset is added on top of it).
	 *
	 * @param view The scrolling container to inset (no-op if null)
	 */
	protected void applyBottomSystemBarInset(final View view) {
		if (view == null) {
			return;
		}
		final int initialBottom = view.getPaddingBottom();
		ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
			final Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
			v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), initialBottom + bars.bottom);
			return insets;
		});
	}
}
