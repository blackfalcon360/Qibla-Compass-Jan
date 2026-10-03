package QiblaCompass.blackfalcon.jan;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Small control screen. The compass and the Qibla arrow live in the status bar. */
public class MainActivity extends Activity {

    private static final int REQ_PERMISSIONS = 1;
    private static final int GOLD = 0xFFC9A227;

    private TextView statusView;
    private TextView qiblaView;
    private Button toggleButton;
    private LocationManager locationManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refreshUi();
            handler.postDelayed(this, 1000);
        }
    };

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            saveLocation(location);
            locationManager.removeUpdates(this); // one fresh fix is enough
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        locationManager = getSystemService(LocationManager.class);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF101216);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        column.setPadding(dp(24), dp(24), dp(24), dp(24));

        TextView title = label("QiblaCompass", 30, 0xFFFFFFFF, true);
        TextView hint = label("Status bar: degrees + direction (e.g. 245\u00B0 SW)\n"
                + "and a small arrow that points to the Kaaba", 15, 0xFF9AA0A6, false);
        statusView = label("", 16, GOLD, false);
        qiblaView = label("", 14, 0xFFFFFFFF, false);

        toggleButton = new Button(this);
        toggleButton.setOnClickListener(v -> {
            if (CompassService.running) {
                CompassService.stop(this);
                refreshUi();
            } else {
                requestAll();
            }
        });

        column.addView(title);
        column.addView(hint);
        column.addView(statusView);
        column.addView(qiblaView);
        column.addView(toggleButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (Build.VERSION.SDK_INT >= 36) {
            // Android 16+: allow "Live updates" so arrow + degrees show as one chip in the status bar
            Button live = new Button(this);
            live.setText("Live updates settings");
            live.setOnClickListener(v -> openLiveUpdateSettings());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(12);
            column.addView(live, lp);
        }

        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView credit = label("By: Black Falcon", 14, GOLD, true);
        credit.setPadding(0, 0, 0, 0);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        lp.setMargins(0, dp(16), dp(20), 0);
        root.addView(credit, lp);

        setContentView(root);

        if (HeadingProvider.isSupported(this)) {
            requestAll();
        } else {
            statusView.setText("This device has no compass sensor");
            toggleButton.setEnabled(false);
        }
    }

    private void requestAll() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!hasLocationPermission()) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (need.isEmpty()) {
            CompassService.start(this);
            refreshLocation();
            refreshUi();
        } else {
            requestPermissions(need.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            CompassService.start(this); // runs either way; parts that need a permission just stay off
            refreshLocation();
            refreshUi();
        }
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Saves your position (used for the Qibla direction). The service picks it up automatically. */
    @SuppressWarnings({"deprecation", "MissingPermission"})
    private void refreshLocation() {
        if (locationManager == null || !hasLocationPermission()) return;
        boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;

        Location best = null;
        String[] providers = {LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER,
                LocationManager.PASSIVE_PROVIDER};
        for (String p : providers) {
            try {
                Location l = locationManager.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (Exception ignored) {
            }
        }
        if (best != null) saveLocation(best);

        // ask for one fresh fix as well
        try {
            String provider = null;
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                provider = LocationManager.NETWORK_PROVIDER;
            } else if (fine && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                provider = LocationManager.GPS_PROVIDER;
            }
            if (provider != null) {
                locationManager.removeUpdates(locationListener);
                locationManager.requestLocationUpdates(provider, 0L, 0f, locationListener);
            }
        } catch (Exception ignored) {
        }
    }

    private void saveLocation(Location l) {
        getSharedPreferences(Qibla.PREFS, MODE_PRIVATE).edit()
                .putBoolean(Qibla.KEY_HAS_LOC, true)
                .putFloat(Qibla.KEY_LAT, (float) l.getLatitude())
                .putFloat(Qibla.KEY_LON, (float) l.getLongitude())
                .apply();
        refreshUi();
    }

    private void openLiveUpdateSettings() {
        try {
            Intent i = new Intent("android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS");
            i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(i);
        } catch (Exception e) {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(i);
        }
    }

    private void refreshUi() {
        if (!HeadingProvider.isSupported(this)) return;

        NotificationManager nm = getSystemService(NotificationManager.class);
        boolean notificationsOk = nm == null || nm.areNotificationsEnabled();
        if (CompassService.running) {
            statusView.setText(notificationsOk
                    ? "Status bar compass: ON"
                    : "Notifications are blocked \u2014 allow them in app settings to see the compass in the status bar");
            toggleButton.setText("Stop");
        } else {
            statusView.setText("Status bar compass: OFF");
            toggleButton.setText("Start");
        }

        SharedPreferences sp = getSharedPreferences(Qibla.PREFS, MODE_PRIVATE);
        if (sp.getBoolean(Qibla.KEY_HAS_LOC, false)) {
            double lat = sp.getFloat(Qibla.KEY_LAT, 0f);
            double lon = sp.getFloat(Qibla.KEY_LON, 0f);
            qiblaView.setText("Qibla: " + Math.round(Qibla.bearing(lat, lon)) + "\u00B0 from true North  \u2022  "
                    + String.format(Locale.US, "%,d", Math.round(Qibla.distanceKm(lat, lon)))
                    + " km to the Kaaba");
        } else {
            qiblaView.setText("Allow location (once) so the arrow can point to the Qibla");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshLocation(); // update your position every time the app is opened
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresher);
        if (locationManager != null) {
            locationManager.removeUpdates(locationListener);
        }
    }

    private TextView label(String text, float sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, 0, 0, dp(16));
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
