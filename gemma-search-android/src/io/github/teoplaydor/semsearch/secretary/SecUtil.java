package io.github.teoplaydor.semsearch.secretary;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The server version's util.py: time in the database (UTC strings), dates for people, text helpers; plus difflib. */
public final class SecUtil {
    private SecUtil() {
    }

    static final Charset UTF8 = Charset.forName("UTF-8");
    public static final String[] WEEKDAYS = {"пн", "вт", "ср", "чт", "пт", "сб", "вс"};
    private static final DateTimeFormatter DB_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    /** The clock (tests set a fixed one). */
    public static volatile Clock clock = Clock.systemUTC();

    public static Instant now() {
        return clock.instant();
    }

    /** All times in the database are UTC strings — they compare as strings. */
    public static String toDb(Instant t) {
        return DB_FMT.format(t.atZone(ZoneOffset.UTC).withNano(0));
    }

    public static Instant fromDb(String s) {
        return LocalDateTime.parse(s, DB_FMT).toInstant(ZoneOffset.UTC);
    }

    private static final Pattern ISO = Pattern.compile(
            "(\\d{4})-(\\d{2})-(\\d{2})(?:[T ](\\d{2}):(\\d{2})(?::(\\d{2})(?:[.,](\\d{1,9}))?)?)?\\s*(Z|[+-]\\d{2}(?::?\\d{2})?)?");

    /** ISO 8601 from the model → a moment; without a zone — local time; a bare date — 23:59 that day. */
    public static Instant parseIso(Object value, ZoneId tz) {
        return parseIso(value, tz, LocalTime.of(23, 59));
    }

