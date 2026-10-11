package io.github.teoplaydor.semsearch.core;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Something with a time found in the text read on a photo or a screenshot (OCR): a train, a flight, a bus, a film or a
 * concert, a doctor's appointment — and a receipt's warranty. Rules over the text, forgiving the reader's slips
 * («0» for «О», spaces lost); what to remind of and how long before.
 */
public final class PhotoEvent {
    public static final String TRAIN = "train", FLIGHT = "flight", BUS = "bus", SHOW = "show", DOCTOR = "doctor", WARRANTY = "warranty";

    /** {@link #TRAIN}, {@link #FLIGHT}… */
    public final String kind;
    /** «Поезд 024А Москва — Казань», «Рейс SU 1234», «Гарантия до 15 марта 2028». */
    public final String title;
    /** When it is (a warranty: when it ends). */
    public final long at;
    /** How long before to remind. */
    public final long before;
    /** The photo it was found on (the app sets it), -1: not known. */
    public long mediaId = -1;

    public PhotoEvent(String kind, String title, long at, long before) {
        this.kind = kind;
        this.title = title;
        this.at = at;
        this.before = before;
    }

    /** When to remind. */
    public long remindAt() {
        return at - before;
    }

    /** «за 2 часа», «за 2 недели»: how long before, in words. */
    public String beforeLabel() {
        long h = before / 3_600_000L;
        if (before % 86_400_000L == 0) {
            long d = before / 86_400_000L;
            return d % 7 == 0 ? "за " + (d == 7 ? "неделю" : Spoken.plural(d / 7, "неделю", "недели", "недель"))
                    : "за " + (d == 1 ? "день" : Spoken.plural(d, "день", "дня", "дней"));
        }
        if (before % 3_600_000L == 0) return "за " + (h == 1 ? "час" : Spoken.plural(h, "час", "часа", "часов"));
        if (before == 90 * 60_000L) return "за полтора часа";
        return "за " + Spoken.plural(before / 60_000L, "минуту", "минуты", "минут");
    }

    private static final int F = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final String MONTHS = "(янв|фев|мар|апр|ма[яй]|июн|июл|авг|сен|окт|ноя|дек|"
            + "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[а-яa-z]*\\.?";

    /** A date and a time found together, where in the text. */
    static final class Stamp {
        long at;
        int pos;
        boolean hasTime;
    }

