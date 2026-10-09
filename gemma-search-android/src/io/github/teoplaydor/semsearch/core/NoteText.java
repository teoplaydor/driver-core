package io.github.teoplaydor.semsearch.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A note's text: lines, some of them a list's items with a box — «☐ » to do, «☑ » done (and as pasted from elsewhere:
 * «- [ ] », «- [x] », «[ ] », «[x] »). What it says for meaning (the boxes left out: ticking one off does not change
 * what the note is about), its first line as its title, the boxes ticked and to tick, and the words it has as they
 * are (a code, a name the meaning of a query will not find).
 */
public final class NoteText {
    public static final String OPEN = "☐ ", DONE = "☑ ";

    private NoteText() {
    }

    public static String[] lines(String body) {
        return (body == null ? "" : body).split("\n", -1);
    }

    /** The line's box: 0 none, 1 to do, 2 done. */
    public static int box(String line) {
        String t = lead(line);
        if (t.startsWith("☐")) return 1;
        if (t.startsWith("☑") || t.startsWith("✅")) return 2;
        String l = t.toLowerCase(Locale.ROOT);
        if (l.startsWith("- [ ]") || l.startsWith("* [ ]") || l.startsWith("[ ]")) return 1;
        if (l.startsWith("- [x]") || l.startsWith("* [x]") || l.startsWith("[x]")) return 2;
        return 0;
    }

    /** The line without its box (and the space after it). */
    public static String rest(String line) {
        String t = lead(line);
        int cut;
        if (t.startsWith("☐") || t.startsWith("☑")) cut = 1;
        else if (t.startsWith("✅")) cut = "✅".length();
        else if (t.startsWith("[")) cut = 3;
        else if (box(line) != 0) cut = 5;
        else return line;
        String r = t.substring(cut);
        return r.startsWith(" ") ? r.substring(1) : r;
    }

    private static String lead(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        return line.substring(i);
    }

    /** The note with line {@code i}'s box ticked or unticked (a box pasted from elsewhere becoming ☐/☑). */
    public static String toggle(String body, int i) {
        String[] ls = lines(body);
        if (i < 0 || i >= ls.length || box(ls[i]) == 0) return body;
        ls[i] = (box(ls[i]) == 1 ? DONE : OPEN) + rest(ls[i]);
        return join(ls);
    }

    /** Line {@code i} given a box to tick, or its box taken away. */
    public static String toggleList(String body, int i) {
        String[] ls = lines(body);
        if (i < 0 || i >= ls.length) return body;
        ls[i] = box(ls[i]) != 0 ? rest(ls[i]) : OPEN + ls[i];
        return join(ls);
    }

    /** Which line {@code offset} (a place in the text) falls on. */
    public static int lineAt(String body, int offset) {
        int n = 0;
        for (int k = 0; k < Math.min(offset, body.length()); k++) if (body.charAt(k) == '\n') n++;
        return n;
    }

    /** Where line {@code i} begins in the text. */
    public static int lineStart(String body, int i) {
        int at = 0;
        for (int n = 0; n < i; n++) {
            int nl = body.indexOf('\n', at);
            if (nl < 0) return body.length();
            at = nl + 1;
        }
        return at;
    }

    /** What the note says, for its meaning: the boxes left out, blank lines dropped. */
    public static String plain(String body) {
        StringBuilder b = new StringBuilder();
        for (String l : lines(body)) {
            String r = rest(l).trim();
            if (r.isEmpty()) continue;
            if (b.length() > 0) b.append('\n');
            b.append(r);
        }
        return b.toString();
    }

    /** {ticked, all} boxes. */
    public static int[] progress(String body) {
        int done = 0, all = 0;
        for (String l : lines(body)) {
            int k = box(l);
            if (k != 0) all++;
            if (k == 2) done++;
        }
        return new int[]{done, all};
    }

    /** The first line with something in it, without its box, at most {@code max} characters. */
    public static String title(String body, int max) {
        for (String l : lines(body)) {
            String r = rest(l).trim();
            if (r.isEmpty()) continue;
            return r.length() <= max ? r : r.substring(0, max - 1).trim() + "…";
        }
        return "";
    }

    /**
     * Whether the note has each word of the query (two letters or more; case and «ё» aside) as it is — a word or the
     * start of one: «домофон» in «домофона», «47К1290» as written.
     */
    public static boolean hasWords(String body, String query) {
        List<String> words = words(query);
        if (words.isEmpty()) return false;
        List<String> mine = words(body);
        for (String w : words) {
            boolean found = false;
            for (String m : mine) {
                if (m.startsWith(w)) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    static List<String> words(String s) {
        List<String> out = new ArrayList<String>();
        if (s == null) return out;
        StringBuilder w = new StringBuilder();
        String t = s.toLowerCase(Locale.ROOT).replace('ё', 'е');
        for (int i = 0; i <= t.length(); i++) {
            char c = i < t.length() ? t.charAt(i) : ' ';
            if (Character.isLetterOrDigit(c)) {
                w.append(c);
            } else {
                if (w.length() >= 2) out.add(w.toString());
                w.setLength(0);
            }
        }
        return out;
    }

    private static String join(String[] ls) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < ls.length; i++) {
            if (i > 0) b.append('\n');
            b.append(ls[i]);
        }
        return b.toString();
    }
}
