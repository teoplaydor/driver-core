import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import io.github.teoplaydor.semsearch.core.MiniJson;
import io.github.teoplaydor.semsearch.secretary.LocalLlm;

/**
 * The secretary's engine (libsecllm.so — llama.cpp behind our JNI) on a tiny random Qwen3 with the real Qwen3
 * vocabulary, in the ternary format of the real model (Q2_0) and in Q4_0: it loads, the system prompt is read once and
 * then taken from the cache (the same answer as read anew), the JSON answer keeps to the grammar, cancel stops it at
 * once, emoji and Cyrillic pass both ways, a prompt too long for the context is refused.
 * usage: java -Dsecllm.lib=libsecllm.so LocalLlmTest tiny-Q2_0.gguf tiny-Q4_0.gguf
 */
public class LocalLlmTest {
    static int bad = 0;

    static void ok(boolean c, String what) {
        System.out.println((c ? "ok   " : "FAIL ") + what);
        if (!c) bad++;
    }

    static final String QUICK_GRAMMAR = "integer ::= (\"-\"? integral-part)\n"
            + "integral-part ::= [0] | [1-9] [0-9]{0,15}\n"
            + "items ::= \"[\" space (items-item (\",\" space items-item)*)? space \"]\"\n"
            + "items-item ::= \"{\" space items-item-i-kv \",\" space items-item-level-kv space \"}\"\n"
            + "items-item-i-kv ::= \"\\\"i\\\"\" space \":\" space integer\n"
            + "items-item-level ::= (\"\\\"urgent\\\"\" | \"\\\"normal\\\"\" | \"\\\"junk\\\"\")\n"
            + "items-item-level-kv ::= \"\\\"level\\\"\" space \":\" space items-item-level\n"
            + "items-kv ::= \"\\\"items\\\"\" space \":\" space items\n"
            + "root ::= \"{\" space items-kv space \"}\"\n"
            + "space ::= | \" \"\n";

