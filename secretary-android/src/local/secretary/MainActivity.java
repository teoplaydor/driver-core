package local.secretary;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.view.View;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import io.github.teoplaydor.semsearch.secretary.Api;
import io.github.teoplaydor.semsearch.secretary.LlmError;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Экран чата. Сервера больше нет: страница (app.html) лежит в приложении, на её запросы отвечает секретарь здесь же
 * ({@link Core}), нейросеть — Gemini напрямую с телефона по ключу из «Настройки». Здесь — окно для страницы, голос,
 * копирование и настройки: телефона и те, что раньше были в config.toml.
 */
public class MainActivity extends Activity {
    static volatile boolean visible;
    private static final int REQ_NOTIFY = 1, REQ_MIC = 2;

    static final String ORIGIN = "https://secretary.local";
    private WebView web;
    private Core core;
    private MediaRecorder recorder;
    private File voiceFile;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        core = Core.get(this);
        Listener.ensureChannels(this);
        // Цвета окна — как у чата (палитра в духе Claude): тёплый тёмный или кремовый светлый.
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        int bg = Color.parseColor(night ? "#262624" : "#faf9f5");
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        if (!night) getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        web = new WebView(this);
        web.setBackgroundColor(bg);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        CookieManager.getInstance().setAcceptCookie(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                Core.Journal.line("page " + m.messageLevel() + ": " + m.message() + " (" + m.sourceId() + ":" + m.lineNumber() + ")");
                return true;
            }
        });
        Core.Journal.line("screen: opened");
        setContentView(web);
        load();

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        if (core.geminiConfig().geminiKey.isEmpty()) askKey(); // без ключа Gemini секретарь не думает
        else if (!hasNotificationAccess()) showSetup();
    }

    private void load() {
        web.loadUrl(ORIGIN + "/app");
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        core.reloadPrefs();
        core.startLoop();
        Listener.ensureChannels(this); // вернулись из настроек с доступом к «Не беспокоить» — канал станет со звуком
        // Сообщения секретаря уже видны в чате. Важные уведомления о чужих чатах оставляем:
        // по ним удобно перейти прямо в нужный разговор.
        NotificationManager nm = getSystemService(NotificationManager.class);
        for (android.service.notification.StatusBarNotification s : nm.getActiveNotifications())
            if (Listener.SERVER_TAG.equals(s.getTag())) nm.cancel(s.getTag(), s.getId());
    }

    @Override protected void onPause() {
        visible = false;
        core.stopLoop();
        super.onPause();
    }

    /** «Назад» — сначала шаг назад внутри страницы (закрыть шторку, вернуться с экрана на вкладку, с вкладки
     *  в чат), и только из чата — свернуть приложение. */
    @Override public void onBackPressed() {
        web.evaluateJavascript("window.goBack ? window.goBack() : false", handled -> {
            if (!"true".equals(handled)) moveTaskToBack(true);
        });
    }

    // ---------------- настройки телефона ----------------

    boolean hasNotificationAccess() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(new ComponentName(this, Listener.class).flattenToString());
    }

    boolean batteryUnrestricted() {
        return getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
    }

    void showSetup() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        boolean access = hasNotificationAccess(), battery = batteryUnrestricted();
        boolean notify = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        boolean focus = Listener.focus(this);
        android.app.NotificationChannel alerts = nm.getNotificationChannel(Listener.alertChannel(this));
        boolean bypass = alerts != null && alerts.canBypassDnd();
        io.github.teoplaydor.semsearch.secretary.GeminiBrain.Config g = core.geminiConfig();
        String about = core.prefs.getString(Core.K_ABOUT, "");
        String[] items = {
                (g.geminiKey.isEmpty() ? "❌ Ключ Gemini не задан" : "✅ Ключ Gemini задан") + " — нажми, чтобы вписать или проверить",
                "🤖 Модели: " + g.fastModel + " (сортировка), " + g.smartModel + " (разговор), голос — " + g.audioModel
                        + (g.thinking.isEmpty() ? "" : ", размышления: " + g.thinking),
                "👤 О себе: " + (about.isEmpty() ? "не заполнено — секретарь сортирует хуже" : about.length() + " знаков"),
                "✍️ Подпись в ответах: " + core.prefs.getString(Core.K_NAME, "не задана"),
                "🗓 Сводки: " + core.prefs.getString(Core.K_DIGESTS, "08:30, 19:00") + "; напоминать до событий за "
                        + core.prefs.getString(Core.K_REMIND, "60, 10") + " мин",
                (access ? "✅" : "❌") + " Доступ к уведомлениям — чтобы секретарь видел сообщения",
                (battery ? "✅" : "❌") + " Работа в фоне без ограничений — чтобы секретарь не засыпал",
                (notify ? "✅" : "❌") + " Показывать уведомления от секретаря",
                (focus ? "🔕 Тишина ВКЛЮЧЕНА" : "🔔 Тишина выключена")
                        + " — на экране только важное, остальное спрятано (не теряется, оно в сводке). Нажми, чтобы "
                        + (focus ? "выключить" : "включить"),
                (bypass ? "✅" : "❌") + " Важное — со звуком даже в «Не беспокоить»",
                "🔊 Проверить звук важного",
                "📜 Отправить журнал (что секретарь делал и почему)",
        };
        new AlertDialog.Builder(this)
                .setTitle("Настройка")
                .setItems(items, (d, which) -> {
                    if (which == 0) askKey();
                    else if (which == 1) editModels();
                    else if (which == 2) editText("О себе", "Кто я и что для меня важно: работа, учёба (занятия по вторникам), "
                            + "важные люди, что не интересно", Core.K_ABOUT, "", true);
                    else if (which == 3) editText("Подпись в ответах на письма", "Имя Фамилия", Core.K_NAME, "", false);
                    else if (which == 4) editSchedule();
                    else if (which == 5) startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                    else if (which == 6) startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName())));
                    else if (which == 7 && Build.VERSION.SDK_INT >= 33)
                        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
                    else if (which == 8) toggleFocus();
                    else if (which == 9) {
                        // Звук в «Не беспокоить» возможен только с доступом к этому режиму; есть доступ — настройки канала.
                        if (!nm.isNotificationPolicyAccessGranted()) {
                            toast("Разреши «Секретарю» доступ к «Не беспокоить» — тогда важное будет со звуком");
                            startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                        } else {
                            Listener.ensureChannels(this);
                            startActivity(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                                    .putExtra(Settings.EXTRA_CHANNEL_ID, Listener.alertChannel(this)));
                        }
                    } else if (which == 10) Listener.testAlert(this);
                    else if (which == 11) shareJournal();
                })
                .setPositiveButton("Готово", null)
                .show();
    }

    private EditText field(LinearLayout box, String label, String value, String hint, boolean multi) {
        android.widget.TextView t = new android.widget.TextView(this);
        t.setText(label);
        t.setPadding(0, 24, 0, 0);
        box.addView(t);
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setInputType(multi ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        if (multi) e.setMinLines(4);
        box.addView(e);
        return e;
    }

    private LinearLayout box() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 8, 48, 8);
        return box;
    }

    /** Ключ Gemini (aistudio.google.com → Get API key): вписать и сразу проверить. */
    void askKey() {
        LinearLayout box = box();
        EditText key = field(box, "Ключ Gemini API (aistudio.google.com → Get API key; лучше платный тариф — "
                + "иначе Google учится на твоих данных). Хранится только на этом телефоне.", core.prefs.getString(Core.K_KEY, ""), "AIza…", false);
        new AlertDialog.Builder(this)
                .setTitle("Ключ Gemini")
                .setView(box)
                .setPositiveButton("Сохранить и проверить", (d, w) -> {
                    core.prefs.edit().putString(Core.K_KEY, key.getText().toString().trim()).apply();
                    Core.Journal.line("settings: Gemini key " + (key.getText().toString().trim().isEmpty() ? "cleared" : "set"));
                    checkModel();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    /** Короткий запрос к быстрой модели: ключ и связь работают? */
    void checkModel() {
        toast("Проверяю связь с Gemini…");
        new Thread(() -> {
            String msg;
            try {
                long t0 = System.currentTimeMillis();
                String r = core.brain.text(false, "Отвечай одним словом.", "Скажи «работает».");
                msg = "✅ Gemini отвечает (" + (System.currentTimeMillis() - t0) + " мс): " + r;
            } catch (LlmError e) {
                msg = "❌ " + e.getMessage();
            }
            Core.Journal.line("settings: check — " + msg);
            String m = msg;
            runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Проверка").setMessage(m).setPositiveButton("Ок", null).show());
        }, "check").start();
    }

    /** Модели — как в config.toml: «gemini:имя» или «openai:имя» (OpenRouter, DeepSeek). */
    void editModels() {
        io.github.teoplaydor.semsearch.secretary.GeminiBrain.Config g = core.geminiConfig();
        LinearLayout box = box();
        EditText fast = field(box, "Быстрая — сортировка уведомлений, разбор чатов, банки", g.fastModel, "gemini:gemini-3.1-flash-lite", false);
        EditText smart = field(box, "Умная — разговор с тобой и черновики", g.smartModel, "gemini:gemini-3.8-flash", false);
        EditText audio = field(box, "Голос — распознавание голосовых", g.audioModel, "gemini:gemini-3.1-flash-lite", false);
        EditText think = field(box, "Размышления: пусто — как решит модель; low — меньше, дешевле и быстрее; число — бюджет",
                g.thinking, "", false);
        EditText okey = field(box, "Ключ для «openai:…» моделей (OpenRouter, DeepSeek), если нужны", g.openaiKey, "", false);
        EditText ourl = field(box, "Адрес OpenAI-совместимого API", g.openaiBaseUrl, "https://openrouter.ai/api/v1", false);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("Модели")
                .setView(sv)
                .setPositiveButton("Сохранить и проверить", (d, w) -> {
                    core.prefs.edit().putString(Core.K_FAST, fast.getText().toString().trim())
                            .putString(Core.K_SMART, smart.getText().toString().trim())
                            .putString(Core.K_AUDIO, audio.getText().toString().trim())
                            .putString(Core.K_THINK, think.getText().toString().trim())
                            .putString(Core.K_OPENAI_KEY, okey.getText().toString().trim())
                            .putString(Core.K_OPENAI_URL, ourl.getText().toString().trim()).apply();
                    Core.Journal.line("settings: models " + fast.getText() + " / " + smart.getText() + " / " + audio.getText()
                            + ", thinking «" + think.getText() + "»");
                    checkModel();
                })
                .setNegativeButton("Отмена", null)
                .setNeutralButton("По умолчанию", (d, w) -> {
                    core.prefs.edit().remove(Core.K_FAST).remove(Core.K_SMART).remove(Core.K_AUDIO).remove(Core.K_THINK).apply();
                    toast("Модели — как в config.example.toml");
                })
                .show();
    }

    void editText(String title, String hint, String key, String def, boolean multi) {
        LinearLayout box = box();
        EditText e = field(box, hint, core.prefs.getString(key, def), "", multi);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Сохранить", (d, w) -> {
                    core.prefs.edit().putString(key, e.getText().toString().trim()).apply();
                    core.reloadPrefs();
                    Core.Journal.line("settings: " + key + " — " + e.getText().toString().trim().length() + " chars");
                    toast("Сохранено");
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    void editSchedule() {
        LinearLayout box = box();
        EditText times = field(box, "Сводки — время через запятую (утренняя до 15:00, вечерняя после)",
                core.prefs.getString(Core.K_DIGESTS, "08:30, 19:00"), "08:30, 19:00", false);
        EditText before = field(box, "Напоминать до занятий и вебинаров — за сколько минут", core.prefs.getString(Core.K_REMIND, "60, 10"),
                "60, 10", false);
        new AlertDialog.Builder(this)
                .setTitle("Сводки и напоминания")
                .setView(box)
                .setPositiveButton("Сохранить", (d, w) -> {
                    core.prefs.edit().putString(Core.K_DIGESTS, times.getText().toString().trim())
                            .putString(Core.K_REMIND, before.getText().toString().trim()).apply();
                    core.reloadPrefs();
                    toast("Сводки: " + core.sec.cfg.digestTimes);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    void shareJournal() {
        if (Core.Journal.share(this) == null) toast("Не удалось сохранить журнал");
    }

    /** «Тишина»: на экране только то, что секретарь счёл важным; остальное спрятано до выключения режима. */
    void toggleFocus() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        boolean on = !Listener.focus(this);
        String result = Listener.setFocus(this, on);
        if (result == null) {
            toast("Разреши «Секретарю» доступ к режиму «Не беспокоить» и включи Тишину ещё раз");
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
            return;
        }
        toast(result);
        android.app.NotificationChannel alerts = nm.getNotificationChannel(Listener.alertChannel(this));
        if (on && (alerts == null || !alerts.canBypassDnd()))
            toast("Включи для «Важное» переключатель «Игнорировать Не беспокоить» — пункт ниже в Настройке");
    }

    // ---------------- голос ----------------

    boolean startVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return false;
        }
        try {
            voiceFile = new File(getCacheDir(), "voice.aac");
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(16000);
            recorder.setAudioEncodingBitRate(32000);
            recorder.setAudioChannels(1);
            recorder.setOutputFile(voiceFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            return true;
        } catch (Exception e) {
            releaseRecorder();
            toast("Микрофон недоступен: " + e.getMessage());
            return false;
        }
    }

    void stopVoice() {
        if (recorder == null) return;
        try {
            recorder.stop();
        } catch (Exception ignored) {
            // слишком короткая запись
        }
        releaseRecorder();
        File f = voiceFile;
        new Thread(() -> {
            String error = null;
            try {
                byte[] audio = Files.readAllBytes(f.toPath());
                if (audio.length < 1000) error = "Слишком коротко";
                else core.voice(audio, "audio/aac"); // Gemini слышит голосовое, текст и ответ — в чат
            } catch (LlmError e) {
                error = "Не расслышал: " + e.getMessage();
                Core.Journal.line("voice: " + e.getMessage());
            } catch (Exception e) {
                error = "Не получилось: " + e.getMessage();
                Core.Journal.error("voice", e);
            }
            String js = "window.onVoiceResult && window.onVoiceResult(" + (error == null) + ","
                    + (error == null ? "null" : io.github.teoplaydor.semsearch.core.MiniJson.write(error)) + ")";
            runOnUiThread(() -> web.evaluateJavascript(js, null));
        }, "voice").start();
    }

    private void releaseRecorder() {
        if (recorder != null) {
            recorder.release();
            recorder = null;
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_MIC && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
            toast("Микрофон разрешён — нажми 🎙 ещё раз");
    }

    void toast(String text) {
        runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show());
    }

    /** Методы, которые вызывает страница чата (window.Android). */
    final class Bridge {
        @JavascriptInterface public boolean startVoice() {
            // Страница ждёт ответа «запись пошла?», а микрофон включается в главном потоке.
            final boolean[] ok = {false};
            final CountDownLatch done = new CountDownLatch(1);
            runOnUiThread(() -> {
                ok[0] = MainActivity.this.startVoice();
                done.countDown();
            });
            try {
                done.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            return ok[0];
        }

        @JavascriptInterface public void stopVoice() {
            runOnUiThread(MainActivity.this::stopVoice);
        }

        @JavascriptInterface public void copy(String text) {
            runOnUiThread(() -> getSystemService(ClipboardManager.class)
                    .setPrimaryClip(ClipData.newPlainText("Секретарь", text)));
        }

        @JavascriptInterface public void seen(long id) {
            Listener.prefs(MainActivity.this).edit().putLong("seen_id", id).apply();
        }

        @JavascriptInterface public void openSetup() {
            runOnUiThread(MainActivity.this::showSetup);
        }

        /** POST страницы (сообщение, кнопка, команда): ответ секретаря, «!код» при ошибке. */
        @JavascriptInterface public String post(String path, String body) {
            Api.Reply r = core.api.handle(path, body);
            return r.status >= 400 ? "!" + r.status : r.json;
        }

        @JavascriptInterface public void shareLog() {
            runOnUiThread(MainActivity.this::shareJournal);
        }

        @JavascriptInterface public void retry() {
            runOnUiThread(MainActivity.this::load);
        }
    }

    static WebResourceResponse json(int status, String body) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-store");
        return new WebResourceResponse("application/json", "utf-8", status, status == 200 ? "OK" : "Error", headers,
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    /** Страница — из приложения; её GET-запросы (новые сообщения, экраны вкладок) — секретарю здесь же. */
    final class Client extends WebViewClient {
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (!"secretary.local".equals(u.getHost())) return null;
            String path = u.getPath() == null ? "/" : u.getPath();
            try {
                if ("/app".equals(path) || "/".equals(path)) {
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Cache-Control", "no-cache");
                    return new WebResourceResponse("text/html", "utf-8", 200, "OK", headers, getAssets().open("app.html"));
                }
                if (path.startsWith("/api/") && "GET".equals(req.getMethod())) {
                    String q = u.getEncodedQuery();
                    Api.Reply r = core.api.handle(path + (q == null ? "" : "?" + q), null);
                    return json(r.status, r.json);
                }
            } catch (Exception e) {
                Core.Journal.error("page request " + path, e);
                return json(500, "{\"error\":\"" + e.getClass().getSimpleName() + "\"}");
            }
            return json(404, "{\"error\":\"not found\"}");
        }

        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri url = req.getUrl();
            if ("secretary.local".equals(url.getHost())) return false;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, url)); // ссылки (Zoom, сайты) — в браузере/приложении
            } catch (Exception e) {
                toast("Нечем открыть ссылку");
            }
            return true;
        }

        @Override public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
            Core.Journal.line("page error " + err.getErrorCode() + " " + err.getDescription() + " at " + req.getUrl());
        }
    }
}
