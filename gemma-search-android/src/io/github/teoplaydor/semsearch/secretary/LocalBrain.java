package io.github.teoplaydor.semsearch.secretary;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.teoplaydor.semsearch.core.MiniJson;

/**
 * The secretary's Brain on the phone: the local model (LocalLlm) instead of the server's Gemini.
 * <ul>
 * <li>JSON answers — as the server asked OpenAI-style models: the schema appended to the system prompt; here also held
 * to it by the grammar, and greedy (the most likely answer — decisions must not wobble);</li>
 * <li>each system prompt is read once and kept (it carries the owner's «about me», which rarely changes);</li>
 * <li>the urgent goes first: a «Тишина» verdict (someone looks at the screen) interrupts a background reading, which
 * is then read again;</li>
 * <li>a prompt longer than the context loses the middle of the request's text, not its end (the newest messages).</li>
 * </ul>
 */
public final class LocalBrain implements Brain {
    static final String JSON_HINT = "\n\nОтвечай строго одним JSON-объектом по этой JSON-схеме:\n";

    /** Where the model comes from; null — none yet (not downloaded). */
    public interface Source {
        File model();
    }

    /** What each answer cost (for «Нейросеть»): tokens read and written. */
    public interface Usage {
        void add(String model, long tokensIn, long tokensOut);
    }

    private final Source source;
    private final Usage usage;
    private final LocalLlm.Options options;
    private LocalLlm llm;
    private File loadedFile;
    private final Object lock = new Object();
    private int urgentWaiting;
    private boolean runningBackground, preempted;
    public volatile String lastStats = "";

    public LocalBrain(Source source, Usage usage, LocalLlm.Options options) {
        this.source = source;
        this.usage = usage;
        this.options = options == null ? new LocalLlm.Options() : options;
    }

    /** The model (loaded on first need). */
    private LocalLlm model() throws LlmError {
        File f = source.model();
        if (f == null || !f.isFile()) throw new LlmError("модель не скачана");
        if (llm != null && f.equals(loadedFile)) return llm;
        if (llm != null) llm.close();
        llm = null;
        long t0 = System.currentTimeMillis();
        Secretary.log("model: loading " + f.getName() + " (" + f.length() / 1000000 + " MB), context " + options.context + ", slots "
                + options.slots + ", threads " + options.threads + ", KV " + (options.kvQ8 ? "q8_0" : "f16"));
        try {
            llm = LocalLlm.open(f, options);
            loadedFile = f;
            Secretary.log("model: loaded in " + (System.currentTimeMillis() - t0) + " ms, «" + llm.meta("general.name") + "», context "
                    + llm.context());
        } catch (IOException | UnsatisfiedLinkError e) {
            Secretary.log("model: did not load after " + (System.currentTimeMillis() - t0) + " ms", e);
            throw new LlmError("модель не загрузилась: " + e.getMessage());
        } finally {
            nativeLog();
        }
        return llm;
    }

    public boolean loaded() {
        synchronized (lock) {
            return llm != null;
        }
    }

    /** Frees the model's memory (it loads again when needed). */
    public void unload() {
        synchronized (lock) {
            if (llm != null) llm.close();
            llm = null;
            loadedFile = null;
        }
    }

    /** llama.cpp's own lines (what it found in the file, the memory it took, its errors) into the log. */
    static void nativeLog() {
        try {
            String lines = LocalLlm.takeNativeLog();
            if (lines != null && !lines.isEmpty()) Secretary.log("llama.cpp:\n" + lines.trim());
        } catch (Throwable ignored) {
            // the library not loaded
        }
    }

    /** The model's name for the usage table. */
    String modelName() {
        File f = loadedFile;
        return f == null ? "local" : f.getName().replaceAll("\\.gguf$", "");
    }

