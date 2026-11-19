package io.github.teoplaydor.semsearch.secretary;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.teoplaydor.semsearch.core.MiniJson;

/**
 * The secretary's model in the cloud, called from the phone itself — the server version's llm.py: a model is
 * «provider:model» («gemini:gemini-3.1-flash-lite», any OpenAI-compatible API «openai:deepseek/deepseek-v4-flash», or
 * Polza AI «polza:qwen/qwen3.6-35b-a3b@yandex» — the model from that one provider, no other), so it changes by one line
 * in the settings. Gemini answers as JSON of the asked schema (responseSchema), its thinking set by «thinking» (a
 * number — a budget, a word — a level); busy answers (429, 5xx) tried again; what each call cost counted (Polza says
 * it in rubles itself). A voice message is heard by the audio model (Gemini, or Polza's speech recognition).
 */
public final class GeminiBrain implements Brain {
    public static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";
    static final int[] RETRY_STATUS = {429, 500, 502, 503, 504};
    public static final String TRANSCRIBE_PROMPT = "Распознай речь в этом аудио и верни только текст, без пояснений. "
            + "Расставь знаки препинания. Если речи нет — верни пустую строку.";

    /** Polza AI: Russian, so reachable from Russia without a VPN; one key for Qwen, YandexGPT, speech recognition. */
    public static final String POLZA_URL = "https://polza.ai/api/v1";
    /** The cheapest Qwen served by Yandex (288 ₽ in, 72 ₽ out per 1M tokens; data stays in Russia). It always thinks
     *  first (Yandex can't turn it off), so its answers are never cut short: no max_tokens. */
    public static final String POLZA_MODEL = "polza:qwen/qwen3.6-35b-a3b@yandex";
    /** GigaAM v3 on Polza's own servers in Russia: 0.25 ₽ a minute, takes the recorder's AAC as is. */
    public static final String POLZA_AUDIO = "polza:ai-sage/gigaam-v3";

    /** USD per 1 million tokens (in, out) — only for the estimate in «Нейросеть»; prices change. */
    public static final Map<String, double[]> PRICES = new HashMap<String, double[]>();

    static {
        PRICES.put("gemini-2.5-flash-lite", new double[]{0.10, 0.40});
        PRICES.put("gemini-3.1-flash-lite", new double[]{0.25, 1.50});
        PRICES.put("gemini-3.5-flash-lite", new double[]{0.30, 2.50});
        PRICES.put("gemini-2.5-flash", new double[]{0.30, 2.50});
        PRICES.put("gemini-3-flash-preview", new double[]{0.50, 3.00});
        PRICES.put("gemini-3.6-flash", new double[]{0.75, 3.75});
        PRICES.put("gemini-3.8-flash", new double[]{0.75, 3.75});
        PRICES.put("deepseek-flash", new double[]{0.30, 1.20});
        PRICES.put("gpt-5-nano", new double[]{0.05, 0.40});
        PRICES.put("gpt-5.4-nano", new double[]{0.20, 1.25});
        PRICES.put("gpt-5.4-mini", new double[]{0.75, 4.50});
    }

    /** The cost estimate in USD, or null for a model with no known price. */
    public static Double estimateCost(String model, long in, long out) {
        double[] p = PRICES.get(model);
        return p == null ? null : (in * p[0] + out * p[1]) / 1_000_000;
    }

    /** The settings (read each call: changed in the app, used at once). */
    public static class Config {
        public String geminiKey = "", openaiKey = "", openaiBaseUrl = "https://openrouter.ai/api/v1";
        public String polzaKey = "", polzaUrl = POLZA_URL;
        public String fastModel = POLZA_MODEL, smartModel = POLZA_MODEL, audioModel = POLZA_AUDIO;
        /** "" — as the model decides; "low" — less thinking, cheaper and faster; a number — a token budget
         *  (Gemini). For Polza a word goes as reasoning effort (Yandex takes low, medium, high). */
        public String thinking = "";
    }

    public interface Settings {
        Config config();
    }

    public interface Usage {
        /** costRub — what the call cost in rubles as the API said it (Polza), 0 when it doesn't say. */
        void add(String model, long tokensIn, long tokensOut, double costRub);
    }

