package com.almothafar.simplebatterynotifier.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.almothafar.simplebatterynotifier.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Shared "About the app" dialog and app-version lookup.
 * <p>
 * Used from two entry points: the main screen's overflow menu (#136) and the version footer at
 * the bottom of the Settings root screen (#113), so both stay one implementation.
 */
public final class AboutDialog {

	private static final String TAG = "AboutDialog";

	private AboutDialog() {
	}

	/**
	 * Show the "About the app" dialog: what the app is, the current version, developer credit,
	 * the open-source license, and a short accuracy/warranty note.
	 *
	 * @param activity The host activity
	 */
	public static void show(final Activity activity) {
		final View content = activity.getLayoutInflater().inflate(R.layout.dialog_about, null);

		final TextView versionView = content.findViewById(R.id.aboutVersion);
		versionView.setText(activity.getString(R.string.about_version, appVersionName(activity)));

		final TextView developerView = content.findViewById(R.id.aboutDeveloper);
		developerView.setText(activity.getString(R.string.about_developer, activity.getString(R.string.developer_name)));

		new MaterialAlertDialogBuilder(activity)
				.setView(content)
				.setPositiveButton(android.R.string.ok, null)
				.setNeutralButton(R.string.about_view_github, (dialog, which) -> openProjectPage(activity))
				.show();
	}

	/**
	 * The app's version name (e.g. "2.0.71"), or an empty string if it can't be read.
	 *
	 * @param context Any context of this app
	 */
	public static String appVersionName(final Context context) {
		try {
			return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
		} catch (PackageManager.NameNotFoundException e) {
			// The app can always resolve its own package, so this is effectively unreachable;
			// log it rather than swallow silently, and degrade to an empty version string.
			Log.w(TAG, "Unable to read app version name", e);
			return "";
		}
	}

	/**
	 * Open the project's GitHub page in a browser.
	 */
	private static void openProjectPage(final Activity activity) {
		final Uri uri = Uri.parse(activity.getString(R.string.about_github_url));
		try {
			activity.startActivity(new Intent(Intent.ACTION_VIEW, uri));
		} catch (ActivityNotFoundException e) {
			Toast.makeText(activity, R.string.no_browser_found, Toast.LENGTH_SHORT).show();
		}
	}
}
