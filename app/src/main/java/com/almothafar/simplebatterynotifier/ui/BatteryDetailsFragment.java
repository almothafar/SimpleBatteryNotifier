package com.almothafar.simplebatterynotifier.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.model.BatteryDO;
import com.almothafar.simplebatterynotifier.service.SystemService;
import com.almothafar.simplebatterynotifier.util.GeneralHelper;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Fragment displaying detailed battery information in a table layout
 */
public class BatteryDetailsFragment extends Fragment {

	private static final String TAG = "BatteryDetailsFragment";

	private BatteryDO batteryDO;
	private Map<String, String> valuesMap;
	private View viewRef;

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
	 * @return The created view
	 */
	@Override
	public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
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
			final TableRow row = createTableRow(view, key, valuesMap.get(key), cellPadding, cellPaddingTop);
			tableLayout.addView(row, rowIndex);
			rowIndex++;
		}
	}

	/**
	 * Create a single table row with label, separator, and value
	 *
	 * @param view           The fragment view
	 * @param label          The label text
	 * @param value          The value text
	 * @param cellPadding    The horizontal cell padding
	 * @param cellPaddingTop The top cell padding
	 * @return The created table row
	 */
	private TableRow createTableRow(final View view, final String label, final String value,
	                                 final int cellPadding, final int cellPaddingTop) {
		final TableRow row = new TableRow(view.getContext());
		final TableRow.LayoutParams layoutParams = new TableRow.LayoutParams(
				TableRow.LayoutParams.MATCH_PARENT, TableRow.LayoutParams.WRAP_CONTENT, 1.0f);
		row.setLayoutParams(layoutParams);
		row.setWeightSum(3);

		final TextView textViewLabel = createLabelTextView(view, label, cellPadding, cellPaddingTop);
		final TextView textViewSep = createSeparatorTextView(view);
		final TextView textViewValue = createValueTextView(view, value, cellPadding, cellPaddingTop);

		// Handle RTL languages (Arabic) by reversing the view order
		// TODO: This is a workaround - should be handled with proper RTL layout support
		if (Locale.getDefault().getLanguage().equalsIgnoreCase("ar")) {
			row.addView(textViewValue);
			row.addView(textViewSep);
			row.addView(textViewLabel);
		} else {
			row.addView(textViewLabel);
			row.addView(textViewSep);
			row.addView(textViewValue);
		}

		return row;
	}

	/**
	 * Create a label TextView
	 *
	 * @param view           The fragment view
	 * @param text           The label text
	 * @param cellPadding    The horizontal cell padding
	 * @param cellPaddingTop The top cell padding
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
	 * @return The created TextView
	 */
	private TextView createValueTextView(final View view, final String text,
	                                      final int cellPadding, final int cellPaddingTop) {
		final TextView textView = new TextView(view.getContext());
		textView.setTextAppearance(R.style.DefaultTextStyle);
		textView.setText(text);
		textView.setTextColor(GeneralHelper.getColor(getResources(), R.color.battery_details_value_color));
		textView.setGravity(Gravity.START);
		textView.setPadding(cellPadding, 0, 0, cellPaddingTop);
		return textView;
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
	 * Fill the battery information map with current battery data
	 *
	 * @param view The fragment view
	 */
	private void fillBatteryInfo(final View view) {
		valuesMap = new HashMap<>();
		final SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(view.getContext());

		final String temperatureStr = sharedPref.getString(
				getString(R.string._pref_key_temperatures_unit),
				getString(R.string._pref_value_temperatures_unit_c));
		String temperatureShort = " ".concat(getResources().getString(R.string.celsius_short));
		float temperature = batteryDO.getTemperature() / 10f;

		if (temperatureStr.equalsIgnoreCase(getString(R.string._pref_value_temperatures_unit_f))) {
			temperature = GeneralHelper.fromCtoF(temperature);
			temperatureShort = " ".concat(getResources().getString(R.string.fahrenheit_short));
		}

		valuesMap.put(getResources().getString(R.string.technology), batteryDO.getTechnology());
		valuesMap.put(getResources().getString(R.string.capacity), batteryDO.getCapacity() + " mAh");
		valuesMap.put(getResources().getString(R.string.voltage), batteryDO.getVoltage() + " mV");
		valuesMap.put(getResources().getString(R.string.power_source), batteryDO.getPowerSource());
		valuesMap.put(getResources().getString(R.string.temperature), temperature + temperatureShort);
		valuesMap.put(getResources().getString(R.string.health), batteryDO.getHealth());
	}
}
