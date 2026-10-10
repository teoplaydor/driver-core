package io.github.teoplaydor.semsearch.core;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What was said for a quick note, understood (Russian, as a recognizer writes it, digits or words): when to remind
 * («завтра в 9», «через полчаса», «в пятницу вечером», «15 марта в 10:30», «в половине восьмого»), how often
 * («каждый день в 8», «по будням», «по понедельникам», «каждый месяц»), a list («купить молоко, хлеб и сыр»), a search
 * instead of a note («найди фото с котом»); what is left becomes the note, without «напомни мне», «ну», «короче».
 * Rules, not a model: instant and the same on any phone.
 */
public final class Spoken {
    /** How a reminder repeats. */
    public static final int ONCE = 0, DAILY = 1, WEEKDAYS = 2, WEEKLY = 7, MONTHLY = 30, YEARLY = 365;

    public static final class Result {
        /** The note's text (a list's items with boxes); empty when it was a search. */
        public String text = "";
        /** When to remind (epoch ms), or 0. */
        public long remindAt;
        /** {@link #ONCE}, {@link #DAILY}… */
        public int repeat = ONCE;
        /** A search was asked for: its words (no note then). */
        public String query;
        /** «Напомни» was said but no time: the time is to be asked. */
        public boolean wantsReminder;
    }

    private static final int F = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private Spoken() {
    }

    /** {@code said} understood at {@code now} in {@code tz}. */
    public static Result parse(String said, long now, TimeZone tz) {
        Result r = new Result();
        String s = said == null ? "" : said.replaceAll("\\s+", " ").trim();
        s = stripFillers(s);
        // a search, not a note
        Matcher q = Pattern.compile("^(?:найди|найти|покажи|поищи|ищи|поиск|отыщи)(?:\\s+мне)?\\s+(.+)$", F).matcher(s);
        if (q.find()) {
            r.query = q.group(1).replaceAll("[.!?]+$", "").trim();
            return r;
        }
        Text t = new Text(s);
        boolean trigger = t.cut("^(?:пожалуйста\\s+)?(?:напомни(?:те)?|напомнить|поставь напоминание|поставить напоминание|"
                + "создай напоминание|напоминание)(?:\\s+мне)?(?:\\s+пожалуйста)?(?:\\s*[:,—-]\\s*|\\s+|$)") != null;
        if (!trigger) t.cut("^(?:запиши(?:те)?|запомни|сохрани|заметка|создай заметку|добавь заметку|новая заметка|"
                + "запиши заметку)(?:\\s+мне)?(?:\\s*[:,—-]\\s*|\\s+|$)");
        t.orig = stripFillers(t.orig);
        t.text = Text.norm(t.orig);

        When w = new When(now, tz);
        w.read(t, trigger);
        // the words a reminder leaves behind: «что», «чтобы», «о том что» at the start
        t.cut("^(?:о том,? ?что|о том,? ?чтобы|что(?:бы)?|про)\\s+");
        String text = tidy(t.orig);
        if (w.any()) {
            r.remindAt = w.resolve();
            r.repeat = w.repeat;
        } else if (trigger) {
            r.wantsReminder = true;
        }
        if (text.isEmpty()) text = "Напоминание";
        r.text = list(text, !t.orig.isEmpty() && Character.isLowerCase(t.orig.charAt(0)));
        return r;
    }

    // ------------------------------------------------------------------ text with spans cut out

    /**
     * The text with spans cut out of it: matched in a copy with «ё» as «е» (one letter for one, so the places agree),
     * cut from both — the note keeps its «ё».
     */
    static final class Text {
        String text, orig;

        Text(String s) {
            orig = s;
            text = norm(s);
        }

        static String norm(String s) {
            return s.replace('ё', 'е').replace('Ё', 'Е');
        }

        /** The first match of {@code regex} cut out (its groups returned), or null when there is none. */
        Matcher cut(String regex) {
            Matcher m = find(regex);
            if (m != null) cut(m);
            return m;
        }

