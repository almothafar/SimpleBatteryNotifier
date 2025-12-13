package com.almothafar.simplebatterynotifier.ui.fragment;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.fragment.app.Fragment;
import com.almothafar.simplebatterynotifier.R;

/**
 * Reusable fragment displaying developer signature
 * Can be included in any activity layout
 */
public class SignatureFragment extends Fragment {

	private static final String TAG = "SignatureFragment";

	/**
	 * Default constructor required for fragment instantiation
	 */
	public SignatureFragment() {
		// Required empty public constructor
	}

	/**
	 * Create and initialize the fragment view
	 *
	 * @param inflater           The LayoutInflater to inflate views
	 * @param container          The parent view container
	 * @param savedInstanceState Saved state bundle
	 * @return The created view
	 */
	@Override
	public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
	                         final Bundle savedInstanceState) {
		final View view = inflater.inflate(R.layout.fragment_signature, container, false);

		// Set up click listener to open developer link
		view.findViewById(R.id.developerSignatureLayout).setOnClickListener(v -> openDeveloperLink());

		return view;
	}

	/**
	 * Open the developer's link (GitHub/website)
	 */
	private void openDeveloperLink() {
		try {
			String link = getString(R.string.developer_link);
			if (!link.startsWith("http://") && !link.startsWith("https://")) {
				link = "https://" + link;
			}
			final Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
			startActivity(browserIntent);
		} catch (final Exception e) {
			Log.e(TAG, "Error opening developer link", e);
		}
	}
}
