import java.io.File;
import java.util.List;
import java.util.Map;

import io.github.teoplaydor.semsearch.core.MiniJson;
import io.github.teoplaydor.semsearch.secretary.LocalBrain;
import io.github.teoplaydor.semsearch.secretary.LocalLlm;
import io.github.teoplaydor.semsearch.secretary.Prompts;

/**
 * The secretary's prompts and grammars on the real engine (a tiny random Qwen3 with Qwen3's vocabulary): every
 * schema's grammar gives JSON of that schema, sampled many ways; how long each system prompt is in tokens; an urgent
 * request («Тишина») goes before a background one, which is read again after; a request too long for the context is
 * cut in the middle; more kinds of request than fit the context push out the oldest kept prompt.
 * usage: java -Dsecllm.lib=libsecllm.so BrainTest tiny-Q2_0.gguf
 */
public class BrainTest {
    static int bad = 0;

    static void ok(boolean c, String what) {
        System.out.println((c ? "ok   " : "FAIL ") + what);
        if (!c) bad++;
    }

    /** Does the value fit the JSON schema (the subset the secretary uses)? Returns the reason it does not, or null. */
    @SuppressWarnings("unchecked")
    static String check(Object v, Map<String, Object> schema, String at) {
        String type = (String) schema.get("type");
        if ("object".equals(type)) {
            Map<String, Object> m = MiniJson.obj(v);
            if (m == null) return at + ": not an object";
            Map<String, Object> props = MiniJson.obj(schema.get("properties"));
            List<Object> req = MiniJson.arr(schema.get("required"));
            if (req != null) for (Object r : req) if (!m.containsKey(r)) return at + ": no " + r;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                Map<String, Object> ps = props == null ? null : MiniJson.obj(props.get(e.getKey()));
                if (ps == null) return at + ": extra " + e.getKey();
                String why = check(e.getValue(), ps, at + "." + e.getKey());
                if (why != null) return why;
            }
            return null;
        }
        if ("array".equals(type)) {
            List<Object> l = MiniJson.arr(v);
            if (l == null) return at + ": not an array";
            for (int i = 0; i < l.size(); i++) {
                String why = check(l.get(i), MiniJson.obj(schema.get("items")), at + "[" + i + "]");
                if (why != null) return why;
            }
            return null;
        }
        if ("string".equals(type)) {
            if (!(v instanceof String)) return at + ": not a string";
            List<Object> en = MiniJson.arr(schema.get("enum"));
            return en != null && !en.contains(v) ? at + ": not one of the enum: " + v : null;
        }
        if ("integer".equals(type)) return v instanceof Long ? null : at + ": not an integer";
        if ("number".equals(type)) return v instanceof Number ? null : at + ": not a number";
        if ("boolean".equals(type)) return v instanceof Boolean ? null : at + ": not a boolean";
        return at + ": unknown type " + type;
    }

    public static void main(String[] a) throws Exception {
        final File model = new File(a[0]);
        LocalLlm.Options o = new LocalLlm.Options();
        o.context = 4096;
        o.slots = 5;
        o.threads = 4;
        LocalLlm m = LocalLlm.open(model, o);
        Prompts.Schema[] schemas = {Prompts.TRIAGE_SCHEMA, Prompts.QUICK_SCHEMA, Prompts.RESOLVE_SCHEMA, Prompts.FIN_SCHEMA,
                Prompts.ASSISTANT_SCHEMA};
        String[] systems = {Prompts.fill(Prompts.CHAT_TRIAGE_SYSTEM, "about_me", "Работаю менеджером.", "tz", "Europe/Moscow"),
                Prompts.fill(Prompts.QUICK_SYSTEM, "about_me", "Работаю менеджером."), Prompts.RESOLVE_SYSTEM,
                Prompts.fill(Prompts.FIN_SYSTEM, "categories", "Продукты, Такси"),
                Prompts.fill(Prompts.ASSISTANT_SYSTEM, "about_me", "Работаю менеджером.", "tz", "Europe/Moscow", "categories", "Продукты")};
        // each schema's grammar takes good answers (in the order the grammar writes the fields) and refuses bad ones
        String ev = "{\"title\": \"Вебинар по SQL\", \"start\": \"2026-11-14T19:00:00+03:00\", \"kind\": \"webinar\", \"registered\": true, \"url\": \"https://zoom.us/j/1\"}";
        Object[][] cases = {
                {Prompts.TRIAGE_SCHEMA, true, "{\"importance\": 3, \"category\": \"personal\", \"summary\": \"Анна зовёт на ужин 🙂\", \"action\": \"Ответить Анне\", \"needs_reply\": true, \"deadline\": \"\", \"events\": []}"},
                {Prompts.TRIAGE_SCHEMA, true, "{\"importance\":1,\"category\":\"study\",\"summary\":\"Занятие \\\"Python\\\" завтра\",\"action\":\"\",\"needs_reply\":false,\"deadline\":\"2026-11-14T23:59:00+03:00\",\"events\":[" + ev + "]}"},
                {Prompts.TRIAGE_SCHEMA, false, "{\"importance\": \"3\", \"category\": \"personal\", \"summary\": \"\", \"action\": \"\", \"needs_reply\": true, \"deadline\": \"\", \"events\": []}"},
                {Prompts.TRIAGE_SCHEMA, false, "{\"importance\": 3, \"category\": \"news\", \"summary\": \"\", \"action\": \"\", \"needs_reply\": true, \"deadline\": \"\", \"events\": []}"},
                {Prompts.TRIAGE_SCHEMA, false, "{\"importance\": 3, \"category\": \"personal\", \"summary\": \"\", \"action\": \"\", \"needs_reply\": true, \"deadline\": \"\"}"},
                {Prompts.TRIAGE_SCHEMA, false, "{\"importance\": 3, \"category\": \"personal\", \"summary\": \"\", \"action\": \"\", \"needs_reply\": true, \"deadline\": \"\", \"events\": []} и ещё текст"},
                {Prompts.QUICK_SCHEMA, true, "{\"items\": [{\"i\": 0, \"level\": \"urgent\"}, {\"i\": 1, \"level\": \"junk\"}]}"},
                {Prompts.QUICK_SCHEMA, false, "{\"items\": [{\"i\": 0, \"level\": \"important\"}]}"},
                {Prompts.RESOLVE_SCHEMA, true, "{\"tasks\": [{\"id\": 12, \"status\": \"done\", \"confidence\": 0.95, \"reason\": \"ты ответил: «Да, буду в 7»\"}]}"},
                {Prompts.RESOLVE_SCHEMA, false, "{\"tasks\": [{\"id\": 12, \"status\": \"maybe\", \"confidence\": 0.5, \"reason\": \"\"}]}"},
                {Prompts.FIN_SCHEMA, true, "{\"is_transaction\": true, \"direction\": \"expense\", \"amount\": 350.5, \"currency\": \"RUB\", \"merchant\": \"PYATEROCHKA\", \"category\": \"Кафе и рестораны\", \"card\": \"1234\"}"},
                {Prompts.FIN_SCHEMA, false, "{\"is_transaction\": true, \"direction\": \"expense\", \"amount\": 350, \"currency\": \"RUB\", \"merchant\": \"\", \"category\": \"Еда\", \"card\": \"\"}"},
                {Prompts.ASSISTANT_SCHEMA, true, "{\"reply\": \"Хорошо.\", \"actions\": [{\"type\": \"add_reminder\", \"text\": \"Позвонить в банк\", \"at\": \"2026-11-14T10:00:00+03:00\"}, {\"type\": \"done\", \"id\": 7}]}"},
                {Prompts.ASSISTANT_SCHEMA, true, "{\"reply\": \"Не умею.\", \"actions\": []}"},
                {Prompts.ASSISTANT_SCHEMA, false, "{\"reply\": \"Ок\", \"actions\": [{\"type\": \"send_money\"}]}"},
        };
        int right = 0;
        for (Object[] c : cases) {
            Prompts.Schema sc = (Prompts.Schema) c[0];
            boolean want = (Boolean) c[1], got = m.grammarAccepts(sc.grammar, (String) c[2]);
            if (want == got) right++;
            else System.out.println("     " + sc.name + (want ? " refused: " : " took: ") + c[2]);
            if (want) {
                String why = check(MiniJson.parse((String) c[2]), MiniJson.obj(MiniJson.parse(sc.json)), "$");
                if (why != null) System.out.println("     not of the schema itself: " + why);
            }
        }
        ok(right == cases.length, "the grammars take good answers and refuse bad ones (" + right + " of " + cases.length + ")");

        // QUICK and RESOLVE narrowed to the request's own numbers: one answer each, in order
        String quickUser = "Уведомления:\n[0] WhatsApp · Анна\n   Анна: привет\n\n[1] Ozon · Скидки\n   Ozon: -70%\n\n[2] Telegram · Мама\n   Мама: ты где?";
        String qg = LocalBrain.grammarFor(Prompts.QUICK_SCHEMA, quickUser);
        ok(m.grammarAccepts(qg, "{\"items\": [{\"i\": 0, \"level\": \"urgent\"}, {\"i\": 1, \"level\": \"junk\"}, {\"i\": 2, \"level\": \"urgent\"}]}")
                        && !m.grammarAccepts(qg, "{\"items\": [{\"i\": 0, \"level\": \"urgent\"}, {\"i\": 2, \"level\": \"junk\"}]}")
                        && !m.grammarAccepts(qg, "{\"items\": [{\"i\": 1, \"level\": \"junk\"}, {\"i\": 0, \"level\": \"urgent\"}, {\"i\": 2, \"level\": \"urgent\"}]}")
                        && !m.grammarAccepts(qg, "{\"items\": []}"),
                "QUICK for 3 notifications: exactly items 0, 1, 2 in order — none skipped, none repeated");
        String resolveUser = "Сейчас: 2026-11-13T12:00+03:00\n\nДела:\n[12] Ответить Анне (WhatsApp · Анна)\n[15] Купить торт\n\n"
                + "Улики (после того, как появились дела):\n[13.11 12:01] Я: Да, буду в 7";
        String rg = LocalBrain.grammarFor(Prompts.RESOLVE_SCHEMA, resolveUser);
        ok(m.grammarAccepts(rg, "{\"tasks\": [{\"id\": 12, \"status\": \"done\", \"confidence\": 0.9, \"reason\": \"ответил\"}, {\"id\": 15, \"status\": \"unclear\", \"confidence\": 0.2, \"reason\": \"\"}]}")
                        && !m.grammarAccepts(rg, "{\"tasks\": [{\"id\": 13, \"status\": \"done\", \"confidence\": 0.9, \"reason\": \"\"}]}"),
                "RESOLVE: exactly tasks 12 and 15 (not the evidence's «[13.11 …]»)");
        int good = 0;
        long t0 = System.nanoTime();
        String quickSys = systems[1] + "\n\nОтвечай строго одним JSON-объектом по этой JSON-схеме:\n" + Prompts.QUICK_SCHEMA.json;
        for (int seed = 1; seed <= 12; seed++) {
            String out = m.raw(LocalLlm.prefixOf(quickSys), LocalLlm.suffixOf(quickUser), qg, 80, 1.0f, seed);
            Map<String, Object> v = MiniJson.obj(MiniJson.parse(out));
            List<Object> items = MiniJson.arr(v.get("items"));
            boolean exact = items.size() == 3 && check(v, MiniJson.obj(MiniJson.parse(Prompts.QUICK_SCHEMA.json)), "$") == null;
            for (int k = 0; exact && k < 3; k++) exact = ((Number) MiniJson.obj(items.get(k)).get("i")).intValue() == k;
            if (exact) good++;
        }
        ok(good == 12, "even a random model, sampled 12 ways, answers QUICK exactly (" + good + " of 12, "
                + (System.nanoTime() - t0) / 12000000 + " ms each)");
        for (int s = 0; s < schemas.length; s++)
            System.out.println("     " + schemas[s].name + ": system prompt with the schema "
                    + m.count(LocalLlm.prefixOf(systems[s] + "\n\nОтвечай строго одним JSON-объектом по этой JSON-схеме:\n" + schemas[s].json)) + " tokens");
        m.close();

        // LocalBrain: JSON answers, the urgent first, long requests cut, prompts pushed out when the context is full
        LocalLlm.Options ob = new LocalLlm.Options();
        ob.context = 2048;
        ob.slots = 4;
        ob.threads = 4;
        final long[] used = new long[2];
        final LocalBrain brain = new LocalBrain(new LocalBrain.Source() {
            public File model() {
                return model;
            }
        }, new LocalBrain.Usage() {
            public void add(String name, long in, long out) {
                used[0] += in;
                used[1] += out;
            }
        }, ob);
        Map<String, Object> q = brain.json(false, true, systems[1], "Уведомления:\n[0] WhatsApp · Анна\n   Анна: привет", Prompts.QUICK_SCHEMA);
        ok(MiniJson.arr(q.get("items")).size() == 1 && used[0] > 0, "LocalBrain: a QUICK answer as a map; tokens counted (" + used[0] + " in, " + used[1] + " out)");

        final String[] background = new String[1];
        final long[] bgDone = new long[1];
        Thread bg = new Thread(new Runnable() {
            public void run() {
                try {
                    background[0] = brain.text(true, "Ты пишешь ответ в мессенджере.", "Напиши длинный ответ про погоду.");
                } catch (Exception e) {
                    background[0] = "ERR " + e;
                }
                bgDone[0] = System.nanoTime();
            }
        });
        bg.start();
        Thread.sleep(150);
        long u0 = System.nanoTime();
        Map<String, Object> urgent = brain.json(false, true, systems[1], "Уведомления:\n[0] Telegram · Мама\n   Мама: ты где?", Prompts.QUICK_SCHEMA);
        long urgentMs = (System.nanoTime() - u0) / 1000000;
        bg.join(30000);
        ok(urgent.containsKey("items") && urgentMs < 1500, "an urgent request went before the background one: " + urgentMs + " ms");
        ok(background[0] != null && !background[0].startsWith("ERR") && bgDone[0] > u0, "…the background one was read again and answered after");

        StringBuilder huge = new StringBuilder("Новое:\n<<<\n");
        for (int i = 0; i < 400; i++) huge.append("[13.11 12:").append(i % 60).append("] Анна: сообщение номер ").append(i).append("\n");
        huge.append("[13.11 13:00] Анна: САМОЕ НОВОЕ\n>>>");
        String t = brain.text(false, systems[0], huge.toString());
        ok(t != null, "a chat far longer than the context: read with its middle cut, answered");

        int okKinds = 0;
        for (int k = 0; k < 6; k++) {
            String sys = "Вид запросов номер " + k + ". " + systems[k % systems.length];
            try {
                brain.json(false, false, sys, "Уведомления:\n[0] SMS · 900\n   900: Покупка 350 ₽", Prompts.QUICK_SCHEMA);
                okKinds++;
            } catch (Exception e) {
                System.out.println("     kind " + k + ": " + e);
            }
        }
        ok(okKinds == 6, "six kinds of request in a 2048-token context: the oldest kept prompts give way, all answered");
        brain.unload();
        ok(!brain.loaded(), "unloaded");

        System.out.println(bad == 0 ? "BRAIN OK" : bad + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }
}
