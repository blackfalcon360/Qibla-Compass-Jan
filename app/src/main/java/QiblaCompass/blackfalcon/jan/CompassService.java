package QiblaCompass.blackfalcon.jan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.hardware.GeomagneticField;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import java.util.Locale;

/**
 * Foreground service. Shows in the status bar:
 *  1) the heading as degrees + direction (e.g. 245 / SW)
 *  2) a small arrow that points to the Kaaba (Qibla), relative to where you are facing
 * Android 16+: both are combined into one "Live Update" chip (arrow icon + "245°SW").
 */
public class CompassService extends Service implements HeadingProvider.Listener {

    public static final String ACTION_STOP = "QiblaCompass.blackfalcon.jan.STOP";
    private static final String CHANNEL_ID = "qibla_compass_v1";
    private static final int ID_MAIN = 1;
    private static final int ID_ARROW = 2;
    private static final String[] DIRS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    private static final String[] DIRS_FULL = {
            "North", "North-East", "East", "South-East", "South", "South-West", "West", "North-West"};

    /** true while the status-bar compass is switched on */
    public static volatile boolean running = false;

    private NotificationManager notificationManager;
    private HeadingProvider provider;
    private boolean sensorsStarted;

    private SharedPreferences prefs;
    private SharedPreferences.OnSharedPreferenceChangeListener prefsListener;
    private boolean hasLoc;
    private double qiblaBearing;   // true-north bearing to the Kaaba
    private double distanceKm;
    private float declination;     // magnetic -> true north correction

    private int lastDegrees = -1;
    private int lastBucket = -999;
    private long lastUpdate = 0;
    private boolean arrowPosted;

    public static void start(Context context) {
        running = true;
        context.startForegroundService(new Intent(context, CompassService.class));
    }