        /** The first match, left in place (to be checked before it is cut). */
        Matcher find(String regex) {
            Matcher m = Pattern.compile(regex, F).matcher(text);
            return m.find() ? m : null;
        }

        void cut(Matcher m) {
            orig = (orig.substring(0, m.start()) + " " + orig.substring(m.end())).replaceAll("\\s+", " ").trim();
            text = norm(orig);
        }
    }

    private static String stripFillers(String s) {
        String prev;
        do {
            prev = s;
            s = s.replaceFirst("(?iu)^(?:ну|короче|так|итак|слушай|окей|ок|ok|значит|вот|типа|это самое|алло|эй)(?:[,.!]\\s*|\\s+)", "")
                    .trim();
        } while (!s.equals(prev));
        return s;
    }

    /** Dangling prepositions and punctuation left by the cuts gone; the first letter capital. */
    static String tidy(String s) {
        String t = s.replaceAll("\\s+([,.!?:;])", "$1").replaceAll("\\s+", " ").trim();
        String prev;
        do {
            prev = t;
            t = t.replaceAll("(?iu)(?:^|\\s)(?:в|во|на|к|ко|до|с)\\s*$", "").replaceAll("^[,.;:—\\-\\s]+|[,;:—\\-\\s]+$", "").trim();
            t = t.replaceAll("(?iu)\\s(?:в|во|на|к)\\s(?=[,.!?])", " ");
        } while (!t.equals(prev));
        t = t.replaceAll("\\s+(?:пожалуйста)$", "").replaceAll("[.]+$", "").trim();
        if (t.isEmpty()) return t;
        return t.substring(0, 1).toUpperCase(Locale.ROOT) + t.substring(1);
    }

    // ------------------------------------------------------------------ lists

    private static final Pattern LEAD = Pattern.compile("^((?:(?:надо|нужно|не забыть|хочу|список|список дел|дела|"
            + "задачи|сегодня|завтра)\\s+)*(?:[а-я]+(?:ть|ти|чь)(?:ся)?)?)(?:\\s*:\\s*|\\s+)(.+)$", F);

