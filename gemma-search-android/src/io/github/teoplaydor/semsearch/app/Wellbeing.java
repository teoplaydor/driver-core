package io.github.teoplaydor.semsearch.app;

import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import io.github.teoplaydor.semsearch.R;

/**
 * «Не залипай / разомнись»: a gentle nudge when the screen has been on continuously for too long (doom-scrolling, or
 * sitting too long with the phone). It reads only the phone's own usage statistics (how long the screen has been on,
 * and which app is in front) — no microphone, no camera, nothing recorded. Energy-cheap: a short check every few
 * minutes by an inexact alarm, nothing kept running. The user grants «Usage access» once in the system settings.
 */
public final class Wellbeing {
    static final String PREF_ON = "wellbeing_on", PREF_AFTER = "wellbeing_after", PREF_QUIET = "wellbeing_quiet";
    private static final String CHANNEL = "wellbeing", PREF_NUDGED = "wellbeing_nudged";
    static final String ACTION = "io.github.teoplaydor.semsearch.STRETCH";
    /** The screen-on time, in minutes, that earns a nudge (by default). */
    static final int DEFAULT_AFTER = 40;
    /** How often the check runs. */
    static final long CHECK_MS = 10 * 60_000L;
    /** After a nudge, nothing more for this long (the screen is still on — don't badger). */
    static final long COOLDOWN_MS = 25 * 60_000L;
    /** How far back events are read. */
    static final long WINDOW_MS = 6 * 3_600_000L;
    /** By default nothing from 23:00 to 8:00. */
    static final int QUIET_FROM = 23, QUIET_TO = 8;

    /** The system's screen on/off usage events (API 29+). */
    private static final int SCREEN_INTERACTIVE = 15, SCREEN_NON_INTERACTIVE = 16, ACTIVITY_RESUMED = 1;

    private Wellbeing() {
    }

    static SharedPreferences prefs(Context c) {
        return Engine.get(c).prefs();
    }

    public static boolean on(Context c) {
        return prefs(c).getBoolean(PREF_ON, false);
    }

    /** The screen-on minutes before a nudge. */
    public static int after(Context c) {
        return prefs(c).getInt(PREF_AFTER, DEFAULT_AFTER);
    }

    static boolean quiet(Context c) {
        return prefs(c).getBoolean(PREF_QUIET, true);
    }

    public static void set(Context c, boolean on, int afterMin, boolean quiet) {
        prefs(c).edit().putBoolean(PREF_ON, on).putInt(PREF_AFTER, afterMin).putBoolean(PREF_QUIET, quiet).apply();
        schedule(c);
    }

