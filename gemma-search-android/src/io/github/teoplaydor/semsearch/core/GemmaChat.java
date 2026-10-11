package io.github.teoplaydor.semsearch.core;

import com.google.ai.edge.litertlm.InputData;
import com.google.ai.edge.litertlm.LiteRtLmJni;
import com.google.ai.edge.litertlm.SamplerConfig;

import java.io.Closeable;
import java.io.File;

/**
 * A generative Gemma (Gemma 4 E2B in LiteRT-LM's format) answering in its own words: one engine, a fresh session for
 * each question (nothing carried over), the model's chat template applied by the session. Text only — no vision or
 * audio executors, so its memory is the text model's.
 */
public final class GemmaChat implements Closeable {
    /** Low temperature: answers from the notes, not inventions. */
    static final double TEMPERATURE = 0.2, TOP_P = 0.9;
    static final int TOP_K = 40;

    private long engine;
    private volatile long session;

    /**
     * @param backend {@link LiteRtEmbedder#GPU} or {@link LiteRtEmbedder#CPU}
     * @param maxTokens the context (prompt and answer together)
     */
    public GemmaChat(File model, String backend, int maxTokens, int threads, File cacheDir) {
        engine = LiteRtLmJni.nativeCreateEngine(model.getPath(), backend, "", "", maxTokens, 0,
                cacheDir != null ? cacheDir.getPath() : "", false, null, "", "", "",
                LiteRtEmbedder.CPU.equals(backend) && threads > 0 ? threads : -1, -1, -1, -1);
        if (engine == 0) throw new IllegalStateException("LiteRT-LM: движок Gemma не создан");
    }

    /** The model's answer to {@code prompt} (its turn markers taken off). */
    public synchronized String generate(String prompt) {
        if (engine == 0) throw new IllegalStateException("closed");
        long s = LiteRtLmJni.nativeCreateSession(engine, new SamplerConfig(TOP_K, TOP_P, TEMPERATURE, 1), null, null, null);
        if (s == 0) throw new IllegalStateException("LiteRT-LM: сессия не создана");
        session = s;
        try {
            return clean(LiteRtLmJni.nativeGenerateContent(s, new InputData[]{new InputData.Text(prompt)}));
        } finally {
            session = 0;
            LiteRtLmJni.nativeDeleteSession(s);
        }
    }

    /** The answer being written stopped (from another thread). */
    public void cancel() {
        long s = session;
        if (s != 0) LiteRtLmJni.nativeCancelProcess(s);
    }

    @Override
    public synchronized void close() {
        if (engine != 0) {
            LiteRtLmJni.nativeDeleteEngine(engine);
            engine = 0;
        }
    }

    /** Turn markers and a «thinking» part left in the text taken off. */
    static String clean(String s) {
        if (s == null) return "";
        String t = s.replaceAll("(?s)<\\|?(?:channel|think)[^>]*>.*?<(?:/think|\\|?channel\\|?)>", "")
                .replaceAll("<\\|?(?:end_of_turn|start_of_turn|turn|eos|bos)\\|?>|<(?:end_of_turn|start_of_turn)>|<turn\\|>|<\\|turn>", "")
                .replaceAll("(?m)^\\s*model\\s*$", "");
        return t.trim();
    }
}
