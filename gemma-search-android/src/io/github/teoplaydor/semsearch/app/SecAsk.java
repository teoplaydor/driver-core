package io.github.teoplaydor.semsearch.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.util.Log;

import java.util.List;
import java.util.Map;

import io.github.teoplaydor.semsearch.R;
import io.github.teoplaydor.semsearch.secretary.Sql.Row;

/**
 * «Сделал?» at unlock (the server version's Tasks.java): the tasks it is time to ask about, one at a time —
 * answered one, at once the next. The secretary itself does not ask again sooner than in 30 minutes.
 */
public final class SecAsk {
    static final String EXTRA_ID = "task_id", EXTRA_TITLE = "task_title", EXTRA_ACTION = "task_action";
    private static volatile long lastCheck;

    private SecAsk() {
    }

    /** The screen unlocked: ask about tasks (not more often than once in 2 minutes). */
    static void onUnlock(Context c) {
        long now = System.currentTimeMillis();
        if (now - lastCheck < 120_000) return;
        lastCheck = now;
        SecLog.i("ask: unlocked");
        askNext(c);
    }

    static void askNext(Context c) {
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                try {
                    SecHub hub = SecHub.get(app);
                    List<Row> due = hub.sec.tasks.due(null);
                    SecLog.i("ask: " + due.size() + " task(s) due");
                    if (due.isEmpty()) return;
                    Map<String, Object> t = hub.sec.tasks.asJson(due.get(0));
                    show(app, hub, t, due.size() - 1);
                    hub.sec.tasks.act(due.get(0).lng("id"), "asked", "");
                } catch (Exception e) {
                    SecLog.e("ask failed", e);
                }
            }
        }, "sec-tasks").start();
    }

    private static PendingIntent broadcast(Context c, long id, String action) {
        Intent i = new Intent(c, Answer.class).putExtra(EXTRA_ID, id).putExtra(EXTRA_ACTION, action);
        return PendingIntent.getBroadcast(c, (int) (id * 10 + action.length()), i, 0x04000000 | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void show(Context c, SecHub hub, Map<String, Object> t, int more) {
        hub.ensureChannels();
        long id = ((Number) t.get("id")).longValue();
        String title = String.valueOf(t.get("title"));
        StringBuilder body = new StringBuilder(title);
        if (!"".equals(t.get("due"))) body.append("\nдо ").append(t.get("due"));
        if (!"".equals(t.get("hint"))) body.append("\n").append(t.get("hint"));
        if (more > 0) body.append("\nещё ").append(more).append(" — спрошу следом");
        String source = String.valueOf(t.get("source"));
        int dot = source.indexOf(" · ");
        String app = dot > 0 ? source.substring(0, dot) : source;

        Intent snooze = new Intent(c, Snooze.class).putExtra(EXTRA_ID, id).putExtra(EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent snoozePi = PendingIntent.getActivity(c, (int) (id * 10 + 9), snooze, 0x04000000 | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder nb = SecCompat.builder(c, hub.alertChannel());
        Icon icon = SecListener.appIcon(c, String.valueOf(t.get("package")));
        if (icon != null) nb.setLargeIcon(icon); // the icon of the app the task came from
        if (!app.isEmpty()) nb.setSubText(app);
        Notification n = nb
                .setSmallIcon(R.drawable.ic_secretary_small)
                .setContentTitle("Сделал?")
                .setContentText(title)
                .setStyle(new Notification.BigTextStyle().bigText(body.toString()))
                .setContentIntent(hub.openApp())
                .addAction(new Notification.Action.Builder(null, "Сделал", broadcast(c, id, "done")).build())
                .addAction(new Notification.Action.Builder(null, "Отложить", snoozePi).build())
                .addAction(new Notification.Action.Builder(null, "Принял", broadcast(c, id, "accept")).build())
                .setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .build();
        try {
            ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(SecHub.ASK_NOTIF, n);
        } catch (SecurityException ignored) {
        }
    }

    /** An answer from the notification: given to the secretary, and at once the next task. */
    static void answer(Context c, final long id, final String action, final String choice) {
        final Context app = c.getApplicationContext();
        ((NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(SecHub.ASK_NOTIF);
        new Thread(new Runnable() {
            public void run() {
                try {
                    SecHub hub = SecHub.get(app);
                    String reply = hub.sec.tasks.act(id, action, choice);
                    if (!reply.isEmpty()) hub.sec.send(reply, null, "app"); // into the chat's history too
                } catch (Exception e) {
                    SecLog.e("ask: answer failed", e);
                }
                askNext(app);
            }
        }, "sec-task-answer").start();
    }

    /** «Сделал» and «Принял» in the notification. */
    public static class Answer extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            answer(c, i.getLongExtra(EXTRA_ID, 0), i.getStringExtra(EXTRA_ACTION), "");
        }
    }

    /** The screen unlocked. */
    static final class Unlock extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            onUnlock(c);
        }
    }

    /** «Отложить»: a small choice of when to ask again. */
    public static class Snooze extends Activity {
        private static final String[] LABELS = {"Через 30 минут", "Через 2 часа", "Вечером", "Завтра утром", "Принял — спроси ближе к сроку", "Не нужно"};
        private static final String[] CHOICES = {"30m", "2h", "evening", "tomorrow", "accept", ""};

        @Override
        protected void onCreate(Bundle state) {
            super.onCreate(state);
            final long id = getIntent().getLongExtra(EXTRA_ID, 0);
            String title = getIntent().getStringExtra(EXTRA_TITLE);
            AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(title == null ? "Когда спросить снова?" : "Когда спросить про «" + title + "»?")
                    .setItems(LABELS, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dlg, int which) {
                            String ch = CHOICES[which];
                            if (ch.isEmpty()) answer(Snooze.this, id, "drop", "");
                            else if ("accept".equals(ch)) answer(Snooze.this, id, "accept", "");
                            else answer(Snooze.this, id, "snooze", ch);
                            finish();
                        }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        public void onCancel(DialogInterface dlg) {
                            finish();
                        }
                    })
                    .create();
            d.show();
        }
    }
}
