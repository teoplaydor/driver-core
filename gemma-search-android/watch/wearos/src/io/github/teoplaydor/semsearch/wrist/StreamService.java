package io.github.teoplaydor.semsearch.wrist;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.os.Vibrator;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Random;
import java.util.UUID;

import io.github.teoplaydor.semsearch.core.WristGesture;

/**
 * The watch's side of «tracks by a turn of the wrist»: finds the phone by its Bluetooth LE service, connects, says
 * HELLO and, while the phone says music is playing, listens to the accelerometer (25 samples a second) and the heading
 * (the rotation vector: with the arm hanging only it sees a twist) and finds the turns itself (the phone's own
 * {@link WristGesture.Finder}): one short packet per turn goes to the phone, which decides which way it was and answers
 * with a byte, the watch vibrates. The phone's radio and processor stay idle in between.
 * When the music stops the phone says «pause» and the sensor is off (the processor may sleep); when the connection
 * drops, the phone is looked for again.
 */
public final class StreamService extends Service implements SensorEventListener {
    static final String ACTION_STOP = "io.github.teoplaydor.semsearch.wrist.STOP";
    static final int NOTIFICATION = 1;
    static final int RATE = 25;
    static final UUID SERVICE = UUID.fromString(WristGesture.SERVICE), SAMPLES = UUID.fromString(WristGesture.SAMPLES),
            EVENTS = UUID.fromString(WristGesture.EVENTS),
            CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    /** More waiting than this and the oldest packets go: the turn is live or it is useless. */
    static final int MAX_QUEUE = 8;
    private static final String CHANNEL = "stream";

    private static volatile String status = "";
    private static volatile StreamService running;
    private static Runnable listener;

    private final Handler h = new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private boolean scanning;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic samples;
    private boolean linked, streaming, writing;
    private final ArrayDeque<byte[]> queue = new ArrayDeque<byte[]>();
    private int seq;
    /** The turns are found here, on the watch; the sample times (ms) by the sensor's clock. */
    private final WristGesture.Finder finder = new WristGesture.Finder();
    private long lastNs;
    private double sampleMs;
    /** The heading of the watch's y axis (radians, north 0, east π/2) from the rotation vector, and when (ns); NaN — none. */
    private double heading = Double.NaN;
    private long headingNs;
    private final float[] rot = new float[9];
    private SensorManager sensors;
    private PowerManager.WakeLock wake;
    private int watchId;
    /** Packets handed to Bluetooth, the last answer heard (for the screen's tests). */
    public int sent, heard = -1;

    // ------------------------------------------------------------------ state for the screen

    public static String status() {
        return status;
    }

    public static boolean isRunning() {
        return running != null;
    }

    static void listen(Runnable r) {
        listener = r;
    }

    private void setStatus(String s) {
        status = s;
        Runnable r = listener;
        if (r != null) h.post(r);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        try {
            if (nm != null && running != null) nm.notify(NOTIFICATION, notification());
        } catch (RuntimeException e) {
            // notifications off: the service runs anyway
        }
    }