    /** Android 5+: whether the user has given «Usage access» (this reads nothing but screen-on time and the app in front). */
    public static boolean hasAccess(Context c) {
        AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
        if (ops == null) return false;
        try {
            int mode;
            if (Build.VERSION.SDK_INT >= 29) {
                mode = (Integer) AppOpsManager.class.getMethod("unsafeCheckOpNoThrow", String.class, int.class, String.class)
                        .invoke(ops, "android:get_usage_stats", Process.myUid(), c.getPackageName());
            } else {
                mode = ops.checkOpNoThrow("android:get_usage_stats", Process.myUid(), c.getPackageName());
            }
            if (mode == AppOpsManager.MODE_DEFAULT) {
                return c.checkCallingOrSelfPermission("android.permission.PACKAGE_USAGE_STATS") == PackageManager.PERMISSION_GRANTED;
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    /** Where the user turns «Usage access» on. */
    static Intent accessSettings() {
        return new Intent("android.settings.USAGE_ACCESS_SETTINGS");
    }

    // ------------------------------------------------------------------ scheduling

    private static PendingIntent pending(Context c) {
        return PendingIntent.getBroadcast(c, 9, new Intent(c, WellbeingCheck.class).setAction(ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000 /* IMMUTABLE */);
    }

    /** The next check armed (or taken away when it is off). */
    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pending(c);
        if (!on(c)) {
            am.cancel(pi);
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel("stretch", 1);
            return;
        }
        // inexact: the phone batches it with its own wake-ups, so it costs almost nothing
        am.set(AlarmManager.RTC, System.currentTimeMillis() + CHECK_MS, pi);
    }

    // ------------------------------------------------------------------ the check

    /** The check: read how long the screen has been on; nudge when it is too long; arm the next check. */
    public static void check(Context c) {
        try {
            if (!on(c)) return;
            if (!hasAccess(c)) return;
            long now = System.currentTimeMillis();
            long onFor = screenOnFor(events(c, now), now);
            long nudged = prefs(c).getLong(PREF_NUDGED, 0);
            if (onFor >= after(c) * 60_000L && !isQuiet(c, now) && now - nudged > COOLDOWN_MS) {
                nudge(c, onFor, currentApp(c, now));
                prefs(c).edit().putLong(PREF_NUDGED, now).apply();
            }
        } finally {
            schedule(c);
        }
    }

    /** No nudges at night (when quiet hours are on). */
    public static boolean isQuiet(Context c, long now) {
        if (!quiet(c)) return false;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int h = cal.get(Calendar.HOUR_OF_DAY);
        return QUIET_FROM > QUIET_TO ? (h >= QUIET_FROM || h < QUIET_TO) : (h >= QUIET_FROM && h < QUIET_TO);
    }

    /** {time, on} pairs (on = 1 screen came on, 0 went off) from the system's usage events, in time order. */
    static List<long[]> events(Context c, long now) {
        List<long[]> out = new ArrayList<long[]>();
        UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return out;
        UsageEvents ev = usm.queryEvents(now - WINDOW_MS, now);
        if (ev == null) return out;
        UsageEvents.Event e = new UsageEvents.Event();
        boolean anyScreen = false;
        List<long[]> foreground = new ArrayList<long[]>();
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            int t = e.getEventType();
            if (t == SCREEN_INTERACTIVE) {
                out.add(new long[]{e.getTimeStamp(), 1});
                anyScreen = true;
            } else if (t == SCREEN_NON_INTERACTIVE) {
                out.add(new long[]{e.getTimeStamp(), 0});
                anyScreen = true;
            } else if (t == ACTIVITY_RESUMED) {
                foreground.add(new long[]{e.getTimeStamp(), 1});
            }
        }
        // old phones (before the screen events): an app in front means the screen is on
        if (!anyScreen) out = foreground;
        Collections.sort(out, new Comparator<long[]>() {
            @Override
            public int compare(long[] a, long[] b) {
                return Long.compare(a[0], b[0]);
            }
        });
        return out;
    }

    /**
     * How long the screen has been on continuously, ending at {@code now}: the time since the last «off» (or the window
     * start when there was none, as long as something says the screen is on). 0 when the screen is off now.
     */
    public static long screenOnFor(List<long[]> events, long now) {
        // the start of the current continuous on-period: an «on» with the screen off (or nothing) before it
        long start = -1;
        boolean onNow = false;
        for (long[] e : events) {
            if (e[1] != 0) {
                if (!onNow) {
                    start = e[0];
                    onNow = true;
                }
            } else {
                onNow = false;
                start = -1;
            }
        }
        if (!onNow) return 0; // off now
        return Math.max(0, now - start);
    }

    /** The app in front now, by name (or "" when unknown). */
    static String currentApp(Context c, long now) {
        UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return "";
        UsageEvents ev = usm.queryEvents(now - 30 * 60_000L, now);
        if (ev == null) return "";
        UsageEvents.Event e = new UsageEvents.Event();
        String last = null;
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            if (e.getEventType() == ACTIVITY_RESUMED) last = e.getPackageName();
        }
        if (last == null || last.equals(c.getPackageName())) return "";
        try {
            PackageManager pm = c.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(last, 0)));
        } catch (Exception ex) {
            return "";
        }
    }

    // ------------------------------------------------------------------ the nudge

    static void nudge(Context c, long onForMs, String app) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Class<?> ch = Class.forName("android.app.NotificationChannel");
                Object channel = ch.getConstructor(String.class, CharSequence.class, int.class)
                        .newInstance(CHANNEL, "Разомнись", 3 /* IMPORTANCE_DEFAULT */);
                NotificationManager.class.getMethod("createNotificationChannel", ch).invoke(nm, channel);
                b = (Notification.Builder) Notification.Builder.class.getConstructor(Context.class, String.class).newInstance(c, CHANNEL);
            } catch (Exception e) {
                b = new Notification.Builder(c);
            }
        } else {
            b = new Notification.Builder(c);
        }
        long min = Math.round(onForMs / 60_000.0);
        String text = "Экран включён уже " + min + " мин" + (app.isEmpty() ? "" : " · " + app) + ". Пора отвлечься и размяться.";
        b.setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentTitle("Разомнись")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_REMINDER);
        try {
            nm.notify("stretch", 1, b.build());
        } catch (SecurityException e) {
            // notifications not allowed
        }
    }
}
