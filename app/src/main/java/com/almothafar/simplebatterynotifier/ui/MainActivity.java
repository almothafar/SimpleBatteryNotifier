package com.almothafar.simplebatterynotifier.ui;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.FragmentManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import androidx.preference.PreferenceManager;
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
    // SETTINGS_RESULT constant removed - no longer needed with ActivityResultLauncher (doesn't use request codes)
    int batteryPercentage;
    String subTitle;
    BatteryDO batteryDO;

    TimerTask updateTask;
    // Use Handler(Looper) constructor - Handler() deprecated to prevent null Looper
    final Handler handler = new Handler(Looper.getMainLooper());
    Timer timer = new Timer();
    private ActivityResultLauncher<Intent> settingsLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        setContentView(R.layout.activity_main);

        // Set up the Toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // setStatusBarColor() and setNavigationBarColor() removed - deprecated in API 35
        // Edge-to-edge is already enabled via WindowCompat.setDecorFitsSystemWindows()
        // System bar colors should be set in themes (values/themes.xml) instead

        // Register activity result launcher for settings
        // Replaces deprecated startActivityForResult() - modern approach doesn't require result handling
        settingsLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    // When returning from settings, refresh the UI with updated preferences
                    initialFirstValues();
                }
        );

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
                handler.post(() -> {
                    final CircularProgressBar c2 = findViewById(R.id.batteryPercentage);
                    fillBatteryInfo();
                    c2.setProgress(batteryPercentage);
                    c2.setTitle(batteryPercentage + "%");
                    c2.setSubTitle(subTitle);

                    FragmentManager fragmentManager = getSupportFragmentManager();
                    BatteryDetailsFragment batteryDetailsFragment = (BatteryDetailsFragment) fragmentManager.findFragmentById(R.id.detailsFragmentLayout);
                    batteryDetailsFragment.updateBatteryDetails(batteryDO);
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
        // Use modern ActivityResultLauncher instead of deprecated startActivityForResult()
        settingsLauncher.launch(i);
    }
}