    public static Instant parseIso(Object value, ZoneId tz, LocalTime dateOnlyTime) {
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) return null;
        Matcher m = ISO.matcher(s);
        if (!m.matches()) return null;
        try {
            LocalDate d = LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            if (m.group(4) == null) {
                if (m.group(8) != null) return null;
                return ZonedDateTime.of(d, dateOnlyTime, tz).toInstant();
            }
            int nanos = 0;
            if (m.group(7) != null) {
                String f = (m.group(7) + "000000000").substring(0, 9);
                nanos = Integer.parseInt(f);
            }
            LocalDateTime lt = LocalDateTime.of(d, LocalTime.of(Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)),
                    m.group(6) == null ? 0 : Integer.parseInt(m.group(6)), nanos));
            String off = m.group(8);
            if (off == null) return lt.atZone(tz).toInstant();
            if ("Z".equals(off)) return lt.toInstant(ZoneOffset.UTC);
            String o = off.replace(":", "");
            int sign = o.charAt(0) == '-' ? -1 : 1;
            int hh = Integer.parseInt(o.substring(1, 3)), mm = o.length() >= 5 ? Integer.parseInt(o.substring(3, 5)) : 0;
            return lt.toInstant(ZoneOffset.ofTotalSeconds(sign * (hh * 3600 + mm * 60)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** «сегодня 19:00», «завтра 10:30», «пт 24.10 18:00». */
    public static String fmtDt(Instant t, ZoneId tz, Instant now) {
        ZonedDateTime loc = t.atZone(tz);
        LocalDate today = (now == null ? now() : now).atZone(tz).toLocalDate();
        String day;
        if (loc.toLocalDate().equals(today)) day = "сегодня";
        else if (loc.toLocalDate().equals(today.plusDays(1))) day = "завтра";
        else day = WEEKDAYS[loc.getDayOfWeek().getValue() - 1] + " " + two(loc.getDayOfMonth()) + "." + two(loc.getMonthValue());
        return day + " " + hm(loc);
    }

    public static String fmtDt(Instant t, ZoneId tz) {
        return fmtDt(t, tz, null);
    }

    public static String hm(ZonedDateTime t) {
        return two(t.getHour()) + ":" + two(t.getMinute());
    }

    /** dd.mm HH:MM */
    public static String dmhm(ZonedDateTime t) {
        return two(t.getDayOfMonth()) + "." + two(t.getMonthValue()) + " " + hm(t);
    }

    static String two(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    /** Python's isoformat(timespec="minutes") of a local time: 2026-11-13T19:05+03:00. */
    public static String isoMinutes(Instant t, ZoneId tz) {
        ZonedDateTime z = t.atZone(tz);
        int off = z.getOffset().getTotalSeconds();
        String sign = off < 0 ? "-" : "+";
        off = Math.abs(off);
        return z.getYear() + "-" + two(z.getMonthValue()) + "-" + two(z.getDayOfMonth()) + "T" + hm(z) + sign
                + two(off / 3600) + ":" + two(off % 3600 / 60);
    }

    /** f"{x:,.0f}".replace(",", " ") + " ₽" — Python rounds halves to even. */
    public static String fmtMoney(double x) {
        return groupThousands(new BigDecimal(x).setScale(0, RoundingMode.HALF_EVEN).toPlainString()) + " ₽";
    }

    /** "1234567" → "1 234 567" (keeps the sign and any fraction). */
    public static String groupThousands(String num) {
        String sign = num.startsWith("-") ? "-" : "";
        String s = sign.isEmpty() ? num : num.substring(1);
        int dot = s.indexOf('.');
        String whole = dot < 0 ? s : s.substring(0, dot), frac = dot < 0 ? "" : s.substring(dot);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < whole.length(); i++) {
            if (i > 0 && (whole.length() - i) % 3 == 0) out.append(' ');
            out.append(whole.charAt(i));
        }
        return sign + out + frac;
    }

    /** f"{x:.Nf}" with Python's rounding of the exact binary value. */
    public static String fixed(double x, int digits) {
        return new BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toPlainString();
    }

    /** html.escape(s, quote=False). */
    public static String esc(Object o) {
        String s = o == null ? "" : String.valueOf(o);
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&') out.append("&amp;");
            else if (c == '<') out.append("&lt;");
            else if (c == '>') out.append("&gt;");
            else out.append(c);
        }
        return out.toString();
    }

    /** html.escape(s, quote=True). */
    public static String escAttr(String s) {
        return esc(s).replace("\"", "&quot;").replace("'", "&#x27;");
    }

    /** Spaces squeezed, cut to n characters with «…». */
    public static String shorten(String s, int n) {
        String t = joinWords(s);
        if (t.codePointCount(0, t.length()) <= n) return t;
        int end = t.offsetByCodePoints(0, n - 1);
        return rstrip(t.substring(0, end)) + "…";
    }

    /** " ".join(s.split()) */
    public static String joinWords(String s) {
        if (s == null) return "";
        String[] parts = s.trim().split("[\\s\\u001c-\\u001f\\u0085\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]+");
        StringBuilder out = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(p);
        }
        return out.toString();
    }

    static String rstrip(String s) {
        int e = s.length();
        while (e > 0 && Character.isWhitespace(s.charAt(e - 1))) e--;
        return s.substring(0, e);
    }

    /** Python's s[:n] on code points. */
    public static String head(String s, int n) {
        if (s == null) return "";
        if (s.codePointCount(0, s.length()) <= n) return s;
        return s.substring(0, s.offsetByCodePoints(0, n));
    }

    /** Python's s[-n:] on code points. */
    public static String tail(String s, int n) {
        if (s == null) return "";
        int cps = s.codePointCount(0, s.length());
        if (cps <= n) return s;
        return s.substring(s.offsetByCodePoints(0, cps - n));
    }

    public static int len(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }

    public static String sha1Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(UTF8));
            StringBuilder out = new StringBuilder();
            for (byte b : d) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    public static String join(String sep, List<String> parts) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) out.append(sep);
            out.append(parts.get(i));
        }
        return out.toString();
    }

    /** Python's bool(value) for what the model or the phone sent. */
    public static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return !((String) v).isEmpty();
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
        if (v instanceof java.util.Collection) return !((java.util.Collection<?>) v).isEmpty();
        if (v instanceof Map) return !((Map<?, ?>) v).isEmpty();
        return true;
    }

    /** str(value or "").strip() */
    public static String strOf(Object v) {
        return truthy(v) ? String.valueOf(v).trim() : "";
    }

    /** Python's int(value): a whole number from a number or its text; null when it is neither. */
    public static Long intOf(Object v) {
        if (v instanceof Long || v instanceof Integer) return ((Number) v).longValue();
        if (v instanceof Number) return (long) ((Number) v).doubleValue();
        if (v instanceof Boolean) return (Boolean) v ? 1L : 0L;
        if (v instanceof String) try {
            return Long.parseLong(((String) v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    /** Python's float(value): null when it is not a number. */
    public static Double floatOf(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof Boolean) return (Boolean) v ? 1.0 : 0.0;
        if (v instanceof String) try {
            return Double.parseDouble(((String) v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    // ---------- difflib.SequenceMatcher(None, a, b).ratio() ----------

    /** The same number as Python's difflib (with its «autojunk» of popular characters in texts of 200+). */
    public static double ratio(String a, String b) {
        int[] x = cps(a), y = cps(b);
        int total = x.length + y.length;
        if (total == 0) return 1.0;
        return 2.0 * matches(x, y) / total;
    }

    static int[] cps(String s) {
        int[] out = new int[s.codePointCount(0, s.length())];
        for (int i = 0, k = 0; i < s.length(); k++) {
            out[k] = s.codePointAt(i);
            i += Character.charCount(out[k]);
        }
        return out;
    }

    private static int matches(int[] a, int[] b) {
        Map<Integer, List<Integer>> b2j = new HashMap<Integer, List<Integer>>();
        for (int j = 0; j < b.length; j++) {
            List<Integer> l = b2j.get(b[j]);
            if (l == null) b2j.put(b[j], l = new ArrayList<Integer>());
            l.add(j);
        }
        if (b.length >= 200) {
            int ntest = b.length / 100 + 1;
            Set<Integer> popular = new HashSet<Integer>();
            for (Map.Entry<Integer, List<Integer>> e : b2j.entrySet()) if (e.getValue().size() > ntest) popular.add(e.getKey());
            for (Integer p : popular) b2j.remove(p);
        }
        int total = 0;
        List<int[]> queue = new ArrayList<int[]>();
        queue.add(new int[]{0, a.length, 0, b.length});
        while (!queue.isEmpty()) {
            int[] q = queue.remove(queue.size() - 1);
            int alo = q[0], ahi = q[1], blo = q[2], bhi = q[3];
            int[] m = longest(a, b, b2j, alo, ahi, blo, bhi);
            int i = m[0], j = m[1], k = m[2];
            if (k > 0) {
                total += k;
                if (alo < i && blo < j) queue.add(new int[]{alo, i, blo, j});
                if (i + k < ahi && j + k < bhi) queue.add(new int[]{i + k, ahi, j + k, bhi});
            }
        }
        return total;
    }

    private static int[] longest(int[] a, int[] b, Map<Integer, List<Integer>> b2j, int alo, int ahi, int blo, int bhi) {
        int besti = alo, bestj = blo, bestsize = 0;
        Map<Integer, Integer> j2len = new HashMap<Integer, Integer>();
        for (int i = alo; i < ahi; i++) {
            Map<Integer, Integer> newj2len = new HashMap<Integer, Integer>();
            List<Integer> js = b2j.get(a[i]);
            if (js != null) {
                for (int j : js) {
                    if (j < blo) continue;
                    if (j >= bhi) break;
                    Integer prev = j2len.get(j - 1);
                    int k = (prev == null ? 0 : prev) + 1;
                    newj2len.put(j, k);
                    if (k > bestsize) {
                        besti = i - k + 1;
                        bestj = j - k + 1;
                        bestsize = k;
                    }
                }
            }
            j2len = newj2len;
        }
        // no junk is given: the junk-extension loops of difflib reduce to these two
        while (besti > alo && bestj > blo && a[besti - 1] == b[bestj - 1]) {
            besti--;
            bestj--;
            bestsize++;
        }
        while (besti + bestsize < ahi && bestj + bestsize < bhi && a[besti + bestsize] == b[bestj + bestsize]) bestsize++;
        return new int[]{besti, bestj, bestsize};
    }
}