    private final Settings settings;
    private final Usage usage;
    /** Where Gemini is (the tests put a stand-in server here). */
    public volatile String geminiUrl = GEMINI_URL;
    public volatile long retryDelayMs = 2000;

    public GeminiBrain(Settings settings, Usage usage) {
        this.settings = settings;
        this.usage = usage;
    }

    /** «provider:model» → [provider, model]. */
    static String[] splitSpec(String spec) throws LlmError {
        String s = spec == null ? "" : spec.trim();
        int i = s.indexOf(':');
        String provider = i < 0 ? "" : s.substring(0, i), model = i < 0 ? "" : s.substring(i + 1).trim();
        if (i < 0 || !("gemini".equals(provider) || "openai".equals(provider) || "polza".equals(provider)) || model.isEmpty())
            throw new LlmError("Неверная модель «" + spec + "»: нужно «polza:имя-модели@поставщик», «gemini:имя-модели»"
                    + " или «openai:имя-модели»");
        return new String[]{provider, model};
    }

    /** Polza's «model@provider» → the request's model and its provider block: that provider only, no fallback to
     *  another (a «model@key=value&…» alias of Polza's own goes as it is). */
    static void polzaModel(String model, Map<String, Object> body) {
        int at = model.indexOf('@');
        String tail = at < 0 ? "" : model.substring(at + 1).trim();
        if (at < 0 || tail.isEmpty() || tail.contains("=")) {
            body.put("model", at >= 0 && tail.isEmpty() ? model.substring(0, at) : model);
            return;
        }
        body.put("model", model.substring(0, at).trim());
        List<Object> only = new ArrayList<Object>();
        only.add(tail);
        body.put("provider", map("only", only, "allow_fallbacks", false));
    }

