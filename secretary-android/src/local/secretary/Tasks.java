package local.secretary;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * «Сделал?» при разблокировке телефона. Дела живут на сервере; телефон берёт те, о которых пора спросить,
 * и показывает по одному: ответил на одно — сразу следующее. Сервер сам не даёт переспрашивать чаще раза в 30 минут.
 */
final class Tasks {
    static final int NOTIF_ID = 2;
    static final String EXTRA_ID = "task_id", EXTRA_TITLE = "task_title", EXTRA_ACTION = "task_action";
    private static volatile long lastCheck;

    private Tasks() {}

    /** Экран разблокировали: спросить о делах (не чаще раза в 2 минуты). */
    static void onUnlock(Context c) {
        long now = System.currentTimeMillis();
        if (now - lastCheck < 120_000) return;
        lastCheck = now;
        askNext(c);
    }

    static void askNext(Context c) {
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                JSONArray tasks = new JSONObject(Net.get("/api/tasks?due=1", 10_000)).getJSONArray("tasks");
                if (tasks.length() == 0) return;
                JSONObject t = tasks.getJSONObject(0);
                show(app, t, tasks.length() - 1);
                act(t.getLong("id"), "asked", "");
            } catch (Exception e) {
                Log.w(Listener.TAG, "tasks: " + e);
            }
        }, "tasks").start();
    }

    static String act(long id, String action, String choice) throws Exception {
        JSONObject body = new JSONObject().put("id", id).put("action", action).put("choice", choice);
        return new JSONObject(Net.postJson("/api/task", body.toString(), 15_000)).optString("reply");
    }

    private static PendingIntent broadcast(Context c, long id, String action) {
        Intent i = new Intent(c, Receiver.class).putExtra(EXTRA_ID, id).putExtra(EXTRA_ACTION, action);
        return PendingIntent.getBroadcast(c, (int) (id * 10 + action.length()), i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void show(Context c, JSONObject t, int more) {
        Listener.ensureChannels(c);
        long id = t.optLong("id");
        String title = t.optString("title");
        StringBuilder body = new StringBuilder(title);
        if (!t.optString("due").isEmpty()) body.append("\nдо ").append(t.optString("due"));
        if (!t.optString("hint").isEmpty()) body.append("\n").append(t.optString("hint"));
        if (more > 0) body.append("\nещё ").append(more).append(" — спрошу следом");
        String source = t.optString("source");
        int dot = source.indexOf(" · ");
        String app = dot > 0 ? source.substring(0, dot) : source;

        Intent snooze = new Intent(c, SnoozeActivity.class).putExtra(EXTRA_ID, id).putExtra(EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent snoozePi = PendingIntent.getActivity(c, (int) (id * 10 + 9), snooze,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder nb = new Notification.Builder(c, Listener.alertChannel(c));
        android.graphics.drawable.Icon icon = Listener.appIcon(c, t.optString("package"));
        if (icon != null) nb.setLargeIcon(icon); // иконка приложения, откуда дело
        if (!app.isEmpty()) nb.setSubText(app);
        Notification n = nb
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Сделал?")
                .setContentText(title)
                .setStyle(new Notification.BigTextStyle().bigText(body.toString()))
                .setContentIntent(Listener.openApp(c))
                .addAction(new Notification.Action.Builder(null, "Сделал", broadcast(c, id, "done")).build())
                .addAction(new Notification.Action.Builder(null, "Отложить", snoozePi).build())
                .addAction(new Notification.Action.Builder(null, "Принял", broadcast(c, id, "accept")).build())
                .setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .build();
        c.getSystemService(NotificationManager.class).notify(NOTIF_ID, n);
    }

    /** Ответ из уведомления: отправить на сервер и сразу спросить про следующее дело. */
    static void answer(Context c, long id, String action, String choice) {
        Context app = c.getApplicationContext();
        app.getSystemService(NotificationManager.class).cancel(NOTIF_ID);
        new Thread(() -> {
            try {
                act(id, action, choice);
            } catch (Exception e) {
                Log.w(Listener.TAG, "task answer: " + e);
            }
            askNext(app);
        }, "task-answer").start();
    }

    /** Кнопки «Сделал» и «Принял» в уведомлении. */
    public static class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            answer(c, i.getLongExtra(EXTRA_ID, 0), i.getStringExtra(EXTRA_ACTION), "");
        }
    }

    /** Экран разблокирован. */
    static final class UnlockReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            onUnlock(c);
        }
    }
}
