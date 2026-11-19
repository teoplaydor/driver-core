package local.secretary;

import static org.junit.Assert.*;

import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Process;
import android.service.notification.StatusBarNotification;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import com.sun.net.httpserver.HttpServer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowNotificationManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import io.github.teoplaydor.semsearch.secretary.SecUtil;
import io.github.teoplaydor.semsearch.secretary.Sql.Row;

/**
 * «Секретарь» без сервера на Android 14 (Robolectric), Polza AI — подставной сервер с ответами, как у настоящей (Qwen
 * от Яндекса сначала думает — reasoning, — потом отвечает; цена в рублях): модели Gemini из прошлой версии уступают
 * место Qwen; без ключа — просьба вписать ключ Polza; уведомление — в базу секретаря; в «Тишине» Qwen решает, что
 * важно, и важное показано со звуком; чат: страница из приложения, сообщение — ответ Qwen; голосовое — услышано
 * GigaAM; «Сделал?» при разблокировке; напоминание — точным будильником; «Нейросеть» — в рублях; журнал пишется.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AppTest {
    static final LinkedList<String> answers = new LinkedList<>();
    static final List<String> asked = new ArrayList<>();

    static String polza(String content) {
        return "{\"model\":\"qwen/qwen3.6-35b-a3b\",\"provider\":\"yandex\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":"
                + io.github.teoplaydor.semsearch.core.MiniJson.write(content) + ",\"reasoning\":\"Thinking process: ...\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":500,\"completion_tokens\":900,\"cost_rub\":0.2088,\"cost\":0.2088}}";
    }

    static void waitFor(String what, Callable<Boolean> c) throws Exception {
        long end = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < end) {
            if (c.call()) return;
            Thread.sleep(20);
        }
        fail("timed out: " + what);
    }

    static Notification titled(ShadowNotificationManager snm, String title) {
        for (Notification n : snm.getAllNotifications())
            if (title.equals(String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TITLE)))) return n;
        return null;
    }

    static WebResourceRequest get(String url) {
        return new WebResourceRequest() {
            public Uri getUrl() { return Uri.parse(url); }
            public boolean isForMainFrame() { return false; }
            public boolean isRedirect() { return false; }
            public boolean hasGesture() { return false; }
            public String getMethod() { return "GET"; }
            public Map<String, String> getRequestHeaders() { return new HashMap<>(); }
        };
    }

    static String read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        return out.toString("UTF-8");
    }

    @Test public void secretaryWithoutServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            InputStream in = ex.getRequestBody();
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String r;
            synchronized (asked) {
                asked.add(ex.getRequestURI().getPath() + " " + body);
                r = answers.isEmpty() ? polza("{}") : answers.removeFirst();
            }
            byte[] out = r.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();

        Application app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).grantPermissions("android.permission.POST_NOTIFICATIONS");
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        ShadowNotificationManager snm = Shadows.shadowOf(nm);
        snm.setNotificationPolicyAccessGranted(true);

        // the previous version's Gemini models and key: the models give way to Qwen via Polza, the key stays
        // (and 2.13's big Qwen saved as the fast model gives way to the 9B)
        Listener.prefs(app).edit().putString(Core.K_AUDIO, "gemini:gemini-3.1-flash-lite").putString(Core.K_KEY, "AIza-old")
                .putString(Core.K_THINK, "512").putString(Core.K_FAST, "polza:qwen/qwen3.6-35b-a3b@yandex").apply();
        // first start: no Polza key — asked for it
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(d);
        assertEquals("Ключ Polza", Shadows.shadowOf(d).getTitle().toString());
        Core core = Core.instance();
        assertNotNull(core);
        assertEquals("polza:qwen/qwen3.5-9b@polza", core.geminiConfig().fastModel);
        assertEquals("polza:qwen/qwen3.6-35b-a3b@yandex", core.geminiConfig().smartModel);
        assertEquals("polza:ai-sage/gigaam-v3", core.geminiConfig().audioModel);
        assertEquals("AIza-old", core.geminiConfig().geminiKey);
        assertEquals("", core.geminiConfig().thinking);
        assertEquals("Polza", core.missingKey());
        core.brain.retryDelayMs = 10;
        core.prefs.edit().putString(Core.K_POLZA_KEY, "pza_test").putString(Core.K_ABOUT, "Работаю менеджером.")
                .putString(Core.K_POLZA_URL, "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1").apply();
        core.reloadPrefs();
        assertNull(core.missingKey());

        // the chat page: from the app; GET answered here, POST through the bridge; Gemini answers the message
        WebView web = (WebView) Shadows.shadowOf(a).getContentView();
        assertEquals("https://secretary.local/app", Shadows.shadowOf(web).getLastLoadedUrl());
        WebResourceResponse page = Shadows.shadowOf(web).getWebViewClient().shouldInterceptRequest(web, get("https://secretary.local/app"));
        String html = read(page.getData());
        assertTrue(html.contains("<title>Секретарь</title>") && html.contains("native.post"));
        answers.add(polza("  ```json\n{\"reply\": \"Завтра свободно.\", \"actions\": [{\"type\": \"add_reminder\", \"text\": \"Позвонить в банк\", \"at\": \""
                + SecUtil.now().plus(Duration.ofHours(1)).toString() + "\"}]}\n```"));
        Object bridge = Shadows.shadowOf(web).getJavascriptInterface("Android");
        String r = (String) bridge.getClass().getMethod("post", String.class, String.class).invoke(bridge, "/api/message", "{\"text\": \"что у меня завтра?\"}");
        assertTrue(r, r.contains("\"ok\":true"));
        waitFor("Qwen's answer in the chat", () -> core.sec.db.chatTail(5).stream().anyMatch(m -> m.text("html").contains("Завтра свободно.")));
        String q = asked.get(asked.size() - 1);
        assertTrue("the conversation: Qwen 35B from Yandex only, via Polza: " + q.substring(0, 200), q.startsWith("/api/v1/chat/completions ")
                && q.contains("\"model\":\"qwen/qwen3.6-35b-a3b\"") && q.contains("\"provider\":{\"only\":[\"yandex\"],\"allow_fallbacks\":false}")
                && !q.contains("response_format"));
        assertTrue("the context and the owner's profile sent", q.contains("Расписание на 7 дней") && q.contains("Работаю менеджером."));
        assertTrue(core.sec.db.chatTail(5).stream().anyMatch(m -> m.text("html").contains("Напомню")));
        WebResourceResponse msgs = Shadows.shadowOf(web).getWebViewClient().shouldInterceptRequest(web,
                get("https://secretary.local/api/messages?after=0&wait=0"));
        assertTrue(read(msgs.getData()).contains("Завтра свободно."));

        // a voice message: heard by GigaAM via Polza, answered by Qwen
        answers.add("{\"text\":\"что у меня завтра\"}");
        answers.add(polza("{\"reply\": \"Завтра в 10 созвон.\", \"actions\": []}"));
        core.voice(new byte[2000], "audio/aac");
        assertTrue(asked.stream().anyMatch(x -> x.startsWith("/api/v1/audio/transcriptions ") && x.contains("\"model\":\"ai-sage/gigaam-v3\"")
                && x.contains("data:audio/aac;base64,")));
        waitFor("the voice answered", () -> core.sec.db.chatTail(5).stream().anyMatch(m -> m.text("html").contains("Завтра в 10 созвон.")));
        assertTrue(core.sec.db.chatTail(5).stream().anyMatch(m -> m.text("html").equals("🎙 <i>что у меня завтра</i>")));

        // the notification reader: a Telegram chat into the secretary; in «Тишина» Qwen says urgent → shown with sound
        assertNotNull(Listener.setFocus(app, true));
        Listener l = Robolectric.setupService(Listener.class);
        l.onListenerConnected();
        java.lang.reflect.Field f = Listener.class.getDeclaredField("sender");
        f.setAccessible(true);
        Notification n = new Notification.Builder(app, "x").setContentTitle("Олег").setContentText("Ты где? Мы уже у входа")
                .setSmallIcon(android.R.drawable.sym_def_app_icon).build();
        Bundle m = new Bundle();
        m.putCharSequence("sender", "Олег");
        m.putCharSequence("text", "Ты где? Мы уже у входа");
        m.putLong("time", System.currentTimeMillis());
        n.extras.putParcelableArray("android.messages", new Bundle[]{m});
        answers.add(polza("{\"items\": [{\"i\": 0, \"level\": \"urgent\"}]}"));
        l.onNotificationPosted(new StatusBarNotification("org.telegram.messenger", "org.telegram.messenger", 7, null, 10123, 0, n,
                Process.myUserHandle(), null, System.currentTimeMillis()));
        Handler h = (Handler) f.get(l);
        Shadows.shadowOf(h.getLooper()).idleFor(Duration.ofSeconds(2));
        waitFor("stored", () -> core.sec.db.q("SELECT * FROM phone_msgs").size() == 1);
        Row pm = core.sec.db.q("SELECT * FROM phone_msgs").get(0);
        assertEquals("Олег", pm.text("sender"));
        waitFor("shown with sound by the secretary", () -> titled(snm, "Олег") != null);
        assertTrue("the fast model (Qwen 9B) filtered it", asked.stream().anyMatch(x -> x.startsWith("/api/v1/chat/completions ")
                && x.contains("Олег") && x.contains("фильтр уведомлений") && x.contains("\"model\":\"qwen/qwen3.5-9b\"")
                && x.contains("\"only\":[\"polza\"]")));
        assertNotNull(Listener.setFocus(app, false));

        // «Сделал?» at unlock
        long task = core.sec.tasks.add("Забрать посылку", null)[0];
        Tasks.askNext(app);
        waitFor("«Сделал?»", () -> titled(snm, "Сделал?") != null);
        assertEquals("Забрать посылку", String.valueOf(titled(snm, "Сделал?").extras.getCharSequence(Notification.EXTRA_TEXT)));

        // the reminder Qwen set: an exact alarm (or a digest's, if one comes sooner)
        java.time.Instant due = core.nextDue();
        core.scheduleAlarm();
        ShadowAlarmManager.ScheduledAlarm next = Shadows.shadowOf(app.getSystemService(AlarmManager.class)).getNextScheduledAlarm();
        assertNotNull(next);
        assertEquals(due.toEpochMilli() + 1000, next.getTriggerAtMs());
        assertTrue(due.isBefore(SecUtil.now().plus(Duration.ofHours(1)).plusSeconds(1)));

        // «Нейросеть»: Qwen's calls with their cost in rubles, as Polza said it
        String usage = core.api.handle("/api/view?name=usage", null).json;
        assertTrue(usage, usage.contains("qwen/qwen3.6-35b-a3b@yandex") && usage.contains(" ₽") && !usage.contains("$")
                && usage.contains("ai-sage/gigaam-v3"));
        l.onDestroy();
        server.stop(0);
    }
}
