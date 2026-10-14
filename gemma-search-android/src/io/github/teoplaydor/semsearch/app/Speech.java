package io.github.teoplaydor.semsearch.app;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import io.github.teoplaydor.semsearch.core.GigaAm;
import io.github.teoplaydor.semsearch.core.Utterances;

/**
 * A whole note recognized on the phone with GigaAM ({@link Engine#recognizeSpeech}): the sound cleaned, cut at its
 * pauses, the phrases put together again into pieces the model takes at once (up to ~22 s — whole sentences, so the
 * punctuation is right), each recognized, the texts joined. Off the main thread.
 */
final class Speech {
    interface Log {
        void line(String text);
    }

    private Speech() {
    }

    /** The text of a note (16 kHz mono as it was recorded); "" — nothing said. */
    static String text(Engine e, float[] x, Log log) throws Exception {
        float[] y = Utterances.clean(x, GigaAm.RATE);
        return text(e, y, Utterances.phrases(y, GigaAm.RATE), log);
    }

    /** The same for a note already cleaned and cut into phrases. */
    static String text(Engine e, float[] y, List<int[]> phrases, Log log) throws Exception {
        List<int[]> pieces = Utterances.groups(phrases, GigaAm.RATE, GigaAm.MAX_MS);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < pieces.size(); i++) {
            int[] p = pieces.get(i);
            long t0 = System.currentTimeMillis();
            String s = e.recognizeSpeech(Arrays.copyOfRange(y, p[0], p[1])).trim();
            if (log != null) {
                log.line(String.format(Locale.ROOT, "gigaam %d/%d %.2f-%.2f s: «%s» in %d ms", i + 1, pieces.size(), p[0] / (float) GigaAm.RATE,
                        p[1] / (float) GigaAm.RATE, s, System.currentTimeMillis() - t0));
            }
            if (!s.isEmpty()) b.append(b.length() > 0 ? " " : "").append(s);
        }
        return b.toString();
    }

    /** 16-bit little-endian PCM as samples. */
    static float[] samples(byte[] pcm) {
        float[] x = new float[pcm.length / 2];
        for (int i = 0; i < x.length; i++) x[i] = (short) ((pcm[2 * i] & 0xff) | (pcm[2 * i + 1] << 8)) / 32768f;
        return x;
    }
}
