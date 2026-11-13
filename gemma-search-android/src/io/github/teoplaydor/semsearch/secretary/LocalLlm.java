package io.github.teoplaydor.semsearch.secretary;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * The secretary's own language model on the phone: a GGUF file (Ternary-Bonsai-4B by default — a Qwen3 in 1.58 bits)
 * run by llama.cpp in libsecllm.so. One model, kept loaded; each kind of request (sorting notifications, reading a
 * chat, judging a task…) has a long fixed system prompt, read once and kept in the model's cache, so a new request
 * pays only for its own text. JSON answers are held to a grammar (GBNF) — always valid, always the asked shape.
 *
 * Not thread-safe by design of the engine: requests run one at a time (the native side holds a lock); cancel() may be
 * called from any thread.
 */
public final class LocalLlm implements Closeable {
    /** Qwen3's chat format, thinking off (the empty think block it was trained to see when not thinking). */
    static final String SYS_OPEN = "<|im_start|>system\n", TURN_END = "<|im_end|>\n", USER_OPEN = "<|im_start|>user\n",
            ASSISTANT_OPEN = "<|im_start|>assistant\n<think>\n\n</think>\n\n";

    private static boolean loaded;

    /** libsecllm.so: from the APK, or — off the phone, in tests — the file named by -Dsecllm.lib. */
    public static synchronized void loadLibrary() {
        if (loaded) return;
        String p = System.getProperty("secllm.lib");
        if (p != null && !p.isEmpty()) System.load(new File(p).getAbsolutePath());
        else System.loadLibrary("secllm");
        loaded = true;
    }

    /** How the model is set up in memory. */
    public static final class Options {
        /** Context in tokens, shared by the kept prompts and the request in hand. */
        public int context = 8192;
        /** Kept system prompts (each kind of request has one). */
        public int slots = 5;
        public int threads = defaultThreads();
        /** The model's memory of the text (K/V) in 8 bits instead of 16: half the memory, practically the same answers. */
        public boolean kvQ8 = true;
    }

    /** The last request: its timing (what the benchmark and the log show). */
    public static final class Stats {
        public final int promptTokens, readTokens, outTokens;
        public final double prefillMs, decodeMs;
        public final boolean prefixHit;

        Stats(String s) {
            String[] f = s.trim().split(" ");
            promptTokens = Integer.parseInt(f[0]);
            readTokens = Integer.parseInt(f[1]);
            prefillMs = Double.parseDouble(f[2]);
            outTokens = Integer.parseInt(f[3]);
            decodeMs = Double.parseDouble(f[4]);
            prefixHit = "1".equals(f[5]);
        }

        public double readPerSecond() {
            return prefillMs > 0 ? readTokens * 1000.0 / prefillMs : 0;
        }