    // ------------------------------------------------------------------ the service

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
        startForeground(NOTIFICATION, notification());
        SharedPreferences p = getSharedPreferences("wrist", MODE_PRIVATE);
        watchId = p.getInt("id", 0);
        if (watchId == 0) {
            // who this watch is to the phone: random, kept (the phone listens only to the watch it met first)
            watchId = new Random().nextInt() | 1;
            p.edit().putInt("id", watchId).apply();
        }
        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wrist:stream");
        wake.setReferenceCounted(false);
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
        registerReceiver(bluetooth, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        scan();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = null;
        try {
            unregisterReceiver(bluetooth);
        } catch (RuntimeException e) {
            // not registered
        }
        stopScan();
        drop();
        h.removeCallbacksAndMessages(null);
        status = "";
        Runnable r = listener;
        if (r != null) r.run();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private final BroadcastReceiver bluetooth = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            if (st == BluetoothAdapter.STATE_ON) scan();
            else if (st == BluetoothAdapter.STATE_OFF) {
                stopScan();
                drop();
                setStatus("Bluetooth выключен");
            }
        }
    };

    // ------------------------------------------------------------------ finding the phone

    private final Runnable rescan = new Runnable() {
        @Override
        public void run() {
            scan();
        }
    };

    void scan() {
        if (running == null || gatt != null || scanning) return;
        try {
            if (adapter == null || !adapter.isEnabled()) {
                setStatus("Bluetooth выключен");
                return;
            }
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) {
                setStatus("Bluetooth не отвечает");
                return;
            }
            ScanFilter f = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE)).build();
            ScanSettings s = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build();
            scanner.startScan(Collections.singletonList(f), s, found);
            scanning = true;
            setStatus("ищу телефон…");
        } catch (SecurityException e) {
            setStatus("нет разрешения «Устройства поблизости»");
        }
    }

    private void stopScan() {
        if (!scanning) return;
        scanning = false;
        try {
            scanner.stopScan(found);
        } catch (RuntimeException e) {
            // Bluetooth already off
        }
    }

    private final ScanCallback found = new ScanCallback() {
        @Override
        public void onScanResult(int type, final ScanResult r) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    if (gatt != null) return;
                    stopScan();
                    connect(r.getDevice());
                }
            });
        }

        @Override
        public void onScanFailed(int code) {
            scanning = false;
            setStatus("поиск не удался (" + code + "), повторю");
            h.postDelayed(rescan, 10_000);
        }
    };

    private void connect(BluetoothDevice dev) {
        try {
            setStatus("подключаюсь к телефону…");
            gatt = dev.connectGatt(this, false, link, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) h.postDelayed(rescan, 5000);
        } catch (SecurityException e) {
            setStatus("нет разрешения «Устройства поблизости»");
        }
    }

    /** The connection gone: everything dropped, the phone looked for again in a moment. */
    private void lost() {
        drop();
        if (running == null) return;
        setStatus("связь с телефоном потеряна — ищу");
        h.postDelayed(rescan, 2000);
    }

    private void drop() {
        pause();
        linked = false;
        samples = null;
        queue.clear();
        writing = false;
        if (gatt != null) {
            try {
                gatt.close();
            } catch (RuntimeException e) {
                // gone
            }
        }
        gatt = null;
    }

    // ------------------------------------------------------------------ the link (the callbacks come on Bluetooth's thread)

    private final BluetoothGattCallback link = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(final BluetoothGatt g, int st, final int state) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    if (g != gatt) return;
                    if (state == BluetoothProfile.STATE_CONNECTED) {
                        g.discoverServices();
                    } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                        lost();
                    }
                }
            });
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt g, int st) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    if (g != gatt) return;
                    BluetoothGattService s = g.getService(SERVICE);
                    BluetoothGattCharacteristic ev = s == null ? null : s.getCharacteristic(EVENTS);
                    samples = s == null ? null : s.getCharacteristic(SAMPLES);
                    if (samples == null || ev == null) {
                        setStatus("это не тот телефон — ищу дальше");
                        lost();
                        return;
                    }
                    // the phone's answers: switched on here and on the phone's side
                    g.setCharacteristicNotification(ev, true);
                    BluetoothGattDescriptor d = ev.getDescriptor(CCCD);
                    if (d != null) {
                        d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        if (g.writeDescriptor(d)) return; // hello after it is written
                    }
                    linked();
                }
            });
        }

        @Override
        public void onDescriptorWrite(final BluetoothGatt g, BluetoothGattDescriptor d, int st) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    if (g == gatt && !linked) linked();
                }
            });
        }

        @Override
        public void onCharacteristicWrite(final BluetoothGatt g, BluetoothGattCharacteristic ch, int st) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    if (g != gatt) return;
                    writing = false;
                    pump();
                }
            });
        }

        @Override
        public void onCharacteristicChanged(final BluetoothGatt g, BluetoothGattCharacteristic ch) {
            final byte[] v = ch.getValue();
            if (v == null || v.length == 0 || !EVENTS.equals(ch.getUuid())) return;
            h.post(new Runnable() {
                @Override
                public void run() {
                    if (g == gatt) heard(v[0] & 0xff);
                }
            });
        }
    };

    private void linked() {
        linked = true;
        send(WristGesture.hello(WristGesture.VERSION_TURNS, watchId, RATE, 1000, "wearos"));
        setStatus("подключено · ждёт музыку");
    }

    /** One byte from the phone. */
    void heard(int ev) {
        heard = ev;
        switch (ev) {
            case WristGesture.EV_STREAM:
                stream();
                break;
            case WristGesture.EV_PAUSE:
                pause();
                setStatus("подключено · музыка не играет");
                break;
            case WristGesture.EV_NEXT:
                buzz(new long[]{0, 40});
                break;
            case WristGesture.EV_PREVIOUS:
                buzz(new long[]{0, 40, 90, 40});
                break;
            case WristGesture.EV_LEARNED:
                buzz(new long[]{0, 180});
                break;
            case WristGesture.EV_IGNORED:
                buzz(new long[]{0, 15});
                break;
            default:
                break;
        }
    }

    private void buzz(long[] pattern) {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) v.vibrate(pattern, -1);
    }

    // ------------------------------------------------------------------ the samples

    private void stream() {
        if (streaming || !linked) return;
        Sensor acc = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (acc == null) {
            setStatus("на часах нет акселерометра");
            return;
        }
        streaming = true;
        finder.reset();
        lastNs = 0;
        wake.acquire(4 * 3_600_000L);
        // 25 a second; the sensor may gather them for up to 0.1 s and hand them over together
        sensors.registerListener(this, acc, 1_000_000 / RATE, 100_000);
        // the heading (gyroscope and compass together): with the arm hanging only it sees a twist
        heading = Double.NaN;
        Sensor rv = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rv != null) sensors.registerListener(this, rv, 1_000_000 / RATE, 100_000);
        setStatus("подключено · повороты кисти слушаются");
    }

    private void pause() {
        if (!streaming) return;
        streaming = false;
        sensors.unregisterListener(this);
        queue.clear();
        if (wake.isHeld()) wake.release();
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (!streaming) return;
        if (e.sensor != null && e.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            // the watch's y axis in the world (east, north, up): the second column
            SensorManager.getRotationMatrixFromVector(rot, e.values);
            heading = Math.atan2(rot[1], rot[4]);
            headingNs = e.timestamp;
            return;
        }
        // the sensor's own time of the sample (batched samples come together but keep their times); without one, the
        // nominal pace
        sampleMs += e.timestamp > lastNs && lastNs > 0 ? (e.timestamp - lastNs) / 1e6 : 1000.0 / RATE;
        lastNs = e.timestamp;
        double h = !Double.isNaN(heading) && Math.abs(e.timestamp - headingNs) < 1_000_000_000L ? heading : Double.NaN;
        if (finder.sample(e.values[0], e.values[1], e.values[2], sampleMs, h)) {
            send(WristGesture.rollPacket(seq++ & 0xff, finder.twist(), finder.took(), finder.source(), finder.slope(), finder.slopeChange()));
        }
    }

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) {
    }

    /** Queued: one write at a time over Bluetooth (HELLO, a packet per turn), each answered by the phone. */
    private void send(byte[] p) {
        queue.add(p);
        while (queue.size() > MAX_QUEUE) queue.poll();
        pump();
    }

    private final Runnable retry = new Runnable() {
        @Override
        public void run() {
            pump();
        }
    };

    private void pump() {
        if (writing || gatt == null || samples == null || queue.isEmpty()) return;
        byte[] p = queue.peek();
        samples.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        samples.setValue(p);
        try {
            writing = gatt.writeCharacteristic(samples);
        } catch (SecurityException e) {
            writing = false;
        }
        if (writing) {
            queue.poll();
            sent++;
        } else {
            h.postDelayed(retry, 20); // Bluetooth busy: a moment later
        }
    }

    // ------------------------------------------------------------------ notification

    private Notification notification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Class<?> ch = Class.forName("android.app.NotificationChannel");
                Object channel = ch.getConstructor(String.class, CharSequence.class, int.class)
                        .newInstance(CHANNEL, "Пульт кистью", 2 /* IMPORTANCE_LOW */);
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                NotificationManager.class.getMethod("createNotificationChannel", ch).invoke(nm, channel);
                b = (Notification.Builder) Notification.Builder.class.getConstructor(Context.class, String.class).newInstance(this, CHANNEL);
            } catch (Exception e) {
                b = new Notification.Builder(this);
            }
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(getApplicationInfo().icon)
                .setContentTitle("Пульт кистью")
                .setContentText(status.isEmpty() ? "запускается" : status)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, RemoteActivity.class), 0x04000000));
        Intent stop = new Intent(this, StreamService.class).setAction(ACTION_STOP);
        b.addAction(new Notification.Action.Builder(0, "Выключить", PendingIntent.getService(this, 1, stop, 0x04000000)).build());
        return b.build();
    }
}