    /**
     * What the text read on a photo is about, if anything with a time: {@code taken} is when the photo was taken (a
     * receipt without a date was bought then), {@code now} — so that only what is ahead counts.
     */
    public static List<PhotoEvent> find(String text, long taken, long now, TimeZone tz) {
        List<PhotoEvent> out = new ArrayList<PhotoEvent>();
        if (text == null || text.trim().isEmpty()) return out;
        String t = clean(text);
        String low = t.toLowerCase(Locale.ROOT);
        List<Stamp> stamps = stamps(t, now, tz);

        // a receipt with a warranty
        Matcher w = Pattern.compile("гарант[а-я]*(?:\\s+[а-я]+){0,2}[\\s:.-]*(\\d{1,2})\\s*(мес[а-я.]*|г(?:од[а-я]*|\\.)?|лет)", F).matcher(low);
        if (w.find()) {
            int n = Integer.parseInt(w.group(1));
            int months = w.group(2).startsWith("мес") ? n : 12 * n;
            long bought = taken > 0 ? taken : now;
            for (Stamp s : stamps(t, 0, tz)) if (s.at <= now + 86_400_000L && s.at > now - 10L * 365 * 86_400_000L) {
                bought = s.at;
                break;
            }
            if (months > 0 && months <= 120) {
                Calendar c = Calendar.getInstance(tz);
                c.setTimeInMillis(bought);
                c.add(Calendar.MONTH, months);
                c.set(Calendar.HOUR_OF_DAY, 10);
                c.set(Calendar.MINUTE, 0);
                c.set(Calendar.SECOND, 0);
                c.set(Calendar.MILLISECOND, 0);
                if (c.getTimeInMillis() > now) {
                    String what = shop(t);
                    out.add(new PhotoEvent(WARRANTY, "Гарантия" + (what != null ? " (" + what + ")" : "") + " до "
                            + dayMonth(c.getTimeInMillis(), tz, true), c.getTimeInMillis(), 14 * 86_400_000L));
                }
            }
        }

        // something at a time: what is it, and which of the times is its start
        String kind = null, title = null;
        long before = 0;
        String anchor = null;
        Matcher m;
        if (has(low, "посадочн|boarding|рейс|flight|вылет|аэропорт|gate|выход на посадку")) {
            kind = FLIGHT;
            before = 3 * 3_600_000L;
            anchor = "вылет|отправлен|departure|boarding|посадк|время";
            String no = null;
            Matcher fm = Pattern.compile("(?:рейс|flight)[\\s:№#]*([A-ZА-Я0-9]{2}\\s?-?\\d{2,4})", F).matcher(t);
            if (fm.find()) no = fm.group(1).toUpperCase(Locale.ROOT).replaceAll("\\s|-", " ").replaceAll("\\s+", " ").trim();
            title = "Рейс" + (no != null ? " " + no : "") + route(t);
        } else if (has(low, "поезд|вагон|ржд|плацкарт|купе|электричк|ласточк|сапсан|отправление")) {
            kind = TRAIN;
            before = 2 * 3_600_000L;
            anchor = "отправлен|отпр";
            m = Pattern.compile("поезд[\\s:№#]*(\\d{2,4}[а-яa-z]?)", F).matcher(t);
            title = "Поезд" + (m.find() ? " " + m.group(1).toUpperCase(Locale.ROOT) : "") + route(t);
        } else if (has(low, "автобус|автовокзал|рейс автобуса|перевозчик")) {
            kind = BUS;
            before = 3_600_000L;
            anchor = "отправлен|отпр";
            title = "Автобус" + route(t);
        } else if (has(low, "сеанс|кинотеатр|концерт|театр|спектакл|билет на|ряд|партер|балкон|начало в|начало:")) {
            kind = SHOW;
            before = 90 * 60_000L;
            anchor = "сеанс|начало|время";
            title = has(low, "кино|сеанс") ? "Кино" : has(low, "концерт") ? "Концерт" : has(low, "театр|спектакл") ? "Театр" : "Мероприятие";
            Matcher q = Pattern.compile("[«\"“]([^»\"”\\n]{2,60})[»\"”]").matcher(t);
            while (q.find()) {
                String name = q.group(1).trim();
                if (name.matches("(?iu).*(?:ржд|ооо|оао|зао|ип\\s).*") || name.length() < 2) continue;
                title += ": " + name;
                break;
            }
        } else if (has(low, "при[её]м|запись|врач|клиник|поликлиник|кабинет|стоматолог|медицинск|талон|анализ")) {
            kind = DOCTOR;
            before = 2 * 3_600_000L;
            anchor = "при[её]м|запис|время|дата";
            Matcher dm = Pattern.compile("(?:врач|специалист)[\\s:-]*([а-яё-]+(?:\\s+[а-яё.]+){0,2})", F).matcher(t);
            Matcher sm = Pattern.compile("(стоматолог[а-я]*|терапевт[а-я]*|окулист[а-я]*|офтальмолог[а-я]*|педиатр[а-я]*|лор[а-я]*|"
                    + "хирург[а-я]*|невролог[а-я]*|гинеколог[а-я]*|уролог[а-я]*|кардиолог[а-я]*|дерматолог[а-я]*|эндокринолог[а-я]*)", F).matcher(low);
            title = "Приём" + (sm.find() ? " · " + sm.group(1) : dm.find() ? " · " + dm.group(1).trim() : "");
        }
        if (kind != null) {
            Stamp s = start(t, stamps, anchor);
            if (s != null && s.hasTime && s.at > now) out.add(new PhotoEvent(kind, title, s.at, before));
        }
        return out;
    }

