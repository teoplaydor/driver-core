package io.github.teoplaydor.semsearch.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;

import io.github.teoplaydor.semsearch.R;

/**
 * "Index while the phone is not in use": the first big indexing (or a big batch of new photos) runs
 * only while the screen is off, on the charger or with enough battery, and pauses the moment the phone
 * is picked up. A foreground service (a silent, minimised notification) keeps the process alive; the
 * battery-optimisation exemption lets it hold a wake lock through Doze and restart after reboot.
 */
public final class IdleIndex {
    static final String CHANNEL = "idle_index";
    /** Below this many new files the regular background job handles them without the service. */
    static final int BIG_BATCH = 50;
    /** On battery, work only above this charge. */
    static final int MIN_BATTERY = 50;

    private IdleIndex() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) {
        return prefs(c).getBoolean("idle_index", false);
    }

    static boolean onBattery(Context c) {
        return prefs(c).getBoolean("idle_battery", true);
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("idle_index", on).apply();
        if (on) start(c);
        else c.stopService(new Intent(c, IdleIndexService.class));
    }

    /** Starts the service; from the background this only works when Android allows it (exemption, boot). */
    static boolean start(Context c) {
        if (!enabled(c)) return false;
        try {
            Intent i = new Intent(c, IdleIndexService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                Context.class.getMethod("startForegroundService", Intent.class).invoke(c, i);
            } else {
                c.startService(i);
            }
            return true;
        } catch (Exception e) {
            android.util.Log.w("SemSearch", "idle index service not started", e);
            return false;
        }
    }

    /** The system will not freeze or limit the app (needed to keep working through Doze at night). */
    static boolean unrestricted(Context c) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    static Intent exemptionRequest(Context c) {
        Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        i.setData(android.net.Uri.parse("package:" + c.getPackageName()));
        return i;
    }

    // ------------------------------------------------------------------ conditions

    /** Why the work waits right now, or null when it may run. */
    static String waitReason(Context c) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        if (pm != null && pm.isInteractive()) return "ждёт, пока экран погаснет";
        Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        boolean charging = false;
        int percent = 100, tempTenths = 0;
        if (b != null) {
            int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            charging = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 || status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) percent = level * 100 / scale;
            tempTenths = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
        }
        if (!charging) {
            if (!onBattery(c)) return "ждёт зарядку";
            if (percent < MIN_BATTERY) return "ждёт зарядку: заряд ниже " + MIN_BATTERY + "%";
            if (pm != null && pm.isPowerSaveMode()) return "пауза: включена экономия батареи";
        }
        if (tempTenths >= 410 || thermalStatus(pm) >= 2) return "пауза: телефон тёплый";
        return null;
    }

    /** PowerManager.getCurrentThermalStatus() (API 29): 2 = moderate throttling and above. */
    private static int thermalStatus(PowerManager pm) {
        if (pm == null || Build.VERSION.SDK_INT < 29) return 0;
        try {
            return (Integer) PowerManager.class.getMethod("getCurrentThermalStatus").invoke(pm);
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ notification

    /** A silent, minimised notification: Android shows foreground work, but it should not get in the way. */
    static Notification notification(Context c, String text, int done, int total) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Class<?> ch = Class.forName("android.app.NotificationChannel");
                Object channel = ch.getConstructor(String.class, CharSequence.class, int.class)
                        .newInstance(CHANNEL, "Индексация в фоне", 1 /* IMPORTANCE_MIN */);
                ch.getMethod("setShowBadge", boolean.class).invoke(channel, false);
                NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                NotificationManager.class.getMethod("createNotificationChannel", ch).invoke(nm, channel);
                b = (Notification.Builder) Notification.Builder.class.getConstructor(Context.class, String.class)
                        .newInstance(c, CHANNEL);
            } catch (Exception e) {
                b = new Notification.Builder(c);
            }
        } else {
            b = new Notification.Builder(c);
        }
        b.setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentTitle("Галерея индексируется, пока телефон не используется")
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(Notification.PRIORITY_MIN)
                .setCategory(Notification.CATEGORY_PROGRESS);
        if (total > 0) b.setProgress(total, done, false);
        Intent open = new Intent(c, MainActivity.class);
        b.setContentIntent(android.app.PendingIntent.getActivity(c, 0, open, 0x04000000 /* FLAG_IMMUTABLE */));
        Intent stop = new Intent(c, IdleIndexService.class).setAction(IdleIndexService.ACTION_STOP);
        b.addAction(new Notification.Action.Builder(0, "Выключить",
                android.app.PendingIntent.getService(c, 1, stop, 0x04000000)).build());
        return b.build();
    }
}
