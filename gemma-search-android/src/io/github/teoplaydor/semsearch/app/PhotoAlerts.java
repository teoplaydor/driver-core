package io.github.teoplaydor.semsearch.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;

import io.github.teoplaydor.semsearch.R;
import io.github.teoplaydor.semsearch.core.PhotoEvent;

/**
 * Something found on a new photo's text — a train, a flight, a show, an appointment, a warranty — offered as a
 * reminder: «Поезд 024А Москва — Казань, 21 октября 14:35 — напомнить за 2 часа?»; «Напомнить» makes a note to the
 * photo with that reminder. Each found thing is offered once; only photos of the last two weeks (older ones show their
 * finds in the viewer).
 */
public final class PhotoAlerts extends BroadcastReceiver {
    static final String ACTION = "io.github.teoplaydor.semsearch.PHOTO_REMIND";
    static final String EXTRA_PHOTO = "photo", EXTRA_TITLE = "title", EXTRA_AT = "at", EXTRA_BEFORE = "before";
    private static final String CHANNEL = "photo_finds", PREF = "photo_offered";
    static final long RECENT_MS = 14 * 86_400_000L;

    static void check(Context c, IndexStore.Item photo) {
        if (photo.ocr == null || photo.ocr.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - photo.date > RECENT_MS) return;
        Set<String> offered = new HashSet<String>(Engine.get(c).prefs().getStringSet(PREF, new HashSet<String>()));
        for (PhotoEvent e : PhotoEvent.find(photo.ocr, photo.date, now, TimeZone.getDefault())) {
            String key = photo.mediaId + ":" + e.at;
            if (e.remindAt() <= now || offered.contains(key)) continue;
            offered.add(key);
            Engine.get(c).prefs().edit().putStringSet(PREF, offered).apply();
            offer(c, photo, e);
        }
    }

    private static void offer(Context c, IndexStore.Item photo, PhotoEvent e) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Class<?> ch = Class.forName("android.app.NotificationChannel");
                Object channel = ch.getConstructor(String.class, CharSequence.class, int.class)
                        .newInstance(CHANNEL, "Найдено на фото", 3 /* IMPORTANCE_DEFAULT */);
                NotificationManager.class.getMethod("createNotificationChannel", ch).invoke(nm, channel);
                b = (Notification.Builder) Notification.Builder.class.getConstructor(Context.class, String.class).newInstance(c, CHANNEL);
            } catch (Exception ex) {
                b = new Notification.Builder(c);
            }
        } else {
            b = new Notification.Builder(c);
        }
        int code = (int) (photo.id * 31 + (e.at / 60_000L));
        String when = (PhotoEvent.WARRANTY.equals(e.kind) ? "" : NoteEditor.when(e.at) + " · ") + "напомнить " + e.beforeLabel() + "?";
        Intent remind = new Intent(c, PhotoAlerts.class).setAction(ACTION).putExtra(EXTRA_PHOTO, photo.id)
                .putExtra(EXTRA_TITLE, e.title).putExtra(EXTRA_AT, e.at).putExtra(EXTRA_BEFORE, e.before).putExtra("code", code);
        Intent open = new Intent(c, MainActivity.class).setAction(MainActivity.ACTION_OPEN_ITEM).putExtra(Reminders.EXTRA_NOTE, photo.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        b.setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentTitle(e.title)
                .setContentText(when)
                .setContentIntent(PendingIntent.getActivity(c, code, open, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000))
                .setAutoCancel(true)
                .addAction(new Notification.Action.Builder(0, "Напомнить",
                        PendingIntent.getBroadcast(c, code, remind, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000)).build());
        try {
            nm.notify("photo", code, b.build());
        } catch (SecurityException ex) {
            // notifications not allowed
        }
    }

    /** A note to the photo with the reminder found on it. */
    static void remind(final Context c, final long photoId, String title, long at, long before, final Engine.Callback<IndexStore.Item> cb) {
        IndexStore s = Engine.get(c).store();
        IndexStore.Item photo = s == null ? null : s.find(photoId);
        String text = title + (title.startsWith("Гарантия") ? "" : " — " + NoteEditor.when(at));
        Engine.get(c).addNote(text, photo, false, Math.max(at - before, System.currentTimeMillis() + 60_000L),
                io.github.teoplaydor.semsearch.core.Spoken.ONCE, cb);
    }

    @Override
    public void onReceive(final Context c, Intent i) {
        if (!ACTION.equals(i.getAction())) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel("photo", i.getIntExtra("code", 0));
        final PendingResult held = goAsync();
        final Context app = c.getApplicationContext();
        final long id = i.getLongExtra(EXTRA_PHOTO, -1);
        final String title = i.getStringExtra(EXTRA_TITLE);
        final long at = i.getLongExtra(EXTRA_AT, 0), before = i.getLongExtra(EXTRA_BEFORE, 0);
        Engine.get(app).afterOpen(new Runnable() {
            @Override
            public void run() {
                remind(app, id, title == null ? "Напоминание" : title, at, before, new Engine.Callback<IndexStore.Item>() {
                    @Override
                    public void done(IndexStore.Item it, Exception e) {
                        if (held != null) held.finish();
                    }
                });
            }
        });
    }
}