    static int maxTokens(Prompts.Schema s, String user) {
        if (s == Prompts.QUICK_SCHEMA) {
            int items = 1;
            for (int i = user.indexOf("\n["); i >= 0; i = user.indexOf("\n[", i + 1)) items++;
            return 24 + 16 * items;
        }
        if (s == Prompts.RESOLVE_SCHEMA) return 400;
        if (s == Prompts.FIN_SCHEMA) return 200;
        if (s == Prompts.ASSISTANT_SCHEMA) return 700;
        return 600; // TRIAGE: a summary, an action, events
    }

    static final Pattern NUMBERED = Pattern.compile("(?m)^\\[(\\d+)\\] ");

    /** The numbers of the request's «[n] …» lines (notifications for QUICK, tasks for RESOLVE), in order. */
    static List<Long> numbered(String user) {
        List<Long> out = new ArrayList<Long>();
        Matcher m = NUMBERED.matcher(user);
        while (m.find()) {
            long n = Long.parseLong(m.group(1));
            if (!out.contains(n)) out.add(n);
        }
        return out;
    }

    /**
     * The grammar for this very request. QUICK and RESOLVE answer about a numbered list: their grammar is narrowed to
     * exactly one answer per number, in order — the model only chooses (a level; done or not), it cannot skip, repeat
     * or invent a number. Still JSON of the same schema.
     */
    public static String grammarFor(Prompts.Schema s, String user) {
        List<Long> ids = numbered(user);
        if (ids.isEmpty() || ids.size() > 60) return s.grammar;
        String list, rest, key;
        if (s == Prompts.QUICK_SCHEMA) {
            list = "items";
            key = "i";
            rest = "items-item-level-kv";
        } else if (s == Prompts.RESOLVE_SCHEMA) {
            list = "tasks";
            key = "id";
            rest = "tasks-item-status-kv \",\" space tasks-item-confidence-kv \",\" space tasks-item-reason-kv";
        } else return s.grammar;
        StringBuilder rule = new StringBuilder(list + " ::= \"[\" space");
        StringBuilder items = new StringBuilder();
        for (int k = 0; k < ids.size(); k++) {
            rule.append(k == 0 ? " " : " \",\" space ").append("n").append(k);
            items.append("n").append(k).append(" ::= \"{\" space \"\\\"").append(key).append("\\\"\" space \":\" space \"")
                    .append(ids.get(k)).append("\" \",\" space ").append(rest).append(" space \"}\"\n");
        }
        rule.append(" space \"]\"");
        StringBuilder g = new StringBuilder();
        for (String line : s.grammar.split("\n")) {
            if (line.startsWith(list + " ::= ")) g.append(rule).append('\n');
            else if (!line.isEmpty()) g.append(line).append('\n');
        }
        return g.append(items).toString();
    }

    public Map<String, Object> json(boolean smart, boolean urgent, String system, String user, Prompts.Schema schema) throws LlmError {
        // the conversation with the owner (smart) is waited for too: before the background
        String out = run(schema.name, urgent || smart, system + JSON_HINT + schema.json, user, grammarFor(schema, user),
                maxTokens(schema, user), 0f);
        try {
            Map<String, Object> m = MiniJson.obj(MiniJson.parse(out.trim()));
            if (m == null) throw new LlmError("ответ не JSON: " + SecUtil.head(out, 200));
            return m;
        } catch (IOException e) {
            Secretary.log("model: " + schema.name + " answer is not JSON: " + SecUtil.head(out, 600));
            throw new LlmError("ответ не JSON (обрезан?): " + SecUtil.head(out, 200));
        }
    }

    public String text(boolean smart, String system, String user) throws LlmError {
        return run("text", smart, system, user, null, 600, 0.7f).trim();
    }

