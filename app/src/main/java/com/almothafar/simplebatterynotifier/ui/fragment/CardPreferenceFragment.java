package com.almothafar.simplebatterynotifier.ui.fragment;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceFragmentCompat;

import com.almothafar.simplebatterynotifier.ui.preference.PreferenceCardDecoration;

import java.util.Set;

/**
 * Base for the app's preference screens: applies the card-group (Material You) look to the list once
 * its view exists (#222). A screen with a footer or other preference that should sit on its own card
 * overrides {@link #cardBreakKeys()}.
 * <p>
 * The styling runs in onViewCreated, not onCreateRecyclerView, because clearing the divider touches
 * the fragment's list, which isn't assigned until the view is created.
 */
public abstract class CardPreferenceFragment extends PreferenceFragmentCompat {

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		PreferenceCardDecoration.apply(this, getListView(), cardBreakKeys());
	}

	/**
	 * @return preference keys that should each start their own card (e.g. a footer); empty by default
	 */
	protected Set<String> cardBreakKeys() {
		return Set.of();
	}
}
