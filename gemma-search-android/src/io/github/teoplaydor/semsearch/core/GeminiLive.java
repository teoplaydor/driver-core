package io.github.teoplaydor.semsearch.core;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A spoken conversation with Gemini through the Live API (BidiGenerateContent over a WebSocket), as Google's own SDK
 * (google-genai) speaks it: the setup (model, spoken answers, the system instruction, the functions it may call, both
 * sides transcribed, the end of speech found by the server), then the voice as it is said — 16 kHz PCM, base64 in
 * JSON — and back the answer's voice (PCM, 24 kHz), what each side said as text, «turn complete», «interrupted», the
 * function calls to answer. The key goes in a header (x-goog-api-key), never in the address.
 */
public final class GeminiLive implements Closeable {
    public static final String HOST = "generativelanguage.googleapis.com";
    static final String WS_PATH = "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";
    /** The voice goes up at this rate. */
    public static final int IN_RATE = 16000;

    public interface Listener {
        /** Set up: the voice may go. */
        void onReady();

        /** A piece of the answer's voice (16-bit samples at {@code rate}). */
        void onAudio(short[] pcm, int rate);

        /** What was heard of the voice, a piece at a time. */
        void onInputText(String text);

        /** What the answer says, a piece at a time. */
        void onOutputText(String text);

        void onTurnComplete();

        /** The answer was cut short (the voice spoke over it). */
        void onInterrupted();

        /** A function to call; answer with {@link #toolResponse}. */
        void onToolCall(String id, String name, Map<String, Object> args);

        /** The server will close the session soon (timeLeft as it says it). */
        void onGoAway(String timeLeft);

        /** Over: the close code and reason (-1: the connection itself failed). */
        void onClosed(int code, String reason);
    }

    /** What the session is set up with. */
    public static final class Config {
        /** «models/…» or the bare name. */
        public String model;
        public String systemInstruction;
        /** A prebuilt voice («Kore», «Puck», …); null — the model's own. */
        public String voice;
        /** Function declarations (name, description, parameters as a JSON schema), or none. */
        public final List<Map<String, Object>> functions = new ArrayList<Map<String, Object>>();
        /** The server ends a turn after this much silence (ms); 0 — its default. */
        public int silenceMs;
        /** How soon the server takes the speech for ended: «END_SENSITIVITY_HIGH» (sooner) or «…_LOW»; null — its default. */
        public String endSensitivity;
    }

    private final WebSocket ws;
    private final Listener listener;
    private volatile boolean ready;

    private GeminiLive(String url, Map<String, String> headers, Config c, final Listener listener) throws IOException {
        this.listener = listener;
        ws = WebSocket.connect(url, headers, 15000, new WebSocket.Listener() {
            @Override
            public void onText(String text) {
                handle(text);
            }

            @Override
            public void onBinary(byte[] data) {
                // the server sends its JSON as binary frames too
                handle(new String(data, StandardCharsets.UTF_8));
            }

            @Override
            public void onClosed(int code, String reason) {
                ready = false;
                listener.onClosed(code, reason);
            }
        });
        try {
            ws.sendText(setup(c));
        } catch (IOException e) {
            // closed by the server at once (a wrong key…): the listener hears why (onClosed)
            if (ws.isOpen()) throw e;
        }
    }

    /**
     * Connects and sends the setup; {@link Listener#onReady} once the server took it.
     *
     * @param host {@link #HOST}, or another (tests: «ws://127.0.0.1:port»)
     */
    public static GeminiLive connect(String host, String apiKey, Config c, Listener listener) throws IOException {
        String base = host.contains("://") ? host : "wss://" + host;
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("x-goog-api-key", apiKey);
        return new GeminiLive(base + WS_PATH, h, c, listener);
    }

