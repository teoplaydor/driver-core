package io.github.teoplaydor.semsearch.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.List;

import io.github.teoplaydor.semsearch.R;
import io.github.teoplaydor.semsearch.core.NoteText;

/**
 * Reminders of notes: an alarm at the time set (exact where Android lets the app, else as close as it can while the
 * phone sleeps), then a notification with the note's first line and the rest under it; a tap opens the note. The
 * alarm carries the note's text (the notification needs no database); after a reboot they are set again.
 */
public final class Reminders extends BroadcastReceiver {
    static final String ACTION = "io.github.teoplaydor.semsearch.REMIND";
    static final String EXTRA_NOTE = "note_id", EXTRA_TEXT = "note_text", EXTRA_AT = "remind_at";
    private static final String CHANNEL = "reminders";

    /** The note's alarm set for {@code it.remind}, or taken away when that is 0 or past. */
    static void schedule(Context c, IndexStore.Item it) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pending(c, it.id, it.body, it.remind);
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
        if (am != null) am.cancel(pending(c, id, null, 0));
    }

    /** Every reminder still ahead set again (after a reboot alarms are gone). */
    static void scheduleAll(Context c, List<IndexStore.Item> notes) {
        for (IndexStore.Item it : notes) if (it.remind > System.currentTimeMillis()) schedule(c, it);
    }

    private static PendingIntent pending(Context c, long id, String text, long at) {
        Intent i = new Intent(c, Reminders.class).setAction(ACTION).putExtra(EXTRA_NOTE, id).putExtra(EXTRA_AT, at);
        if (text != null) i.putExtra(EXTRA_TEXT, text);
        return PendingIntent.getBroadcast(c, (int) id, i, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000 /* FLAG_IMMUTABLE */);
    }

    @Override
    public void onReceive(Context c, Intent i) {
        if (!ACTION.equals(i.getAction())) return;
        long id = i.getLongExtra(EXTRA_NOTE, -1);
        String text = i.getStringExtra(EXTRA_TEXT);
        if (id < 0) return;
        notify(c, id, text == null ? "" : text);
        // the reminder done: none set on the note any more (unless it was set for another time meanwhile); the
        // process kept alive till that is written
        final PendingResult held = goAsync();
        Engine.get(c).reminded(id, i.getLongExtra(EXTRA_AT, 0), new Runnable() {
            @Override
            public void run() {
                if (held != null) held.finish();
            }
        });
    }

    /** The notification: the note's first line, its text under it; a tap opens the note. */
    static void notify(Context c, long id, String text) {
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
        try {
            nm.notify("note", (int) id, b.build());
        } catch (SecurityException e) {
            // notifications not allowed: nothing to show
        }
    }
}
