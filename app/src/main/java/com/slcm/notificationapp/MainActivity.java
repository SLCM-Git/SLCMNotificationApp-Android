package com.slcm.notificationapp;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import android.content.ComponentName;
import android.text.TextUtils;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity {

    private TextView tvStatus;
    private Button btnEnableNotificationListener,btnEnableBattery;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        btnEnableNotificationListener = findViewById(R.id.btnEnableNotificationListener);
        btnEnableBattery = findViewById(R.id.btnEnableBattery);

        btnEnableNotificationListener.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            startActivity(intent);
            Toast.makeText(this, "Please enable the Notification Listener for this app.", Toast.LENGTH_LONG).show();
        });

        btnEnableBattery.setOnClickListener(v -> {
            requestIgnoreBatteryOptimizations(this);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        if (isNotificationServiceEnabled()) {
            tvStatus.setText("Notification Listener: ENABLED\nTarget Package: " + Constants.TARGET_PACKAGE_NAME + "\nServer URL: " + Constants.SERVER_URL);
            btnEnableNotificationListener.setEnabled(false);
            btnEnableNotificationListener.setText("Listener Enabled");
        } else {
            tvStatus.setText("Notification Listener: DISABLED.\nPlease enable it in settings.");
            btnEnableNotificationListener.setEnabled(true);
            btnEnableNotificationListener.setText("Enable Listener in Settings");
        }
    }

    private boolean isNotificationServiceEnabled() {
        String pkgName = getPackageName();
        final String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (!TextUtils.isEmpty(flat)) {
            final String[] names = flat.split(":");
            for (String name : names) {
                final ComponentName cn = ComponentName.unflattenFromString(name);
                if (cn != null) {
                    if (TextUtils.equals(pkgName, cn.getPackageName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public void requestIgnoreBatteryOptimizations(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            String packageName = context.getPackageName();
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + packageName));
                context.startActivity(intent);
            }
        }
    }

}