    /** The setup message (what the SDK sends for the same LiveConnectConfig). */
    public static String setup(Config c) {
        Map<String, Object> gen = new LinkedHashMap<String, Object>();
        gen.put("responseModalities", list("AUDIO"));
        if (c.voice != null && !c.voice.isEmpty()) {
            gen.put("speechConfig", map("voiceConfig", map("prebuiltVoiceConfig", map("voiceName", c.voice))));
        }
        Map<String, Object> s = new LinkedHashMap<String, Object>();
        s.put("model", c.model.startsWith("models/") ? c.model : "models/" + c.model);
        s.put("generationConfig", gen);
        if (c.systemInstruction != null && !c.systemInstruction.isEmpty()) {
            s.put("systemInstruction", map("parts", list(map("text", c.systemInstruction)), "role", "user"));
        }
        if (!c.functions.isEmpty()) s.put("tools", list(map("functionDeclarations", new ArrayList<Object>(c.functions))));
        if (c.silenceMs > 0 || c.endSensitivity != null) {
            Map<String, Object> vad = new LinkedHashMap<String, Object>();
            if (c.endSensitivity != null) vad.put("endOfSpeechSensitivity", c.endSensitivity);
            if (c.silenceMs > 0) vad.put("silenceDurationMs", c.silenceMs);
            s.put("realtimeInputConfig", map("automaticActivityDetection", vad));
        }
        s.put("inputAudioTranscription", new LinkedHashMap<String, Object>());
        s.put("outputAudioTranscription", new LinkedHashMap<String, Object>());
        return MiniJson.write(map("setup", s));
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static List<Object> list(Object... v) {
        return new ArrayList<Object>(Arrays.asList(v));
    }

    public boolean ready() {
        return ready && ws.isOpen();
    }

    /** A piece of the voice: 16-bit samples at 16 kHz (n of them from {@code off}). */
    public void sendAudio(short[] pcm, int off, int n) throws IOException {
        byte[] b = new byte[2 * n];
        for (int i = 0; i < n; i++) {
            short v = pcm[off + i];
            b[2 * i] = (byte) v;
            b[2 * i + 1] = (byte) (v >> 8);
        }
        ws.sendText(MiniJson.write(map("realtimeInput", map("audio", map("data", java.util.Base64.getEncoder().encodeToString(b),
                "mimeType", "audio/pcm;rate=" + IN_RATE)))));
    }

    /** The voice paused (the microphone off for now): what was said is answered. */
    public void audioStreamEnd() throws IOException {
        ws.sendText(MiniJson.write(map("realtimeInput", map("audioStreamEnd", Boolean.TRUE))));
    }

    /** Something typed instead of said. */
    public void sendText(String text) throws IOException {
        ws.sendText(MiniJson.write(map("realtimeInput", map("text", text))));
    }

    /** The answer to a function call. */
    public void toolResponse(String id, String name, Map<String, Object> response) throws IOException {
        Map<String, Object> r = map("name", name, "response", response);
        if (id != null) r.put("id", id);
        ws.sendText(MiniJson.write(map("toolResponse", map("functionResponses", list(r)))));
    }

    static final Pattern RATE = Pattern.compile("rate=(\\d+)");

    /** Base64, the standard alphabet or the URL-safe one (the SDK's own serializer writes that), padded or not. */
    static byte[] bytes(String b64) {
        return b64.indexOf('-') >= 0 || b64.indexOf('_') >= 0 ? java.util.Base64.getUrlDecoder().decode(b64) : java.util.Base64.getDecoder().decode(b64);
    }

    void handle(String text) {
        try {
            dispatch(text);
        } catch (RuntimeException e) {
            // one odd message (or a listener's error) does not end the conversation
            System.err.println("GeminiLive: " + e);
        }
    }

    private void dispatch(String text) {
        Map<String, Object> m;
        try {
            m = MiniJson.obj(MiniJson.parse(text));
        } catch (IOException e) {
            return;
        }
        if (m == null) return;
        if (m.containsKey("setupComplete")) {
            ready = true;
            listener.onReady();
        }
        Map<String, Object> sc = MiniJson.obj(m.get("serverContent"));
        if (sc != null) {
            Map<String, Object> turn = MiniJson.obj(sc.get("modelTurn"));
            List<Object> parts = turn != null ? MiniJson.arr(turn.get("parts")) : null;
            if (parts != null) {
                for (Object o : parts) {
                    Map<String, Object> inline = MiniJson.obj(MiniJson.obj(o).get("inlineData"));
                    if (inline == null) continue;
                    String mime = MiniJson.str(inline, "mimeType", "");
                    String data = MiniJson.str(inline, "data", "");
                    if (!mime.startsWith("audio/pcm") || data.isEmpty()) continue;
                    Matcher r = RATE.matcher(mime);
                    int rate = r.find() ? Integer.parseInt(r.group(1)) : 24000;
                    byte[] b = bytes(data);
                    short[] pcm = new short[b.length / 2];
                    for (int i = 0; i < pcm.length; i++) pcm[i] = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8));
                    listener.onAudio(pcm, rate);
                }
            }
            Map<String, Object> in = MiniJson.obj(sc.get("inputTranscription"));
            if (in != null && !MiniJson.str(in, "text", "").isEmpty()) listener.onInputText(MiniJson.str(in, "text", ""));
            Map<String, Object> out = MiniJson.obj(sc.get("outputTranscription"));
            if (out != null && !MiniJson.str(out, "text", "").isEmpty()) listener.onOutputText(MiniJson.str(out, "text", ""));
            if (MiniJson.bool(sc, "interrupted", false)) listener.onInterrupted();
            if (MiniJson.bool(sc, "turnComplete", false)) listener.onTurnComplete();
        }
        Map<String, Object> tc = MiniJson.obj(m.get("toolCall"));
        List<Object> calls = tc != null ? MiniJson.arr(tc.get("functionCalls")) : null;
        if (calls != null) {
            for (Object o : calls) {
                Map<String, Object> f = MiniJson.obj(o);
                Map<String, Object> args = MiniJson.obj(f.get("args"));
                listener.onToolCall(MiniJson.str(f, "id", null), MiniJson.str(f, "name", ""), args != null ? args : new HashMap<String, Object>());
            }
        }
        Map<String, Object> ga = MiniJson.obj(m.get("goAway"));
        if (ga != null) listener.onGoAway(MiniJson.str(ga, "timeLeft", ""));
    }

    @Override
    public void close() {
        ready = false;
        ws.close();
    }

    // ------------------------------------------------------------------------------------------------ models

    /** The models that speak live ({@code bidiGenerateContent}), best first (see {@link #rank}). */
    public static List<String> liveModels(String host, String apiKey) throws IOException {
        List<String> out = new ArrayList<String>();
        String page = null;
        String base = host.contains("://") ? host.replaceFirst("^wss?://", host.startsWith("ws://") ? "http://" : "https://") : "https://" + host;
        for (int round = 0; round < 10; round++) {
            URL u = new URL(base + "/v1beta/models?pageSize=1000" + (page != null ? "&pageToken=" + java.net.URLEncoder.encode(page, "UTF-8") : ""));
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setRequestProperty("x-goog-api-key", apiKey);
            int code = c.getResponseCode();
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            String body = in == null ? "" : read(in);
            if (code != 200) throw new IOException("Gemini API: HTTP " + code + (body.isEmpty() ? "" : " — " + errorText(body)));
            Map<String, Object> m = MiniJson.obj(MiniJson.parse(body));
            List<Object> models = m != null ? MiniJson.arr(m.get("models")) : null;
            if (models != null) {
                for (Object o : models) {
                    Map<String, Object> md = MiniJson.obj(o);
                    List<Object> methods = MiniJson.arr(md.get("supportedGenerationMethods"));
                    if (methods != null && methods.contains("bidiGenerateContent")) out.add(MiniJson.str(md, "name", ""));
                }
            }
            page = m != null ? MiniJson.str(m, "nextPageToken", null) : null;
            if (page == null || page.isEmpty()) break;
        }
        return rank(out);
    }

    /** The message of an API error JSON ({"error": {"message": …}}), or the body itself. */
    public static String errorText(String body) {
        try {
            Map<String, Object> e = MiniJson.obj(MiniJson.obj(MiniJson.parse(body)).get("error"));
            if (e != null && MiniJson.str(e, "message", null) != null) return MiniJson.str(e, "message", null);
        } catch (Exception ignored) {
            // not JSON
        }
        return body.length() > 300 ? body.substring(0, 300) : body;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        try {
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        } finally {
            in.close();
        }
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * Best first: a native-audio model (it hears and speaks itself — the most natural voice), then the newest version
     * (gemini-3… before gemini-2.5…), then the newest date in its name; «tts» and «image» ones last.
     */
    public static List<String> rank(List<String> names) {
        List<String> out = new ArrayList<String>(names);
        Collections.sort(out, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                int d = Integer.compare(score(b), score(a));
                if (d != 0) return d;
                d = compareVersions(version(b), version(a));
                if (d != 0) return d;
                d = Long.compare(date(b), date(a));
                return d != 0 ? d : a.compareTo(b);
            }
        });
        return out;
    }

    private static int score(String n) {
        String s = n.toLowerCase();
        int v = 0;
        if (s.contains("native-audio")) v += 4;
        if (s.contains("live")) v += 2;
        if (s.contains("tts") || s.contains("image") || s.contains("embedding")) v -= 8;
        return v;
    }

    private static final Pattern VERSION = Pattern.compile("gemini-(\\d+(?:\\.\\d+)?)");

    private static double[] version(String n) {
        Matcher m = VERSION.matcher(n);
        if (!m.find()) return new double[]{0};
        String[] p = m.group(1).split("\\.");
        double[] v = new double[p.length];
        for (int i = 0; i < p.length; i++) v[i] = Double.parseDouble(p[i]);
        return v;
    }

    private static int compareVersions(double[] a, double[] b) {
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            double x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (x != y) return Double.compare(x, y);
        }
        return 0;
    }

    private static final Pattern DATE_MY = Pattern.compile("-(\\d{2})-(\\d{4})(?:$|-)"), DATE_YMD = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})"),
            DATE_MD = Pattern.compile("-(\\d{2})-(\\d{2})(?:$|-)");

    /** A date in the name («…-09-2025», «…-2025-12-16», «…-04-09»), as yyyymm(dd); 0 — none. */
    private static long date(String n) {
        Matcher m = DATE_YMD.matcher(n);
        if (m.find()) return Long.parseLong(m.group(1) + m.group(2) + m.group(3));
        m = DATE_MY.matcher(n);
        if (m.find()) return Long.parseLong(m.group(2) + m.group(1) + "00");
        m = DATE_MD.matcher(n);
        if (m.find()) return Long.parseLong(m.group(1) + m.group(2));
        return 0;
    }
}
