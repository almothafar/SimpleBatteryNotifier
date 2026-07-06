package com.almothafar.simplebatterynotifier.ui.fragment;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.model.BatteryDO;
import com.almothafar.simplebatterynotifier.service.BatteryHealthTracker;
import com.almothafar.simplebatterynotifier.service.SystemService;
import com.almothafar.simplebatterynotifier.util.GeneralHelper;
import com.almothafar.simplebatterynotifier.util.TemperatureUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Fragment displaying detailed battery information in a table layout
 */
public class BatteryDetailsFragment extends Fragment {

	private static final String TAG = "BatteryDetailsFragment";

	// Scroll hint (#75): a one-time bob showing the details table scrolls. All tunable.
	private static final long SCROLL_HINT_DELAY_MS = 1300L;   // wait before the first bob
	private static final long SCROLL_HINT_BOB_MS = 2300L;     // duration of one down+up bob
	private static final int SCROLL_HINT_BOB_COUNT = 2;       // how many bobs
	private static final int SCROLL_HINT_REVEAL_DP = 96;      // max peek distance

	private BatteryDO batteryDO;
	private Map<String, String> valuesMap;
	private View viewRef;

	// #94: when this device's charge counter can't be trusted, the Capacity row shows "Unknown" with a
	// tappable info icon instead of a misleading mAh figure. Computed in fillBatteryInfo, applied by
	// createDetailsTable to just the capacity row.
	private boolean capacityUnreliable;
	private String capacityLabel;

	/**
	 * Default constructor required for fragment instantiation
	 */
	public BatteryDetailsFragment() {
		// Required empty public constructor
	}

	/**
	 * Create and initialize the fragment view
	 *
	 * @param inflater           The LayoutInflater to inflate views
	 * @param container          The parent view container
	 * @param savedInstanceState Saved state bundle
	 *
	 * @return The created view
	 */
	@Override
	public View onCreateView(final LayoutInflater inflater,
	                         final ViewGroup container,
	                         final Bundle savedInstanceState) {
		final View view = inflater.inflate(R.layout.fragment_battery_details, container, false);
		this.viewRef = view;
		batteryDO = SystemService.getBatteryInfo(view.getContext());

		// CRITICAL: Check for null batteryDO
		if (isNull(batteryDO)) {
			Log.w(TAG, "Unable to retrieve battery information");
		} else {
			createDetailsTable(view);
		}

		maybeShowScrollHint(view);

		return view;
	}

	/**
	 * Create and populate the battery details table
	 *
	 * @param view The fragment view containing the table
	 */
	public void createDetailsTable(final View view) {
		fillBatteryInfo(view);

		final TableLayout tableLayout = view.findViewById(R.id.batteryDetailsTable);
		tableLayout.removeAllViews();
		tableLayout.setStretchAllColumns(true);
		final int cellPadding = getResources().getDimensionPixelSize(R.dimen.battery_details_cell_padding);
		final int cellPaddingTop = getResources().getDimensionPixelSize(R.dimen.battery_details_cell_padding_top);

		int rowIndex = 0;
		for (final String key : valuesMap.keySet()) {
			// Only the capacity row gets the "unreliable reading" info affordance (#94).
			final boolean unreliable = capacityUnreliable && key.equals(capacityLabel);
			final TableRow row = createTableRow(view, key, valuesMap.get(key), cellPadding, cellPaddingTop, unreliable);
			tableLayout.addView(row, rowIndex);
			rowIndex++;
		}
	}

	/**
	 * Update the battery details with new data
	 *
	 * @param batteryDO The new battery data object
	 */
	public void updateBatteryDetails(final BatteryDO batteryDO) {
		this.batteryDO = batteryDO;
		if (nonNull(viewRef) && nonNull(batteryDO)) {
			this.createDetailsTable(viewRef);
		}
	}

