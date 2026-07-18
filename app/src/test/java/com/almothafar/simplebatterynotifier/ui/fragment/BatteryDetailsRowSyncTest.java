package com.almothafar.simplebatterynotifier.ui.fragment;

import android.content.Context;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * Robolectric tests for {@link BatteryDetailsFragment#syncRows} (issue #161): steady-state
 * refreshes must update existing rows in place — never recreate them, which reset accessibility
 * focus and scroll every 3 s — while rows whose key appears or vanishes (rate warm-up,
 * time-to-full gating) are inserted or removed at the right position.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BatteryDetailsRowSyncTest {

	private Context context;
	private TableLayout table;
	private Map<String, TableRow> rowViews;
	private BatteryDetailsFragment.RowBinder binder;

	@Before
	public void setUp() {
		context = ApplicationProvider.getApplicationContext();
		table = new TableLayout(context);
		rowViews = new LinkedHashMap<>();
		// Minimal binder: one TextView per row carrying the value, updated in place on rebind.
		binder = new BatteryDetailsFragment.RowBinder() {
			@Override
			public TableRow createRow(final String label, final CharSequence value) {
				final TableRow row = new TableRow(context);
				final TextView valueView = new TextView(context);
				valueView.setText(value);
				row.addView(valueView);
				return row;
			}

			@Override
			public void bindValue(final TableRow row, final String label, final CharSequence value) {
				((TextView) row.getChildAt(0)).setText(value);
			}
		};
	}

	@Test
	public void steadyState_updatesValuesWithoutRecreatingRows() {
		BatteryDetailsFragment.syncRows(table, rowViews, values("Voltage", "3900 mV", "Temperature", "25 °C"), binder);
		final TableRow voltageRow = rowViews.get("Voltage");
		final TableRow temperatureRow = rowViews.get("Temperature");

		BatteryDetailsFragment.syncRows(table, rowViews, values("Voltage", "3850 mV", "Temperature", "26 °C"), binder);

		// Same view instances (a11y focus survives), fresh values, no row growth.
		assertSame(voltageRow, table.getChildAt(0));
		assertSame(temperatureRow, table.getChildAt(1));
		assertEquals(2, table.getChildCount());
		assertEquals("3850 mV", valueAt(0));
		assertEquals("26 °C", valueAt(1));
	}

	@Test
	public void vanishedKey_removesOnlyItsRow() {
		BatteryDetailsFragment.syncRows(table, rowViews, values("Rate", "9%/h", "Voltage", "3900 mV", "Temperature", "25 °C"), binder);
		final TableRow voltageRow = rowViews.get("Voltage");
		final TableRow temperatureRow = rowViews.get("Temperature");

		// The rate row hides (e.g. static level); its neighbours must survive untouched.
		BatteryDetailsFragment.syncRows(table, rowViews, values("Voltage", "3900 mV", "Temperature", "25 °C"), binder);

		assertEquals(2, table.getChildCount());
		assertSame(voltageRow, table.getChildAt(0));
		assertSame(temperatureRow, table.getChildAt(1));
	}

	@Test
	public void appearingKey_isInsertedAtItsPosition() {
		BatteryDetailsFragment.syncRows(table, rowViews, values("Voltage", "3900 mV", "Temperature", "25 °C"), binder);
		final TableRow voltageRow = rowViews.get("Voltage");
		final TableRow temperatureRow = rowViews.get("Temperature");

		// The rate row appears at the TOP after warm-up; existing rows shift down but stay the same views.
		BatteryDetailsFragment.syncRows(table, rowViews, values("Rate", "9%/h", "Voltage", "3900 mV", "Temperature", "25 °C"), binder);

		assertEquals(3, table.getChildCount());
		assertSame(rowViews.get("Rate"), table.getChildAt(0));
		assertSame(voltageRow, table.getChildAt(1));
		assertSame(temperatureRow, table.getChildAt(2));
		assertEquals("9%/h", valueAt(0));
	}

	@Test
	public void midTableInsertion_landsBetweenItsNeighbours() {
		BatteryDetailsFragment.syncRows(table, rowViews, values("Rate", "9%/h", "Temperature", "25 °C"), binder);

		// Time-to-full gates open mid-table (#124): it must land between rate and temperature.
		BatteryDetailsFragment.syncRows(table, rowViews, values("Rate", "9%/h", "Time to full", "~1h 20m", "Temperature", "25 °C"), binder);

		assertEquals(3, table.getChildCount());
		assertSame(rowViews.get("Time to full"), table.getChildAt(1));
		assertEquals("~1h 20m", valueAt(1));
	}

	// --- helpers -------------------------------------------------------------

	private static Map<String, CharSequence> values(final CharSequence... labelValuePairs) {
		final Map<String, CharSequence> map = new LinkedHashMap<>();
		for (int i = 0; i < labelValuePairs.length; i += 2) {
			map.put(labelValuePairs[i].toString(), labelValuePairs[i + 1]);
		}
		return map;
	}

	private String valueAt(final int rowIndex) {
		final TableRow row = (TableRow) table.getChildAt(rowIndex);
		return ((TextView) row.getChildAt(0)).getText().toString();
	}
}
