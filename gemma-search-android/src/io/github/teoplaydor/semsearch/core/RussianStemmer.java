package io.github.teoplaydor.semsearch.core;

/**
 * Snowball Russian stemmer (https://snowballstem.org/algorithms/russian/stemmer.html),
 * used to look up inflected query words in the RU→EN bridge dictionary.
 * Checked against the reference implementation (snowballstemmer) in test/StemmerParityTest.
 */
public final class RussianStemmer {
    private RussianStemmer() {}

    private static final String VOWELS = "аеиоуыэюя";

    private static final String[] PERFECTIVE_GERUND_1 = {"в", "вши", "вшись"};
    private static final String[] PERFECTIVE_GERUND_2 = {"ив", "ивши", "ившись", "ыв", "ывши", "ывшись"};
    private static final String[] ADJECTIVE = {"ее", "ие", "ые", "ое", "ими", "ыми", "ей", "ий", "ый", "ой", "ем",
            "им", "ым", "ом", "его", "ого", "ему", "ому", "их", "ых", "ую", "юю", "ая", "яя", "ою", "ею"};
    private static final String[] PARTICIPLE_1 = {"ем", "нн", "вш", "ющ", "щ"};
    private static final String[] PARTICIPLE_2 = {"ивш", "ывш", "ующ"};
    private static final String[] REFLEXIVE = {"ся", "сь"};
    private static final String[] VERB_1 = {"ла", "на", "ете", "йте", "ли", "й", "л", "ем", "н", "ло", "но", "ет", "ют",
            "ны", "ть", "ешь", "нно"};
    private static final String[] VERB_2 = {"ила", "ыла", "ена", "ейте", "уйте", "ите", "или", "ыли", "ей", "уй", "ил",
            "ыл", "им", "ым", "ен", "ило", "ыло", "ено", "ят", "ует", "уют", "ит", "ыт", "ены", "ить", "ыть", "ишь",
            "ую", "ю"};
    private static final String[] NOUN = {"а", "ев", "ов", "ие", "ье", "е", "иями", "ями", "ами", "еи", "ии", "и", "ией",
            "ей", "ой", "ий", "й", "иям", "ям", "ием", "ем", "ам", "ом", "о", "у", "ах", "иях", "ях", "ы", "ь", "ию",
            "ью", "ю", "ия", "ья", "я"};

    private static boolean isVowel(char c) {
        return VOWELS.indexOf(c) >= 0;
    }

    public static String stem(String word) {
        StringBuilder w = new StringBuilder(word.toLowerCase(java.util.Locale.ROOT).replace('ё', 'е'));
        int n = w.length();
        // RV: after the first vowel; R2: standard Snowball R2.
        int pV = n, p2 = n;
        int i = 0;
        while (i < n && !isVowel(w.charAt(i))) i++;
        if (i < n) {
            pV = i + 1;
            int j = pV;
            while (j < n && isVowel(w.charAt(j))) j++;      // gopast non-v (R1)
            if (j < n) {
                j++;
                while (j < n && !isVowel(w.charAt(j))) j++; // gopast v
                if (j < n) {
                    j++;
                    while (j < n && isVowel(w.charAt(j))) j++; // gopast non-v
                    if (j < n) p2 = j + 1;
                }
            }
        }
        if (pV > w.length()) return w.toString();

        // Step 1
        if (!removeGrouped(w, pV, PERFECTIVE_GERUND_1, PERFECTIVE_GERUND_2)) {
            removeLongest(w, pV, REFLEXIVE);
            if (!removeAdjectival(w, pV)) {
                if (!removeGrouped(w, pV, VERB_1, VERB_2)) {
                    removeLongest(w, pV, NOUN);
                }
            }
        }
        // Step 2
        if (w.length() > pV && w.charAt(w.length() - 1) == 'и') w.setLength(w.length() - 1);
        // Step 3: derivational (whole ending inside R2)
        String d = longestSuffix(w, pV, new String[]{"ост", "ость"});
        if (d != null && w.length() - d.length() >= p2) w.setLength(w.length() - d.length());
        // Step 4: tidy up
        String t = longestSuffix(w, pV, new String[]{"ейш", "ейше", "н", "ь"});
        if (t != null) {
            if (t.equals("ейш") || t.equals("ейше")) {
                w.setLength(w.length() - t.length());
                if (endsWith(w, pV, "нн")) w.setLength(w.length() - 1);
            } else if (t.equals("н")) {
                if (endsWith(w, pV, "нн")) w.setLength(w.length() - 1);
            } else {
                w.setLength(w.length() - 1);
            }
        }
        return w.toString();
    }

    private static boolean endsWith(StringBuilder w, int limit, String s) {
        int start = w.length() - s.length();
        return start >= limit && w.indexOf(s, start) == start;
    }

    /** Longest suffix from the list lying entirely within [limit, end). */
    private static String longestSuffix(StringBuilder w, int limit, String[] list) {
        String best = null;
        for (String s : list) {
            if ((best == null || s.length() > best.length()) && endsWith(w, limit, s)) best = s;
        }
        return best;
    }

    private static boolean removeLongest(StringBuilder w, int limit, String[] list) {
        String s = longestSuffix(w, limit, list);
        if (s == null) return false;
        w.setLength(w.length() - s.length());
        return true;
    }

    /**
     * Snowball "among" over two groups: group-1 endings must follow а/я (kept), group-2 endings
     * are removed as is. Only the longest matching ending is considered.
     */
    private static boolean removeGrouped(StringBuilder w, int limit, String[] g1, String[] g2) {
        String a = longestSuffix(w, limit, g1), b = longestSuffix(w, limit, g2);
        if (a == null && b == null) return false;
        if (b != null && (a == null || b.length() >= a.length())) {
            w.setLength(w.length() - b.length());
            return true;
        }
        int before = w.length() - a.length() - 1;
        if (before < limit) return false;
        char c = w.charAt(before);
        if (c != 'а' && c != 'я') return false;
        w.setLength(w.length() - a.length());
        return true;
    }

    private static boolean removeAdjectival(StringBuilder w, int limit) {
        if (!removeLongest(w, limit, ADJECTIVE)) return false;
        removeGrouped(w, limit, PARTICIPLE_1, PARTICIPLE_2);
        return true;
    }
}
