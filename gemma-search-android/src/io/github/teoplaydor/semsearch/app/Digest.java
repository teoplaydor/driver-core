package io.github.teoplaydor.semsearch.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import io.github.teoplaydor.semsearch.R;
import io.github.teoplaydor.semsearch.core.NoteText;
import io.github.teoplaydor.semsearch.core.PhotoEvent;
import io.github.teoplaydor.semsearch.core.Spoken;

/**
 * The morning digest: once a day at the time chosen, one quiet notification — today's reminders by the hour, what is
 * left to tick in the lists, what waits at places, what was found in photos for today and tomorrow (a ticket, an
 * appointment). Nothing to say: nothing shown.
 */
public final class Digest extends BroadcastReceiver {
    static final String ACTION = "io.github.teoplaydor.semsearch.DIGEST";
    static final String PREF_ON = "digest_on", PREF_AT = "digest_at";
    private static final String CHANNEL = "digest";
    /** 8:00 by default (minutes of the day). */
    static final int DEFAULT_AT = 8 * 60;

    static boolean on(Context c) {
        return Engine.get(c).prefs().getBoolean(PREF_ON, false);
    }

    /** Minutes of the day it comes at. */
    static int at(Context c) {
        return Engine.get(c).prefs().getInt(PREF_AT, DEFAULT_AT);
    }

    static void set(Context c, boolean on, int minutes) {
        Engine.get(c).prefs().edit().putBoolean(PREF_ON, on).putInt(PREF_AT, minutes).apply();
        schedule(c);
    }

    private static PendingIntent pending(Context c) {
        return PendingIntent.getBroadcast(c, 7, new Intent(c, Digest.class).setAction(ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000 /* IMMUTABLE */);
    }

    /** The next digest set (or taken away when it is off). */
    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pending(c);
        if (!on(c)) {
            am.cancel(pi);
            return;
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next(System.currentTimeMillis(), at(c)), pi);
    }

    /** The next {@code minutes}-of-the-day after {@code now}. */
    static long next(long now, int minutes) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, minutes / 60);
        c.set(Calendar.MINUTE, minutes % 60);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= now) c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    @Override
    public void onReceive(final Context c, Intent i) {
        final Context app = c.getApplicationContext();
        final PendingResult held = goAsync();
        Engine.get(app).afterOpen(new Runnable() {
            @Override
            public void run() {
                try {
                    IndexStore s = Engine.get(app).store();
                    if (s != null) show(app, lines(s.notes(), s.photoEvents(), System.currentTimeMillis()));
                } finally {
                    schedule(app);
                    if (held != null) held.finish();
                }
            }
        });
    }

    /**
     * What the digest says, a line each: «9:00 · Позвонить маме», «Купить: молоко, хлеб и ещё 2», «Дома: полить цветы»,
     * «Завтра 14:35 · Поезд Москва — Казань (фото)». Empty: nothing to say.
     */
    static List<String> lines(List<IndexStore.Item> notes, List<PhotoEvent> found, long now) {
        List<String> out = new ArrayList<String>();
        Calendar end = Calendar.getInstance();
        end.setTimeInMillis(now);
        end.set(Calendar.HOUR_OF_DAY, 23);
        end.set(Calendar.MINUTE, 59);
        end.set(Calendar.SECOND, 59);
        long todayEnd = end.getTimeInMillis(), tomorrowEnd = todayEnd + 86_400_000L;
        DateFormat hm = DateFormat.getTimeInstance(DateFormat.SHORT);
        // today's reminders, by the hour
        List<IndexStore.Item> today = new ArrayList<IndexStore.Item>();
        for (IndexStore.Item n : notes) if (n.remind > now - 60_000L && n.remind <= todayEnd) today.add(n);
        Collections.sort(today, new Comparator<IndexStore.Item>() {
            @Override
            public int compare(IndexStore.Item a, IndexStore.Item b) {
                return Long.compare(a.remind, b.remind);
            }
        });
        for (IndexStore.Item n : today) {
            out.add(hm.format(new Date(n.remind)) + " · " + NoteText.title(n.body, 50)
                    + (n.repeat != Spoken.ONCE ? " (" + Spoken.repeatLabel(n.repeat) + ")" : ""));
        }
        // found in photos: today and tomorrow
        if (found != null) {
            for (PhotoEvent e : found) {
                if (e.at <= now || e.at > tomorrowEnd) continue;
                out.add((e.at <= todayEnd ? "" : "Завтра ") + hm.format(new Date(e.at)) + " · " + e.title + " (фото)");
            }
        }
        // lists: what is left to tick
        for (IndexStore.Item n : notes) {
            String[] ls = NoteText.lines(n.body);
            List<String> open = new ArrayList<String>();
            String head = null;
            for (String l : ls) {
                int b = NoteText.box(l);
                if (b == 1) open.add(NoteText.rest(l));
                else if (b == 0 && head == null && !l.trim().isEmpty()) head = l.trim();
            }
            if (open.isEmpty()) continue;
            StringBuilder sb = new StringBuilder(head != null ? head.replaceAll(":$", "") + ": " : "");
            for (int k = 0; k < Math.min(3, open.size()); k++) sb.append(k > 0 ? ", " : "").append(open.get(k));
            if (open.size() > 3) sb.append(" и ещё ").append(open.size() - 3);
            out.add(sb.toString());
            if (out.size() >= 12) break;
        }
        // places
        for (IndexStore.Item n : notes) {
            if (n.place == null) continue;
            out.add((n.leaving ? "Уходя: " + n.place : n.place) + " · " + NoteText.title(n.body, 50));
        }
        return out;
    }

    static void show(Context c, List<String> lines) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || lines.isEmpty()) return;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Class<?> ch = Class.forName("android.app.NotificationChannel");
                Object channel = ch.getConstructor(String.class, CharSequence.class, int.class)
                        .newInstance(CHANNEL, "Утренняя сводка", 3 /* IMPORTANCE_DEFAULT */);
                NotificationManager.class.getMethod("createNotificationChannel", ch).invoke(nm, channel);
                b = (Notification.Builder) Notification.Builder.class.getConstructor(Context.class, String.class).newInstance(c, CHANNEL);
            } catch (Exception e) {
                b = new Notification.Builder(c);
            }
        } else {
            b = new Notification.Builder(c);
        }
        Notification.InboxStyle style = new Notification.InboxStyle();
        for (int k = 0; k < Math.min(lines.size(), 7); k++) style.addLine(lines.get(k));
        if (lines.size() > 7) style.setSummaryText("и ещё " + (lines.size() - 7));
        Intent open = new Intent(c, MainActivity.class).setAction(MainActivity.ACTION_NOTES)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        b.setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentTitle("Сегодня")
                .setContentText(lines.get(0) + (lines.size() > 1 ? " и ещё " + (lines.size() - 1) : ""))
                .setStyle(style)
                .setContentIntent(PendingIntent.getActivity(c, 8, open, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_REMINDER);
        try {
            nm.notify("digest", 1, b.build());
        } catch (SecurityException e) {
            // notifications not allowed
        }
    }
}
