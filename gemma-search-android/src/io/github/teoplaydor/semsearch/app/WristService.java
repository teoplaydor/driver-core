package io.github.teoplaydor.semsearch.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;

import io.github.teoplaydor.semsearch.R;

/**
 * Keeps the watch link ({@link WristLink.Server}) while «tracks by a turn of the wrist» is on: Android shows foreground
 * work with a quiet notification. Bluetooth turned off and on again: the link closes and reopens with it.
 */
public final class WristService extends Service {
    static final String ACTION_STOP = "io.github.teoplaydor.semsearch.WRIST_STOP";
    static final int NOTIFICATION = 4302;
    private static final String CHANNEL = "wrist";
    private static volatile WristService running;

    private final BroadcastReceiver bluetooth = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            WristLink.Server s = WristLink.server;
            if (s == null) return;
            if (st == BluetoothAdapter.STATE_ON) s.open();
            else if (st == BluetoothAdapter.STATE_TURNING_OFF || st == BluetoothAdapter.STATE_OFF) {
                s.close();
                WristLink.setStatus("Bluetooth выключен");
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
        startForeground(NOTIFICATION, notification(this));
        WristLink.Server s = new WristLink.Server(this);
        WristLink.server = s;
        registerReceiver(bluetooth, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        s.open();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            WristLink.setOn(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!WristLink.on(this)) {
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
        WristLink.Server s = WristLink.server;
        WristLink.server = null;
        if (s != null) s.close();
        WristLink.setStatus("");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** The notification follows the link's state. */
    static void update() {
        WristService s = running;
        if (s == null) return;
        NotificationManager nm = (NotificationManager) s.getSystemService(Context.NOTIFICATION_SERVICE);
        try {
            if (nm != null) nm.notify(NOTIFICATION, notification(s));
        } catch (RuntimeException e) {
            // notifications off: the service runs anyway
        }
    }

    static Notification notification(Context c) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Class<?> ch = Class.forName("android.app.NotificationChannel");
                Object channel = ch.getConstructor(String.class, CharSequence.class, int.class)
                        .newInstance(CHANNEL, "Часы: треки поворотом кисти", 1 /* IMPORTANCE_MIN */);
                ch.getMethod("setShowBadge", boolean.class).invoke(channel, false);
                NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                NotificationManager.class.getMethod("createNotificationChannel", ch).invoke(nm, channel);
                b = (Notification.Builder) Notification.Builder.class.getConstructor(Context.class, String.class).newInstance(c, CHANNEL);
            } catch (Exception e) {
                b = new Notification.Builder(c);
            }
        } else {
            b = new Notification.Builder(c);
        }
        String st = WristLink.status();
        b.setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentTitle("Треки поворотом кисти")
                .setContentText(st.isEmpty() ? "ждёт часы" : st)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setPriority(Notification.PRIORITY_MIN)
                .setCategory(Notification.CATEGORY_SERVICE);
        b.setContentIntent(PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), 0x04000000 /* FLAG_IMMUTABLE */));
        Intent stop = new Intent(c, WristService.class).setAction(ACTION_STOP);
        b.addAction(new Notification.Action.Builder(0, "Выключить", PendingIntent.getService(c, 2, stop, 0x04000000)).build());
        return b.build();
    }
}
