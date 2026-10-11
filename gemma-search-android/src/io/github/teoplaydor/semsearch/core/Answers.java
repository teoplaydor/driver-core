package io.github.teoplaydor.semsearch.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A question to the notes answered with their own words: the lines of the notes, documents and texts read on photos
 * that share the most of the question's words (by their stems), the most meaningful first — «какой код от домофона у
 * Саши?» → «Код домофона у Саши: 47К1290, подъезд 3». «Что купить?» → what is left to tick in the shopping list. No
 * model needed; with one, the lines found are what it answers from.
 */
public final class Answers {
    private Answers() {
    }

    /** A line and where it came from. */
    public static final class Line {
        public final String text;
        /** Its source's index in what was given. */
        public final int source;
        /** The line's place in its source (the title is 0). */
        public final int at;
        public double score;

        Line(String text, int source, int at) {
            this.text = text;
            this.source = source;
            this.at = at;
        }
    }

    private static final Set<String> ASK = new HashSet<String>();

    static {
        for (String w : ("какой какая какое какие какую каком каких когда где сколько во до скольки кто кого кому чей чья чье чьи "
                + "что чем чего я мне меня мой моя мое мои у в во на и с со по за от из к ко ли же бы а но или как зовут называется "
                + "есть был была было были будет нужно надо можно ли это этот эта тот та там тут скажи подскажи напомни "
                + "спроси вопрос пожалуйста мы нам наш наша").split(" ")) ASK.add(w);
    }

    /** The question's words that matter, as stems: «какой код от домофона у Саши?» → код, домофон, саш. */
    public static List<String> keys(String question) {
        List<String> out = new ArrayList<String>();
        for (String w : words(question)) {
            if (ASK.contains(w)) continue;
            String s = stem(w);
            if (!s.isEmpty() && !out.contains(s)) out.add(s);
        }
        return out;
    }

    static List<String> words(String s) {
        List<String> out = new ArrayList<String>();
        if (s == null) return out;
        for (String w : s.toLowerCase(Locale.ROOT).replace('ё', 'е').split("[^\\p{L}\\p{N}]+")) if (!w.isEmpty()) out.add(w);
        return out;
    }

    static String stem(String w) {
        if (w.matches("\\d+") || !w.matches(".*[а-я].*")) return w;
        if (w.matches("день|дня|дню|днем|дне|дни|дней|дням")) return "ден";
        String s = RussianStemmer.stem(w);
        return s.length() > 5 ? s.substring(0, s.length() - 1) : s;
    }

    /** The lines of a source (a note, a document, a photo's text): its title first, then each line or sentence. */
    public static List<String> split(String title, String body) {
        List<String> out = new ArrayList<String>();
        if (title != null && !title.trim().isEmpty()) out.add(title.trim());
        if (body == null) return out;
        for (String l : body.split("\\n")) {
            String line = l.trim();
            if (line.isEmpty()) continue;
            if (line.length() <= 100 || !line.matches("(?s).*[.!?]\\s+\\S.*")) {
                out.add(line);
                continue;
            }
            // a paragraph: by its sentences
            for (String sent : line.split("(?<=[.!?])\\s+")) if (!sent.trim().isEmpty()) out.add(sent.trim());
        }
        return out;
    }