	/**
	 * #75: on first display, gently bob the details list down and back up when rows sit below the
	 * fold — a short motion cue that (with the always-on fading edge) shows the table scrolls. Bobs
	 * {@link #SCROLL_HINT_BOB_COUNT} times; the first touch cancels it so it never fights the user,
	 * and it is skipped when the system "remove animations" setting is on.
	 *
	 * @param root The fragment view containing the scroll view
	 */
	@SuppressLint("ClickableViewAccessibility")
	private void maybeShowScrollHint(final View root) {
		final ScrollView scroll = root.findViewById(R.id.batteryDetailsScroll);
		if (isNull(scroll)) {
			return;
		}
		// Respect the accessibility "remove animations" setting.
		final float animScale = Settings.Global.getFloat(root.getContext().getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
		if (animScale == 0f) {
			return;
		}
		// Delay so the screen settles first, then the bob reads as intentional (not a load glitch).
		scroll.postDelayed(() -> {
			final View content = scroll.getChildAt(0);
			if (isNull(content)) {
				return;
			}
			final int hiddenPx = content.getHeight() - scroll.getHeight();
			if (hiddenPx <= 0) {
				return; // nothing below the fold — no hint needed
			}
			final int reveal = Math.min(hiddenPx, Math.round(SCROLL_HINT_REVEAL_DP * scroll.getResources().getDisplayMetrics().density));
			// Peek down to `reveal` and back, repeated SCROLL_HINT_BOB_COUNT times.
			final ObjectAnimator hint = ObjectAnimator.ofInt(scroll, "scrollY", 0, reveal, 0);
			hint.setDuration(SCROLL_HINT_BOB_MS);
			hint.setRepeatCount(SCROLL_HINT_BOB_COUNT - 1);
			hint.setInterpolator(new AccelerateDecelerateInterpolator());

			// Never fight the user: the first touch cancels the hint and hands scrolling back.
			scroll.setOnTouchListener((v, event) -> {
				hint.cancel();
				return false; // don't consume — let the ScrollView scroll normally
			});
			hint.addListener(new AnimatorListenerAdapter() {
				@Override
				public void onAnimationEnd(final Animator animation) {
					scroll.setOnTouchListener(null);
				}
			});
			hint.start();
		}, SCROLL_HINT_DELAY_MS);
	}

	/**
	 * Create a single table row with label, separator, and value
	 *
	 * @param view            The fragment view
	 * @param label           The label text
	 * @param value           The value text
	 * @param cellPadding     The horizontal cell padding
	 * @param cellPaddingTop  The top cell padding
	 * @param valueUnreliable When true, decorate the value cell with a tappable "unreliable reading" icon
	 *
	 * @return The created table row
	 */
	private TableRow createTableRow(final View view, final String label, final String value,
	                                final int cellPadding, final int cellPaddingTop, final boolean valueUnreliable) {
		final TableRow row = new TableRow(view.getContext());
		final TableRow.LayoutParams layoutParams = new TableRow.LayoutParams(
				TableRow.LayoutParams.MATCH_PARENT, TableRow.LayoutParams.WRAP_CONTENT, 1.0f);
		row.setLayoutParams(layoutParams);
		row.setWeightSum(3);

		// Enable proper RTL layout support
		ViewCompat.setLayoutDirection(row, ViewCompat.LAYOUT_DIRECTION_LOCALE);

		final TextView textViewLabel = createLabelTextView(view, label, cellPadding, cellPaddingTop);
		final TextView textViewSep = createSeparatorTextView(view);
		final TextView textViewValue = createValueTextView(view, value, cellPadding, cellPaddingTop, valueUnreliable);

		// Add views in logical order (label -> separator -> value)
		// RTL languages will automatically reverse the visual order
		row.addView(textViewLabel);
		row.addView(textViewSep);
		row.addView(textViewValue);

		return row;
	}

	/**
	 * Create a label TextView
	 *
	 * @param view           The fragment view
	 * @param text           The label text
	 * @param cellPadding    The horizontal cell padding
	 * @param cellPaddingTop The top cell padding
	 *
	 * @return The created TextView
	 */
	private TextView createLabelTextView(final View view, final String text,
	                                     final int cellPadding, final int cellPaddingTop) {
		final TextView textView = new TextView(view.getContext());
		textView.setTextAppearance(R.style.DefaultTextStyle);
		textView.setText(text);
		textView.setGravity(Gravity.END);
		textView.setTextColor(GeneralHelper.getColor(getResources(), R.color.battery_details_label_color));
		textView.setPadding(0, 0, cellPadding, cellPaddingTop);
		return textView;
	}

	/**
	 * Create a separator TextView (colon)
	 * <p>
	 * Uses string resource for proper internationalization and RTL language support.
	 *
	 * @param view The fragment view
	 *
	 * @return The created TextView
	 */
	private TextView createSeparatorTextView(final View view) {
		final TextView textView = new TextView(view.getContext());
		textView.setText(getString(R.string.battery_details_separator));
		textView.setGravity(Gravity.CENTER);
		textView.setTextColor(GeneralHelper.getColor(getResources(), R.color.battery_details_label_color));
		return textView;
	}

	/**
	 * Create a value TextView
	 *
	 * @param view           The fragment view
	 * @param text           The value text
	 * @param cellPadding    The horizontal cell padding
	 * @param cellPaddingTop The top cell padding
	 * @param unreliable     When true, append a tappable amber info icon that opens the "unreliable
	 *                       reading" explanation (#94)
	 *
	 * @return The created TextView
	 */
	private TextView createValueTextView(final View view,
	                                     final String text,
	                                     final int cellPadding,
	                                     final int cellPaddingTop,
	                                     final boolean unreliable) {
		final TextView textView = new TextView(view.getContext());
		textView.setTextAppearance(R.style.DefaultTextStyle);
		textView.setText(text);
		textView.setTextColor(GeneralHelper.getColor(getResources(), R.color.battery_details_value_color));
		textView.setGravity(Gravity.START);
		textView.setPadding(cellPadding, 0, 0, cellPaddingTop);
		if (unreliable) {
			// #94: amber info affordance after the value; tap to learn why the reading can't be trusted.
			textView.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_info_amber, 0);
			textView.setCompoundDrawablePadding((int) (6 * getResources().getDisplayMetrics().density));
			textView.setContentDescription(getString(R.string.battery_reading_unreliable_cd));
			textView.setOnClickListener(v -> showCapacityUnreliableDialog());
		}
		return textView;
	}

