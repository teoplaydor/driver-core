package io.github.teoplaydor.semsearch.secretary;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.teoplaydor.semsearch.secretary.Sql.Row;

import static io.github.teoplaydor.semsearch.secretary.SecUtil.esc;
import static io.github.teoplaydor.semsearch.secretary.SecUtil.fmtMoney;
import static io.github.teoplaydor.semsearch.secretary.SecUtil.fromDb;
import static io.github.teoplaydor.semsearch.secretary.SecUtil.now;
import static io.github.teoplaydor.semsearch.secretary.SecUtil.shorten;

/**
 * The server version's finance.py: bank notifications → operations → reports. Confirmation codes are dropped at once
 * and never stored. The model reads the notification; the sum it gives must literally be in the text.
 */
public final class Finance {
    /** Python's \\s on text: no-break spaces too (banks write «350 ₽» with one). */
    static final String WS = "[\\s\\u00a0\\u2007\\u2009\\u202f]";
    static final Pattern SECRET_RE = Pattern.compile(
            "(код[\\p{L}\\p{N}_]*|code|парол[\\p{L}\\p{N}_]*|password|cvc|cvv)\\D{0,25}\\d{4,8}|никому" + WS + "+не" + WS + "+(сообщайте|говорите|называйте)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    static final String CUR = "(?:₽|руб\\.?|rub\\b|р\\.?(?![а-яё])|\\$|usd\\b|€|eur\\b)";
    static final Pattern AMOUNT_RE = Pattern.compile(
            "(?<![\\d.,])(\\d{1,3}(?:[\\u0020\\u00a0\\u202f]\\d{3})+|\\d+)(?:[.,](\\d{1,2}))?" + WS + "?" + CUR,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    static final List<String> SPENDING = Collections.singletonList("expense");
    static final List<String> INCOMING = java.util.Arrays.asList("income", "transfer_in", "refund");
    static final int SHOW_OPS = 4; // this many latest purchases under a category, the rest by a button
    static final String[] MONTHS = {"Январь", "Февраль", "Март", "Апрель", "Май", "Июнь", "Июль", "Август", "Сентябрь",
            "Октябрь", "Ноябрь", "Декабрь"};
    static final Map<String, String> DIRECTION_LABEL = new HashMap<String, String>();

    static {
        DIRECTION_LABEL.put("expense", "Покупка");
        DIRECTION_LABEL.put("income", "Доход");
        DIRECTION_LABEL.put("transfer_out", "Перевод");
        DIRECTION_LABEL.put("transfer_in", "Перевод вам");
        DIRECTION_LABEL.put("refund", "Возврат");
        DIRECTION_LABEL.put("other", "Операция");
    }

    public static boolean looksSecret(String text) {
        return SECRET_RE.matcher(text).find();
    }

    public static List<Double> findAmounts(String text) {
        List<Double> out = new ArrayList<Double>();
        Matcher m = AMOUNT_RE.matcher(text);
        while (m.find()) {
            String digits = m.group(1).replaceAll("\\D", "");
            String frac = m.group(2) == null ? "0" : m.group(2);
            out.add(Double.parseDouble(digits + "." + frac));
        }
        return out;
    }

    public static String merchantKey(String name) {
        String s = SecUtil.lower(name == null ? "" : name).replaceAll("[^a-zа-яё ]+", " ");
        List<String> words = new ArrayList<String>();
        for (String w : s.split(" ")) if (w.length() > 1) words.add(w);
        return SecUtil.head(SecUtil.join(" ", words), 60);
    }

    public static String textHash(String text) {
        return SecUtil.sha1Hex(SecUtil.joinWords(text));
    }

    static String guessDirection(String text) {
        String t = SecUtil.lower(text);
        if (t.contains("возврат") || t.contains("отмена")) return "refund";
        for (String w : new String[]{"зачислен", "поступлен", "пополнен", "зарплат", "перевод от"}) if (t.contains(w)) return "income";
        if (t.contains("перевод")) return "transfer_out";
        return "expense";
    }

    private final Secretary app;

    Finance(Secretary app) {
        this.app = app;
    }

    /** The operation's id, or -1 if the notification is not about money / a duplicate / a secret. */
    public long handleNotification(String text, String source, Instant posted) {
        text = SecUtil.head(text == null ? "" : text.trim(), 2000);
        if (text.isEmpty() || looksSecret(text)) return -1;
        List<Double> amounts = findAmounts(text);
        if (amounts.isEmpty()) return -1;
        SecDb db = app.db;
        String h = textHash(text);
        Instant ts = posted == null || posted.isAfter(now()) ? now() : posted;
        if (db.txSeenHash(h, ts)) return -1;
        if (source == null) source = "";

        boolean flagged = false;
        Map<String, Object> data;
        try {
            data = app.llm.json(false, false, Prompts.fill(Prompts.FIN_SYSTEM, "categories", SecUtil.join(", ", Prompts.FIN_CATEGORIES)),
                    Prompts.fill(Prompts.FIN_USER, "source", source.isEmpty() ? "неизвестно" : source, "text", text), Prompts.FIN_SCHEMA);
        } catch (LlmError e) {
            Secretary.log("reading a bank notification failed: " + e.getMessage());
            data = new HashMap<String, Object>();
            data.put("is_transaction", true);
            data.put("direction", guessDirection(text));
            data.put("amount", amounts.get(0));
            data.put("currency", "RUB");
            data.put("merchant", "");
            data.put("category", "Другое");
            data.put("card", "");
            flagged = true;
        }
        if (!SecUtil.truthy(data.get("is_transaction"))) return -1;
        Double a = SecUtil.truthy(data.get("amount")) ? SecUtil.floatOf(data.get("amount")) : Double.valueOf(0);
        double amount = a == null ? 0.0 : Math.abs(a);
        String currency = SecUtil.head(SecUtil.truthy(data.get("currency")) ? String.valueOf(data.get("currency")) : "RUB", 3)
                .toUpperCase(Locale.ROOT);
        // the model's sum must literally be in the text
        boolean found = false;
        for (double x : amounts) if (Math.abs(x - amount) < 0.01) found = true;
        if (!found) {
            flagged = true;
            if ("RUB".equals(currency) || amount <= 0) amount = amounts.get(0);
        }
        Object d = data.get("direction");
        String direction = d instanceof String && Prompts.FIN_DIRECTIONS.contains(d) ? (String) d : guessDirection(text);
        String merchant = SecUtil.head(SecUtil.strOf(data.get("merchant")), 100);
        String key = merchantKey(merchant);
        String category = null;
        if (!key.isEmpty()) {
            category = db.merchantCategory(key);
            if (category == null) category = db.lastCategory(key);
        }
        if (category == null) {
            Object c = data.get("category");
            category = c instanceof String && Prompts.FIN_CATEGORIES.contains(c) ? (String) c : "Другое";
        }
        if (db.txDuplicate(amount, direction, key, ts)) return -1;
        String card = SecUtil.head(SecUtil.strOf(data.get("card")), 20);
        String cl = SecUtil.lower(card);
        if (cl.equals("null") || cl.equals("none") || cl.equals("нет") || cl.equals("-")) card = "";
        long txId = db.addTx(ts, SecUtil.head(source, 50), direction, amount, currency, merchant, key, category, card, flagged, text, h);

        String cur = "RUB".equals(currency) ? "" : " " + currency;
        if ("expense".equals(direction) && "RUB".equals(currency) && amount >= app.cfg.largeExpense) {
            app.send("💸 <b>Крупное списание:</b> " + fmtMoney(amount) + " — " + esc(merchant.isEmpty() ? "без названия" : merchant)
                    + " (" + esc(category) + ")");
        } else if (flagged) {
            String amt = SecUtil.groupThousands(SecUtil.fixed(amount, 2));
            app.send("🧐 Не уверен, что правильно понял операцию: " + DIRECTION_LABEL.get(direction) + " " + amt
                    + (cur.isEmpty() ? " ₽" : cur) + " — " + esc(merchant) + ".\nТекст: <i>" + esc(SecUtil.head(text, 300)) + "</i>");
        }
        return txId;
    }

    // ---------- reports ----------

    /** Sums in rubles: [spending by category, sums by kind of operation, spending by merchant]. */
    @SuppressWarnings("unchecked")
    private Map<String, Double>[] sums(Instant start, Instant end) {
        Map<String, Double> byCat = new LinkedHashMap<String, Double>(), byDir = new LinkedHashMap<String, Double>(),
                byMerchant = new LinkedHashMap<String, Double>();
        for (Row tx : app.db.txBetween(start, end)) {
            if (!"RUB".equals(tx.text("currency"))) continue;
            add(byDir, tx.text("direction"), tx.dbl("amount"));
            if (SPENDING.contains(tx.text("direction"))) {
                add(byCat, tx.text("category"), tx.dbl("amount"));
                add(byMerchant, tx.truthy("merchant") ? tx.text("merchant") : "без названия", tx.dbl("amount"));
            }
        }
        return new Map[]{byCat, byDir, byMerchant};
    }

    private static void add(Map<String, Double> m, String k, double v) {
        Double old = m.get(k);
        m.put(k, (old == null ? 0 : old) + v);
    }

    private static double g(Map<String, Double> m, String k) {
        Double v = m.get(k);
        return v == null ? 0 : v;
    }

    /** Largest first (a stable sort, as Python's sorted). */
    private static List<Map.Entry<String, Double>> desc(Map<String, Double> m) {
        List<Map.Entry<String, Double>> l = new ArrayList<Map.Entry<String, Double>>(m.entrySet());
        Collections.sort(l, new Comparator<Map.Entry<String, Double>>() {
            public int compare(Map.Entry<String, Double> a, Map.Entry<String, Double> b) {
                return Double.compare(b.getValue(), a.getValue());
            }
        });
        return l;
    }

    private Instant[] dayBounds(int offset) {
        ZonedDateTime loc = now().atZone(app.cfg.tz);
        ZonedDateTime start = loc.toLocalDate().atStartOfDay(app.cfg.tz).plusDays(offset);
        return new Instant[]{start.toInstant(), start.plusDays(1).toInstant()};
    }

    private Instant monthStart() {
        ZonedDateTime loc = now().atZone(app.cfg.tz);
        return loc.toLocalDate().withDayOfMonth(1).atStartOfDay(app.cfg.tz).toInstant();
    }

    public String dayLine() {
        Instant[] b = dayBounds(0);
        Map<String, Double>[] s = sums(b[0], b[1]);
        double total = g(s[1], "expense");
        if (total == 0) return null;
        List<String> parts = new ArrayList<String>();
        for (Map.Entry<String, Double> e : desc(s[0]).subList(0, Math.min(3, s[0].size())))
            parts.add(esc(e.getKey()) + " " + fmtMoney(e.getValue()));
        return "💸 Сегодня потрачено: <b>" + fmtMoney(total) + "</b> (" + SecUtil.join(", ", parts) + ")";
    }

    public String monthReport() {
        Map<String, Double>[] s = sums(monthStart(), now().plusSeconds(1));
        if (s[1].isEmpty())
            return "За этот месяц операций пока нет. Если телефон уже пересылает уведомления банков — они появятся после первой покупки.";
        List<String> lines = new ArrayList<String>();
        lines.add("<b>💸 Расходы с 1 числа:</b> " + fmtMoney(g(s[1], "expense")));
        for (Map.Entry<String, Double> e : desc(s[0])) lines.add("• " + esc(e.getKey()) + ": " + fmtMoney(e.getValue()));
        List<Map.Entry<String, Double>> top = desc(s[2]);
        top = top.subList(0, Math.min(5, top.size()));
        if (!top.isEmpty()) {
            lines.add("");
            lines.add("Больше всего:");
            for (Map.Entry<String, Double> e : top) lines.add("• " + esc(e.getKey()) + ": " + fmtMoney(e.getValue()));
        }
        lines.add("");
        extra(lines, s[1]);
        return SecUtil.join("\n", lines).trim();
    }

    private static void extra(List<String> lines, Map<String, Double> byDir) {
        if (g(byDir, "transfer_out") != 0) lines.add("Переводы людям: " + fmtMoney(g(byDir, "transfer_out")));
        if (g(byDir, "refund") != 0) lines.add("Возвраты: " + fmtMoney(g(byDir, "refund")));
        double in = g(byDir, "income") + g(byDir, "transfer_in");
        if (in != 0) lines.add("Поступления: " + fmtMoney(in));
    }

    public String weekReport() {
        Instant end = dayBounds(1)[0];
        Map<String, Double>[] cur = sums(end.minus(Duration.ofDays(7)), end), prev = sums(end.minus(Duration.ofDays(14)), end.minus(Duration.ofDays(7)));
        double total = g(cur[1], "expense"), before = g(prev[1], "expense");
        if (total == 0) return null;
        String head = "<b>📊 Неделя:</b> потрачено " + fmtMoney(total);
        if (before != 0) {
            double diff = (total - before) / before * 100;
            head += " (" + (diff >= 0 ? "+" : "") + SecUtil.fixed(diff, 0) + "% к прошлой)";
        }
        List<String> lines = new ArrayList<String>();
        lines.add(head);
        for (Map.Entry<String, Double> e : desc(cur[0])) lines.add("• " + esc(e.getKey()) + ": " + fmtMoney(e.getValue()));
        return SecUtil.join("\n", lines);
    }

    /** Money in short — for the conversation with the owner. */
    public String contextSummary() {
        Map<String, Double>[] s = sums(monthStart(), now().plusSeconds(1));
        Instant[] b = dayBounds(0);
        Map<String, Double>[] today = sums(b[0], b[1]);
        double todaySum = 0;
        for (double v : today[0].values()) todaySum += v;
        List<String> cats = new ArrayList<String>();
        for (Map.Entry<String, Double> e : desc(s[0])) cats.add(e.getKey() + " " + SecUtil.fixed(e.getValue(), 0));
        return "Расходы: сегодня " + SecUtil.fixed(todaySum, 0) + " ₽; с начала месяца " + SecUtil.fixed(g(s[1], "expense"), 0) + " ₽ ("
                + (cats.isEmpty() ? "нет данных" : SecUtil.join(", ", cats)) + "); переводы людям " + SecUtil.fixed(g(s[1], "transfer_out"), 0)
                + " ₽; поступления " + SecUtil.fixed(g(s[1], "income") + g(s[1], "transfer_in"), 0) + " ₽.";
    }

    // ---------- the app's screens: in detail, by operation ----------

    /** «25.10 18:41  Самокат · Продукты — −627 ₽». */
    public String opLine(Row tx, boolean withDay, boolean withCategory) {
        ZonedDateTime loc = fromDb(tx.text("ts")).atZone(app.cfg.tz);
        String when = withDay ? SecUtil.dmhm(loc) : SecUtil.hm(loc);
        String label = DIRECTION_LABEL.get(tx.text("direction"));
        String who = esc(shorten(tx.truthy("merchant") ? tx.text("merchant") : label != null ? label : "Операция", 40));
        if (withCategory && SPENDING.contains(tx.text("direction"))) who += " · " + esc(tx.text("category"));
        String amount = "RUB".equals(tx.text("currency")) ? fmtMoney(tx.dbl("amount"))
                : SecUtil.groupThousands(SecUtil.fixed(tx.dbl("amount"), 2)) + " " + tx.text("currency");
        String sign = INCOMING.contains(tx.text("direction")) ? "+" : "−";
        String flag = tx.truthy("flagged") ? " ❔" : "";
        return when + "  " + who + " — <b>" + sign + amount + "</b>" + flag;
    }

    static Map<String, Object> block(String html, Kb kb) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("html", html);
        m.put("buttons", Kb.list(kb));
        return m;
    }

    /** The «Расходы» tab: the month's total, for each category its latest purchases, transfers apart. */
    public List<Map<String, Object>> screenMonth() {
        Instant start = monthStart(), end = now().plusSeconds(1);
        List<Row> txs = app.db.txRecent(start, end, null, 300);
        List<Map<String, Object>> blocks = new ArrayList<Map<String, Object>>();
        if (txs.isEmpty()) {
            blocks.add(block(monthReport(), null));
            return blocks;
        }
        Map<String, Double>[] s = sums(start, end);
        List<String> head = new ArrayList<String>();
        head.add("<b>" + MONTHS[start.atZone(app.cfg.tz).getMonthValue() - 1] + ":</b> потрачено <b>" + fmtMoney(g(s[1], "expense")) + "</b>");
        extra(head, s[1]);
        blocks.add(block(SecUtil.join("\n", head), null));
        for (Map.Entry<String, Double> e : desc(s[0])) {
            String cat = e.getKey();
            List<Row> ops = new ArrayList<Row>();
            for (Row t : txs)
                if (SPENDING.contains(t.text("direction")) && cat.equals(t.text("category")) && "RUB".equals(t.text("currency"))) ops.add(t);
            List<String> lines = new ArrayList<String>();
            lines.add("<b>" + esc(cat) + "</b> — " + fmtMoney(e.getValue()));
            for (Row t : ops.subList(0, Math.min(SHOW_OPS, ops.size()))) lines.add(opLine(t, true, false));
            Kb kb = null;
            if (ops.size() > SHOW_OPS) {
                lines.add("<i>и ещё " + (ops.size() - SHOW_OPS) + "</i>");
                int idx = Prompts.FIN_CATEGORIES.contains(cat) ? Prompts.FIN_CATEGORIES.indexOf(cat) : Prompts.FIN_CATEGORIES.size() - 1;
                kb = new Kb().row("Все " + ops.size() + " операций", "cat:" + idx);
            }
            blocks.add(block(SecUtil.join("\n", lines), kb));
        }
        List<Row> other = new ArrayList<Row>();
        for (Row t : txs) if (!SPENDING.contains(t.text("direction"))) other.add(t);
        if (!other.isEmpty()) {
            List<String> lines = new ArrayList<String>();
            lines.add("<b>Переводы и поступления</b>");
            for (Row t : other.subList(0, Math.min(SHOW_OPS, other.size()))) lines.add(opLine(t, true, false));
            blocks.add(block(SecUtil.join("\n", lines), null));
        }
        blocks.add(block("", new Kb().row("📜 Вся история операций", "ops:0")));
        return blocks;
    }

    /** [title, blocks] */
    public Object[] screenCategory(int idx) {
        String cat = idx >= 0 && idx < Prompts.FIN_CATEGORIES.size() ? Prompts.FIN_CATEGORIES.get(idx) : "Другое";
        List<Row> ops = app.db.txRecent(monthStart(), now().plusSeconds(1), cat, 300);
        List<Map<String, Object>> blocks = new ArrayList<Map<String, Object>>();
        if (ops.isEmpty()) {
            blocks.add(block("В этом месяце покупок в этой категории нет.", null));
            return new Object[]{cat, blocks};
        }
        double total = 0;
        for (Row t : ops) if ("RUB".equals(t.text("currency"))) total += t.dbl("amount");
        blocks.add(block("<b>" + esc(cat) + "</b> с 1 числа — " + fmtMoney(total), null));
        blocks.addAll(byDay(ops, false));
        return new Object[]{cat, blocks};
    }

    /** All operations of two months by day: purchases, transfers, incomes, refunds. */
    public List<Map<String, Object>> screenHistory() {
        List<Row> ops = app.db.txRecent(now().minus(Duration.ofDays(60)), now().plusSeconds(1), null, 300);
        if (ops.isEmpty()) {
            List<Map<String, Object>> b = new ArrayList<Map<String, Object>>();
            b.add(block("Операций пока нет.", null));
            return b;
        }
        return byDay(ops, true);
    }

    private List<Map<String, Object>> byDay(List<Row> ops, boolean withCategory) {
        ZoneId tz = app.cfg.tz;
        LocalDate today = now().atZone(tz).toLocalDate();
        Map<LocalDate, List<Row>> days = new LinkedHashMap<LocalDate, List<Row>>();
        for (Row t : ops) {
            LocalDate d = fromDb(t.text("ts")).atZone(tz).toLocalDate();
            List<Row> l = days.get(d);
            if (l == null) days.put(d, l = new ArrayList<Row>());
            l.add(t);
        }
        List<Map<String, Object>> blocks = new ArrayList<Map<String, Object>>();
        for (Map.Entry<LocalDate, List<Row>> e : days.entrySet()) {
            LocalDate day = e.getKey();
            String label = day.equals(today) ? "Сегодня" : day.equals(today.minusDays(1)) ? "Вчера"
                    : SecUtil.two(day.getDayOfMonth()) + "." + SecUtil.two(day.getMonthValue()) + ", " + SecUtil.WEEKDAYS[day.getDayOfWeek().getValue() - 1];
            double spent = 0;
            for (Row t : e.getValue()) if (SPENDING.contains(t.text("direction")) && "RUB".equals(t.text("currency"))) spent += t.dbl("amount");
            List<String> lines = new ArrayList<String>();
            lines.add("<b>" + label + "</b>" + (spent != 0 ? " — потрачено " + fmtMoney(spent) : ""));
            for (Row t : e.getValue()) lines.add(opLine(t, false, withCategory));
            blocks.add(block(SecUtil.join("\n", lines), null));
        }
        return blocks;
    }
}