    private String run(String kind, boolean urgent, String system, String user, String grammar, int maxTokens, float temperature)
            throws LlmError {
        long t0 = System.currentTimeMillis();
        Secretary.log("model: " + kind + (urgent ? " (urgent)" : "") + " asked: system " + system.length() + " chars, request "
                + user.length() + " chars, answer up to " + maxTokens + " tokens");
        try {
            String out = run(urgent, system, user, grammar, maxTokens, temperature);
            Secretary.log("model: " + kind + " answered in " + (System.currentTimeMillis() - t0) + " ms — " + lastStats + "\n  → "
                    + SecUtil.head(out, 400).replace("\n", "⏎"));
            return out;
        } catch (LlmError e) {
            Secretary.log("model: " + kind + " failed after " + (System.currentTimeMillis() - t0) + " ms: " + e.getMessage());
            nativeLog();
            throw e;
        }
    }

    /** One request; the urgent ones go before and interrupt the rest. */
    private String run(boolean urgent, String system, String user, String grammar, int maxTokens, float temperature) throws LlmError {
        for (int attempt = 0; ; attempt++) {
            LocalLlm m;
            synchronized (lock) {
                if (urgent) {
                    urgentWaiting++;
                    if (runningBackground && llm != null) {
                        llm.cancel(); // the background one is read again later
                        preempted = true;
                        Secretary.log("model: an urgent request interrupts the background one");
                    }
                }
                try {
                    // the background waits while an urgent one waits or runs
                    while (!urgent && urgentWaiting > 0) lock.wait();
                } catch (InterruptedException e) {
                    if (urgent) urgentWaiting--;
                    Thread.currentThread().interrupt();
                    throw new LlmError("прервано");
                }
                if (!urgent) runningBackground = true;
            }
            String out;
            try {
                synchronized (this) { // one request at a time
                    synchronized (lock) {
                        m = model();
                    }
                    if (urgent) m.resetCancel(); // a cancel left for a background request that already ended
                    try {
                        out = m.chat(system, fit(m, system, user, maxTokens), grammar, maxTokens, temperature);
                    } catch (RuntimeException e) {
                        throw new LlmError("модель: " + e.getMessage());
                    }
                    LocalLlm.Stats st = m.lastStats();
                    if (st != null) {
                        lastStats = st.toString();
                        if (out != null && usage != null) usage.add(modelName(), st.promptTokens, st.outTokens);
                    }
                }
            } finally {
                synchronized (lock) {
                    if (urgent) urgentWaiting--;
                    else runningBackground = false;
                    lock.notifyAll();
                }
            }
            if (out != null) return out;
            boolean wasPreempted;
            synchronized (lock) {
                wasPreempted = !urgent && preempted;
                if (!urgent) preempted = false;
            }
            // interrupted by an urgent one — read it again when that one is done; otherwise it does not fit
            if (!wasPreempted || attempt >= 5) throw new LlmError("модель не ответила (запрос не поместился)");
        }
    }

    /** The request's text cut in the middle so that the prompt and the answer fit the context. */
    static String fit(LocalLlm m, String system, String user, int maxTokens) {
        int room = m.context() - m.count(LocalLlm.prefixOf(system)) - maxTokens - 16;
        int total = m.count(LocalLlm.suffixOf(user));
        if (total <= room) return user;
        double keep = Math.max(0, (double) room / total * 0.95);
        for (int i = 0; i < 24; i++) {
            int n = (int) (user.length() * keep);
            int head = safe(user, n / 4), tail = safe(user, user.length() - (n - n / 4));
            String cut = user.substring(0, head) + "\n…\n" + user.substring(tail);
            if (m.count(LocalLlm.suffixOf(cut)) <= room) return cut;
            keep *= 0.85;
        }
        return "…";
    }

    /** An index not inside a surrogate pair. */
    private static int safe(String s, int i) {
        i = Math.max(0, Math.min(s.length(), i));
        if (i > 0 && i < s.length() && Character.isLowSurrogate(s.charAt(i)) && Character.isHighSurrogate(s.charAt(i - 1))) i--;
        return i;
    }
}
