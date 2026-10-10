package io.github.teoplaydor.semsearch.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;
import java.util.List;

import io.github.teoplaydor.semsearch.R;
import io.github.teoplaydor.semsearch.core.NoteText;
import io.github.teoplaydor.semsearch.core.Spoken;

/**
 * Reminders of notes: an alarm at the time set (exact where Android lets the app, else as close as it can while the
 * phone sleeps), then a notification with the note's first line and the rest under it; a tap opens the note, its
 * buttons put it off (10 minutes, an hour, tomorrow morning). A repeating one is set again for its next time. The alarm
 * carries the note's text (the notification needs no database) and the regular time of a repeating one (a put-off
 * reminder does not move it); after a reboot they are set again.
 */
public final class Reminders extends BroadcastReceiver {
    static final String ACTION = "io.github.teoplaydor.semsearch.REMIND", ACTION_SNOOZE = "io.github.teoplaydor.semsearch.SNOOZE";
    static final String EXTRA_NOTE = "note_id", EXTRA_TEXT = "note_text", EXTRA_AT = "remind_at", EXTRA_BASE = "remind_base",
            EXTRA_REPEAT = "remind_repeat", EXTRA_DELAY = "snooze_min";
    /** «Завтра в 9» among the put-off choices. */
    static final int TOMORROW = -1;
    private static final String CHANNEL = "reminders";

    /** The note's alarm set for {@code it.remind}, or taken away when that is 0 or past. */
    static void schedule(Context c, IndexStore.Item it) {
        schedule(c, it, it.remind);
    }

    /** @param base the reminder's regular time (a put-off one rings at {@code it.remind}, repeats from {@code base}) */
    static void schedule(Context c, IndexStore.Item it, long base) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pending(c, it.id, it.body, it.remind, base, it.repeat);
        if (it.remind <= System.currentTimeMillis()) {
            am.cancel(pi);
            return;
        }
        boolean exact = true;
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                exact = (Boolean) AlarmManager.class.getMethod("canScheduleExactAlarms").invoke(am);
            } catch (Exception e) {
                exact = false;
            }
        }
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it.remind, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it.remind, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it.remind, pi);
        }
    }

    static void cancel(Context c, long id) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pending(c, id, null, 0, 0, 0));
    }

    /** Every reminder still ahead set again (after a reboot alarms are gone). */
    static void scheduleAll(Context c, List<IndexStore.Item> notes) {
        for (IndexStore.Item it : notes) if (it.remind > System.currentTimeMillis()) schedule(c, it);
    }

    private static PendingIntent pending(Context c, long id, String text, long at, long base, int repeat) {
        Intent i = new Intent(c, Reminders.class).setAction(ACTION).putExtra(EXTRA_NOTE, id).putExtra(EXTRA_AT, at)
                .putExtra(EXTRA_BASE, base).putExtra(EXTRA_REPEAT, repeat);
        if (text != null) i.putExtra(EXTRA_TEXT, text);
        return PendingIntent.getBroadcast(c, (int) id, i, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000 /* FLAG_IMMUTABLE */);
    }

    @Override
    public void onReceive(final Context c, Intent i) {
        long id = i.getLongExtra(EXTRA_NOTE, -1);
        if (id < 0) return;
        final PendingResult held = goAsync();
        Runnable done = new Runnable() {
            @Override
            public void run() {
                if (held != null) held.finish();
            }
        };
        if (ACTION_SNOOZE.equals(i.getAction())) {
            // put off: the notification goes, the alarm comes again then
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel("note", (int) id);
            int delay = i.getIntExtra(EXTRA_DELAY, 10);
            Engine.get(c).snooze(id, snoozeTime(System.currentTimeMillis(), delay), i.getLongExtra(EXTRA_BASE, 0), done);
            return;
        }
        if (!ACTION.equals(i.getAction())) {
            done.run();
            return;
        }
        String text = i.getStringExtra(EXTRA_TEXT);
        long at = i.getLongExtra(EXTRA_AT, 0), base = i.getLongExtra(EXTRA_BASE, 0);
        notify(c, id, text == null ? "" : text, base > 0 ? base : at, i.getIntExtra(EXTRA_REPEAT, Spoken.ONCE));
        // the reminder done: none set on the note any more, or its next time (unless it was set anew meanwhile)
        Engine.get(c).reminded(id, at, base, done);
    }

    /** When a reminder put off by {@code minutes} (or till tomorrow 9:00) rings. */
    static long snoozeTime(long now, int minutes) {
        if (minutes != TOMORROW) return now + minutes * 60_000L;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.add(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, 9);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    static void notify(Context c, long id, String text) {
        notify(c, id, text, 0, Spoken.ONCE);
    }

    /** The notification: the note's first line, its text under it; a tap opens the note, the buttons put it off. */
    static void notify(Context c, long id, String text, long base, int repeat) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Class<?> ch = Class.forName("android.app.NotificationChannel");
                Object channel = ch.getConstructor(String.class, CharSequence.class, int.class)
                        .newInstance(CHANNEL, "Напоминания о заметках", 4 /* IMPORTANCE_HIGH */);
                NotificationManager.class.getMethod("createNotificationChannel", ch).invoke(nm, channel);
                b = (Notification.Builder) Notification.Builder.class.getConstructor(Context.class, String.class).newInstance(c, CHANNEL);
            } catch (Exception e) {
                b = new Notification.Builder(c);
            }
        } else {
            b = new Notification.Builder(c);
        }
        Intent open = new Intent(c, MainActivity.class).setAction(MainActivity.ACTION_OPEN_NOTE).putExtra(EXTRA_NOTE, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tap = PendingIntent.getActivity(c, (int) id, open, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000);
        String title = NoteText.title(text, 60);
        b.setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentTitle(title.isEmpty() ? "Напоминание" : title)
                .setContentText(NoteText.plain(text).replace('\n', ' '))
                .setStyle(new Notification.BigTextStyle().bigText(NoteText.plain(text)))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setPriority(Notification.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_ALL);
        if (repeat != Spoken.ONCE) b.setSubText(Spoken.repeatLabel(repeat));
        int k = 1;
        for (int[] choice : new int[][]{{10}, {60}, {TOMORROW}}) {
            Intent s = new Intent(c, Reminders.class).setAction(ACTION_SNOOZE).putExtra(EXTRA_NOTE, id)
                    .putExtra(EXTRA_DELAY, choice[0]).putExtra(EXTRA_BASE, base);
            PendingIntent pi = PendingIntent.getBroadcast(c, (int) (id * 4 + k++), s, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000);
            String label = choice[0] == TOMORROW ? "Завтра в 9" : choice[0] == 60 ? "Через час" : "Через 10 мин";
            b.addAction(new Notification.Action.Builder(0, label, pi).build());
        }
        try {
            nm.notify("note", (int) id, b.build());
        } catch (SecurityException e) {
            // notifications not allowed: nothing to show
        }
    }
}