    public static void stop(Context context) {
        running = false;
        context.stopService(new Intent(context, CompassService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = getSystemService(NotificationManager.class);

        // Silent, but DEFAULT importance so the icons rank above LOW ones.
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Qibla Compass", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.enableLights(false);
        channel.setShowBadge(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        channel.setDescription("Live heading and Qibla arrow in the status bar");
        notificationManager.createNotificationChannel(channel);

        prefs = getSharedPreferences(Qibla.PREFS, MODE_PRIVATE);
        loadLocation();
        prefsListener = (sp, key) -> { // the app saved a new location
            loadLocation();
            lastDegrees = -1;
            lastBucket = -999;
        };
        prefs.registerOnSharedPreferenceChangeListener(prefsListener);

        provider = new HeadingProvider(this, this, 0.3);
    }

    private void loadLocation() {
        hasLoc = prefs.getBoolean(Qibla.KEY_HAS_LOC, false);
        if (hasLoc) {
            double lat = prefs.getFloat(Qibla.KEY_LAT, 0f);
            double lon = prefs.getFloat(Qibla.KEY_LON, 0f);
            qiblaBearing = Qibla.bearing(lat, lon);
            distanceKm = Qibla.distanceKm(lat, lon);
            declination = new GeomagneticField((float) lat, (float) lon, 0f,
                    System.currentTimeMillis()).getDeclination();
        } else {
            declination = 0f;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        running = true;
        Notification n = buildMain(false, 0f, 0, 0, 0f, false);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID_MAIN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(ID_MAIN, n);
        }

        if (!sensorsStarted) {
            sensorsStarted = provider.start(SensorManager.SENSOR_DELAY_UI);
            if (!sensorsStarted) {
                stopSelf(); // no compass sensor on this phone
                return START_NOT_STICKY;
            }
        }
        return START_STICKY;
    }

    @Override
    public void onHeading(float magneticDegrees) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastUpdate < 400) return; // don't spam the notifications

        // true-north heading once we know where we are (the Qibla bearing is also from true north)
        float heading = (magneticDegrees + declination + 360f) % 360f;
        int deg = Math.round(heading) % 360;
        int idx = ((int) ((heading + 22.5f) / 45f)) % 8;

        float rel = 0f;
        int bucket = 0;
        if (hasLoc) {
            rel = Qibla.relative(qiblaBearing, heading);
            bucket = Math.round(rel / 5f); // arrow moves in 5-degree steps
        }
        boolean degChanged = deg != lastDegrees;
        boolean arrowChanged = hasLoc && bucket != lastBucket;
        if (!degChanged && !arrowChanged) return;
        lastUpdate = now;
        lastDegrees = deg;
        lastBucket = bucket;

        boolean live = Build.VERSION.SDK_INT >= 36 && canPostPromoted();
        notificationManager.notify(ID_MAIN, buildMain(true, heading, deg, idx, rel, live));

        if (hasLoc && !live) {
            notificationManager.notify(ID_ARROW, buildArrow(rel));
            arrowPosted = true;
        } else if (arrowPosted) {
            notificationManager.cancel(ID_ARROW);
            arrowPosted = false;
        }
    }

    private String qiblaPhrase(float rel) {
        int a = Math.round(Math.abs(rel));
        if (a <= 3) return "Facing the Qibla";
        return "Qibla: turn " + a + "\u00B0 " + (rel > 0 ? "right" : "left");
    }

    private String distanceText() {
        return String.format(Locale.US, "%,d km", Math.round(distanceKm));
    }

    private PendingIntent openIntent() {
        return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Main notification: degrees + direction (icon or Live Update chip). */
    @SuppressWarnings("deprecation")
    private Notification buildMain(boolean known, float heading, int deg, int idx, float rel, boolean live) {
        String title = known ? deg + "\u00B0  " + DIRS[idx] : "Compass starting\u2026";
        String text;
        if (!known) {
            text = "";
        } else if (hasLoc) {
            text = DIRS_FULL[idx] + "\n" + qiblaPhrase(rel) + "  \u2022  " + distanceText();
        } else {
            text = DIRS_FULL[idx] + "\nOpen the app and allow location to see the Qibla arrow";
        }
        String chip = known ? deg + "\u00B0" + DIRS[idx] : "";

        Icon icon;
        if (live) {
            icon = Icon.createWithBitmap(hasLoc ? makeArrowIcon(rel) : makeNeedleIcon(heading));
        } else if (known) {
            icon = Icon.createWithBitmap(makeStatusIcon(String.valueOf(deg), DIRS[idx]));
        } else {
            icon = Icon.createWithBitmap(makeStatusIcon("--", ""));
        }

        Intent stopIntent = new Intent(this, CompassService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(text.replace('\n', ' '))
                .setSubText("By: Black Falcon")
                .setContentIntent(openIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop);

        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        if (Build.VERSION.SDK_INT >= 36) {
            b.setStyle(new Notification.BigTextStyle().bigText(text));
            requestLiveUpdate(b, chip);
        }
        return b.build();
    }

    /** Second status-bar icon: a small arrow pointing to the Kaaba. */
    private Notification buildArrow(float rel) {
        int a = Math.round(Math.abs(rel));
        String title = a <= 3 ? "Facing the Qibla" : "Qibla: turn " + a + "\u00B0 " + (rel > 0 ? "right" : "left");
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(Icon.createWithBitmap(makeArrowIcon(rel)))
                .setContentTitle(title)
                .setContentText("Kaaba is " + distanceText() + " away  \u2022  bearing "
                        + Math.round(qiblaBearing) + "\u00B0")
                .setSubText("By: Black Falcon")
                .setContentIntent(openIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build();
    }

    /** Android 16 "Live Update" APIs, called by reflection so the project still builds with compileSdk 34. */
    private boolean canPostPromoted() {
        try {
            Object r = NotificationManager.class
                    .getMethod("canPostPromotedNotifications")
                    .invoke(notificationManager);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            return false;
        }
    }

    private void requestLiveUpdate(Notification.Builder b, String chipText) {
        try {
            Notification.Builder.class
                    .getMethod("setRequestPromotedOngoing", boolean.class)
                    .invoke(b, true);
            if (!chipText.isEmpty()) {
                Notification.Builder.class
                        .getMethod("setShortCriticalText", String.class)
                        .invoke(b, chipText);
            }
        } catch (Throwable ignored) {
            // older Android: plain notification is used instead
        }
    }

    // ---------------------------------------------------------------- icons

    /** White-on-transparent icon: degrees on the top line, direction letters on the bottom line. */
    private Bitmap makeStatusIcon(String top, String bottom) {
        int size = 96;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextAlign(Paint.Align.CENTER);

        if (bottom.isEmpty()) {
            drawFitted(c, p, top, size / 2f, size / 2f + 18f, 72f, size - 4f);
        } else {
            drawFitted(c, p, top, size / 2f, 44f, 50f, size - 4f);
            drawFitted(c, p, bottom, size / 2f, 90f, 50f, size - 4f);
        }
        return bmp;
    }

    /**
     * Qibla arrow. "Up" = the direction you are facing; the arrow turns to point at the Kaaba.
     * A ring appears around it when you are facing the Qibla (within 3 degrees).
     */
    private Bitmap makeArrowIcon(float rel) {
        int size = 96;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setStyle(Paint.Style.FILL);

        c.save();
        c.rotate(rel, 48f, 48f);
        Path arrow = new Path();
        arrow.moveTo(48f, 10f);
        arrow.lineTo(76f, 46f);
        arrow.lineTo(60f, 46f);
        arrow.lineTo(60f, 86f);
        arrow.lineTo(36f, 86f);
        arrow.lineTo(36f, 46f);
        arrow.lineTo(20f, 46f);
        arrow.close();
        c.drawPath(arrow, p);
        c.restore();

        if (Math.abs(rel) <= 3f) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(4f);
            c.drawCircle(48f, 48f, 45f, p);
        }
        return bmp;
    }

    /** Fallback (Live Update, no location yet): ring + needle whose north tip follows the heading. */
    private Bitmap makeNeedleIcon(float heading) {
        int size = 96;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(6f);
        c.drawCircle(48f, 48f, 42f, p);

        c.save();
        c.rotate(-heading, 48f, 48f);
        Path north = new Path();
        north.moveTo(48f, 12f);
        north.lineTo(62f, 50f);
        north.lineTo(34f, 50f);
        north.close();
        p.setStyle(Paint.Style.FILL);
        c.drawPath(north, p);

        Path south = new Path();
        south.moveTo(48f, 84f);
        south.lineTo(62f, 50f);
        south.lineTo(34f, 50f);
        south.close();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4f);
        c.drawPath(south, p);
        c.restore();
        return bmp;
    }

    private static void drawFitted(Canvas c, Paint p, String text, float cx, float baseline,
                                   float maxSize, float maxWidth) {
        p.setTextSize(maxSize);
        float width = p.measureText(text);
        if (width > maxWidth) {
            p.setTextSize(maxSize * maxWidth / width);
        }
        c.drawText(text, cx, baseline, p);
    }

    @Override
    public void onDestroy() {
        provider.stop();
        sensorsStarted = false;
        running = false;
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
        notificationManager.cancel(ID_ARROW);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
