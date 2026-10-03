package QiblaCompass.blackfalcon.jan;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.Surface;

import java.util.Arrays;

/**
 * Reads the phone sensors and reports a smooth compass heading (0-360, magnetic north).
 * Flat phone  -> direction the top of the phone points.
 * Upright phone -> direction the back of the phone faces (like a camera).
 */
public class HeadingProvider implements SensorEventListener {

    public interface Listener {
        void onHeading(float degrees);
    }

    private final Context context;
    private final Listener listener;
    private final double smoothing;
    private final SensorManager sensorManager;
    private final Sensor rotationSensor;
    private final Sensor accelSensor;
    private final Sensor magnetSensor;

    private final float[] rotMatrix = new float[9];
    private final float[] remapped = new float[9];
    private final float[] orientation = new float[3];
    private final float[] gravity = new float[3];
    private final float[] geomagnetic = new float[3];
    private boolean haveGravity, haveMagnet;

    private double sinAvg, cosAvg;
    private boolean initialised;

    public static boolean isSupported(Context context) {
        SensorManager sm = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        return sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null
                || (sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
                && sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null);
    }

    public HeadingProvider(Context context, Listener listener, double smoothing) {
        this.context = context;
        this.listener = listener;
        this.smoothing = smoothing;
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        magnetSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
    }

    public boolean start(int delay) {
        initialised = false;
        haveGravity = false;
        haveMagnet = false;
        if (rotationSensor != null) {
            return sensorManager.registerListener(this, rotationSensor, delay);
        }
        if (accelSensor != null && magnetSensor != null) {
            boolean a = sensorManager.registerListener(this, accelSensor, delay);
            boolean m = sensorManager.registerListener(this, magnetSensor, delay);
            return a && m;
        }
        return false;
    }

    public void stop() {
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        boolean ready = false;

        switch (event.sensor.getType()) {
            case Sensor.TYPE_ROTATION_VECTOR:
                float[] v = event.values.length > 4 ? Arrays.copyOf(event.values, 4) : event.values;
                SensorManager.getRotationMatrixFromVector(rotMatrix, v);
                ready = true;
                break;
            case Sensor.TYPE_ACCELEROMETER:
                lowPass(event.values, gravity, haveGravity);
                haveGravity = true;
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                lowPass(event.values, geomagnetic, haveMagnet);
                haveMagnet = true;
                break;
            default:
                return;
        }

        if (!ready && haveGravity && haveMagnet) {
            ready = SensorManager.getRotationMatrix(rotMatrix, null, gravity, geomagnetic);
        }
        if (!ready) return;

        double azimuth;
        if (Math.abs(rotMatrix[8]) > 0.7f) {
            // phone lying (mostly) flat: direction of the top edge of the screen
            int axisX, axisY;
            switch (displayRotation()) {
                case Surface.ROTATION_90:
                    axisX = SensorManager.AXIS_Y;
                    axisY = SensorManager.AXIS_MINUS_X;
                    break;
                case Surface.ROTATION_180:
                    axisX = SensorManager.AXIS_MINUS_X;
                    axisY = SensorManager.AXIS_MINUS_Y;
                    break;
                case Surface.ROTATION_270:
                    axisX = SensorManager.AXIS_MINUS_Y;
                    axisY = SensorManager.AXIS_X;
                    break;
                default:
                    axisX = SensorManager.AXIS_X;
                    axisY = SensorManager.AXIS_Y;
                    break;
            }
            SensorManager.remapCoordinateSystem(rotMatrix, axisX, axisY, remapped);
            SensorManager.getOrientation(remapped, orientation);
            azimuth = orientation[0];
        } else {
            // phone held upright: direction the back of the phone faces
            azimuth = Math.atan2(-rotMatrix[2], -rotMatrix[5]);
        }

        if (!initialised) {
            sinAvg = Math.sin(azimuth);
            cosAvg = Math.cos(azimuth);
            initialised = true;
        } else {
            // circular low-pass filter: no jump when passing 359 -> 0
            sinAvg += smoothing * (Math.sin(azimuth) - sinAvg);
            cosAvg += smoothing * (Math.cos(azimuth) - cosAvg);
        }
        double deg = Math.toDegrees(Math.atan2(sinAvg, cosAvg));
        listener.onHeading((float) ((deg + 360.0) % 360.0));
    }

    private int displayRotation() {
        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        Display d = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
        return d == null ? Surface.ROTATION_0 : d.getRotation();
    }

    private static void lowPass(float[] in, float[] out, boolean started) {
        for (int i = 0; i < 3; i++) {
            out[i] = started ? out[i] + 0.2f * (in[i] - out[i]) : in[i];
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
