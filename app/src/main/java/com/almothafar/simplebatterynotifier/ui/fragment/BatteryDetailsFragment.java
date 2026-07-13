package com.almothafar.simplebatterynotifier.ui.fragment;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
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
import com.almothafar.simplebatterynotifier.service.BatteryRateTracker;
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

	// #108: the drain-rate row's label, and the colour applied to its value cell (amber near the user's
	// limit, red at/above it, while discharging; 0 = no special colour). Both set in fillBatteryInfo and
	// applied by createDetailsTable to just that row, mirroring the capacity special-case above.
	private String rateLabel;
	private int rateValueColor;

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
		// Colon-aligned rows with the divider near the horizontal centre (#96): stretch BOTH the label (0)
		// and value (2) columns so they share the width evenly, and the end-aligned labels' colons line up
		// around the middle in both LTR and RTL (rather than wherever the widest label happens to end).
		// Locale direction orders columns right-to-left in Arabic; long labels/values shrink rather than clip.
		ViewCompat.setLayoutDirection(tableLayout, ViewCompat.LAYOUT_DIRECTION_LOCALE);
		tableLayout.setColumnStretchable(0, true);
		tableLayout.setColumnStretchable(1, false);
		tableLayout.setColumnStretchable(2, true);
		tableLayout.setColumnShrinkable(0, true);
		tableLayout.setColumnShrinkable(2, true);
		final int cellPadding = getResources().getDimensionPixelSize(R.dimen.battery_details_cell_padding);
		final int cellPaddingTop = getResources().getDimensionPixelSize(R.dimen.battery_details_cell_padding_top);

		int rowIndex = 0;
		for (final String key : valuesMap.keySet()) {
			// Only the capacity row gets the "unreliable reading" info affordance (#94).
			final boolean unreliable = capacityUnreliable && key.equals(capacityLabel);
			// Only the drain-rate row is coloured (amber/red near the limit while discharging) — #108.
			final int valueColor = (rateValueColor != 0 && key.equals(rateLabel)) ? rateValueColor : 0;
			final TableRow row = createTableRow(view, key, valuesMap.get(key), cellPadding, cellPaddingTop, unreliable, valueColor);
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
	 * @param valueColor      Colour for the value text, or 0 to keep the default value colour (#108)
	 *
	 * @return The created table row
	 */
	private TableRow createTableRow(final View view, final String label, final String value,
	                                final int cellPadding, final int cellPaddingTop,
	                                final boolean valueUnreliable, final int valueColor) {
		final TableRow row = new TableRow(view.getContext());
		final TableRow.LayoutParams layoutParams = new TableRow.LayoutParams(
				TableRow.LayoutParams.MATCH_PARENT, TableRow.LayoutParams.WRAP_CONTENT, 1.0f);
		row.setLayoutParams(layoutParams);
		row.setWeightSum(3);

		// Enable proper RTL layout support
		ViewCompat.setLayoutDirection(row, ViewCompat.LAYOUT_DIRECTION_LOCALE);

		final TextView textViewLabel = createLabelTextView(view, label, cellPadding, cellPaddingTop);
		final TextView textViewSep = createSeparatorTextView(view);
		final TextView textViewValue = createValueTextView(view, value, cellPadding, cellPaddingTop, valueUnreliable, valueColor);

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
		// Right-align the label against the colon (END) so labels form a fixed column and the colons line
		// up; relative padding + locale text direction keep the gap on the correct side in RTL (#96).
		textView.setGravity(Gravity.END);
		textView.setTextDirection(View.TEXT_DIRECTION_LOCALE);
		textView.setTextColor(GeneralHelper.getColor(getResources(), R.color.battery_details_label_color));
		textView.setPaddingRelative(0, 0, cellPadding, cellPaddingTop);
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
	 * @param valueColor     Colour for the value text, or 0 to keep the default value colour (#108)
	 *
	 * @return The created TextView
	 */
	private TextView createValueTextView(final View view,
	                                     final String text,
	                                     final int cellPadding,
	                                     final int cellPaddingTop,
	                                     final boolean unreliable,
	                                     final int valueColor) {
		final TextView textView = new TextView(view.getContext());
		textView.setTextAppearance(R.style.DefaultTextStyle);
		textView.setText(text);
		textView.setTextColor(valueColor != 0
		                      ? valueColor
		                      : GeneralHelper.getColor(getResources(), R.color.battery_details_value_color));
		// Start-align + relative padding + locale text direction so numeric (Latin) values sit right after
		// the colon like the Arabic text values do, instead of flying to the far edge in RTL (#96).
		textView.setGravity(Gravity.START);
		textView.setTextDirection(View.TEXT_DIRECTION_LOCALE);
		textView.setPaddingRelative(cellPadding, 0, 0, cellPaddingTop);
		if (unreliable) {
			// #94: amber warning affordance after the value; tap to learn why the reading can't be trusted.
			// Same mark as the insights health figure, so the two screens read consistently.
			textView.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_warning_amber, 0);
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

		// #108: live charge/drain rate and signed current at the very top, each shown only when its
		// reading is trustworthy. Recording here also feeds the smoothing window from the foreground
		// refresh (the other feed is the battery broadcast) without any polling timer of our own.
		addRateRows(view);

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
			// Western digits (0-9) in every locale — see design_capacity_value / #96.
			valuesMap.put(getResources().getString(R.string.design_capacity),
					getResources().getString(R.string.design_capacity_value, String.valueOf(designCapacity)));
		}

		// Add charge cycles from the battery health tracker - positioned right after capacity
		final int chargeCycles = BatteryHealthTracker.getEffectiveCycleCount(view.getContext());
		valuesMap.put(getResources().getString(R.string.charge_cycles), String.valueOf(chargeCycles));

		valuesMap.put(getResources().getString(R.string.voltage), batteryDO.getVoltage() + " mV");
		valuesMap.put(getResources().getString(R.string.power_source), batteryDO.getPowerSource());
		valuesMap.put(getResources().getString(R.string.temperature),
				TemperatureUtils.format(view.getContext(), batteryDO.getTemperature()));
		valuesMap.put(getResources().getString(R.string.battery_condition), batteryDO.getHealth());
	}

	/**
	 * Adds the drain/charge rate and signed-current rows at the top of the table (#108).
	 * <p>
	 * Each row appears only when its reading is trustworthy (independent gating like #94): the rate hides
	 * during the brief post-unplug warm-up or a static level, the current hides when the device doesn't
	 * report a plausible mA. The label flips between "Drain rate" and "Charge rate" with direction, and
	 * the rate value is coloured amber/red near the user's limit while discharging (see {@link #rateColor}).
	 *
	 * @param view The fragment view
	 */
	private void addRateRows(final View view) {
		rateLabel = null;
		rateValueColor = 0;

		final BatteryRateTracker.BatteryRate rate = BatteryRateTracker.record(view.getContext(), batteryDO);

		if (rate.hasRate()) {
			rateLabel = getResources().getString(rate.charging() ? R.string.charge_rate : R.string.drain_rate);
			valuesMap.put(rateLabel, BatteryRateTracker.formatRateValue(view.getContext(), rate.percentPerHour()));
			rateValueColor = rateColor(view.getContext(), rate);
		}
		addTimeToFullRow(view, rate);
		if (rate.hasCurrent()) {
			valuesMap.put(getResources().getString(R.string.battery_current),
					BatteryRateTracker.formatCurrentValue(view.getContext(), rate.currentMilliAmps()));
		}
	}

	/**
	 * Adds the estimated time-to-full row directly below the charge rate it is derived from (#124). Shown
	 * only while charging, with a trustworthy rate, and below the taper top (level &lt; 99%) — hidden
	 * otherwise, so the user never sees a garbage "0m" or a wildly optimistic figure right as the charge
	 * tapers. The estimate is a rough linear projection (see
	 * {@link BatteryRateTracker#estimateMinutesToFull}); precision is a non-goal — the OS owns the accurate
	 * number. Reuses the already-computed {@code rate}; only the level is read here.
	 *
	 * @param view The fragment view
	 * @param rate The already-computed rate for this refresh
	 */
	private void addTimeToFullRow(final View view, final BatteryRateTracker.BatteryRate rate) {
		if (!rate.charging() || !rate.hasRate()) {
			return;
		}
		final int level = Math.round(batteryDO.getBatteryPercentage());
		if (level >= 99) {
			return;
		}
		final int minutes = BatteryRateTracker.estimateMinutesToFull(level, rate.percentPerHour());
		if (minutes <= 0) {
			return;
		}
		valuesMap.put(getResources().getString(R.string.time_to_full),
				BatteryRateTracker.formatTimeToFull(view.getContext(), minutes));
	}

	/**
	 * The colour for the drain-rate value: red at/above the user's limit, amber as it approaches (derived
	 * just below the limit), otherwise the default. Charging is left uncoloured in v1 — its context traps
	 * (thermal throttling, deliberate trickle near 100%) make a single limit misleading (#108).
	 *
	 * @param context The context for resolving colours and the limit preference
	 * @param rate    The computed rate
	 *
	 * @return a colour int, or 0 to keep the default value colour
	 */
	private int rateColor(final Context context, final BatteryRateTracker.BatteryRate rate) {
		if (rate.charging()) {
			return 0;
		}
		final int limit = BatteryRateTracker.getDrainLimitPercentPerHour(context);
		if (rate.percentPerHour() >= limit) {
			return GeneralHelper.getColor(getResources(), R.color.battery_rate_high);
		}
		if (rate.percentPerHour() >= BatteryRateTracker.amberThreshold(limit)) {
			return GeneralHelper.getColor(getResources(), R.color.battery_rate_warn);
		}
		return 0;
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
