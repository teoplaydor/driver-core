package io.github.teoplaydor.semsearch.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Word-level Russian → English translation of search queries (dictionary + Snowball stems).
 *
 * EmbeddingGemma 2 understands Russian text well, but its text↔image alignment is strongest for
 * English, so for photos/videos the app embeds both the original query and this English
 * rendering and searches with their normalised sum.
 */
public final class QueryBridge {
    public static final class Result {
        /** English rendering, e.g. "cat on sofa". */
        public final String english;
        /** Number of content words that were translated. */
        public final int translated;
        /** Content words that were not in the dictionary. */
        public final List<String> unknown;

        Result(String english, int translated, List<String> unknown) {
            this.english = english;
            this.translated = translated;
            this.unknown = unknown;
        }
    }

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:[-'][\\p{L}\\p{N}]+)*");

    private final Map<String, String> function = new HashMap<String, String>();
    private final Map<String, String> byStem = new HashMap<String, String>();

    public static QueryBridge load(InputStream lexicon) throws IOException {
        QueryBridge b = new QueryBridge();
        BufferedReader r = new BufferedReader(new InputStreamReader(lexicon, "UTF-8"));
        try {
            String line;
            boolean functionSection = false;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.equals("[function]")) {
                    functionSection = true;
                    continue;
                }
                if (line.equals("[words]")) {
                    functionSection = false;
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String en = line.substring(eq + 1).trim();
                for (String form : line.substring(0, eq).split("\\|")) {
                    form = normalize(form.trim());
                    if (form.isEmpty()) continue;
                    if (functionSection) {
                        b.function.put(form, en);
                    } else {
                        String key = stemPhrase(form);
                        if (!b.byStem.containsKey(key)) b.byStem.put(key, en);
                        if (!b.byStem.containsKey(form)) b.byStem.put(form, en);
                    }
                }
            }
        } finally {
            r.close();
        }
        return b;
    }

    public static boolean hasCyrillic(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\u0400' && c <= '\u04FF') return true;
        }
        return false;
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    private static String stemPhrase(String phrase) {
        StringBuilder sb = new StringBuilder();
        for (String w : phrase.split("\\s+")) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(RussianStemmer.stem(w));
        }
        return sb.toString();
    }

    /** @return the English rendering, or null when no content word could be translated. */
    public Result translate(String query) {
        List<String> words = new ArrayList<String>();
        Matcher m = WORD.matcher(normalize(query));
        while (m.find()) words.add(m.group());
        List<String> out = new ArrayList<String>();
        List<Boolean> isFunction = new ArrayList<Boolean>();
        List<String> unknown = new ArrayList<String>();
        int translated = 0;
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i);
            if (!hasCyrillic(w)) { // English words, numbers, brand names pass through
                out.add(w);
                isFunction.add(false);
                translated++;
                continue;
            }
            if (i + 1 < words.size()) {
                String two = byStem.get(RussianStemmer.stem(w) + " " + RussianStemmer.stem(words.get(i + 1)));
                if (two != null) {
                    out.add(two);
                    isFunction.add(false);
                    translated++;
                    i++;
                    continue;
                }
            }
            String f = function.get(w);
            if (f != null) {
                if (!f.isEmpty()) {
                    out.add(f);
                    isFunction.add(true);
                }
                continue;
            }
            // Exact form first (disambiguates "голубой"/"голубь", "летом"/"летит"), then the stem.
            String stem = RussianStemmer.stem(w);
            String en = byStem.get(w);
            if (en == null) en = byStem.get(stem);
            // Snowball has no part of speech: "ресторан" → "рестора" (verb rule), "ресторане" → "ресторан".
            if (en == null) en = byStem.get(RussianStemmer.stem(stem));
            if (en != null) {
                out.add(en);
                isFunction.add(false);
                translated++;
            } else {
                unknown.add(w);
            }
        }
        if (translated == 0) return null;
        // Function words at the edges ("with" in "кот с Барсиком" → "cat with") only add noise.
        int from = 0, to = out.size();
        while (from < to && isFunction.get(from)) from++;
        while (to > from && isFunction.get(to - 1)) to--;
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(out.get(i));
        }
        return new Result(sb.toString(), translated, unknown);
    }
}
