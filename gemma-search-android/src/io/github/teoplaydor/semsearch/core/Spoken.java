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
    /** On the last day of every month. */
    public static final int MONTHLY_LAST = 31;
    /** Every n days ({@code EVERY_DAYS + n}), every n months ({@code EVERY_MONTHS + n}). */
    public static final int EVERY_DAYS = 1000, EVERY_MONTHS = 2000;
    /** On the n-th (5: the last) such weekday of every month: {@code MONTHLY_NTH + n * 10 + Calendar's day}. */
    public static final int MONTHLY_NTH = 3000;

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
        /** «Когда буду дома / у магазина»: the place to remind at (as said, in its usual form), or null. */
        public String place;
        /** «Когда выйду из дома»: on leaving the place, not on coming to it. */
        public boolean leaving;
        /**
         * «За день до дня рождения Оли»: what the reminder comes before, when no date was said for it (its date is to
         * be found in the notes, or asked) — with {@link #beforeMs}, how long before.
         */
        public String beforeEvent;
        public long beforeMs;
        /** A question to the notes («какой код домофона у Саши?»), or null. */
        public String question;
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
        // a question to the notes, not a note: «какой код от домофона?», «напомни, когда день рождения Оли»
        Matcher ask = Pattern.compile("^(?:(?:скажи|подскажи|напомни|спроси|вопрос)(?:те)?(?:\\s+мне)?(?:\\s*[,:]\\s*|\\s+))?"
                + "((?:какой|какая|какое|какие|какую|каком|когда|где|сколько|во сколько|до скольки|кто|кому|чей|чья|чьё|чье|"
                + "как зовут|как называется|что я|что мне|что нужно|что надо|почему|зачем|откуда|куда|есть ли|был ли|была ли)"
                + "(?:\\s.*)?)$", F).matcher(s);
        if (ask.find() && !PLACE.matcher(s).find() && (ask.group(1).endsWith("?") || s.matches("(?iu)^(?:скажи|подскажи|спроси|вопрос).*")
                || !Text.norm(ask.group(1)).matches("(?iu)^(?:когда|как)\\s.*") || ask.group(1).matches("(?iu)^когда\\s+(?:у|день|будет|был|была|"
                + "заканчивается|кончается|истекает|начинается|прием|приём|встреча|запись|день)\\b.*"))) {
            r.question = ask.group(1).replaceAll("[.!]+$", "").trim();
            if (!r.question.endsWith("?")) r.question += "?";
            r.question = r.question.substring(0, 1).toUpperCase(Locale.ROOT) + r.question.substring(1);
            return r;
        }
        Text t = new Text(s);
        boolean trigger = t.cut("^(?:пожалуйста\\s+)?(?:напомни(?:те)?|напомнить|поставь напоминание|поставить напоминание|"
                + "создай напоминание|напоминание)(?:\\s+мне)?(?:\\s+пожалуйста)?(?:\\s*[:,—-]\\s*|\\s+|$)") != null;
        if (!trigger) t.cut("^(?:запиши(?:те)?|запомни|сохрани|заметка|создай заметку|добавь заметку|новая заметка|"
                + "запиши заметку)(?:\\s+мне)?(?:\\s*[:,—-]\\s*|\\s+|$)");
        t.orig = stripFillers(t.orig);
        t.text = Text.norm(t.orig);

        // where: «когда буду дома», «когда приду на работу», «как выйду из дома»
        Matcher pm = PLACE.matcher(t.text);
        if (pm.find()) {
            r.leaving = pm.group(1) != null;
            r.place = placeName(t.orig.substring(pm.start(2), pm.end(2)));
            t.cut(pm);
        }
        When w = new When(now, tz);
        w.read(t, trigger);
        // the words a reminder leaves behind: «что», «чтобы», «о том что» at the start
        t.cut("^(?:о том,? ?что|о том,? ?чтобы|что(?:бы)?|про)\\s+");
        if (w.beforeMs > 0) t.cut("^до\\s+(?=[а-яё]+(?:ть|ти|чь)(?:ся)?(?:\\s|$))"); // «за день до 15 марта оплатить…»
        String text = tidy(t.orig);
        if (w.beforeMs > 0 && !w.datePart()) {
            // «за день до дня рождения Оли»: before what — its date is to be found
            Matcher ev = Pattern.compile("(?:^|\\s)до\\s+((?:(?![а-яё]+(?:ть|ти|чь)(?:ся)?(?:\\s|$))[^,.;!?\\s]+\\s*){1,5})", F).matcher(t.text);
            if (ev.find()) r.beforeEvent = t.orig.substring(ev.start(1), ev.end(1)).trim();
            r.beforeMs = w.beforeMs;
            r.wantsReminder = true;
            r.repeat = w.repeat;
        } else if (w.any()) {
            r.remindAt = w.resolve();
            r.repeat = w.repeat;
        } else if (trigger && r.place == null) {
            r.wantsReminder = true;
        }
        if (text.isEmpty()) text = "Напоминание";
        r.text = list(text, !t.orig.isEmpty() && Character.isLowerCase(t.orig.charAt(0)));
        return r;
    }

    /**
     * The next time a text names a date of («День рождения Оли — 15 марта», «Оля: 15.03») comes — that day at 9:00 — or 0.
     */
    public static long dateIn(String text, long now, TimeZone tz) {
        if (text == null) return 0;
        When w = new When(now, tz);
        w.read(new Text(text.replaceAll("\\s+", " ")), false);
        if (w.day < 0 || w.month < 0) return 0;
        w.hour = -1;
        w.relativeMs = -1;
        w.relativeDays = w.relativeMonths = w.addDays = w.weekday = -1;
        w.repeat = ONCE;
        return w.moment();
    }

    /**
     * Whether a note is about the event named so: its words (by their stems, two letters at least) all in it —
     * «дня рождения Оли» is in «День рождения Оли — 15 марта».
     */
    public static boolean about(String note, String event) {
        if (note == null || event == null) return false;
        List<String> mine = new ArrayList<String>();
        for (String w : Text.norm(note.toLowerCase(Locale.ROOT)).split("[^\\p{L}\\p{N}]+")) if (!w.isEmpty()) mine.add(stemOf(w));
        int n = 0;
        for (String w : Text.norm(event.toLowerCase(Locale.ROOT)).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() < 2 || w.matches("до|на|в|во|у|к|с|со|по|за|и|для|от|мой|моя|моего|моей|нашего|нашей")) continue;
            String st = stemOf(w);
            if (st.length() > 4) st = st.substring(0, Math.max(3, st.length() - 1)); // «рождения» / «рождение»
            boolean found = false;
            for (String m : mine) if (m.startsWith(st) || st.startsWith(m) && m.length() >= 3) found = true;
            if (!found) return false;
            n++;
        }
        return n > 0;
    }

    /** A word's stem, «день» and «дня» (the vowel that comes and goes) alike. */
    private static String stemOf(String w) {
        if (w.matches("день|дня|дню|днем|дне|дни|дней|дням|днями|днях")) return "ден";
        return RussianStemmer.stem(w);
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

    /** «когда буду дома», «как приду на работу», «когда выйду из дома»: group 1 — leaving, group 2 — the place. */
    static final Pattern PLACE = Pattern.compile("(?:^|\\s)(?:когда|как только|как)\\s+(?:я\\s+)?(?:(выйду|уйду|уеду|выеду|буду уходить|"
            + "буду уезжать)\\s+(?:из|с|от)\\s+|(?:буду|приду|приеду|окажусь|зайду|подойду|подъеду|доберусь|вернусь|попаду|"
            + "буду проходить мимо|буду проезжать мимо|буду рядом с|буду около|буду возле)\\s+(?:(?:в|во|на|у|к|ко|рядом с|около|возле|"
            + "мимо)\\s+)?)(дома|домой|дом|(?:(?![а-яё]+(?:ть|ти|чь)(?:ся)?(?:\\s|$))[а-яё0-9-]+)(?:\\s+(?!(?:[а-яё]+(?:ть|ти|чь)(?:ся)?|и|а|"
            + "то|в|во|на|к|завтра|сегодня|утром|вечером)(?:\\s|$|[,.]))[а-яё0-9-]+){0,2})(?=\\s|$|[,.!?])", F);

    private static final String[][] PLACES = {{"дом", "Дом"}, {"работ", "Работа"}, {"офис", "Офис"}, {"магазин", "Магазин"},
            {"супермаркет", "Магазин"}, {"рынк", "Рынок"}, {"рынок", "Рынок"}, {"дач", "Дача"}, {"аптек", "Аптека"},
            {"школ", "Школа"}, {"садик", "Детский сад"}, {"детск", "Детский сад"}, {"университет", "Университет"},
            {"институт", "Институт"}, {"спортзал", "Спортзал"}, {"зал", "Спортзал"}, {"больниц", "Больница"},
            {"поликлиник", "Поликлиника"}, {"вокзал", "Вокзал"}, {"аэропорт", "Аэропорт"}, {"гараж", "Гараж"},
            {"мам", "У мамы"}, {"родител", "У родителей"}, {"бабушк", "У бабушки"}, {"центр", "Центр"}};

    /** The place said, in the form a list of places keeps: «дома» → «Дом», «на работу» → «Работа», «у магазина» → «Магазин». */
    public static String placeName(String said) {
        String s = Text.norm(said.trim().toLowerCase(Locale.ROOT));
        for (String[] p : PLACES) if (s.startsWith(p[0])) return p[1];
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
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
        /** «за день до …»: this long before the moment said. */
        long beforeMs;
        /** In the month: its last day, first day, middle; its n-th (5: last) {@link #nthDay}. */
        boolean lastDay, monthStart, monthMiddle;
        int nth = -1, nthDay = -1;

        When(long now, TimeZone tz) {
            this.now = now;
            this.tz = tz;
        }

        boolean any() {
            return repeat != ONCE || relativeMs >= 0 || relativeDays >= 0 || relativeMonths >= 0 || addDays >= 0 || weekday >= 0
                    || day >= 0 || hour >= 0 || monthRule();
        }

        /** A day or a time was said (not only «за день до»). */
        boolean datePart() {
            return relativeMs >= 0 || relativeDays >= 0 || relativeMonths >= 0 || addDays >= 0 || weekday >= 0 || day >= 0
                    || hour >= 0 || monthRule() || repeat != ONCE && repeat != YEARLY;
        }

        private boolean monthRule() {
            return lastDay || monthStart || monthMiddle || nth > 0;
        }

        private static final String NTH = "(перв(?:ый|ую|ое|ым|ая)|втор(?:ой|ую|ое|ым|ая)|трет(?:ий|ью|ье|ьим|ья)|четверт(?:ый|ую|ое|ым|ая)|"
                + "последн(?:ий|юю|ее|им|яя))";
        private static final String WEEKDAY = "(понедельник(?:ам)?|вторник(?:ам)?|сред(?:у|ам|а)|четверг(?:ам)?|пятниц(?:у|ам|а)|"
                + "суббот(?:у|ам|а)|воскресень(?:е|ям))";

        private static int nthOf(String w) {
            String s = w.toLowerCase(Locale.ROOT).replace('ё', 'е');
            if (s.startsWith("перв")) return 1;
            if (s.startsWith("втор")) return 2;
            if (s.startsWith("трет")) return 3;
            if (s.startsWith("четверт")) return 4;
            return 5;
        }

        /** @param reminder «напомни» was said: a bare «в 3» is a time then, not «в 3 магазина» */
        void read(Text t, boolean reminder) {
            Matcher m;
            // «за день до …», «за 2 часа до …», «за неделю до …»: «до …» stays, what follows it is the moment
            if (t.cut("(?:^|\\s)за\\s+полчаса(?=\\s+до\\s)") != null) {
                beforeMs = 30 * 60_000L;
            } else if ((m = t.cut("(?:^|\\s)за\\s+(?:" + NUM + "\\s+)?(минут[уы]?|мин|час(?:а|ов)?|дн(?:я|ей)|день|сутки|недел(?:ю|и|ь)|"
                    + "месяц(?:а|ев)?)(?=\\s+до\\s)")) != null) {
                int n = m.group(1) == null ? 1 : Math.max(1, num(m.group(1)));
                String u = m.group(2).toLowerCase(Locale.ROOT);
                beforeMs = u.startsWith("мин") ? n * 60_000L : u.startsWith("час") ? n * 3_600_000L
                        : u.startsWith("нед") ? n * 7 * 86_400_000L : u.startsWith("мес") ? n * 30 * 86_400_000L : n * 86_400_000L;
            }
            // how often, by the month: «каждый последний день месяца», «каждую последнюю пятницу месяца»
            if (t.cut("(?:^|\\s)(?:каждый\\s+последний\\s+день\\s+месяца|в\\s+последний\\s+день\\s+каждого\\s+месяца|"
                    + "по\\s+последним\\s+дням\\s+месяца|в\\s+конце\\s+каждого\\s+месяца)(?=\\s|$|[,.])") != null) {
                repeat = MONTHLY_LAST;
                lastDay = true;
            } else if ((m = t.cut("(?:^|\\s)(?:кажд(?:ый|ую|ое)\\s+" + NTH + "\\s+" + WEEKDAY + "(?:\\s+(?:каждого\\s+)?месяца)?|"
                    + "(?:в|во)\\s+" + NTH + "\\s+" + WEEKDAY + "\\s+каждого\\s+месяца|по\\s+(первым|вторым|третьим|четвертым|"
                    + "последним)\\s+" + WEEKDAY + "(?:\\s+месяца)?)(?=\\s|$|[,.])")) != null) {
                String which = m.group(1) != null ? m.group(1) : m.group(3) != null ? m.group(3) : m.group(5);
                String dayWord = m.group(2) != null ? m.group(2) : m.group(4) != null ? m.group(4) : m.group(6);
                nth = nthOf(which);
                nthDay = CAL_DAYS[dayIndex(dayWord)];
                repeat = MONTHLY_NTH + nth * 10 + nthDay;
            } else if ((m = t.cut("(?:^|\\s)(?:каждые|раз\\s+в)\\s+" + NUM + "\\s+(дн(?:я|ей)|день|недел(?:и|ь|ю)|месяц(?:а|ев)?)(?=\\s|$|[,.])")) != null) {
                int n = Math.max(1, num(m.group(1)));
                String u = m.group(2).toLowerCase(Locale.ROOT);
                if (u.startsWith("мес")) repeat = n == 1 ? MONTHLY : EVERY_MONTHS + n;
                else repeat = u.startsWith("нед") ? (n == 1 ? WEEKLY : EVERY_DAYS + 7 * n) : (n == 1 ? DAILY : EVERY_DAYS + n);
            } else if (t.cut("(?:^|\\s)(?:раз\\s+в\\s+две\\s+недели|каждые\\s+две\\s+недели|через\\s+неделю\\s+каждую)(?=\\s|$|[,.])") != null) {
                repeat = EVERY_DAYS + 14;
            } else if (t.cut("(?:^|\\s)(?:раз\\s+в\\s+квартал|ежеквартально|каждый\\s+квартал)(?=\\s|$|[,.])") != null) {
                repeat = EVERY_MONTHS + 3;
            } else if (t.cut("(?:^|\\s)(?:раз\\s+в\\s+полгода|каждые\\s+полгода|каждые\\s+пол\\s+года)(?=\\s|$|[,.])") != null) {
                repeat = EVERY_MONTHS + 6;
            }
            // how often
            if (repeat != ONCE) {
                // already said
            } else if ((m = t.cut("(?:^|\\s)(?:каждый|каждое|каждую|по)\\s+(утр(?:о|ам)|вечер(?:ам)?|ноч(?:ь|ам)|дн(?:ям|ём|ем))(?=\\s|$|[,.])")) != null) {
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
            // in the month, the week, the year
            if (!monthRule()) {
                if (t.cut("(?:^|\\s)(?:в\\s+последний\\s+день\\s+месяца|в\\s+конце\\s+месяца|последним\\s+днем\\s+месяца)(?=\\s|$|[,.])") != null) {
                    lastDay = true;
                } else if (t.cut("(?:^|\\s)(?:в\\s+начале\\s+(?:следующего\\s+)?месяца|первого\\s+числа)(?=\\s|$|[,.])") != null) {
                    monthStart = true;
                } else if (t.cut("(?:^|\\s)(?:в\\s+середине\\s+месяца)(?=\\s|$|[,.])") != null) {
                    monthMiddle = true;
                } else if ((m = t.cut("(?:^|\\s)(?:в|во)\\s+" + NTH + "\\s+" + WEEKDAY + "\\s+(?:(?:этого|следующего)\\s+)?месяца(?=\\s|$|[,.])")) != null) {
                    nth = nthOf(m.group(1));
                    nthDay = CAL_DAYS[dayIndex(m.group(2))];
                }
            }
            if (t.cut("(?:^|\\s)(?:в\\s+конце\\s+(?:этой\\s+)?недели|к\\s+концу\\s+недели)(?=\\s|$|[,.])") != null) {
                weekday = Calendar.FRIDAY;
            } else if (t.cut("(?:^|\\s)(?:на\\s+следующей\\s+неделе|в\\s+начале\\s+(?:следующей\\s+)?недели)(?=\\s|$|[,.])") != null) {
                weekday = Calendar.MONDAY;
            } else if (t.cut("(?:^|\\s)(?:в\\s+конце\\s+года|к\\s+концу\\s+года)(?=\\s|$|[,.])") != null) {
                day = 31;
                month = Calendar.DECEMBER;
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

        /** The moment, {@link #beforeMs} before it when «за … до» was said (but not before now). */
        long resolve() {
            long at = moment();
            if (beforeMs <= 0) return at;
            long r = at - beforeMs;
            if (repeat != ONCE) while (r <= now && at > 0) {
                at = next(at, repeat, tz);
                r = at - beforeMs;
            }
            return Math.max(r, now + 60_000L);
        }

        /** The month's day the rule says, in {@code c}'s month (its time kept). */
        private void monthDay(Calendar c) {
            if (lastDay) c.set(Calendar.DAY_OF_MONTH, c.getActualMaximum(Calendar.DAY_OF_MONTH));
            else if (monthStart) c.set(Calendar.DAY_OF_MONTH, 1);
            else if (monthMiddle) c.set(Calendar.DAY_OF_MONTH, 15);
            else if (nth > 0) nthWeekday(c, nth, nthDay);
        }

        /** The moment: the day said (or the next that fits), at the time said (or a time that fits the day). */
        long moment() {
            Calendar c = Calendar.getInstance(tz, new Locale("ru"));
            c.setTimeInMillis(now);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            if (relativeMs >= 0 && hour < 0 && addDays < 0 && weekday < 0 && day < 0 && !monthRule()) return now + relativeMs;
            if (monthRule()) {
                // this month's such day at the time said (9:00 by default); when it has passed, next month's
                int h = hour < 0 ? 9 : hour, mi = hour < 0 ? 0 : minute;
                if (hour >= 0 && pm && h < 12) h += 12;
                if (hour >= 0 && !am && !pm && !night && h >= 1 && h <= 6) h += 12;
                c.set(Calendar.HOUR_OF_DAY, h);
                c.set(Calendar.MINUTE, mi);
                monthDay(c);
                if (c.getTimeInMillis() <= now) {
                    c.set(Calendar.DAY_OF_MONTH, 1);
                    c.add(Calendar.MONTH, 1);
                    monthDay(c);
                }
                return c.getTimeInMillis();
            }
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
                    // the evening's passed too: tomorrow morning's comes first
                    if (c.getTimeInMillis() <= now) c.add(Calendar.HOUR_OF_DAY, -12);
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
            case MONTHLY_LAST:
                c.set(Calendar.DAY_OF_MONTH, 1);
                c.add(Calendar.MONTH, 1);
                c.set(Calendar.DAY_OF_MONTH, c.getActualMaximum(Calendar.DAY_OF_MONTH));
                break;
            default:
                if (repeat > EVERY_DAYS && repeat < EVERY_MONTHS) c.add(Calendar.DAY_OF_MONTH, repeat - EVERY_DAYS);
                else if (repeat > EVERY_MONTHS && repeat < MONTHLY_NTH) c.add(Calendar.MONTH, repeat - EVERY_MONTHS);
                else if (repeat > MONTHLY_NTH && repeat < MONTHLY_NTH + 60) {
                    c.set(Calendar.DAY_OF_MONTH, 1);
                    c.add(Calendar.MONTH, 1);
                    nthWeekday(c, (repeat - MONTHLY_NTH) / 10, (repeat - MONTHLY_NTH) % 10);
                } else return 0;
        }
        return c.getTimeInMillis();
    }

    /** {@code c} set to its month's n-th (5: last) {@code calDay}, its time kept. */
    static void nthWeekday(Calendar c, int n, int calDay) {
        if (n >= 5) {
            c.set(Calendar.DAY_OF_MONTH, c.getActualMaximum(Calendar.DAY_OF_MONTH));
            while (c.get(Calendar.DAY_OF_WEEK) != calDay) c.add(Calendar.DAY_OF_MONTH, -1);
            return;
        }
        c.set(Calendar.DAY_OF_MONTH, 1);
        while (c.get(Calendar.DAY_OF_WEEK) != calDay) c.add(Calendar.DAY_OF_MONTH, 1);
        c.add(Calendar.DAY_OF_MONTH, 7 * (n - 1));
    }

    private static final String[] NTH_WORD = {"", "первый", "второй", "третий", "четвёртый", "последний"};
    private static final String[] NTH_WORD_F = {"", "первую", "вторую", "третью", "четвёртую", "последнюю"};
    private static final String[] NTH_WORD_N = {"", "первое", "второе", "третье", "четвёртое", "последнее"};
    private static final String[] DAY_ACC = {"", "воскресенье", "понедельник", "вторник", "среду", "четверг", "пятницу", "субботу"};

    private static final String[] RRULE_DAY = {"", "SU", "MO", "TU", "WE", "TH", "FR", "SA"};

    /** The calendar's rule (RFC 5545 RRULE) for a reminder repeating so, or null when it does not repeat. */
    public static String rrule(int repeat) {
        switch (repeat) {
            case ONCE:
                return null;
            case DAILY:
                return "FREQ=DAILY";
            case WEEKDAYS:
                return "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR";
            case WEEKLY:
                return "FREQ=WEEKLY";
            case MONTHLY:
                return "FREQ=MONTHLY";
            case YEARLY:
                return "FREQ=YEARLY";
            case MONTHLY_LAST:
                return "FREQ=MONTHLY;BYMONTHDAY=-1";
            default:
                if (repeat > EVERY_DAYS && repeat < EVERY_MONTHS) {
                    int n = repeat - EVERY_DAYS;
                    return n % 7 == 0 ? "FREQ=WEEKLY;INTERVAL=" + n / 7 : "FREQ=DAILY;INTERVAL=" + n;
                }
                if (repeat > EVERY_MONTHS && repeat < MONTHLY_NTH) return "FREQ=MONTHLY;INTERVAL=" + (repeat - EVERY_MONTHS);
                if (repeat > MONTHLY_NTH && repeat < MONTHLY_NTH + 60) {
                    int n = (repeat - MONTHLY_NTH) / 10, d = (repeat - MONTHLY_NTH) % 10;
                    return "FREQ=MONTHLY;BYDAY=" + (n >= 5 ? "-1" : String.valueOf(n)) + RRULE_DAY[d];
                }
                return null;
        }
    }

    /** «2 дня», «5 дней»: the word for {@code n} of a thing by its three forms. */
    public static String plural(long n, String one, String few, String many) {
        long m = Math.abs(n) % 100, d = m % 10;
        String w = m >= 11 && m <= 14 ? many : d == 1 ? one : d >= 2 && d <= 4 ? few : many;
        return n + " " + w;
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
            case MONTHLY_LAST:
                return "в последний день месяца";
            default:
                if (repeat > EVERY_DAYS && repeat < EVERY_MONTHS) {
                    int n = repeat - EVERY_DAYS;
                    if (n % 7 == 0) return n == 14 ? "раз в две недели" : "каждые " + plural(n / 7, "неделю", "недели", "недель");
                    return "каждые " + plural(n, "день", "дня", "дней");
                }
                if (repeat > EVERY_MONTHS && repeat < MONTHLY_NTH) {
                    int n = repeat - EVERY_MONTHS;
                    return n == 3 ? "раз в квартал" : n == 6 ? "раз в полгода" : "каждые " + plural(n, "месяц", "месяца", "месяцев");
                }
                if (repeat > MONTHLY_NTH && repeat < MONTHLY_NTH + 60) {
                    int n = (repeat - MONTHLY_NTH) / 10, d = (repeat - MONTHLY_NTH) % 10;
                    boolean fem = d == Calendar.WEDNESDAY || d == Calendar.FRIDAY || d == Calendar.SATURDAY;
                    String w = fem ? NTH_WORD_F[n] : d == Calendar.SUNDAY ? NTH_WORD_N[n] : NTH_WORD[n];
                    return (w.startsWith("втор") ? "во " : "в ") + w + " " + DAY_ACC[d] + " месяца";
                }
                return "";
        }
    }
}