	/**
	 * Fill the battery information map with current battery data
	 *
	 * @param view The fragment view
	 */
	private void fillBatteryInfo(final View view) {
		valuesMap = new LinkedHashMap<>();

		valuesMap.put(getResources().getString(R.string.technology), batteryDO.getTechnology());

		// Capacity is an estimate from BatteryManager. Show "Unknown" rather than "0 mAh" when the device
		// doesn't report the charge counter, and also when the reported figure can't be trusted (#94) —
		// in that case createDetailsTable adds a tappable info icon explaining why.
		final int capacity = batteryDO.getCapacity();
		capacityUnreliable = BatteryHealthTracker.isBatteryReadingUnreliable(view.getContext());
		final String capacityText = (capacity > 0 && !capacityUnreliable)
		                            ? capacity + " mAh"
		                            : getResources().getString(R.string.unknown);
		capacityLabel = getResources().getString(R.string.capacity);
		valuesMap.put(capacityLabel, capacityText);

		// Show the user-entered design (rated) capacity when set (issue #32)
		final int designCapacity = BatteryHealthTracker.getDesignCapacity(view.getContext());
		if (designCapacity > 0) {
			valuesMap.put(getResources().getString(R.string.design_capacity),
					getResources().getString(R.string.design_capacity_value, designCapacity));
		}

		// Add charge cycles from the battery health tracker - positioned right after capacity
		final int chargeCycles = BatteryHealthTracker.getEffectiveCycleCount(view.getContext());
		valuesMap.put(getResources().getString(R.string.charge_cycles), String.valueOf(chargeCycles));

		valuesMap.put(getResources().getString(R.string.voltage), batteryDO.getVoltage() + " mV");
		valuesMap.put(getResources().getString(R.string.power_source), batteryDO.getPowerSource());
		valuesMap.put(getResources().getString(R.string.temperature),
				TemperatureUtils.format(view.getContext(), batteryDO.getTemperature()));
		valuesMap.put(getResources().getString(R.string.health), batteryDO.getHealth());
	}

	/**
	 * Explains why the capacity (and the health figure derived from it) can't be trusted on this device,
	 * including the possibility that the battery is genuinely wearing out (#94). Shares its wording with
	 * the battery insights screen.
	 */
	private void showCapacityUnreliableDialog() {
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.battery_reading_unreliable_dialog_title)
				.setMessage(R.string.battery_reading_unreliable_dialog_message)
				.setPositiveButton(android.R.string.ok, null)
				.show();
	}
}
