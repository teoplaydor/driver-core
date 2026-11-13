package io.github.teoplaydor.semsearch.app;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.teoplaydor.semsearch.core.MiniJson;
import io.github.teoplaydor.semsearch.secretary.Api;

/**
 * The secretary's own screen: the server version's chat page (app.html — the chat, «Дела», «Сегодня», «Сводка»,
 * «Расходы») in a WebView. Its API is answered here on the phone: GET (the chat's long poll, the tab screens) through
 * the WebView's request interception, POST (a message, a button) through the page's bridge, as WebView does not show
 * a request's body to the app. A voice message is heard by GigaAM on the phone.
 */
public class SecActivity extends Activity {
    static final String ORIGIN = "https://secretary.local";
    static final int REQ_MIC = 71, REQ_NOTIFY = 72;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private WebView web;
    private SecHub hub;
    private volatile AudioRecord rec;
    private Thread recorder;
    private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        hub = SecHub.get(this);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#262624"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new Client());
        setContentView(web);
        web.loadUrl(ORIGIN + "/app");
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFY);
        // first time: what is needed (the model, the access to notifications)
        if (hub.modelFile() == null || !SecListener.hasAccess(this)) startActivity(new Intent(this, SecSetup.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        SecHub.visible = true;
        hub.startLoop();
        hub.reloadPrefs();
        hub.clearChatNotifications();
    }

    @Override
    protected void onPause() {
        SecHub.visible = false;
        hub.stopLoop();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.goBack ? window.goBack() : false", new android.webkit.ValueCallback<String>() {
            public void onReceiveValue(String handled) {
                if (!"true".equals(handled)) SecActivity.super.onBackPressed();
            }
        });
    }

    @Override
    protected void onDestroy() {
        stopRecording();
        web.destroy();
        super.onDestroy();
    }

    static WebResourceResponse json(int status, String body) {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Cache-Control", "no-store");
        return new WebResourceResponse("application/json", "utf-8", status, status == 200 ? "OK" : "Error", headers,
                new ByteArrayInputStream(body.getBytes(UTF8)));
    }

    final class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (!"secretary.local".equals(u.getHost())) return null;
            String path = u.getPath() == null ? "/" : u.getPath();
            try {
                if ("/app".equals(path) || "/".equals(path)) {
                    InputStream in = getAssets().open("secretary/app.html");
                    Map<String, String> headers = new HashMap<String, String>();
                    headers.put("Cache-Control", "no-cache");
                    return new WebResourceResponse("text/html", "utf-8", 200, "OK", headers, in);
                }
                if (path.startsWith("/api/") && "GET".equals(req.getMethod())) {
                    String q = u.getEncodedQuery();
                    Api.Reply r = hub.api.handle(path + (q == null ? "" : "?" + q), null);
                    return json(r.status, r.json);
                }
            } catch (Exception e) {
                return json(500, "{\"error\":\"" + e.getClass().getSimpleName() + "\"}");
            }
            return json(404, "{\"error\":\"not found\"}");
        }

        // Android 7's
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri url = req.getUrl();
            return open(url);
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String u) {
            return open(Uri.parse(u));
        }

        private boolean open(Uri url) {
            if ("secretary.local".equals(url.getHost())) return false;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, url)); // links (Zoom, sites) — in their apps
            } catch (Exception e) {
                Toast.makeText(SecActivity.this, "Нечем открыть ссылку", Toast.LENGTH_SHORT).show();
            }
            return true;
        }
    }

    /** What the page calls: window.Android. */
    final class Bridge {
        /** A POST of the API: the JSON answer, or "!status" for an error status. */
        @JavascriptInterface
        public String post(String path, String body) {
            Api.Reply r = hub.api.handle(path, body);
            return r.status >= 400 ? "!" + r.status : r.json;
        }

        @JavascriptInterface
        public boolean startVoice() {
            final boolean[] ok = {false};
            final CountDownLatch done = new CountDownLatch(1);
            runOnUiThread(new Runnable() {
                public void run() {
                    ok[0] = SecActivity.this.startVoice();
                    done.countDown();
                }
            });
            try {
                done.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            return ok[0];
        }

        @JavascriptInterface
        public void stopVoice() {
            runOnUiThread(new Runnable() {
                public void run() {
                    SecActivity.this.stopVoice();
                }
            });
        }

        @JavascriptInterface
        public void copy(final String text) {
            runOnUiThread(new Runnable() {
                public void run() {
                    ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Секретарь", text));
                }
            });
        }

        @JavascriptInterface
        public void seen(long id) {
            hub.prefs.edit().putLong("seen_id", id).apply();
        }

        @JavascriptInterface
        public void openSetup() {
            runOnUiThread(new Runnable() {
                public void run() {
                    startActivity(new Intent(SecActivity.this, SecSetup.class));
                }
            });
        }

        @JavascriptInterface
        public void retry() {
            runOnUiThread(new Runnable() {
                public void run() {
                    web.reload();
                }
            });
        }
    }

    // ---------------- voice: recorded here, heard by GigaAM on the phone ----------------

    private boolean startVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return false;
        }
        if (!Engine.get(this).speechInstalled()) {
            Toast.makeText(this, "Распознавание речи не скачано: Смысловой поиск → настройки → голос", Toast.LENGTH_LONG).show();
            return false;
        }
        try {
            int rate = 16000;
            int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            final AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate) * 2);
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                r.release();
                return false;
            }
            synchronized (pcm) {
                pcm.reset();
            }
            rec = r;
            r.startRecording();
            recorder = new Thread(new Runnable() {
                public void run() {
                    byte[] buf = new byte[3200];
                    int total = 0;
                    while (rec == r && total < 16000 * 2 * 120) {
                        int n = r.read(buf, 0, buf.length);
                        if (n < 0) break;
                        synchronized (pcm) {
                            pcm.write(buf, 0, n);
                        }
                        total += n;
                    }
                }
            }, "sec-mic");
            recorder.start();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private byte[] stopRecording() {
        AudioRecord r = rec;
        rec = null;
        if (r == null) return null;
        try {
            if (recorder != null) recorder.join(500);
        } catch (InterruptedException ignored) {
        }
        try {
            r.stop();
        } catch (Exception ignored) {
        }
        r.release();
        synchronized (pcm) {
            return pcm.toByteArray();
        }
    }

    private void stopVoice() {
        final byte[] audio = stopRecording();
        if (audio == null) return;
        new Thread(new Runnable() {
            public void run() {
                String error = null;
                try {
                    String text = Speech.text(Engine.get(SecActivity.this), Speech.samples(audio), null);
                    Map<String, Object> body = new HashMap<String, Object>();
                    body.put("text", text);
                    hub.api.handle("/api/voice", MiniJson.write(body));
                } catch (Exception e) {
                    error = "Не расслышал: " + e.getMessage();
                }
                final String err = error;
                runOnUiThread(new Runnable() {
                    public void run() {
                        String js = "window.onVoiceResult && window.onVoiceResult(" + (err == null) + ","
                                + (err == null ? "null" : MiniJson.write(err)) + ")";
                        web.evaluateJavascript(js, null);
                    }
                });
            }
        }, "sec-voice").start();
    }
}
