import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import io.github.teoplaydor.semsearch.core.MiniJson;
import io.github.teoplaydor.semsearch.secretary.GeminiBrain;
import io.github.teoplaydor.semsearch.secretary.LlmError;
import io.github.teoplaydor.semsearch.secretary.Prompts;

/**
 * The secretary on Gemini from the phone (the server version's llm.py) against a stand-in server: the request as Gemini
 * takes it (the key in a header, the system instruction, the schema with types in capitals, thinking), the answer read
 * without the model's thoughts, the tokens counted, a busy answer tried again, a refusal told in words, a country not
 * served told so, the OpenAI-compatible path, a voice message sent as audio.
 */
public class GeminiBrainTest {
    static int bad = 0;

    static void ok(boolean c, String what) {
        System.out.println((c ? "ok   " : "FAIL ") + what);
        if (!c) bad++;
    }

    /** Prepared answers: [status, body]. What came: [path, headers line, body]. */
    static final LinkedList<Object[]> answers = new LinkedList<Object[]>();
    static final List<String[]> got = new ArrayList<String[]>();

    public static void main(String[] a) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            public void handle(HttpExchange ex) {
                try {
                    InputStream in = ex.getRequestBody();
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
                    synchronized (got) {
                        got.add(new String[]{ex.getRequestURI().getPath(), "key=" + ex.getRequestHeaders().getFirst("x-goog-api-key")
                                + " auth=" + ex.getRequestHeaders().getFirst("Authorization"), b.toString("UTF-8")});
                    }
                    Object[] r;
                    synchronized (answers) {
                        r = answers.isEmpty() ? new Object[]{500, "{}"} : answers.removeFirst();
                    }
                    byte[] out = ((String) r[1]).getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders((Integer) r[0], out.length);
                    OutputStream os = ex.getResponseBody();
                    os.write(out);
                    os.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();

        final GeminiBrain.Config cfg = new GeminiBrain.Config();
        cfg.geminiKey = "test-key";
        final List<Object[]> used = new ArrayList<Object[]>();
        GeminiBrain g = new GeminiBrain(new GeminiBrain.Settings() {
            public GeminiBrain.Config config() {
                return cfg;
            }
        }, new GeminiBrain.Usage() {
            public void add(String model, long in, long out) {
                used.add(new Object[]{model, in, out});
            }
        });
        g.geminiUrl = base + "/v1beta/models/%s:generateContent";
        g.retryDelayMs = 10;

        // a JSON answer of the QUICK schema from the fast model
        answers.add(new Object[]{200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"дума...\",\"thought\":true},"
                + "{\"text\":\"{\\\"items\\\": [{\\\"i\\\": 0, \\\"level\\\": \\\"urgent\\\"}]}\"}]},\"finishReason\":\"STOP\"}],"
                + "\"usageMetadata\":{\"promptTokenCount\":812,\"candidatesTokenCount\":14,\"thoughtsTokenCount\":30}}"});
        Map<String, Object> r = g.json(false, true, "Ты — фильтр уведомлений.", "Уведомления:\n[0] WhatsApp · Анна", Prompts.QUICK_SCHEMA);
        ok("urgent".equals(MiniJson.obj(MiniJson.arr(r.get("items")).get(0)).get("level")), "the answer read, the model's thoughts left out");
        String[] req = got.get(0);
        ok("/v1beta/models/gemini-3.1-flash-lite:generateContent".equals(req[0]), "the fast model asked: " + req[0]);
        ok(req[1].contains("key=test-key"), "the key in the x-goog-api-key header, not in the address");
        Map<String, Object> body = MiniJson.obj(MiniJson.parse(req[2]));
        Map<String, Object> gen = MiniJson.obj(body.get("generationConfig"));
        Map<String, Object> rs = MiniJson.obj(gen.get("responseSchema"));
        ok("application/json".equals(gen.get("responseMimeType")) && "OBJECT".equals(rs.get("type"))
                        && "ARRAY".equals(MiniJson.obj(MiniJson.obj(rs.get("properties")).get("items")).get("type")),
                "the schema as Gemini's responseSchema: types in capitals");
        ok("Ты — фильтр уведомлений.".equals(MiniJson.obj(MiniJson.arr(MiniJson.obj(body.get("systemInstruction")).get("parts")).get(0)).get("text"))
                        && "user".equals(MiniJson.obj(MiniJson.arr(body.get("contents")).get(0)).get("role")),
                "the system instruction and the user's turn");
        ok(!gen.containsKey("thinkingConfig"), "no thinking setting — as the model decides");
        ok(used.size() == 1 && "gemini-3.1-flash-lite".equals(used.get(0)[0]) && (Long) used.get(0)[1] == 812 && (Long) used.get(0)[2] == 44,
                "tokens counted: in 812, out 14 + 30 thinking");

        // the smart model for the conversation; thinking «low»; a busy answer first
        cfg.thinking = "low";
        answers.add(new Object[]{503, "{\"error\":{\"message\":\"overloaded\"}}"});
        answers.add(new Object[]{200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"```json\\n{\\\"reply\\\": \\\"Хорошо.\\\", \\\"actions\\\": []}\\n```\"}]}}]}"});
        r = g.json(true, false, "Ты — личный секретарь.", "что у меня завтра?", Prompts.ASSISTANT_SCHEMA);
        ok("Хорошо.".equals(r.get("reply")), "busy (503) — tried again; a fenced JSON read");
        req = got.get(got.size() - 1);
        ok(req[0].contains("gemini-3.8-flash"), "the smart model for the conversation");
        ok("low".equals(MiniJson.obj(MiniJson.obj(MiniJson.obj(MiniJson.parse(req[2])).get("generationConfig")).get("thinkingConfig")).get("thinkingLevel")),
                "thinking «low» → thinkingLevel");
        cfg.thinking = "0";
        answers.add(new Object[]{200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Добрый день!\"}]}}]}"});
        ok("Добрый день!".equals(g.text(true, "Ты пишешь ответ.", "Переписка")), "free text (a draft)");
        req = got.get(got.size() - 1);
        Map<String, Object> gen2 = MiniJson.obj(MiniJson.obj(MiniJson.parse(req[2])).get("generationConfig"));
        ok(((Number) MiniJson.obj(gen2.get("thinkingConfig")).get("thinkingBudget")).longValue() == 0 && !gen2.containsKey("responseSchema"),
                "thinking «0» → thinkingBudget 0; no schema for text");
        cfg.thinking = "";

        // refusals
        answers.add(new Object[]{400, "{\"error\":{\"message\":\"API key not valid\"}}"});
        try {
            g.text(false, "s", "u");
            ok(false, "a bad key is an error");
        } catch (LlmError e) {
            ok(e.getMessage().contains("400") && e.getMessage().contains("API key not valid"), "a bad key: «" + e.getMessage() + "»");
        }
        answers.add(new Object[]{400, "{\"error\":{\"message\":\"User location is not supported for the API use.\"}}"});
        try {
            g.text(false, "s", "u");
            ok(false, "a country not served is an error");
        } catch (LlmError e) {
            ok(e.getMessage().contains("VPN"), "a country not served — told to use a VPN");
        }
        for (int i = 0; i < 4; i++) answers.add(new Object[]{503, "{}"});
        try {
            g.text(false, "s", "u");
            ok(false, "busy four times is an error");
        } catch (LlmError e) {
            ok(e.getMessage().contains("503"), "busy four times — given up: «" + e.getMessage() + "»");
        }
        answers.add(new Object[]{200, "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}"});
        try {
            g.json(false, false, "s", "u", Prompts.QUICK_SCHEMA);
            ok(false, "no candidates is an error");
        } catch (LlmError e) {
            ok(e.getMessage().contains("SAFETY"), "blocked: «" + e.getMessage() + "»");
        }
        cfg.geminiKey = "";
        try {
            g.text(false, "s", "u");
            ok(false, "no key is an error");
        } catch (LlmError e) {
            ok(e.getMessage().contains("ключ"), "no key: «" + e.getMessage() + "»");
        }
        cfg.geminiKey = "test-key";
        cfg.fastModel = "claude-whatever";
        try {
            g.text(false, "s", "u");
            ok(false, "a model without a provider is an error");
        } catch (LlmError e) {
            ok(e.getMessage().contains("gemini:имя-модели"), "a model without «gemini:» — said how to write it");
        }

        // an OpenAI-compatible API (OpenRouter, DeepSeek): the schema in the system prompt, json_object
        cfg.fastModel = "openai:deepseek/deepseek-v4-flash";
        cfg.openaiKey = "or-key";
        cfg.openaiBaseUrl = base + "/api/v1";
        answers.add(new Object[]{200, "{\"choices\":[{\"message\":{\"content\":\"{\\\"is_transaction\\\": true, \\\"direction\\\": \\\"expense\\\", "
                + "\\\"amount\\\": 350, \\\"currency\\\": \\\"RUB\\\", \\\"merchant\\\": \\\"X\\\", \\\"category\\\": \\\"Продукты\\\", \\\"card\\\": \\\"\\\"}\"}}],"
                + "\"usage\":{\"prompt_tokens\":300,\"completion_tokens\":40}}"});
        r = g.json(false, false, "Извлеки операцию.", "Покупка 350 ₽", Prompts.FIN_SCHEMA);
        req = got.get(got.size() - 1);
        Map<String, Object> ob = MiniJson.obj(MiniJson.parse(req[2]));
        ok(Boolean.TRUE.equals(r.get("is_transaction")) && "/api/v1/chat/completions".equals(req[0]) && req[1].contains("auth=Bearer or-key")
                        && "deepseek/deepseek-v4-flash".equals(ob.get("model"))
                        && "json_object".equals(MiniJson.obj(ob.get("response_format")).get("type"))
                        && ((String) MiniJson.obj(MiniJson.arr(ob.get("messages")).get(0)).get("content")).contains("по этой JSON-схеме"),
                "openai:…: chat/completions, Bearer key, json_object, the schema in the system prompt");
        ok("deepseek/deepseek-v4-flash".equals(used.get(used.size() - 1)[0]) && (Long) used.get(used.size() - 1)[1] == 300, "its tokens counted");

        // a voice message: audio inline, the server's prompt, the audio model
        answers.add(new Object[]{200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"что у меня завтра\\n\"}]}}]}"});
        String heard = g.transcribe(new byte[]{1, 2, 3, 4, 5}, "audio/aac");
        req = got.get(got.size() - 1);
        Map<String, Object> vb = MiniJson.obj(MiniJson.parse(req[2]));
        List<Object> parts = MiniJson.arr(MiniJson.obj(MiniJson.arr(vb.get("contents")).get(0)).get("parts"));
        Map<String, Object> inline = MiniJson.obj(MiniJson.obj(parts.get(0)).get("inlineData"));
        ok("что у меня завтра".equals(heard) && "audio/aac".equals(inline.get("mimeType")) && "AQIDBAU=".equals(inline.get("data"))
                        && GeminiBrain.TRANSCRIBE_PROMPT.equals(MiniJson.obj(parts.get(1)).get("text")) && req[0].contains("gemini-3.1-flash-lite"),
                "a voice message: the audio inline, the server's prompt, the audio model");
        ok(Math.abs(GeminiBrain.estimateCost("gemini-3.1-flash-lite", 1_000_000, 1_000_000) - 1.75) < 1e-9
                && GeminiBrain.estimateCost("local", 1, 1) == null, "the cost estimate (USD per million tokens)");

        server.stop(0);
        System.out.println(bad == 0 ? "GEMINI OK" : bad + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }
}
