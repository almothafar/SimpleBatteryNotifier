package com.almothafar.simplebatterynotifier.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.model.BatteryDO;
import com.almothafar.simplebatterynotifier.service.SystemService;
import com.almothafar.simplebatterynotifier.util.GeneralHelper;

import java.util.HashMap;
import java.util.Locale;


public class BatteryDetailsFragment extends Fragment {

    private BatteryDO batteryDO;
    private HashMap<String, String> valuesMap;
    private View viewRef;

    public BatteryDetailsFragment() {
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_battery_details, container, false);
        this.viewRef = view;
        batteryDO = SystemService.getBatteryInfo(view.getContext());
        createDetailsTable(view);

        // Inflate the layout for this fragment
        return view;
    }

    public void createDetailsTable(View view) {
        fillBatteryInfo(view);

        TableLayout tableLayout = view.findViewById(R.id.batteryDetailsTable);
        tableLayout.removeAllViews();
        tableLayout.setStretchAllColumns(true);
        int cellPadding = getResources().getDimensionPixelSize(R.dimen.battery_details_cell_padding);
        int cellPaddingTop = getResources().getDimensionPixelSize(R.dimen.battery_details_cell_padding_top);

        int i = 0;
        for (String key : valuesMap.keySet()) {
            TableRow row = new TableRow(view.getContext());
            TableRow.LayoutParams layoutParams = new TableRow.LayoutParams(TableRow.LayoutParams.MATCH_PARENT, TableRow.LayoutParams.WRAP_CONTENT, 1.0f);
            row.setLayoutParams(layoutParams);
            row.setWeightSum(3);

            TextView textViewLabel = new TextView(view.getContext());
            textViewLabel.setTextAppearance(view.getContext(), R.style.DefaultTextStyle);
            textViewLabel.setText(key);
            textViewLabel.setGravity(Gravity.END);
            textViewLabel.setTextColor(GeneralHelper.getColor(getResources(), R.color.battery_details_label_color));
            textViewLabel.setPadding(0, 0, cellPadding, cellPaddingTop);

            TextView textViewSep = new TextView(view.getContext());
            textViewSep.setText(":");
            textViewSep.setGravity(Gravity.CENTER);
            textViewSep.setTextColor(GeneralHelper.getColor(getResources(), R.color.battery_details_label_color));

            TextView textViewValue = new TextView(view.getContext());
            textViewValue.setTextAppearance(view.getContext(), R.style.DefaultTextStyle);
            textViewValue.setText(valuesMap.get(key));
            textViewValue.setTextColor(GeneralHelper.getColor(getResources(), R.color.battery_details_value_color));
            textViewValue.setGravity(Gravity.START);
            textViewValue.setPadding(cellPadding, 0, 0, cellPaddingTop);

            // TODO fix this is workaround for arabic layout, it must be handled with RTL or another layout xml file, but lets postpone it for now :)
            if (Locale.getDefault().getLanguage().equalsIgnoreCase("ar")) {
                row.addView(textViewValue);
                row.addView(textViewSep);
                row.addView(textViewLabel);
            } else {
                row.addView(textViewLabel);
                row.addView(textViewSep);
                row.addView(textViewValue);
            }
            tableLayout.addView(row, i);
            i++;
        }
    }

    private void fillBatteryInfo(View view) {
        valuesMap = new HashMap<>();
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(view.getContext());

        String temperatureStr = sharedPref.getString(getString(R.string._pref_key_temperatures_unit), getString(R.string._pref_value_temperatures_unit_c));
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

    public void updateBatteryDetails(BatteryDO batteryDO) {
        this.batteryDO = batteryDO;
        this.createDetailsTable(viewRef);
    }
}
