package io.github.teoplaydor.semsearch.app;

import static org.junit.Assert.*;

import android.app.AlarmManager;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.service.notification.StatusBarNotification;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.shadows.ShadowNotificationManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.teoplaydor.semsearch.core.HfRepo;
import io.github.teoplaydor.semsearch.secretary.SecUtil;
import io.github.teoplaydor.semsearch.secretary.Sql.Row;

/**
 * The secretary inside SemSearch on Android 14 (Robolectric), without its model (not downloaded — what a fresh install
 * has): the notification reader takes a messenger's notification whole into the secretary's database; «Тишина» puts on
 * «Не беспокоить» and, the model missing, shows a person's message again with sound and gives the old settings back
 * when it ends; the secretary's own message becomes a notification; «Сделал?» at unlock and its answer; the exact
 * alarm for a reminder; the chat page served from the app with its API (GET intercepted, POST through the bridge);
 * the settings screen; the GGUF picked from a repo's files.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SecretaryAppTest {
    static Application app;
    static NotificationManager nm;
    static ShadowNotificationManager snm;

    static List<Notification> all() {
        return snm.getAllNotifications();
    }

    static Notification titled(String title) {
        for (Notification n : all()) if (title.equals(String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TITLE)))) return n;
        return null;
    }

    static void waitFor(String what, java.util.concurrent.Callable<Boolean> c) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) {
            if (c.call()) return;
            Thread.sleep(20);
        }
        fail("timed out: " + what);
    }

    static StatusBarNotification messenger(String pkg, int id, String chat, String... senderText) {
        Notification n = new Notification.Builder(app, "x").setContentTitle(chat).setContentText(senderText[senderText.length - 1])
                .setSmallIcon(android.R.drawable.sym_def_app_icon).build();
        Bundle[] msgs = new Bundle[senderText.length / 2];
        for (int i = 0; i < msgs.length; i++) {
            Bundle b = new Bundle();
            b.putCharSequence("sender", senderText[2 * i]);
            b.putCharSequence("text", senderText[2 * i + 1]);
            b.putLong("time", System.currentTimeMillis());
            msgs[i] = b;
        }
        n.extras.putParcelableArray("android.messages", msgs);
        return new StatusBarNotification(pkg, pkg, id, null, 10123, 0, n, Process.myUserHandle(), null, System.currentTimeMillis());
    }

    /** Lets the listener's pass run (it waits 300 ms for more notifications to come with this one). */
    static void pass(SecListener l) {
        Shadows.shadowOf(l.handler.getLooper()).idleFor(Duration.ofSeconds(1));
    }

    static WebResourceRequest get(final String url) {
        return new WebResourceRequest() {
            public Uri getUrl() {
                return Uri.parse(url);
            }

            public boolean isForMainFrame() {
                return false;
            }

            public boolean isRedirect() {
                return false;
            }

            public boolean hasGesture() {
                return false;
            }

            public String getMethod() {
                return "GET";
            }

            public Map<String, String> getRequestHeaders() {
                return new HashMap<String, String>();
            }
        };
    }

    static String read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return new String(out.toByteArray(), "UTF-8");
    }

    @Test
    public void secretary() throws Exception {
        app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).grantPermissions("android.permission.POST_NOTIFICATIONS");
        nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        snm = Shadows.shadowOf(nm);
        snm.setNotificationPolicyAccessGranted(true);
        final SecHub hub = SecHub.get(app);

        // the GGUF of a repo: the ternary format first, not the projector, not the bigger forms
        Constructor<HfRepo.RemoteFile> rf = HfRepo.RemoteFile.class.getDeclaredConstructor(String.class, long.class);
        rf.setAccessible(true);
        List<HfRepo.RemoteFile> files = Arrays.asList(rf.newInstance("README.md", 900L), rf.newInstance("Ternary-Bonsai-4B-Q4_0.gguf", 2_300_000_000L),
                rf.newInstance("Ternary-Bonsai-4B-Q2_0.gguf", 1_250_000_000L), rf.newInstance("Ternary-Bonsai-4B-PQ2_0.gguf", 1_100_000_000L),
                rf.newInstance("mmproj-F16.gguf", 600_000_000L));
        assertEquals("Ternary-Bonsai-4B-PQ2_0.gguf", SecHub.pickGguf(files).path);
        assertEquals("1.1 → 4", "Ternary-Bonsai-4B-Q2_0.gguf", SecHub.pickGguf(files.subList(0, 3)).path);
        assertNull(hub.modelFile());

        // the reader connected: a WhatsApp chat's notification read whole into the secretary
        final SecListener l = Robolectric.setupService(SecListener.class);
        l.onListenerConnected();
        l.onNotificationPosted(messenger("com.whatsapp", 1, "Анна", "Анна", "Привет! Во сколько завтра встречаемся?"));
        pass(l);
        waitFor("the message stored", new java.util.concurrent.Callable<Boolean>() {
            public Boolean call() {
                return hub.sec.db.q("SELECT * FROM phone_msgs").size() == 1;
            }
        });
        Row m = hub.sec.db.q("SELECT * FROM phone_msgs").get(0);
        assertEquals("Анна", m.text("sender"));
        assertEquals("Привет! Во сколько завтра встречаемся?", m.text("text"));
        assertEquals("WhatsApp is not installed here — its label is the package", "com.whatsapp", m.text("package"));
        l.onNotificationPosted(messenger("com.whatsapp", 1, "Анна", "Анна", "Привет! Во сколько завтра встречаемся?"));
        pass(l);
        assertEquals("the same notification again — not twice", 1, hub.sec.db.q("SELECT * FROM phone_msgs").size());

        // Тишина: «Не беспокоить» on; the model missing — a person's message shown again with sound, an ad not
        assertNotNull(SecListener.setFocus(app, true));
        assertTrue(hub.focus());
        assertEquals(NotificationManager.INTERRUPTION_FILTER_PRIORITY, nm.getCurrentInterruptionFilter());
        assertTrue(SecListener.hiddenByDnd(app));
        int before = all().size();
        l.onNotificationPosted(messenger("org.telegram.messenger", 2, "Олег", "Олег", "Ты где? Мы уже у входа"));
        Notification ad = new Notification.Builder(app, "x").setContentTitle("Скидки до 70%").setContentText("Только сегодня")
                .setSmallIcon(android.R.drawable.sym_def_app_icon).build();
        l.onNotificationPosted(new StatusBarNotification("ru.ozon.app.android", "ru.ozon.app.android", 3, null, 10124, 0, ad,
                Process.myUserHandle(), null, System.currentTimeMillis()));
        pass(l);
        waitFor("Олег shown by the secretary", new java.util.concurrent.Callable<Boolean>() {
            public Boolean call() {
                return titled("Олег") != null;
            }
        });
        assertNull("an ad is not shown again", titled("Скидки до 70%"));
        Notification alert = titled("Олег");
        assertEquals("Ты где? Мы уже у входа", String.valueOf(alert.extras.getCharSequence(Notification.EXTRA_TEXT)));
        assertTrue("on the secretary's «Важное» channel", alert.getChannelId().startsWith(SecHub.CH_ALERT + "_"));
        assertTrue(all().size() > before);
        assertNotNull(SecListener.setFocus(app, false));
        assertFalse(hub.focus());
        assertEquals("the old «Не беспокоить» back", NotificationManager.INTERRUPTION_FILTER_ALL, nm.getCurrentInterruptionFilter());

        // the secretary's own message — a notification (its first line the title), not while its screen is open
        hub.sec.send("<b>🔴 Срочно</b> · WhatsApp\nЧат: Анна\n\nАнна ждёт ответа");
        Notification own = titled("🔴 Срочно · WhatsApp");
        assertNotNull(own);
        assertTrue(String.valueOf(own.extras.getCharSequence(Notification.EXTRA_TEXT)).contains("Анна ждёт ответа"));
        SecHub.visible = true;
        hub.sec.send("<b>Тихое</b>\nпока экран открыт");
        assertNull(titled("Тихое"));
        SecHub.visible = false;

        // «Сделал?» at unlock: the task with its three buttons; «Сделал» closes it
        long task = hub.sec.tasks.add("Забрать посылку", null)[0];
        SecAsk.askNext(app);
        waitFor("«Сделал?» shown", new java.util.concurrent.Callable<Boolean>() {
            public Boolean call() {
                return titled("Сделал?") != null;
            }
        });
        Notification ask = titled("Сделал?");
        assertEquals("Забрать посылку", String.valueOf(ask.extras.getCharSequence(Notification.EXTRA_TEXT)));
        assertEquals(3, ask.actions.length);
        assertEquals("Сделал", String.valueOf(ask.actions[0].title));
        waitFor("asked: not again for 30 minutes", new java.util.concurrent.Callable<Boolean>() {
            public Boolean call() {
                return hub.sec.tasks.due(null).isEmpty();
            }
        });
        new SecAsk.Answer().onReceive(app, new Intent(app, SecAsk.Answer.class).putExtra(SecAsk.EXTRA_ID, task)
                .putExtra(SecAsk.EXTRA_ACTION, "done"));
        final long t = task;
        waitFor("the task done", new java.util.concurrent.Callable<Boolean>() {
            public Boolean call() {
                return "done".equals(hub.sec.db.getTask(t).text("status"));
            }
        });

        // a reminder: an exact alarm at its time (or at a digest's, if one comes sooner)
        java.time.Instant at = SecUtil.now().plus(Duration.ofMinutes(20));
        hub.sec.db.addReminder("Позвонить в банк", at);
        java.time.Instant due = hub.nextDue();
        assertTrue(!due.isAfter(SecUtil.fromDb(SecUtil.toDb(at))));
        hub.scheduleAlarm();
        ShadowAlarmManager sam = Shadows.shadowOf((AlarmManager) app.getSystemService(Context.ALARM_SERVICE));
        ShadowAlarmManager.ScheduledAlarm next = sam.getNextScheduledAlarm();
        assertNotNull(next);
        assertEquals(due.toEpochMilli() + 1000, next.getTriggerAtMs());

        // the chat page: served from the app; GET answered by the interception, POST through the bridge
        ActivityController<SecActivity> ac = Robolectric.buildActivity(SecActivity.class).setup();
        SecActivity a = ac.get();
        Intent setup = Shadows.shadowOf(a).getNextStartedActivity();
        assertEquals("no model yet — the settings open", SecSetup.class.getName(), setup.getComponent().getClassName());
        WebView web = (WebView) Shadows.shadowOf(a).getContentView();
        assertEquals(SecActivity.ORIGIN + "/app", Shadows.shadowOf(web).getLastLoadedUrl());
        WebResourceResponse page = Shadows.shadowOf(web).getWebViewClient().shouldInterceptRequest(web, get(SecActivity.ORIGIN + "/app"));
        String html = read(page.getData());
        assertTrue(html.contains("<title>Секретарь</title>") && html.contains("native.post"));
        WebResourceResponse msgs = Shadows.shadowOf(web).getWebViewClient().shouldInterceptRequest(web,
                get(SecActivity.ORIGIN + "/api/messages?after=-1&wait=0"));
        assertEquals(200, msgs.getStatusCode());
        assertTrue(read(msgs.getData()).contains("Срочно"));
        assertNull("other sites are not touched", Shadows.shadowOf(web).getWebViewClient().shouldInterceptRequest(web, get("https://example.org/")));
        Object bridge = Shadows.shadowOf(web).getJavascriptInterface("Android");
        String r = (String) bridge.getClass().getMethod("post", String.class, String.class).invoke(bridge, "/api/message", "{\"text\": \"что у меня завтра?\"}");
        assertTrue(r, r.contains("\"ok\":true"));
        assertEquals("!400", bridge.getClass().getMethod("post", String.class, String.class).invoke(bridge, "/api/message", "{\"text\": \" \"}"));
        hub.sec.drain();
        List<Row> chat = hub.sec.db.chatTail(3);
        assertEquals("что у меня завтра?", chat.get(chat.size() - 2).text("html"));
        assertTrue("no model — said in the chat", chat.get(chat.size() - 1).text("html").contains("Не получилось"));
        assertTrue(SecHub.visible);
        ac.pause();
        assertFalse(SecHub.visible);
        ac.destroy();

        // the settings screen
        SecSetup s = Robolectric.buildActivity(SecSetup.class).setup().get();
        assertNotNull(s);
        l.onDestroy();
    }
}