    /** JSON Schema → Gemini's responseSchema (types in capitals). */
    @SuppressWarnings("unchecked")
    static Object toGeminiSchema(Object schema) {
        Map<String, Object> m = MiniJson.obj(schema);
        if (m == null) return schema;
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            String k = e.getKey();
            if ("type".equals(k)) out.put("type", String.valueOf(e.getValue()).toUpperCase(Locale.ROOT));
            else if ("properties".equals(k)) {
                Map<String, Object> props = new LinkedHashMap<String, Object>();
                for (Map.Entry<String, Object> p : MiniJson.obj(e.getValue()).entrySet()) props.put(p.getKey(), toGeminiSchema(p.getValue()));
                out.put("properties", props);
            } else if ("items".equals(k)) out.put("items", toGeminiSchema(e.getValue()));
            else out.put(k, e.getValue());
        }
        return out;
    }

    static final Pattern FENCE = Pattern.compile("^\\s*```(?:json)?\\s*|\\s*```\\s*$");

    /** The model's text as a JSON object (fences around it and words before or after it forgiven). */
    static Map<String, Object> parseJson(String text) throws LlmError {
        String s = FENCE.matcher(text.trim()).replaceAll("");
        Object data = null;
        try {
            data = MiniJson.parse(s);
        } catch (IOException e) {
            int a = s.indexOf('{'), b = s.lastIndexOf('}');
            if (0 <= a && a < b) {
                try {
                    data = MiniJson.parse(s.substring(a, b + 1));
                } catch (IOException ignored) {
                    data = null;
                }
            }
        }
        Map<String, Object> m = MiniJson.obj(data);
        if (m == null) throw new LlmError("Нейросеть вернула не JSON: " + SecUtil.head(text, 200));
        return m;
    }

    public Map<String, Object> json(boolean smart, boolean urgent, String system, String user, Prompts.Schema schema) throws LlmError {
        Config cfg = settings.config();
        String[] pm = splitSpec(smart ? cfg.smartModel : cfg.fastModel);
        String text;
        try {
            if ("gemini".equals(pm[0])) {
                text = gemini(cfg, pm[1], system, textParts(user), MiniJson.parse(schema.json));
            } else {
                String sys = system + "\n\nОтвечай строго одним JSON-объектом по этой JSON-схеме:\n" + schema.json;
                text = openai(cfg, pm[0], pm[1], sys, user, true);
            }
        } catch (IOException e) { // the schema itself (never)
            throw new LlmError(String.valueOf(e.getMessage()));
        }
        return parseJson(text);
    }

    public String text(boolean smart, String system, String user) throws LlmError {
        Config cfg = settings.config();
        String[] pm = splitSpec(smart ? cfg.smartModel : cfg.fastModel);
        if ("gemini".equals(pm[0])) return gemini(cfg, pm[1], system, textParts(user), null).trim();
        return openai(cfg, pm[0], pm[1], system, user, false).trim();
    }

    /** What was said in a voice message (audio as recorded: «audio/aac», «audio/wav»…); "" — nothing said. */
    public String transcribe(byte[] audio, String mime) throws LlmError {
        Config cfg = settings.config();
        String[] pm = splitSpec(cfg.audioModel);
        if ("polza".equals(pm[0])) return polzaTranscribe(cfg, pm[1], audio, mime);
        if (!"gemini".equals(pm[0]))
            throw new LlmError("Голосовые распознаются через Polza («polza:ai-sage/gigaam-v3») или Gemini («gemini:…»)");
        List<Object> parts = new ArrayList<Object>();
        Map<String, Object> inline = new LinkedHashMap<String, Object>();
        inline.put("mimeType", mime);
        inline.put("data", java.util.Base64.getEncoder().encodeToString(audio));
        Map<String, Object> p1 = new LinkedHashMap<String, Object>();
        p1.put("inlineData", inline);
        parts.add(p1);
        Map<String, Object> p2 = new LinkedHashMap<String, Object>();
        p2.put("text", TRANSCRIBE_PROMPT);
        parts.add(p2);
        return gemini(cfg, pm[1], "Ты — точный распознаватель речи.", parts, null).trim();
    }

    /** Polza's speech recognition (OpenAI's /audio/transcriptions, the file as a data URL); billed by the minute. */
    private String polzaTranscribe(Config cfg, String model, byte[] audio, String mime) throws LlmError {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("file", "data:" + mime + ";base64," + java.util.Base64.getEncoder().encodeToString(audio));
        body.put("language", "ru");
        Map<String, Object> data = post(polzaBase(cfg) + "/audio/transcriptions", body, polzaHeaders(cfg));
        Map<String, Object> u = MiniJson.obj(data.get("usage"));
        if (usage != null) usage.add(model, 0, 0, costRub(u));
        Object text = data.get("text");
        if (!(text instanceof String)) throw new LlmError("Неожиданный ответ распознавания: " + SecUtil.head(MiniJson.write(data), 300));
        return ((String) text).trim();
    }

    private static String polzaBase(Config cfg) {
        String base = cfg.polzaUrl == null || cfg.polzaUrl.trim().isEmpty() ? POLZA_URL : cfg.polzaUrl.trim();
        return base.replaceAll("/+$", "");
    }

    private static Map<String, String> polzaHeaders(Config cfg) throws LlmError {
        if (cfg.polzaKey == null || cfg.polzaKey.trim().isEmpty())
            throw new LlmError("Не задан ключ Polza: «Настройка» → «Ключ Polza»");
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Authorization", "Bearer " + cfg.polzaKey.trim());
        return headers;
    }

    /** What is left on the Polza account, in rubles («924.83»), or null when it can't be learned. */
    public String polzaBalance() {
        HttpURLConnection c = null;
        try {
            Config cfg = settings.config();
            c = (HttpURLConnection) new URL(polzaBase(cfg) + "/balance").openConnection();
            c.setConnectTimeout(20_000);
            c.setReadTimeout(30_000);
            for (Map.Entry<String, String> h : polzaHeaders(cfg).entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            if (c.getResponseCode() != 200) return null;
            Map<String, Object> m = MiniJson.obj(MiniJson.parse(read(c.getInputStream())));
            Object v = m == null ? null : m.containsKey("available") ? m.get("available") : m.get("amount");
            return v == null ? null : SecUtil.fixed(Double.parseDouble(String.valueOf(v)), 2);
        } catch (Exception e) {
            Secretary.log("llm: Polza balance not learned: " + e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Rubles the call cost, as Polza puts it in usage (cost_rub); 0 when not said. */
    static double costRub(Map<String, Object> u) {
        Object c = u == null ? null : u.get("cost_rub");
        if (c instanceof Number) return ((Number) c).doubleValue();
        if (c instanceof String) try {
            return Double.parseDouble((String) c);
        } catch (NumberFormatException ignored) {
        }
        return 0;
    }

    static List<Object> textParts(String text) {
        List<Object> parts = new ArrayList<Object>();
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("text", text);
        parts.add(p);
        return parts;
    }

    private String gemini(Config cfg, String model, String system, List<Object> parts, Object schema) throws LlmError {
        if (cfg.geminiKey == null || cfg.geminiKey.trim().isEmpty())
            throw new LlmError("Не задан ключ Gemini: «Настройка» → «Ключ Gemini»");
        Map<String, Object> gen = new LinkedHashMap<String, Object>();
        if (schema != null) {
            gen.put("responseMimeType", "application/json");
            gen.put("responseSchema", toGeminiSchema(schema));
        }
        String t = cfg.thinking == null ? "" : cfg.thinking.trim();
        if (!t.isEmpty()) {
            Map<String, Object> th = new LinkedHashMap<String, Object>();
            if (t.matches("\\d+")) th.put("thinkingBudget", Long.parseLong(t));
            else th.put("thinkingLevel", t);
            gen.put("thinkingConfig", th);
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("systemInstruction", map("parts", textParts(system)));
        List<Object> contents = new ArrayList<Object>();
        contents.add(map("role", "user", "parts", parts));
        body.put("contents", contents);
        if (!gen.isEmpty()) body.put("generationConfig", gen);
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("x-goog-api-key", cfg.geminiKey.trim());
        Map<String, Object> data = post(String.format(Locale.ROOT, geminiUrl, model), body, headers);

        Map<String, Object> um = MiniJson.obj(data.get("usageMetadata"));
        if (usage != null) usage.add(model, MiniJson.num(um, "promptTokenCount", 0),
                MiniJson.num(um, "candidatesTokenCount", 0) + MiniJson.num(um, "thoughtsTokenCount", 0), 0);
        List<Object> candidates = MiniJson.arr(data.get("candidates"));
        if (candidates == null || candidates.isEmpty()) {
            Object fb = data.containsKey("promptFeedback") ? data.get("promptFeedback") : data;
            throw new LlmError("Gemini не дал ответа: " + SecUtil.head(MiniJson.write(fb), 300));
        }
        Map<String, Object> c0 = MiniJson.obj(candidates.get(0));
        List<Object> outParts = MiniJson.arr(MiniJson.obj(c0 == null ? null : c0.get("content")) == null ? null
                : MiniJson.obj(c0.get("content")).get("parts"));
        StringBuilder text = new StringBuilder();
        if (outParts != null) for (Object o : outParts) {
            Map<String, Object> p = MiniJson.obj(o);
            if (p != null && !Boolean.TRUE.equals(p.get("thought")) && p.get("text") instanceof String) text.append((String) p.get("text"));
        }
        if (text.length() == 0 && schema != null)
            throw new LlmError("Пустой ответ Gemini (finishReason=" + (c0 == null ? null : c0.get("finishReason")) + ")");
        return text.toString();
    }

    /** An OpenAI-compatible chat call: «openai:…» (its own key and address) or Polza AI («polza:…»). */
    private String openai(Config cfg, String provider, String model, String system, String user, boolean jsonMode) throws LlmError {
        boolean polza = "polza".equals(provider);
        List<Object> messages = new ArrayList<Object>();
        messages.add(map("role", "system", "content", system));
        messages.add(map("role", "user", "content", user));
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        String url;
        Map<String, String> headers;
        if (polza) {
            polzaModel(model, body);
            headers = polzaHeaders(cfg);
            url = polzaBase(cfg) + "/chat/completions";
            String t = cfg.thinking == null ? "" : cfg.thinking.trim();
            if (!t.isEmpty() && !t.matches("\\d+")) body.put("reasoning", map("effort", t));
        } else {
            if (cfg.openaiKey == null || cfg.openaiKey.trim().isEmpty())
                throw new LlmError("Не задан ключ для «openai:…» моделей: «Настройка» → «Модели»");
            body.put("model", model);
            headers = new HashMap<String, String>();
            headers.put("Authorization", "Bearer " + cfg.openaiKey.trim());
            String base = cfg.openaiBaseUrl == null || cfg.openaiBaseUrl.isEmpty() ? "https://openrouter.ai/api/v1" : cfg.openaiBaseUrl;
            url = base.replaceAll("/+$", "") + "/chat/completions";
        }
        body.put("messages", messages);
        // Polza: no JSON mode — Yandex's json_object garbles this thinking Qwen ("}json{", keys with dots), and its
        // strict json_schema fills every optional field with stand-ins; the schema in the prompt is answered cleanly
        if (jsonMode && !polza) body.put("response_format", map("type", "json_object"));
        Map<String, Object> data = post(url, body, headers);
        Map<String, Object> u = MiniJson.obj(data.get("usage"));
        if (usage != null) usage.add(model, MiniJson.num(u, "prompt_tokens", 0),
                MiniJson.num(u, "completion_tokens", 0), costRub(u));
        Map<String, Object> c0, msg;
        try {
            c0 = MiniJson.obj(MiniJson.arr(data.get("choices")).get(0));
            msg = MiniJson.obj(c0.get("message"));
        } catch (RuntimeException e) {
            throw new LlmError("Неожиданный ответ API: " + SecUtil.head(MiniJson.write(data), 300));
        }
        Object content = msg == null ? null : msg.get("content");
        String text = content == null ? "" : String.valueOf(content);
        if (text.trim().isEmpty() && "length".equals(c0.get("finish_reason")))
            throw new LlmError("Нейросеть не успела ответить: всё ушло на размышления (finish_reason=length)");
        return text;
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static boolean retryable(int code) {
        for (int s : RETRY_STATUS) if (s == code) return true;
        return false;
    }

    /** A POST with the server version's patience: busy answers tried again after 2, 5, 12.5 s. */
    private Map<String, Object> post(String url, Map<String, Object> body, Map<String, String> headers) throws LlmError {
        long delay = retryDelayMs;
        byte[] payload = MiniJson.write(body).getBytes(SecUtil.UTF8);
        for (int attempt = 0; ; attempt++) {
            boolean last = attempt >= 3;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(20_000);
                c.setReadTimeout(120_000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
                c.setFixedLengthStreamingMode(payload.length);
                OutputStream os = c.getOutputStream();
                try {
                    os.write(payload);
                } finally {
                    os.close();
                }
                int code = c.getResponseCode();
                String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
                if (code == 200) {
                    Map<String, Object> m = MiniJson.obj(MiniJson.parse(text));
                    if (m == null) throw new LlmError("Неожиданный ответ API: " + SecUtil.head(text, 300));
                    return m;
                }
                String msg = SecUtil.head(text, 500);
                String low = msg.toLowerCase(Locale.ROOT);
                if (code == 402 || low.contains("insufficient") || low.contains("недостаточно"))
                    throw new LlmError("На счёте нейросети кончились деньги (" + code + "): пополни баланс. " + msg);
                if (low.contains("location is not supported") || low.contains("unsupported_country"))
                    throw new LlmError("Нейросеть не обслуживает страну, из которой идёт запрос (российский IP): "
                            + "включи VPN на телефоне и повтори.");
                if (!retryable(code) || last) throw new LlmError("Ошибка нейросети " + code + ": " + msg);
                Secretary.log("llm: answer " + code + ", again in " + delay / 1000.0 + " s");
            } catch (IOException e) {
                if (last) throw new LlmError("Нет связи с нейросетью: " + e);
                Secretary.log("llm: network error " + e + ", again in " + delay / 1000.0 + " s");
            } finally {
                if (c != null) c.disconnect();
            }
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmError("прервано");
            }
            delay = (long) (delay * 2.5);
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try {
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        } finally {
            in.close();
        }
        return new String(out.toByteArray(), SecUtil.UTF8);
    }
}