    /**
     * The lines that answer, best first (at most {@code max}): of each source's lines, those with the most of the
     * question's words; a line's neighbours count a little (a title with its value on the next line).
     */
    public static List<Line> best(String question, List<String[]> sources, int max) {
        List<String> keys = keys(question);
        List<Line> lines = new ArrayList<Line>();
        if (keys.isEmpty()) return lines;
        // «сколько…», «какая цена…»: a line with an amount answers
        boolean amount = question.toLowerCase(Locale.ROOT).matches("(?s).*(?:сколько|почем|цена|стоимост|размер плат|какая сумма).*");
        for (int s = 0; s < sources.size(); s++) {
            List<String> ls = split(sources.get(s)[0], sources.get(s)[1]);
            Set<String> whole = new HashSet<String>();
            for (String l : ls) for (String w : words(l)) whole.add(stem(w));
            int inSource = 0;
            for (String k : keys) if (has(whole, k)) inSource++;
            if (inSource == 0) continue;
            for (int i = 0; i < ls.size(); i++) {
                Set<String> mine = new HashSet<String>();
                for (String w : words(ls.get(i))) mine.add(stem(w));
                int n = 0;
                double w = 0;
                for (int k = 0; k < keys.size(); k++) {
                    if (!has(mine, keys.get(k))) continue;
                    n++;
                    w += k == 0 ? 1.0 : 0.8; // the first word asked about («гарантия на пылесос») weighs most
                }
                if (n == 0 && inSource < keys.size()) continue;
                Line l = new Line(ls.get(i), s, i);
                // the line's own words most, the source's some (the title says whose code it is)
                l.score = w + 0.5 * inSource / keys.size() + (hasValue(ls.get(i)) ? 0.3 : 0) - 0.002 * i
                        + (amount && ls.get(i).matches("(?iu).*\\d[\\d\\s]*(?:руб|₽|р\\.|\\$|€|%|шт|кг|мес|лет|год|тыс).*") ? 1.5 : 0);
                lines.add(l);
            }
        }
        Collections.sort(lines, new Comparator<Line>() {
            @Override
            public int compare(Line a, Line b) {
                return Double.compare(b.score, a.score);
            }
        });
        return lines.size() > max ? new ArrayList<Line>(lines.subList(0, max)) : lines;
    }

    private static boolean has(Set<String> words, String key) {
        if (words.contains(key)) return true;
        for (String w : words) if (key.length() >= 3 && (w.startsWith(key) || key.startsWith(w) && w.length() >= 4)) return true;
        return false;
    }

    /** A line that holds something to answer with: digits, a «:», a date. */
    private static boolean hasValue(String line) {
        return line.matches(".*\\d.*") || line.contains(":") || line.contains("—");
    }

    /** «Что купить?», «что в списке», «что осталось»: the question is about what is left to tick. */
    public static boolean asksList(String question) {
        String q = question.toLowerCase(Locale.ROOT).replace('ё', 'е');
        return q.matches("(?s).*(?:что|чего)\\s+(?:мне\\s+|нужно\\s+|надо\\s+|еще\\s+|осталось\\s+)*(?:купить|сделать|взять|"
                + "осталось|в списке|не хватает|забыл).*") || q.matches("(?s).*(?:список|списке)\\s+(?:покупок|дел).*");
    }

    /** The items left to tick in a note («молоко, хлеб и сыр»), or null when it has none. */
    public static String openItems(String body) {
        List<String> open = new ArrayList<String>();
        for (String l : NoteText.lines(body)) if (NoteText.box(l) == 1) open.add(NoteText.rest(l).trim());
        if (open.isEmpty()) return null;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < open.size(); i++) {
            if (i > 0) b.append(i == open.size() - 1 ? " и " : ", ");
            b.append(open.get(i));
        }
        return b.toString();
    }

    /** The answer line made short: its list box gone, at most {@code max} characters (cut at a word). */
    public static String tidy(String line, int max) {
        String t = NoteText.box(line) != 0 ? NoteText.rest(line).trim() : line.trim();
        if (t.length() <= max) return t;
        int cut = t.lastIndexOf(' ', max - 1);
        return t.substring(0, cut > max / 2 ? cut : max - 1).trim() + "…";
    }

    /**
     * What a generative model is asked: the question and the lines found (numbered, with their sources), to answer from
     * them alone, briefly, in Russian — or say it is not in the notes.
     */
    public static String prompt(String question, List<String> passages) {
        StringBuilder b = new StringBuilder();
        b.append("Ответь на вопрос пользователя коротко (одно-два предложения) и только по его заметкам ниже. ")
                .append("Если ответа в заметках нет, ответь: «В заметках этого нет».\n\nЗаметки:\n");
        for (int i = 0; i < passages.size(); i++) b.append(i + 1).append(". ").append(passages.get(i).replace('\n', ' ')).append('\n');
        b.append("\nВопрос: ").append(question.trim());
        return b.toString();
    }
}