    public static void main(String[] a) throws Exception {
        String system = "Ты — фильтр уведомлений на телефоне владельца. Владелец хочет видеть на экране только важное. "
                + "Для каждого уведомления реши, что с ним сделать прямо сейчас: urgent, normal или junk.";
        String user = "Уведомления:\n0. Telegram — Мама: Ты где? 🙂\n1. Ozon: Скидки до 90% только сегодня!";
        String user2 = "Уведомления:\n0. Сбербанк: Покупка 450 р. Пятёрочка. Баланс 12 300 р.";

        LocalLlm.Options o = new LocalLlm.Options();
        o.context = 2048;
        o.slots = 2;
        o.threads = 4;
        LocalLlm m = LocalLlm.open(new File(a[0]), o);
        ok(m.context() == 2048, "loaded " + m.file.getName() + ": «" + m.meta("general.name") + "», context " + m.context());
        ok(m.meta("no.such.key") == null, "a missing metadata key: null");
        String native1 = LocalLlm.takeNativeLog();
        ok(native1 != null && native1.contains("qwen3") && native1.split("\n").length > 20,
                "llama.cpp's own lines kept for the app's log (" + (native1 == null ? 0 : native1.split("\n").length) + " lines)");
        ok("".equals(LocalLlm.takeNativeLog()), "…and taken once");

        // the same request twice: the second takes the system prompt from the cache, the answer is the same
        String first = m.chat(system, user, null, 24, 0);
        LocalLlm.Stats s1 = m.lastStats();
        String second = m.chat(system, user, null, 24, 0);
        LocalLlm.Stats s2 = m.lastStats();
        ok(first != null && first.equals(second), "greedy, cached system prompt: the same answer (" + first.length() + " chars)");
        ok(!s1.prefixHit && s1.readTokens == s1.promptTokens, "the first time the whole prompt is read: " + s1);
        int userTokens = m.count(user + "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n");
        ok(s2.prefixHit && s2.readTokens == userTokens && s2.promptTokens == s1.promptTokens,
                "the second time only the request's own " + userTokens + " tokens: " + s2);

        // another kind of request takes the second place; the first stays cached
        m.chat("Ты проверяешь, выполнил ли владелец свои дела.", user2, null, 8, 0);
        ok(!m.lastStats().prefixHit, "another system prompt: read and kept in the second place");
        String third = m.chat(system, user, null, 24, 0);
        ok(m.lastStats().prefixHit && first.equals(third), "the first still cached (2 places), the answer the same");
        m.chat("Третий вид запросов.", user2, null, 8, 0);
        m.chat(system, user, null, 24, 0);
        ok(m.lastStats().prefixHit, "a third kind pushes out the least recently used one (the second), not the first");
        m.chat("Ты проверяешь, выполнил ли владелец свои дела.", user2, null, 8, 0);
        ok(!m.lastStats().prefixHit, "…which is read anew when it comes back");

        // the whole prompt read at once, nothing cached: the same first word (the split changes only rounding)
        String joint = m.raw("", "<|im_start|>system\n" + system + "<|im_end|>\n<|im_start|>user\n" + user
                + "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n", null, 24, 0, 1);
        ok(joint != null && m.lastStats().promptTokens == s1.promptTokens, "read whole: the same " + s1.promptTokens + " tokens");
        System.out.println("     whole: «" + preview(joint) + "»\n     split: «" + preview(first) + "»");

        // JSON held to the grammar: parses, has the asked shape
        long t0 = System.nanoTime();
        String json = m.chat(system, user, QUICK_GRAMMAR, 120, 0);
        LocalLlm.Stats sj = m.lastStats();
        boolean shape = false;
        try {
            Map<String, Object> root = MiniJson.obj(MiniJson.parse(json));
            List<Object> items = MiniJson.arr(root.get("items"));
            shape = items != null;
            for (Object it : items) {
                Map<String, Object> e = MiniJson.obj(it);
                shape &= e.get("i") instanceof Number && Pattern.matches("urgent|normal|junk", String.valueOf(e.get("level")));
            }
        } catch (Exception e) {
            shape = false;
        }
        ok(shape || sj.outTokens == 120, "the answer kept to the grammar: " + preview(json) + " (" + sj + ", "
                + (System.nanoTime() - t0) / 1000000 + " ms)");
        // a grammar with one answer: exactly it, emoji and Cyrillic both ways
        String exact = m.chat(system, user, "root ::= \"👍 Привет, мама! ёЁ\"", 40, 0);
        ok("👍 Привет, мама! ёЁ".equals(exact), "emoji and Cyrillic through the grammar and back: «" + exact + "»");
        ok(m.count("👍") >= 1 && m.count("👍") <= 3, "an emoji is " + m.count("👍") + " token(s), not broken halves");
        // sampled text: answers, and differs by seed
        String sa = m.raw(LocalLlm.prefixOf(system), LocalLlm.suffixOf(user), null, 16, 0.9f, 1);
        String sb = m.raw(LocalLlm.prefixOf(system), LocalLlm.suffixOf(user), null, 16, 0.9f, 2);
        ok(sa != null && sb != null && !sa.equals(sb), "sampled answers differ by seed");
        try {
            m.chat(system, user, "root ::= ( unclosed", 10, 0);
            ok(false, "a broken grammar is refused");
        } catch (IllegalArgumentException e) {
            ok(true, "a broken grammar is refused");
        }

        // cancel: a forced 1500-token answer stopped after 50 ms
        final LocalLlm mm = m;
        final String[] res = {"-"};
        Thread t = new Thread(new Runnable() {
            public void run() {
                res[0] = mm.chat(system, user, "root ::= \"x \"{1500}", 1600, 0);
            }
        });
        long c0 = System.nanoTime();
        t.start();
        Thread.sleep(50);
        m.cancel();
        t.join(5000);
        long took = (System.nanoTime() - c0) / 1000000;
        ok(!t.isAlive() && res[0] == null && took < 1000, "cancelled: null after " + took + " ms (" + preview(res[0]) + ")");
        ok(first.equals(m.chat(system, user, null, 24, 0)), "after a cancel the next request is as before");

        // too long for the context: refused, not cut
        StringBuilder huge = new StringBuilder();
        while (huge.length() < 20000) huge.append("очень длинное уведомление ");
        ok(m.chat(system, huge.toString(), null, 10, 0) == null, "a prompt longer than the context: null");
        ok(first.equals(m.chat(system, user, null, 24, 0)), "…and the cache is still right after it");
        m.close();
        try {
            m.count("x");
            ok(false, "closed: refuses");
        } catch (IllegalStateException e) {
            ok(true, "closed: refuses");
        }

        // the same in Q4_0 (the faster-to-read form of the same weights) and with a 16-bit cache
        if (a.length > 1) {
            LocalLlm.Options o4 = new LocalLlm.Options();
            o4.context = 1024;
            o4.slots = 1;
            o4.threads = 4;
            o4.kvQ8 = false;
            LocalLlm m4 = LocalLlm.open(new File(a[1]), o4);
            String q1 = m4.chat(system, user, null, 16, 0), q2 = m4.chat(system, user, null, 16, 0);
            ok(q1 != null && q1.equals(q2) && m4.lastStats().prefixHit, "Q4_0, 16-bit cache: loads, cached, the same — " + m4.lastStats());
            m4.close();
        }
        try {
            LocalLlm.open(new File("/nonexistent.gguf"), o);
            ok(false, "no file: an error");
        } catch (java.io.IOException e) {
            ok(true, "no file: an error (" + e.getMessage() + ")");
        }

        System.out.println(bad == 0 ? "LOCAL LLM OK" : bad + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }

    static String preview(String s) {
        if (s == null) return "null";
        s = s.replace("\n", "⏎");
        return s.length() > 90 ? s.substring(0, 90) + "…" : s;
    }
}
