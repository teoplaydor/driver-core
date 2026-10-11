import static org.junit.Assert.*;

import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.os.Looper;
import android.os.Vibrator;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBluetoothGatt;
import org.robolectric.shadows.ShadowBluetoothGattServer;
import org.robolectric.shadows.ShadowBluetoothLeScanner;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPowerManager;
import org.robolectric.shadows.ShadowSensor;
import org.robolectric.shadows.ShadowSensorManager;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.github.teoplaydor.semsearch.app.WristLink;
import io.github.teoplaydor.semsearch.app.WristService;
import io.github.teoplaydor.semsearch.core.WristGesture;
import io.github.teoplaydor.semsearch.wrist.StreamService;

/**
 * The Wear OS watch app and the phone together, end to end, in one Robolectric runtime: the watch (StreamService) scans
 * for the phone's service, connects, raises the link to bigger packets, switches the phone's answers on, says HELLO;
 * the phone (WristLink) remembers it and, music playing, tells it to stream; the watch's accelerometer (a simulated
 * turn of the wrist, in m/s² as Android gives it) goes out in packets; the phone presses «next» / «previous» and
 * answers, the watch vibrates; the music stops — the watch stops its sensor and lets the processor sleep; the
 * connection drops — the watch looks for the phone again. Between the two, the test carries the bytes as the air would.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WristWatchTest {
    static final UUID SERVICE = UUID.fromString(WristGesture.SERVICE);

    Application app;
    ShadowBluetoothGattServer phone;
    ShadowBluetoothGatt watchLink;
    BluetoothGatt watchGatt;
    BluetoothGattCharacteristic samples, events;
    BluetoothGattDescriptor cccd;
    BluetoothDevice watchAsSeen;
    byte[] carried, answered;
    int id;

    /** Run the main thread until nothing is due, carrying each write and answer across. */
    void settle() {
        ShadowLooper ml = Shadows.shadowOf(Looper.getMainLooper());
        for (int i = 0; i < 100_000; i++) {
            carry();
            if (ml.isIdle()) break;
            ml.runOneTask();
        }
        carry();
    }

    void carry() {
        if (watchLink == null) return;
        byte[] b = watchLink.getLatestWrittenBytes();
        if (b != null && b != carried) {
            carried = b;
            if (b == BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) {
                phone.getGattServerCallback().onDescriptorWriteRequest(watchAsSeen, id++, cccd, false, true, 0, b);
            } else {
                phone.notifyOnCharacteristicWriteRequest(watchAsSeen, id++, samples, false, false, 0, b);
            }
        }
        byte[] ev = events.getValue();
        if (ev != null && ev != answered && answered != null) {
            answered = ev;
            watchLink.getGattCallback().onCharacteristicChanged(watchGatt, events);
        } else if (answered == null) {
            answered = ev; // the initial value is not an answer
        }
    }

    /** The watch's accelerometer, face up on the left wrist: still / turned around the forearm by deg and back. */
    void move(ShadowSensorManager sm, double... segs) {
        java.util.Random rnd = new java.util.Random(11);
        for (int i = 0; i < segs.length; i += 2) {
            double deg = segs[i], s = segs[i + 1];
            int n = (int) Math.round(s * 25);
            for (int k = 0; k < n; k++) {
                double t = k / 25.0, x = Math.sin(Math.PI * t / s), th = Math.toRadians(deg) * x * x;
                SensorEvent e = ShadowSensorManager.createSensorEvent(3, Sensor.TYPE_ACCELEROMETER);
                e.values[0] = (float) (0.1 * rnd.nextGaussian());
                e.values[1] = (float) (SensorManager.GRAVITY_EARTH * Math.sin(th) + 0.1 * rnd.nextGaussian());
                e.values[2] = (float) (SensorManager.GRAVITY_EARTH * Math.cos(th) + 0.1 * rnd.nextGaussian());
                sm.sendSensorEventToListeners(e);
                settle();
            }
        }
    }

    @Test
    public void watchAndPhone() {
        app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).grantPermissions("android.permission.BLUETOOTH_ADVERTISE", "android.permission.BLUETOOTH_CONNECT",
                "android.permission.BLUETOOTH_SCAN", "android.permission.POST_NOTIFICATIONS");
        Shadows.shadowOf(app.getPackageManager()).setSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE, true);
        BluetoothAdapter ad = ((BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
        Shadows.shadowOf(ad).setEnabled(true);
        Shadows.shadowOf(ad).setIsMultipleAdvertisementSupported(true);
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        Shadows.shadowOf(am).setIsMusicActive(true);
        SensorManager sensors = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
        ShadowSensorManager ssm = Shadows.shadowOf(sensors);
        ssm.addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER));
        Vibrator vib = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
        Shadows.shadowOf(vib).setHasVibrator(true);

        // the phone: on, its GATT server up and advertising
        WristLink.setOn(app, true);
        ServiceController<WristService> pc = Robolectric.buildService(WristService.class).create();
        WristLink.Server server = WristLink.current();
        phone = Shadows.shadowOf(server.gatt);
        BluetoothGattService svc = server.gatt.getService(SERVICE);
        samples = svc.getCharacteristic(UUID.fromString(WristGesture.SAMPLES));
        events = svc.getCharacteristic(UUID.fromString(WristGesture.EVENTS));
        cccd = events.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));

        // the watch: started, looking for the phone's service
        ServiceController<StreamService> wc = Robolectric.buildService(StreamService.class).create();
        StreamService w = wc.get();
        ShadowBluetoothLeScanner scanner = Shadows.shadowOf(ad.getBluetoothLeScanner());
        assertEquals("one scan", 1, scanner.getActiveScans().size());
        assertEquals("by the phone's service", SERVICE,
                scanner.getActiveScans().get(0).scanFilters().get(0).getServiceUuid().getUuid());
        assertTrue(StreamService.status(), StreamService.status().contains("ищу телефон"));

        // found: the watch connects
        BluetoothDevice phoneAsSeen = ad.getRemoteDevice("5A:11:22:33:44:55");
        for (ScanCallback cb : scanner.getScanCallbacks()) cb.onScanResult(1, new ScanResult(phoneAsSeen, null, -58, 0));
        settle();
        assertTrue("the scan stops", scanner.getActiveScans().isEmpty());
        watchGatt = Shadows.shadowOf(phoneAsSeen).getBluetoothGatts().get(0);
        watchLink = Shadows.shadowOf(watchGatt);
        watchLink.addDiscoverableService(svc);
        watchLink.allowCharacteristicNotification(events);
        watchAsSeen = ad.getRemoteDevice("7B:00:00:00:00:01");
        phone.notifyConnection(watchAsSeen);
        watchLink.notifyConnection(phoneAsSeen.getAddress());
        settle();

        // HELLO taken, the watch remembered; music plays, so: stream
        assertTrue(WristLink.status(), WristLink.status().contains("wearos") && WristLink.status().contains("25 Гц"));
        assertEquals("told to stream", WristGesture.EV_STREAM, w.heard);
        assertTrue("the accelerometer on", ssm.hasListener(w));
        assertTrue("awake while streaming", ShadowPowerManager.getLatestWakeLock().isHeld());
        assertTrue(StreamService.status(), StreamService.status().contains("слушаются"));

        // a turn to the right: the phone presses «next», the watch buzzes once
        int before = w.sent;
        move(ssm, 0, 1, 70, 0.6, 0, 1);
        assertTrue("samples went out in big packets: " + (w.sent - before), w.sent - before >= 3 && w.sent - before <= 5);
        assertEquals("next", WristGesture.EV_NEXT, server.lastEvent);
        assertEquals("the watch heard", WristGesture.EV_NEXT, w.heard);
        assertTrue(Arrays.toString(Shadows.shadowOf(vib).getPattern()), Arrays.equals(new long[]{0, 40}, Shadows.shadowOf(vib).getPattern()));
        assertTrue(WristLink.last(app).contains("следующий"));
        // to the left: «previous», twice
        move(ssm, -70, 0.6, 0, 1);
        assertEquals("previous", WristGesture.EV_PREVIOUS, server.lastEvent);
        assertTrue(Arrays.equals(new long[]{0, 40, 90, 40}, Shadows.shadowOf(vib).getPattern()));

        // the music stops: the watch is told to pause, its sensor off, the processor free to sleep
        Shadows.shadowOf(am).setIsMusicActive(false);
        ShadowLooper.idleMainLooper(2100, TimeUnit.MILLISECONDS);
        settle();
        assertEquals(WristGesture.EV_PAUSE, w.heard);
        assertFalse("the accelerometer off", ssm.hasListener(w));
        assertFalse("asleep", ShadowPowerManager.getLatestWakeLock().isHeld());
        int quiet = w.sent;
        move(ssm, 0, 1, 70, 0.6, 0, 1);
        assertEquals("nothing sent while paused", quiet, w.sent);
        // and plays again: streaming again
        Shadows.shadowOf(am).setIsMusicActive(true);
        ShadowLooper.idleMainLooper(2100, TimeUnit.MILLISECONDS);
        settle();
        assertEquals(WristGesture.EV_STREAM, w.heard);
        assertTrue(ssm.hasListener(w));
        move(ssm, 0, 1, 70, 0.6, 0, 1);
        assertEquals("next again", WristGesture.EV_NEXT, server.lastEvent);

        // the connection drops: the watch stops and looks for the phone again
        watchLink.notifyDisconnection(phoneAsSeen.getAddress());
        phone.notifyDisconnection(watchAsSeen);
        settle();
        assertFalse(ssm.hasListener(w));
        assertTrue(StreamService.status(), StreamService.status().contains("потеряна"));
        ShadowLooper.idleMainLooper(2100, TimeUnit.MILLISECONDS);
        assertEquals("looking again", 1, scanner.getActiveScans().size());
        assertTrue(WristLink.status(), WristLink.status().contains("отключились"));

        // off on the watch: nothing left running
        wc.destroy();
        assertTrue(scanner.getActiveScans().isEmpty());
        assertFalse(StreamService.isRunning());
        WristLink.setOn(app, false);
        pc.destroy();
    }
}