    /**
     * «Купить молоко, хлеб и сыр» → «Купить:» and three items; «молоко, хлеб, сыр, кофе» → four items. A list needs a
     * verb (or «надо», «список») before two items or more, else three; items of a few words each.
     */
    static String list(String text, boolean saidLower) {
        if (text.contains("\n")) return text;
        String body = text.replaceAll("[.!]+$", "");
        Matcher m = LEAD.matcher(body);
        String head = "", rest = body;
        if (m.find() && !m.group(1).trim().isEmpty()) {
            head = m.group(1).trim();
            rest = m.group(2);
        }
        String[] parts = rest.split("\\s*,\\s*(?:и\\s+|а также\\s+|а ещё\\s+|а еще\\s+)?|\\s+и\\s+(?=[^,]*$)|\\s*;\\s*");
        List<String> items = new ArrayList<String>();
        for (String p : parts) {
            String it = p.trim();
            if (it.isEmpty()) continue;
            if (it.split("\\s+").length > 6) return text; // a sentence, not a list's item
            items.add(it);
        }
        int commas = rest.split(",", -1).length - 1;
        // a comma at least: «позвонить в банк и уточнить про кредит» is one thing to do, not a list
        boolean enough = !head.isEmpty() ? items.size() >= 2 && commas >= 1 : items.size() >= 3 && commas >= 2;
        if (!enough) return text;
        StringBuilder b = new StringBuilder();
        if (!head.isEmpty()) b.append(tidy(head)).append(':');
        else if (saidLower && items.get(0).length() > 1 && Character.isLowerCase(items.get(0).charAt(1))) {
            // the first item as said (the note's capital letter is not the item's)
            items.set(0, items.get(0).substring(0, 1).toLowerCase(Locale.ROOT) + items.get(0).substring(1));
        }
        for (String it : items) {
            if (b.length() > 0) b.append('\n');
            b.append(NoteText.OPEN).append(it.replaceAll("[.!]+$", ""));
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ numbers in words

    private static final String NUM = "(\\d{1,4}|(?:двадцать|тридцать|сорок|пятьдесят)(?:\\s+(?:одну|одна|один|две|два|три|четыре|пять|"
            + "шесть|семь|восемь|девять))?|одну|одна|один|две|два|три|четыре|пять|шесть|семь|восемь|девять|десять|"
            + "одиннадцать|двенадцать|тринадцать|четырнадцать|пятнадцать|шестнадцать|семнадцать|восемнадцать|"
            + "девятнадцать|пару|пара|несколько|полтора|полторы)";

    static int num(String w) {
        if (w == null) return -1;
        String s = w.toLowerCase(Locale.ROOT).trim();
        if (s.matches("\\d+")) return Integer.parseInt(s);
        int total = 0;
        for (String p : s.split("\\s+")) {
            int v = word(p);
            if (v < 0) return -1;
            total += v;
        }
        return total;
    }

    private static int word(String p) {
        switch (p) {
            case "один": case "одна": case "одну": return 1;
            case "два": case "две": case "пару": case "пара": return 2;
            case "три": case "несколько": return 3;
            case "четыре": return 4;
            case "пять": return 5;
            case "шесть": return 6;
            case "семь": return 7;
            case "восемь": return 8;
            case "девять": return 9;
            case "десять": return 10;
            case "одиннадцать": return 11;
            case "двенадцать": return 12;
            case "тринадцать": return 13;
            case "четырнадцать": return 14;
            case "пятнадцать": return 15;
            case "шестнадцать": return 16;
            case "семнадцать": return 17;
            case "восемнадцать": return 18;
            case "девятнадцать": return 19;
            case "двадцать": return 20;
            case "тридцать": return 30;
            case "сорок": return 40;
            case "пятьдесят": return 50;
            default: return -1;
        }
    }

    /** Ordinal hours in the genitive, as «в половине восьмого» says them. */
    private static final String[] ORD = {"первого", "второго", "третьего", "четвертого", "пятого", "шестого", "седьмого",
            "восьмого", "девятого", "десятого", "одиннадцатого", "двенадцатого"};
    private static final String ORDS = "(первого|второго|третьего|четвертого|пятого|шестого|седьмого|восьмого|девятого|"
            + "десятого|одиннадцатого|двенадцатого|\\d{1,2})";

    private static int ord(String w) {
        String s = w.toLowerCase(Locale.ROOT);
        if (s.matches("\\d+")) return Integer.parseInt(s);
        for (int i = 0; i < ORD.length; i++) if (ORD[i].equals(s)) return i + 1;
        return -1;
    }

    private static final String[] DAYS = {"понедельник", "вторник", "сред", "четверг", "пятниц", "суббот", "воскресень"};
    private static final int[] CAL_DAYS = {Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
            Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY};
    private static final String[] MONTHS = {"январ", "феврал", "март", "апрел", "ма[яй]", "июн", "июл", "август", "сентябр",
            "октябр", "ноябр", "декабр"};

    private static int dayIndex(String w) {
        String s = w.toLowerCase(Locale.ROOT);
        for (int i = 0; i < DAYS.length; i++) if (s.startsWith(DAYS[i])) return i;
        return -1;
    }

    // ------------------------------------------------------------------ when

    /** The parts of a time said, put together into a moment. */
    static final class When {
        final long now;
        final TimeZone tz;
        int repeat = ONCE;
        long relativeMs = -1; // «через …» in time units
        int relativeDays = -1, relativeMonths = -1;
        int addDays = -1; // «сегодня» 0, «завтра» 1
        int weekday = -1; // Calendar's day
        int day = -1, month = -1, year = -1;
        int hour = -1, minute = 0;
        boolean pm, am, night, partOfDay;

        When(long now, TimeZone tz) {
            this.now = now;
            this.tz = tz;
        }

        boolean any() {
            return repeat != ONCE || relativeMs >= 0 || relativeDays >= 0 || relativeMonths >= 0 || addDays >= 0 || weekday >= 0
                    || day >= 0 || hour >= 0;
        }

        /** @param reminder «напомни» was said: a bare «в 3» is a time then, not «в 3 магазина» */
        void read(Text t, boolean reminder) {
            Matcher m;
            // how often
            if ((m = t.cut("(?:^|\\s)(?:каждый|каждое|каждую|по)\\s+(утр(?:о|ам)|вечер(?:ам)?|ноч(?:ь|ам)|дн(?:ям|ём|ем))(?=\\s|$|[,.])")) != null) {
                repeat = DAILY;
                partOfDay(m.group(1));
            } else if (t.cut("(?:^|\\s)(?:каждый день|ежедневно|каждые сутки)(?=\\s|$|[,.])") != null) {
                repeat = DAILY;
            } else if (t.cut("(?:^|\\s)(?:по будням|в будни|по рабочим дням|каждый будний день|в будние дни)(?=\\s|$|[,.])") != null) {
                repeat = WEEKDAYS;
            } else if ((m = t.cut("(?:^|\\s)(?:каждый|каждую|каждое|по)\\s+(понедельник(?:ам)?|вторник(?:ам)?|сред(?:у|ам)|четверг(?:ам)?|"
                    + "пятниц(?:у|ам)|суббот(?:у|ам)|воскресень(?:е|ям))(?=\\s|$|[,.])")) != null) {
                repeat = WEEKLY;
                weekday = CAL_DAYS[dayIndex(m.group(1))];
            } else if (t.cut("(?:^|\\s)(?:каждую неделю|еженедельно|раз в неделю)(?=\\s|$|[,.])") != null) {
                repeat = WEEKLY;
            } else if (t.cut("(?:^|\\s)(?:каждый месяц|ежемесячно|раз в месяц)(?=\\s|$|[,.])") != null) {
                repeat = MONTHLY;
            } else if (t.cut("(?:^|\\s)(?:каждый год|ежегодно|раз в год)(?=\\s|$|[,.])") != null) {
                repeat = YEARLY;
            }
            // in how long
            if (t.cut("(?:^|\\s)через\\s+полчаса(?=\\s|$|[,.])") != null) {
                relativeMs = 30 * 60_000L;
            } else if (t.cut("(?:^|\\s)через\\s+полтора\\s+часа(?=\\s|$|[,.])") != null) {
                relativeMs = 90 * 60_000L;
            } else if ((m = t.cut("(?:^|\\s)через\\s+(?:" + NUM + "\\s+)?(минут[уы]?|мин|секунд[уы]?|час(?:а|ов)?|дн(?:я|ей)|день|сутки|"
                    + "недел(?:ю|и|ь)|месяц(?:а|ев)?|год(?:а)?|лет)(?=\\s|$|[,.])")) != null) {
                int n = m.group(1) == null ? 1 : num(m.group(1));
                if (n < 0) n = 1;
                String u = m.group(2).toLowerCase(Locale.ROOT);
                if (u.startsWith("мин")) relativeMs = n * 60_000L;
                else if (u.startsWith("сек")) relativeMs = Math.max(1, n) * 1000L;
                else if (u.startsWith("час")) relativeMs = n * 3_600_000L;
                else if (u.startsWith("д") || u.startsWith("сут")) relativeDays = n;
                else if (u.startsWith("нед")) relativeDays = 7 * n;
                else if (u.startsWith("мес")) relativeMonths = n;
                else relativeMonths = 12 * n;
            }
            // which day
            if (t.cut("(?:^|\\s)послезавтра(?=\\s|$|[,.])") != null) addDays = 2;
            else if (t.cut("(?:^|\\s)завтра(?=\\s|$|[,.])") != null) addDays = 1;
            else if (t.cut("(?:^|\\s)(?:сегодня|сейчас же)(?=\\s|$|[,.])") != null) addDays = 0;
            if ((m = t.cut("(?:^|\\s)(?:в|во)\\s+(?:следующ(?:ий|ую|ее)\\s+|эт(?:от|у|о)\\s+)?(понедельник|вторник|среду|четверг|пятницу|субботу|"
                    + "воскресенье)(?=\\s|$|[,.])")) != null) {
                weekday = CAL_DAYS[dayIndex(m.group(1))];
            } else if (t.cut("(?:^|\\s)на\\s+выходных(?=\\s|$|[,.])") != null) {
                weekday = Calendar.SATURDAY;
            }
            StringBuilder months = new StringBuilder();
            for (String mo : MONTHS) months.append(months.length() > 0 ? "|" : "").append(mo).append("[а-я]*");
            if ((m = t.find("(?:^|\\s)(?:на\\s+)?(\\d{1,2}|[а-я]+)(?:-?го|-?е)?\\s+(" + months + ")(?:\\s+(\\d{4})(?:\\s+года?)?)?(?=\\s|$|[,.])")) != null
                    && (num(m.group(1)) > 0 && num(m.group(1)) <= 31 || ordDay(m.group(1)) > 0)) {
                t.cut(m);
                day = num(m.group(1)) > 0 ? num(m.group(1)) : ordDay(m.group(1));
                month = monthIndex(m.group(2));
                if (m.group(3) != null) year = Integer.parseInt(m.group(3));
            } else if ((m = t.find("(?:^|\\s)(?:на\\s+)?(\\d{1,2})\\.(\\d{1,2})(?:\\.(\\d{2,4}))?(?=\\s|$|[,]|\\.(?:\\s|$))")) != null
                    && Integer.parseInt(m.group(2)) >= 1 && Integer.parseInt(m.group(2)) <= 12 && Integer.parseInt(m.group(1)) >= 1
                    && Integer.parseInt(m.group(1)) <= 31 && !t.text.substring(0, m.start()).matches("(?is).*(?:^|\\s)(?:в|к)\\s*$")) {
                t.cut(m);
                day = Integer.parseInt(m.group(1));
                month = Integer.parseInt(m.group(2)) - 1;
                if (m.group(3) != null) year = Integer.parseInt(m.group(3)) + (m.group(3).length() == 2 ? 2000 : 0);
            } else if ((m = t.cut("(?:^|\\s)(\\d{1,2})(?:-?го)?\\s+числа(?=\\s|$|[,.])")) != null) {
                day = Integer.parseInt(m.group(1));
            }
            // what time
            if ((m = t.cut("(?:^|\\s)(?:в|к)\\s+(\\d{1,2})[:.](\\d{2})(?:\\s+(утра|дня|вечера|ночи))?(?=\\s|$|[,.])")) != null) {
                hour = Integer.parseInt(m.group(1));
                minute = Integer.parseInt(m.group(2));
                qualifier(m.group(3));
            } else if ((m = t.cut("(?:^|\\s)в\\s+половин[еу]\\s+" + ORDS + "(?:\\s+(утра|дня|вечера|ночи))?(?=\\s|$|[,.])")) != null) {
                hour = ord(m.group(1)) - 1;
                minute = 30;
                qualifier(m.group(2));
            } else if ((m = t.cut("(?:^|\\s)в\\s+четверть\\s+" + ORDS + "(?:\\s+(утра|дня|вечера|ночи))?(?=\\s|$|[,.])")) != null) {
                hour = ord(m.group(1)) - 1;
                minute = 15;
                qualifier(m.group(2));
            } else if ((m = t.cut("(?:^|\\s)без\\s+четверти\\s+" + NUM + "(?:\\s+(утра|дня|вечера|ночи))?(?=\\s|$|[,.])")) != null) {
                hour = num(m.group(1)) - 1;
                minute = 45;
                qualifier(m.group(2));
            } else if ((m = t.find("(?:^|\\s)(?:в|к)\\s+" + NUM + "(\\s+час(?:а|ов)?)?(?:\\s+" + NUM + "(?:\\s+минут[уы]?)?)?"
                    + "(?:\\s+(утра|дня|вечера|ночи))?(?=\\s|$|[,.!?])")) != null && num(m.group(1)) >= 0 && num(m.group(1)) <= 24
                    && (reminder || m.group(2) != null || m.group(4) != null || m.group(3) != null && m.group(3).matches("\\d{2}")
                    || addDays >= 0 || weekday >= 0 || day >= 0 || repeat != ONCE || relativeDays >= 0
                    || m.start() == 0 || t.text.substring(m.end()).matches("(?s)\\s*(?:[,.!?].*)?"))) {
                // a bare «в 3» is a time only where it is one: with «напомни», «часов», «утра», a day, first or last
                t.cut(m);
                hour = num(m.group(1)) % 24;
                int mm = m.group(3) == null ? 0 : num(m.group(3));
                minute = mm >= 0 && mm < 60 ? mm : 0;
                qualifier(m.group(4));
            } else if (t.cut("(?:^|\\s)в\\s+полдень(?=\\s|$|[,.])") != null) {
                hour = 12;
            } else if (t.cut("(?:^|\\s)в\\s+полночь(?=\\s|$|[,.])") != null) {
                hour = 0;
                night = true;
            }
            if (hour < 0 && (m = t.cut("(?:^|\\s)(утром|с утра|днем|в обед|после обеда|вечером|ночью|перед сном)(?=\\s|$|[,.])")) != null) {
                partOfDay(m.group(1));
            } else if (hour >= 0 && (m = t.cut("(?:^|\\s)(утром|вечером|ночью|днем)(?=\\s|$|[,.])")) != null) {
                qualifier(m.group(1).startsWith("утр") ? "утра" : m.group(1).startsWith("веч") ? "вечера"
                        : m.group(1).startsWith("ноч") ? "ночи" : "дня");
            }
        }

        private static int ordDay(String w) {
            String[] o = {"первое", "второе", "третье", "четвертое", "пятое", "шестое", "седьмое", "восьмое", "девятое", "десятое"};
            String s = w.toLowerCase(Locale.ROOT);
            for (int i = 0; i < o.length; i++) if (s.equals(o[i]) || s.equals(o[i].replaceAll("е$", "го"))) return i + 1;
            return -1;
        }

        private static int monthIndex(String w) {
            String s = w.toLowerCase(Locale.ROOT);
            for (int i = 0; i < MONTHS.length; i++) if (Pattern.compile("^" + MONTHS[i]).matcher(s).find()) return i;
            return -1;
        }

        private void qualifier(String q) {
            if (q == null) return;
            String s = q.toLowerCase(Locale.ROOT);
            if (s.startsWith("утр")) am = true;
            else if (s.startsWith("веч") || s.startsWith("дн")) pm = true;
            else if (s.startsWith("ноч")) night = true;
        }

        private void partOfDay(String p) {
            String s = p.toLowerCase(Locale.ROOT);
            partOfDay = true;
            if (s.contains("утр")) hour = 9;
            else if (s.contains("после обеда")) hour = 14;
            else if (s.contains("обед") || s.startsWith("дн") || s.startsWith("дне")) hour = 13;
            else if (s.contains("перед сном")) {
                hour = 22;
                minute = 30;
            } else if (s.startsWith("веч")) hour = 19;
            else if (s.startsWith("ноч")) hour = 23;
            am = hour < 12;
        }

        /** The moment: the day said (or the next that fits), at the time said (or a time that fits the day). */
        long resolve() {
            Calendar c = Calendar.getInstance(tz, new Locale("ru"));
            c.setTimeInMillis(now);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            if (relativeMs >= 0 && hour < 0 && addDays < 0 && weekday < 0 && day < 0) return now + relativeMs;
            boolean dayGiven = addDays >= 0 || weekday >= 0 || day >= 0 || relativeDays >= 0 || relativeMonths >= 0;
            if (relativeDays >= 0) c.add(Calendar.DAY_OF_MONTH, relativeDays);
            if (relativeMonths >= 0) c.add(Calendar.MONTH, relativeMonths);
            if (addDays >= 0) c.add(Calendar.DAY_OF_MONTH, addDays);
            if (day >= 0) {
                int y = c.get(Calendar.YEAR);
                if (month >= 0) c.set(Calendar.MONTH, month);
                c.set(Calendar.DAY_OF_MONTH, Math.min(day, c.getActualMaximum(Calendar.DAY_OF_MONTH)));
                if (year > 0) c.set(Calendar.YEAR, year);
                else c.set(Calendar.YEAR, y);
            }
            if (weekday >= 0 && day < 0 && addDays < 0) {
                // the next such day (today said on that day: next week's — unless it repeats)
                int add = (weekday - c.get(Calendar.DAY_OF_WEEK) + 7) % 7;
                if (add == 0 && repeat != WEEKLY) add = 7;
                c.add(Calendar.DAY_OF_MONTH, add);
            }
            int h = hour, mi = minute;
            if (h < 0) {
                if (repeat != ONCE || dayGiven) {
                    h = 9;
                    mi = 0;
                } else {
                    h = c.get(Calendar.HOUR_OF_DAY);
                    mi = c.get(Calendar.MINUTE);
                }
                if (!dayGiven && repeat == ONCE && relativeMs < 0) {
                    // «сегодня» alone: this evening, or in an hour when the evening has come
                    h = 19;
                    mi = 0;
                }
            } else {
                if (pm && h < 12) h += 12;
                if (night && h == 12) h = 0;
                if (night && h >= 6 && h < 12) h += 12;
                if (!am && !pm && !night && !partOfDay && h >= 1 && h <= 6) h += 12; // «в 3» — in the afternoon
            }
            c.set(Calendar.HOUR_OF_DAY, h);
            c.set(Calendar.MINUTE, mi);
            if (relativeMs >= 0) c.setTimeInMillis(c.getTimeInMillis() + relativeMs);
            if (addDays < 0 && weekday < 0 && day < 0 && relativeDays < 0 && relativeMonths < 0) {
                // a time alone: today, or tomorrow when it has passed (an hour without «утра» may mean the evening)
                if (c.getTimeInMillis() <= now && hour >= 0 && !am && !pm && !night && !partOfDay && h < 12 && repeat == ONCE) {
                    c.add(Calendar.HOUR_OF_DAY, 12);
                }
                if (c.getTimeInMillis() <= now && hour < 0 && repeat == ONCE && relativeMs < 0) {
                    c.setTimeInMillis(now + 3_600_000L); // «сегодня» late: in an hour
                    c.set(Calendar.SECOND, 0);
                    c.set(Calendar.MILLISECOND, 0);
                }
                while (c.getTimeInMillis() <= now) c.add(Calendar.DAY_OF_MONTH, 1);
            } else if (day >= 0 && year <= 0 && c.getTimeInMillis() <= now) {
                // a date already past this year: next year's (a day of the month alone: next month's)
                if (month >= 0) c.add(Calendar.YEAR, 1);
                else c.add(Calendar.MONTH, 1);
            }
            if (repeat == WEEKDAYS) {
                while (c.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
                        || c.getTimeInMillis() <= now) {
                    c.add(Calendar.DAY_OF_MONTH, 1);
                }
            }
            if (repeat != ONCE) while (c.getTimeInMillis() <= now) c.setTimeInMillis(next(c.getTimeInMillis(), repeat, tz));
            return c.getTimeInMillis();
        }
    }

    /** The next time a reminder at {@code at} repeating so comes. */
    public static long next(long at, int repeat, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(at);
        switch (repeat) {
            case DAILY:
                c.add(Calendar.DAY_OF_MONTH, 1);
                break;
            case WEEKDAYS:
                do {
                    c.add(Calendar.DAY_OF_MONTH, 1);
                } while (c.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY);
                break;
            case WEEKLY:
                c.add(Calendar.DAY_OF_MONTH, 7);
                break;
            case MONTHLY:
                c.add(Calendar.MONTH, 1);
                break;
            case YEARLY:
                c.add(Calendar.YEAR, 1);
                break;
            default:
                return 0;
        }
        return c.getTimeInMillis();
    }

    /** «каждый день», «по будням»…, or "". */
    public static String repeatLabel(int repeat) {
        switch (repeat) {
            case DAILY:
                return "каждый день";
            case WEEKDAYS:
                return "по будням";
            case WEEKLY:
                return "каждую неделю";
            case MONTHLY:
                return "каждый месяц";
            case YEARLY:
                return "каждый год";
            default:
                return "";
        }
    }
}
