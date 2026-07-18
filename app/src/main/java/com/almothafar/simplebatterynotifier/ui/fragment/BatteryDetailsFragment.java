package com.almothafar.simplebatterynotifier.ui.fragment;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
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

	// #173: the avg line under the Current value renders at this fraction of the cell's text size.
	private static final float AVG_LINE_SIZE_RATIO = 0.8f;

	// The value TextView is the third cell of every row (label, separator, value).
	private static final int VALUE_CELL_INDEX = 2;

	private BatteryDO batteryDO;
	private Map<String, CharSequence> valuesMap;
	private View viewRef;

	// The rows currently in the table, keyed by label (#161): each refresh updates the value cells in
	// place and only adds/removes rows whose keys changed, instead of rebuilding the whole table —
	// which lost accessibility focus every 3 s and churned allocations. Rebuilt with the view.
	private final Map<String, TableRow> rowViews = new LinkedHashMap<>();
	private TableLayout tableLayout;
	private int cellPadding;
	private int cellPaddingTop;

	// #94: when this device's charge counter can't be trusted, the Capacity row shows "Unknown" with a
	// tappable amber-warning icon instead of a misleading mAh figure. Computed in fillBatteryInfo, applied
	// by the row binder to just the capacity row.
	private boolean capacityUnreliable;
	private String capacityLabel;

	// Per-label value-cell decorations for this refresh, applied by the row binder (#161/#188). Keyed by
	// the row's current label (unique per row), so a row can be re-bound in place while its decoration
	// still tracks its live state.
	//   - valueColorByLabel: the drain-rate colour, amber near the user's limit / red at-or-above it while
	//     discharging (#108); absent = the default value colour.
	//   - pendingInfoByLabel: the rate/time/current rows stay present even when their value isn't ready yet
	//     (#188); such a row shows the "—" placeholder plus a tappable info icon, and this maps the label to
	//     its {dialogTitleRes, dialogMessageRes}.
	private final Map<String, Integer> valueColorByLabel = new LinkedHashMap<>();
	private final Map<String, int[]> pendingInfoByLabel = new LinkedHashMap<>();

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

		setupTable(view);

		// CRITICAL: Check for null batteryDO
		if (isNull(batteryDO)) {
			Log.w(TAG, "Unable to retrieve battery information");
		} else {
			refreshDetailsTable(view);
		}

		maybeShowScrollHint(view);

		return view;
	}

	/**
	 * One-time table setup for a freshly inflated view: column behaviour, cached paddings, and a clean
	 * row registry (the old view's rows are gone with it).
	 *
	 * @param view The fragment view containing the table
	 */
	private void setupTable(final View view) {
		tableLayout = view.findViewById(R.id.batteryDetailsTable);
		rowViews.clear();
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
		cellPadding = getResources().getDimensionPixelSize(R.dimen.battery_details_cell_padding);
		cellPaddingTop = getResources().getDimensionPixelSize(R.dimen.battery_details_cell_padding_top);
	}

	/**
	 * Refresh the battery details table with the current {@code batteryDO}.
	 * <p>
	 * Steady-state refreshes (every 3 s while the screen is open) update the existing rows' value
	 * cells in place; rows are only created or removed when their key genuinely appears or vanishes
	 * (rate warm-up, time-to-full gating, capacity turning unknown). This keeps accessibility focus,
	 * scroll position and the view tree stable across refreshes (#161).
	 *
	 * @param view The fragment view containing the table
	 */
	private void refreshDetailsTable(final View view) {
		fillBatteryInfo(view);

		syncRows(tableLayout, rowViews, valuesMap, new RowBinder() {
			@Override
			public TableRow createRow(final String label, final CharSequence value) {
				return createTableRow(view, label, value, cellPadding, cellPaddingTop,
						isUnreliableRow(label), valueColorFor(label), pendingInfoFor(label));
			}

			@Override
			public void bindValue(final TableRow row, final String label, final CharSequence value) {
				final TextView valueView = (TextView) row.getChildAt(VALUE_CELL_INDEX);
				valueView.setText(value);
				applyValueDecorations(valueView, isUnreliableRow(label), valueColorFor(label), pendingInfoFor(label));
			}
		});
	}

	/** Only the capacity row gets the amber "unreliable reading" affordance (#94). */
	private boolean isUnreliableRow(final String label) {
		return capacityUnreliable && label.equals(capacityLabel);
	}

	/** The drain-rate row's amber/red colour near the limit while discharging (#108); 0 = default. */
	private int valueColorFor(final String label) {
		return valueColorByLabel.getOrDefault(label, 0);
	}

	/** The {titleRes, messageRes} for a row currently showing the pending placeholder (#188), or null. */
	private int[] pendingInfoFor(final String label) {
		return pendingInfoByLabel.get(label);
	}

	/**
	 * Bring the table's rows in line with the desired label→value map: bind existing rows' value
	 * cells in place, remove rows whose label vanished, and insert new rows at their position.
	 * Relative order of surviving rows never changes (rows only appear/disappear), so inserting at
	 * the walk index keeps every row where it belongs. Static and binder-driven so the diffing is
	 * unit-testable without a fragment (#161).
	 *
	 * @param tableLayout the table being updated
	 * @param rowViews    the rows currently in the table, keyed by label — updated in place
	 * @param values      the desired rows for this refresh, in display order
	 * @param binder      creates a full row for a new label / rebinds the value cell of an existing one
	 */
	static void syncRows(final TableLayout tableLayout, final Map<String, TableRow> rowViews,
	                     final Map<String, CharSequence> values, final RowBinder binder) {
		// Drop rows whose label is gone this refresh.
		rowViews.entrySet().removeIf(entry -> {
			if (values.containsKey(entry.getKey())) {
				return false;
			}
			tableLayout.removeView(entry.getValue());
			return true;
		});

		int index = 0;
		for (final Map.Entry<String, CharSequence> entry : values.entrySet()) {
			TableRow row = rowViews.get(entry.getKey());
			if (isNull(row)) {
				row = binder.createRow(entry.getKey(), entry.getValue());
				tableLayout.addView(row, index);
				rowViews.put(entry.getKey(), row);
			} else {
				binder.bindValue(row, entry.getKey(), entry.getValue());
			}
			index++;
		}
	}

	/**
	 * How {@link #syncRows} materializes rows: create a complete row for a label that just appeared,
	 * or rebind the value cell of a row that persists across refreshes.
	 */
	interface RowBinder {

		TableRow createRow(String label, CharSequence value);

		void bindValue(TableRow row, String label, CharSequence value);
	}

	/**
	 * Update the battery details with new data
	 *
	 * @param batteryDO The new battery data object
	 */
	public void updateBatteryDetails(final BatteryDO batteryDO) {
		this.batteryDO = batteryDO;
		if (nonNull(viewRef) && nonNull(batteryDO)) {
			refreshDetailsTable(viewRef);
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
	 * @param valueUnreliable When true, decorate the value cell with a tappable amber "unreliable reading" icon
	 * @param valueColor      Colour for the value text, or 0 to keep the default value colour (#108)
	 * @param pendingInfo     {dialogTitleRes, dialogMessageRes} for a placeholder row's info icon, or null (#188)
	 *
	 * @return The created table row
	 */
	private TableRow createTableRow(final View view, final String label, final CharSequence value,
	                                final int cellPadding, final int cellPaddingTop,
	                                final boolean valueUnreliable, final int valueColor, final int[] pendingInfo) {
		final TableRow row = new TableRow(view.getContext());
		final TableRow.LayoutParams layoutParams = new TableRow.LayoutParams(
				TableRow.LayoutParams.MATCH_PARENT, TableRow.LayoutParams.WRAP_CONTENT, 1.0f);
		row.setLayoutParams(layoutParams);
		row.setWeightSum(3);

		// Enable proper RTL layout support
		ViewCompat.setLayoutDirection(row, ViewCompat.LAYOUT_DIRECTION_LOCALE);

		final TextView textViewLabel = createLabelTextView(view, label, cellPadding, cellPaddingTop);
		final TextView textViewSep = createSeparatorTextView(view);
		final TextView textViewValue = createValueTextView(view, value, cellPadding, cellPaddingTop, valueUnreliable, valueColor, pendingInfo);

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
	 * @param pendingInfo    {dialogTitleRes, dialogMessageRes} for a placeholder row's info icon, or null (#188)
	 *
	 * @return The created TextView
	 */
	private TextView createValueTextView(final View view,
	                                     final CharSequence text,
	                                     final int cellPadding,
	                                     final int cellPaddingTop,
	                                     final boolean unreliable,
	                                     final int valueColor,
	                                     final int[] pendingInfo) {
		final TextView textView = new TextView(view.getContext());
		textView.setTextAppearance(R.style.DefaultTextStyle);
		textView.setText(text);
		// Start-align + relative padding + locale text direction so numeric (Latin) values sit right after
		// the colon like the Arabic text values do, instead of flying to the far edge in RTL (#96).
		textView.setGravity(Gravity.START);
		textView.setTextDirection(View.TEXT_DIRECTION_LOCALE);
		textView.setPaddingRelative(cellPadding, 0, 0, cellPaddingTop);
		applyValueDecorations(textView, unreliable, valueColor, pendingInfo);
		return textView;
	}

	/**
	 * (Re-)apply the per-refresh decorations of a value cell: the rate colour (#108), the capacity row's
	 * amber "unreliable reading" affordance (#94), and the neutral info affordance on a rate/time/current
	 * row that is showing the "—" placeholder because its value isn't ready yet (#188). Called at creation
	 * and on every in-place rebind (#161), so it also has to <em>clear</em> all of them when a row's state
	 * changes back (e.g. the rate finishes warming up and the placeholder becomes a real number).
	 *
	 * @param textView    the value cell
	 * @param unreliable  when true, decorate with the tappable amber warning icon (capacity, #94)
	 * @param valueColor  colour for the value text, or 0 for the default value colour
	 * @param pendingInfo {dialogTitleRes, dialogMessageRes} for the neutral info icon (#188), or null
	 */
	private void applyValueDecorations(final TextView textView, final boolean unreliable, final int valueColor,
	                                   final int[] pendingInfo) {
		textView.setTextColor(valueColor != 0
		                      ? valueColor
		                      : GeneralHelper.getColor(getResources(), R.color.battery_details_value_color));
		final int iconPadding = (int) (6 * getResources().getDisplayMetrics().density);
		if (unreliable) {
			// #94: amber warning affordance after the value; tap to learn why the reading can't be trusted.
			// Same mark as the insights health figure, so the two screens read consistently.
			textView.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_warning_amber, 0);
			textView.setCompoundDrawablePadding(iconPadding);
			textView.setContentDescription(getString(R.string.battery_reading_unreliable_cd));
			textView.setOnClickListener(v -> showCapacityUnreliableDialog());
		} else if (nonNull(pendingInfo)) {
			// #188: neutral info affordance after the "—" placeholder; tap to learn why the value isn't ready.
			textView.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_info, 0);
			textView.setCompoundDrawablePadding(iconPadding);
			textView.setContentDescription(getString(R.string.battery_value_info_cd));
			final int titleRes = pendingInfo[0];
			final int messageRes = pendingInfo[1];
			textView.setOnClickListener(v -> showInfoDialog(titleRes, messageRes));
		} else {
			textView.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0);
			textView.setContentDescription(null);
			textView.setOnClickListener(null);
			textView.setClickable(false);
		}
	}

	/**
	 * Fill the battery information map with current battery data
	 *
	 * @param view The fragment view
	 */
	private void fillBatteryInfo(final View view) {
		valuesMap = new LinkedHashMap<>();
		valueColorByLabel.clear();
		pendingInfoByLabel.clear();

		// #108/#188: live charge/drain rate, time estimate and signed current at the very top. These rows
		// stay put across refreshes; a not-yet-ready value shows a placeholder + info icon rather than
		// hiding. Recording here also feeds the smoothing window from the foreground refresh (the other
		// feed is the battery broadcast) without any polling timer of our own.
		addLiveRows(view);

		valuesMap.put(getResources().getString(R.string.technology), batteryDO.getTechnology());

		// Capacity is an estimate from BatteryManager. Show "Unknown" rather than "0 mAh" when the device
		// doesn't report the charge counter, and also when the reported figure can't be trusted (#94) —
		// in that case the row binder adds a tappable info icon explaining why. The snapshot's capacity
		// is passed down so no second capacity estimate is read this tick (#161).
		final int capacity = batteryDO.getCapacity();
		capacityUnreliable = BatteryHealthTracker.isBatteryReadingUnreliable(view.getContext(), capacity);
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

		// Add charge cycles from the battery health tracker - positioned right after capacity. The
		// snapshot already carries the OS cycle count, so this triggers no extra sticky read (#161).
		final int chargeCycles = BatteryHealthTracker.getEffectiveCycleCount(view.getContext(), batteryDO.getCycleCount());
		valuesMap.put(getResources().getString(R.string.charge_cycles), String.valueOf(chargeCycles));

		valuesMap.put(getResources().getString(R.string.voltage), batteryDO.getVoltage() + " mV");
		valuesMap.put(getResources().getString(R.string.power_source), batteryDO.getPowerSource());
		valuesMap.put(getResources().getString(R.string.temperature),
				TemperatureUtils.format(view.getContext(), batteryDO.getTemperature()));
		valuesMap.put(getResources().getString(R.string.battery_condition), batteryDO.getHealth());
	}

	/**
	 * Adds the three "live" rows at the top of the table — rate, time estimate and signed current (#108,
	 * #124, #188). Unlike the rest, these three are <b>always present</b>: rather than hiding when a value
	 * isn't ready (which made rows appear/disappear and shift the table), a not-yet-available value shows a
	 * "—" placeholder with a tappable info icon explaining why. The labels follow the charge direction —
	 * "Charge rate"/"Drain rate" and "Time to full"/"Time remaining" — while "Current" is constant; because
	 * the labels are stable within a direction, steady-state refreshes update the values in place with no
	 * flicker (a label only changes on plug/unplug, when the whole context changes anyway).
	 *
	 * @param view The fragment view
	 */
	private void addLiveRows(final View view) {
		final BatteryRateTracker.BatteryRate rate = BatteryRateTracker.record(view.getContext(), batteryDO);
		final boolean charging = rate.charging();

		// Rate row: real %/h (coloured amber/red near the limit while discharging), else the placeholder.
		final String rateLabel = getString(charging ? R.string.charge_rate : R.string.drain_rate);
		if (rate.hasRate()) {
			valuesMap.put(rateLabel, BatteryRateTracker.formatRateValue(view.getContext(), rate.percentPerHour()));
			final int color = rateColor(view.getContext(), rate);
			if (color != 0) {
				valueColorByLabel.put(rateLabel, color);
			}
		} else {
			putPendingRow(rateLabel, R.string.battery_rate_pending_dialog_title, R.string.battery_rate_pending_dialog_message);
		}

		addTimeRow(view, rate, charging);

		// Current row: the signed mA (with the windowed-average second line), else the placeholder.
		final String currentLabel = getString(R.string.battery_current);
		if (rate.hasCurrent()) {
			valuesMap.put(currentLabel, currentValueText(view, rate));
		} else {
			putPendingRow(currentLabel, R.string.battery_current_pending_dialog_title, R.string.battery_current_pending_dialog_message);
		}
	}

	/**
	 * Adds the estimated-time row directly below the rate it is derived from: "Time to full" while charging,
	 * "Time remaining" while discharging (#124/#188). A rough capacity-free linear projection (see
	 * {@link BatteryRateTracker#estimateMinutesToFull} / {@link BatteryRateTracker#estimateMinutesToEmpty});
	 * precision is a non-goal — the OS owns the accurate figure. When the rate isn't ready, or the estimate
	 * degenerates (already full/empty, or right at the charge taper where a figure would mislead), the row
	 * stays put and shows the placeholder rather than vanishing. Reuses the already-computed {@code rate};
	 * only the level is read here.
	 *
	 * @param view     The fragment view
	 * @param rate     The already-computed rate for this refresh
	 * @param charging Whether the battery is charging (picks the label and the projection direction)
	 */
	private void addTimeRow(final View view, final BatteryRateTracker.BatteryRate rate, final boolean charging) {
		final String label = getString(charging ? R.string.time_to_full : R.string.time_remaining);
		if (rate.hasRate()) {
			final int level = batteryDO.getBatteryPercentageInt();
			final int minutes = charging
					? BatteryRateTracker.estimateMinutesToFull(level, rate.percentPerHour())
					: BatteryRateTracker.estimateMinutesToEmpty(level, rate.percentPerHour());
			if (minutes > 0) {
				valuesMap.put(label, BatteryRateTracker.formatDuration(view.getContext(), minutes));
				return;
			}
		}
		putPendingRow(label, R.string.battery_time_pending_dialog_title, R.string.battery_time_pending_dialog_message);
	}

	/**
	 * Places a "live" row in its not-yet-ready state (#188): the "—" placeholder value plus a tappable info
	 * icon whose dialog explains why. Keeps the row present so the table doesn't reflow.
	 *
	 * @param label      the row label
	 * @param titleRes   the info dialog's title resource
	 * @param messageRes the info dialog's message resource
	 */
	private void putPendingRow(final String label, final int titleRes, final int messageRes) {
		valuesMap.put(label, getString(R.string.battery_value_pending));
		pendingInfoByLabel.put(label, new int[]{titleRes, messageRes});
	}

	/**
	 * The Current row's value (#173): the moment value as the headline, and — once the window has
	 * enough data — the windowed average on its own second line, rendered smaller and in the label
	 * colour so it reads as a quiet anchor under the ticking instant. A second line (rather than an
	 * inline "(avg: …)") because the inline form wrapped mid-parenthesis in narrow columns, larger
	 * font scales, and Arabic.
	 *
	 * @param view The fragment view
	 * @param rate The already-computed rate for this refresh
	 *
	 * @return the styled value text for the Current row
	 */
	private CharSequence currentValueText(final View view, final BatteryRateTracker.BatteryRate rate) {
		final String instant = BatteryRateTracker.formatCurrentValue(view.getContext(), rate.currentMilliAmps());
		if (!rate.hasAvgCurrent()) {
			return instant;
		}
		final String avgLine = BatteryRateTracker.formatAverageCurrentLine(view.getContext(), rate.avgCurrentMilliAmps());
		final SpannableString styled = new SpannableString(instant + "\n" + avgLine);
		final int avgStart = instant.length() + 1;
		styled.setSpan(new RelativeSizeSpan(AVG_LINE_SIZE_RATIO), avgStart, styled.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		styled.setSpan(new ForegroundColorSpan(GeneralHelper.getColor(getResources(), R.color.battery_details_label_color)),
				avgStart, styled.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		return styled;
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

	/**
	 * Explains why a "live" row's value isn't ready yet (#188), opened from the neutral info icon on a
	 * placeholder rate/time/current row. The wording is row-specific (rate settling, estimate not ready,
	 * current unavailable).
	 *
	 * @param titleRes   the dialog title resource
	 * @param messageRes the dialog message resource
	 */
	private void showInfoDialog(final int titleRes, final int messageRes) {
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(titleRes)
				.setMessage(messageRes)
				.setPositiveButton(android.R.string.ok, null)
				.show();
	}
}
