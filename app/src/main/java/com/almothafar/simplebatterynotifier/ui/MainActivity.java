package com.almothafar.simplebatterynotifier.ui;

import android.app.FragmentManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.preference.PreferenceManager;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.WindowCompat;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;

import com.almothafar.simplebatterynotifier.R;
import com.almothafar.simplebatterynotifier.model.BatteryDO;
import com.almothafar.simplebatterynotifier.service.SystemService;
import com.almothafar.simplebatterynotifier.service.PowerConnectionService;
import com.almothafar.simplebatterynotifier.util.GeneralHelper;
import com.almothafar.simplebatterynotifier.ui.widgets.CircularProgressBar;

import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity {

    private static final long UPDATER_DELAY = 300;
    private static final long UPDATER_PERIOD = 3000;
    private static final int SETTINGS_RESULT = 1;
    int batteryPercentage;
    String subTitle;
    BatteryDO batteryDO;

    TimerTask updateTask;
    final Handler handler = new Handler();
    Timer timer = new Timer();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        setContentView(R.layout.activity_main);

        // Set up the Toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // Set status bar to transparent to show toolbar color behind it
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);

        // Set navigation bar color to semi-transparent for better visibility
        getWindow().setNavigationBarColor(0x80000000); // 50% transparent black

        // If not started will start it.
        // TODO check if service is running before do this.
        startService(new Intent(this, PowerConnectionService.class));


    }

    @Override
    protected void onPostResume() {
        super.onPostResume();

        initialFirstValues();

        startUpdateTimer();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        timer.cancel();
    }

    private void startUpdateTimer() {
        updateTask = new TimerTask() {
            public void run() {
                handler.post(new Runnable() {
                    public void run() {
                        final CircularProgressBar c2 = (CircularProgressBar) findViewById(R.id.batteryPercentage);
                        fillBatteryInfo();
                        c2.setProgress(batteryPercentage);
                        c2.setTitle(batteryPercentage + "%");
                        c2.setSubTitle(subTitle);

                        FragmentManager fragmentManager = getFragmentManager();
                        BatteryDetailsFragment batteryDetailsFragment = (BatteryDetailsFragment) fragmentManager.findFragmentById(R.id.detailsFragmentLayout);
                        batteryDetailsFragment.updateBatteryDetails(batteryDO);
                    }
                });
            }
        };
        timer.schedule(updateTask, UPDATER_DELAY, UPDATER_PERIOD);  // here is t.schedule( , delay, period);
    }

    private void initialFirstValues() {
        fillBatteryInfo();
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);

        final CircularProgressBar c2 = (CircularProgressBar) findViewById(R.id.batteryPercentage);
        c2.setWarningLevel(sharedPref.getInt(getString(R.string._pref_key_warn_battery_level), 40));
        c2.setCriticalLevel(sharedPref.getInt(getString(R.string._pref_key_critical_battery_level), 20));
        c2.animateProgressTo(0, batteryPercentage, new CircularProgressBar.ProgressAnimationListener() {

            @Override
            public void onAnimationStart() {
            }

            @Override
            public void onAnimationProgress(int progress) {
                c2.setTitle(progress + "%");
                c2.setSubTitle(subTitle);
            }

            @Override
            public void onAnimationFinish() {
            }
        });
    }

    protected void fillBatteryInfo() {
        batteryDO = SystemService.getBatteryInfo(this);

        boolean isCharging = batteryDO.getStatus() == BatteryManager.BATTERY_STATUS_CHARGING;
        boolean isFull = batteryDO.getStatus() == BatteryManager.BATTERY_STATUS_FULL;

        batteryPercentage = (int) batteryDO.getBatteryPercentage();

        if (isCharging) {
            subTitle = getResources().getString(R.string.charging);
        } else {
            subTitle = getResources().getString(R.string.discharging);
        }
        if (isFull) {
            subTitle = getResources().getString(R.string.charged);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            OpenSettings();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void OpenSettings() {
        Intent i = new Intent(this, SettingsActivity.class);
        startActivityForResult(i, SETTINGS_RESULT);
    }
}