        public double writePerSecond() {
            return decodeMs > 0 ? outTokens * 1000.0 / decodeMs : 0;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "prompt %d (read %d%s) %.0f ms = %.1f tok/s; answer %d tok %.0f ms = %.1f tok/s",
                    promptTokens, readTokens, prefixHit ? ", system prompt from cache" : "", prefillMs, readPerSecond(),
                    outTokens, decodeMs, writePerSecond());
        }
    }

    private long handle;
    public final File file;
    public final Options options;

    private LocalLlm(long handle, File file, Options options) {
        this.handle = handle;
        this.file = file;
        this.options = options;
    }

    /** Loads the model (seconds: the file is mapped, its pages read as they are needed). */
    public static LocalLlm open(File gguf, Options o) throws IOException {
        loadLibrary();
        if (o == null) o = new Options();
        if (!gguf.isFile()) throw new IOException("no model file: " + gguf);
        long h = nativeLoad(gguf.getAbsolutePath(), o.context, o.threads, o.slots, o.kvQ8);
        if (h == 0) throw new IOException("the model did not load: " + gguf.getName());
        LocalLlm m = new LocalLlm(h, gguf, o);
        if (nativeSpecial(h, "<|im_start|>") < 0 || nativeSpecial(h, "<|im_end|>") < 0) {
            m.close();
            throw new IOException("not a Qwen-format chat model: " + gguf.getName());
        }
        return m;
    }

    private long h() {
        if (handle == 0) throw new IllegalStateException("closed");
        return handle;
    }

    /**
     * One chat turn: the system prompt (kept in the cache — put only what does not change into it), the user's text,
     * an optional GBNF grammar for the answer. temperature 0 — the most likely answer (for decisions and JSON).
     * Returns the answer, or null when cancelled or too long for the context.
     */
    public String chat(String system, String user, String grammar, int maxTokens, float temperature) {
        return raw(prefixOf(system), suffixOf(user), grammar, maxTokens, temperature, 1);
    }

    public static String prefixOf(String system) {
        return SYS_OPEN + system + TURN_END + USER_OPEN;
    }

    public static String suffixOf(String user) {
        return user + TURN_END + ASSISTANT_OPEN;
    }

    /** The prompt as given: `prefix` kept in the cache, `suffix` read each time. */
    public String raw(String prefix, String suffix, String grammar, int maxTokens, float temperature, int seed) {
        String out = nativeGenerate(h(), prefix, suffix, grammar == null ? "" : grammar, maxTokens, temperature, seed);
        if (out != null && out.startsWith("\u0001grammar")) throw new IllegalArgumentException("bad grammar");
        if (out != null && out.startsWith("\u0001")) throw new IllegalStateException("the engine failed: " + out.substring(1));
        return out;
    }

    /**
     * Stops the request in hand (from any thread): it returns null. A cancel that comes just before a request starts
     * stops that one (it is used up by it).
     */
    public void cancel() {
        long hh = handle;
        if (hh != 0) nativeCancel(hh);
    }

    /** Forgets a cancel not used yet. */
    public void resetCancel() {
        long hh = handle;
        if (hh != 0) nativeResetCancel(hh);
    }

    public Stats lastStats() {
        String s = nativeStats(h());
        return s == null ? null : new Stats(s);
    }

    /** Tokens in a text. */
    public int count(String text) {
        return nativeCount(h(), text);
    }

    public int context() {
        return nativeContext(h());
    }

    /** Does the grammar take this text as the model would write it? (For tests of the grammars.) */
    public boolean grammarAccepts(String grammar, String text) {
        int r = nativeGrammarAccepts(h(), grammar, text);
        if (r < 0) throw new IllegalArgumentException("bad grammar");
        return r == 1;
    }

    /**
     * llama.cpp's own log lines since the last call (what it found in the model file, the buffers it took, why it
     * failed), then forgotten. "" when there are none, null when the library is not loaded.
     */
    public static String takeNativeLog() {
        if (!loaded) return null;
        return nativeTakeLog();
    }

    /** A string from the model file ("general.name"…), or null. */
    public String meta(String key) {
        return nativeMeta(h(), key);
    }

    @Override
    public synchronized void close() {
        long hh = handle;
        handle = 0;
        if (hh != 0) nativeFree(hh);
    }

    /**
     * Threads: the phone's fast cores (those within 70% of the fastest one's top frequency), at least 2, at most 8 —
     * slow cores only hold the fast ones back in llama.cpp's even split of the work.
     */
    public static int defaultThreads() {
        int n = Runtime.getRuntime().availableProcessors();
        long[] max = new long[n];
        long top = 0;
        int known = 0;
        for (int i = 0; i < n; i++) {
            max[i] = readLong("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq");
            if (max[i] > 0) known++;
            top = Math.max(top, max[i]);
        }
        int fast = n;
        if (known == n && top > 0) {
            fast = 0;
            for (long m : max) if (m >= top * 7 / 10) fast++;
        }
        return Math.max(2, Math.min(8, fast));
    }

    private static long readLong(String path) {
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new java.io.FileReader(path));
            return Long.parseLong(r.readLine().trim());
        } catch (Exception e) {
            return -1;
        } finally {
            if (r != null) try {
                r.close();
            } catch (IOException ignored) {
            }
        }
    }

    static native long nativeLoad(String path, int nCtx, int nThreads, int nSlots, boolean kvQ8);

    static native void nativeFree(long h);

    static native void nativeCancel(long h);

    static native void nativeResetCancel(long h);

    static native String nativeGenerate(long h, String prefix, String suffix, String grammar, int maxTokens, float temperature, int seed);

    static native String nativeStats(long h);

    static native int nativeCount(long h, String text);

    static native int nativeContext(long h);

    static native String nativeMeta(long h, String key);

    static native int nativeSpecial(long h, String text);

    static native int nativeGrammarAccepts(long h, String grammar, String text);

    static native String nativeTakeLog();
}