    private static boolean has(String low, String words) {
        return Pattern.compile("(?:^|[^а-яa-z])(?:" + words + ")", F).matcher(low).find();
    }

    /** The reader's slips mended where they matter: «О» and «З» read in numbers, spaces around «:» and «.». */
    static String clean(String s) {
        String t = s.replace('\u00A0', ' ');
        // a letter O read for zero inside numbers, spaces in «14 : 35», «21 . 10 . 2026»
        Matcher m = Pattern.compile("(?<=\\d)[OoОо](?=\\d)|(?<=\\d)[OoОо](?=[:.]\\d)|(?<=[:.])[OoОо](?=\\d)").matcher(t);
        t = m.replaceAll("0");
        t = t.replaceAll("(\\d)\\s*([:.])\\s*(\\d)", "$1$2$3");
        return t;
    }

    /** Every «date + time» (or date alone) in the text, in order. */
    static List<Stamp> stamps(String t, long now, TimeZone tz) {
        List<Stamp> out = new ArrayList<Stamp>();
        Matcher m = Pattern.compile("(?<![\\d.])(\\d{1,2})[./](\\d{1,2})(?:[./](\\d{2,4}))?(?![\\d])|(\\d{4})-(\\d{2})-(\\d{2})|"
                + "(?<!\\d)(\\d{1,2})\\s+" + MONTHS + "(?:\\s+(\\d{4}))?", F).matcher(t);
        while (m.find()) {
            int d, mo, y = -1;
            if (m.group(1) != null) {
                d = Integer.parseInt(m.group(1));
                mo = Integer.parseInt(m.group(2));
                if (m.group(3) != null) y = Integer.parseInt(m.group(3)) + (m.group(3).length() == 2 ? 2000 : 0);
            } else if (m.group(4) != null) {
                y = Integer.parseInt(m.group(4));
                mo = Integer.parseInt(m.group(5));
                d = Integer.parseInt(m.group(6));
            } else {
                d = Integer.parseInt(m.group(7));
                mo = month(m.group(8)) + 1;
                if (m.group(9) != null) y = Integer.parseInt(m.group(9));
            }
            if (d < 1 || d > 31 || mo < 1 || mo > 12 || y > 0 && (y < 2000 || y > 2100)) continue;
            if (m.group(1) != null && m.group(3) == null && looksTime(t, m)) continue;
            Calendar c = Calendar.getInstance(tz);
            c.setTimeInMillis(now > 0 ? now : System.currentTimeMillis());
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 0);
            if (y > 0) c.set(Calendar.YEAR, y);
            c.set(Calendar.MONTH, mo - 1);
            c.set(Calendar.DAY_OF_MONTH, Math.min(d, c.getActualMaximum(Calendar.DAY_OF_MONTH)));
            if (y < 0 && now > 0 && c.getTimeInMillis() + 86_400_000L < now) c.add(Calendar.YEAR, 1);
            Stamp s = new Stamp();
            s.pos = m.start();
            // the time: right after the date (on the same line or the next), else right before it
            Matcher tm = Pattern.compile("^[^\\n\\d]{0,24}(?:\\n[^\\n\\d]{0,24})?(?<!\\d)([01]?\\d|2[0-3])[:.]([0-5]\\d)(?!\\d)").matcher(t.substring(m.end()));
            Matcher bm = Pattern.compile("(?<!\\d)([01]?\\d|2[0-3])[:.]([0-5]\\d)(?![\\d.])[^\\n\\d]{0,12}$").matcher(t.substring(0, m.start()));
            if (tm.find()) {
                c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(tm.group(1)));
                c.set(Calendar.MINUTE, Integer.parseInt(tm.group(2)));
                s.hasTime = true;
            } else if (bm.find()) {
                c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(bm.group(1)));
                c.set(Calendar.MINUTE, Integer.parseInt(bm.group(2)));
                s.hasTime = true;
            }
            s.at = c.getTimeInMillis();
            out.add(s);
        }
        return out;
    }

    /** «14.35» (a time) rather than «14.03» (a date): a time word or «:»-less clock next to it. */
    private static boolean looksTime(String t, Matcher m) {
        int d = Integer.parseInt(m.group(1)), mo = Integer.parseInt(m.group(2));
        if (mo > 12) return true;
        String before = t.substring(Math.max(0, m.start() - 12), m.start()).toLowerCase(Locale.ROOT);
        return before.matches("(?s).*(?:время|в|начало|отпр\\.?|прибыт[а-я.]*|сеанс)\\s*:?\\s*$") && d <= 23 && m.group(2).length() == 2;
    }

    private static int month(String w) {
        String s = w.toLowerCase(Locale.ROOT);
        String[] ru = {"янв", "фев", "мар", "апр", "ма", "июн", "июл", "авг", "сен", "окт", "ноя", "дек"};
        String[] en = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};
        for (int i = 0; i < 12; i++) if (s.startsWith(ru[i]) || s.startsWith(en[i])) return i;
        return 0;
    }

    /** The start: the first stamp with a time after the anchor word («Отправление»), else the first with a time. */
    private static Stamp start(String t, List<Stamp> stamps, String anchor) {
        if (anchor != null) {
            Matcher a = Pattern.compile("(?:" + anchor + ")", F).matcher(t);
            while (a.find()) {
                for (Stamp s : stamps) if (s.hasTime && s.pos >= a.start() && s.pos - a.end() < 80) return s;
            }
        }
        for (Stamp s : stamps) if (s.hasTime) return s;
        return null;
    }

    /** « Москва — Казань» when the text names two places with a dash or an arrow between, else "". */
    private static String route(String t) {
        Matcher m = Pattern.compile("([А-ЯЁ][А-ЯЁа-яё-]{2,}(?: [А-ЯЁ][А-ЯЁа-яё-]{2,})?) *(?:—|–|-|→|->|>) *([А-ЯЁ][А-ЯЁа-яё-]{2,}"
                + "(?: [А-ЯЁ][А-ЯЁа-яё-]{2,})?)").matcher(t);
        while (m.find()) {
            String a = cap(m.group(1)), b = cap(m.group(2));
            if (a.equalsIgnoreCase(b)) continue;
            return " " + a + " — " + b;
        }
        return "";
    }

    private static String cap(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder();
        for (String w : l.split(" ")) {
            if (b.length() > 0) b.append(' ');
            String[] parts = w.split("-");
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) b.append('-');
                if (!parts[i].isEmpty()) b.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
            }
        }
        return b.toString();
    }

    /** A receipt's shop or product: the first line of capitals or quotes («ООО "Техника"», «М.Видео»). */
    private static String shop(String t) {
        Matcher m = Pattern.compile("(?:ООО|ИП|АО|ПАО)\\s+[«\"]?([^«»\"\\n]{2,30})[»\"]?").matcher(t);
        if (m.find()) return m.group(1).trim();
        m = Pattern.compile("(?m)^\\s*([A-ZА-ЯЁ][A-Za-zА-ЯЁа-яё.\\- ]{2,24})\\s*$").matcher(t);
        return m.find() ? m.group(1).trim() : null;
    }

    private static final String[] GEN = {"января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября",
            "октября", "ноября", "декабря"};

    /** «15 марта», «15 марта 2028». */
    public static String dayMonth(long at, TimeZone tz, boolean year) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(at);
        return c.get(Calendar.DAY_OF_MONTH) + " " + GEN[c.get(Calendar.MONTH)] + (year ? " " + c.get(Calendar.YEAR) : "");
    }
}
