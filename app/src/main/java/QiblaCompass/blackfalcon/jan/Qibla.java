package QiblaCompass.blackfalcon.jan;

/** Qibla maths + the keys used to share the saved location between the app and the service. */
public final class Qibla {

    static final String PREFS = "qibla_prefs";
    static final String KEY_HAS_LOC = "has_loc";
    static final String KEY_LAT = "lat";
    static final String KEY_LON = "lon";

    private static final double KAABA_LAT = 21.422487;
    private static final double KAABA_LON = 39.826206;

    private Qibla() {
    }

    /** Great-circle bearing from (lat, lon) to the Kaaba, degrees clockwise from TRUE north (0-360). */
    static double bearing(double lat, double lon) {
        double p1 = Math.toRadians(lat);
        double p2 = Math.toRadians(KAABA_LAT);
        double dl = Math.toRadians(KAABA_LON - lon);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }

    /** Distance to the Kaaba in km (haversine). */
    static double distanceKm(double lat, double lon) {
        double p1 = Math.toRadians(lat);
        double p2 = Math.toRadians(KAABA_LAT);
        double dp = p2 - p1;
        double dl = Math.toRadians(KAABA_LON - lon);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * 6371.0 * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /** Signed angle (-180..180) you must turn from 'heading' to face 'target'. Positive = turn right. */
    static float relative(double target, double heading) {
        return (float) ((target - heading + 540.0) % 360.0 - 180.0);
    }
}
