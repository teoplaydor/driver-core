package io.github.teoplaydor.semsearch.secretary;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import io.github.teoplaydor.semsearch.core.MiniJson;
import io.github.teoplaydor.semsearch.secretary.Sql.Row;

import static io.github.teoplaydor.semsearch.secretary.SecUtil.esc;
import static io.github.teoplaydor.semsearch.secretary.SecUtil.fmtDt;
import static io.github.teoplaydor.semsearch.secretary.SecUtil.fromDb;
import static io.github.teoplaydor.semsearch.secretary.SecUtil.now;
import static io.github.teoplaydor.semsearch.secretary.SecUtil.shorten;

/**
 * The server version's tasks.py: the owner's tasks. They come from mails, messages and notifications («забрать заказ»,
 * «ответить Анне») or by the owner's request. At unlock the phone asks «Сделал?»: Сделал / Отложить / Принял.
 */
public final class Tasks {
    static final double AUTO_CLOSE_CONFIDENCE = 0.8; // lower — not closed by itself but asked «done?»
    /** Tasks that are done by opening the chat. */
    static final Pattern READ_ONLY = Pattern.compile("^\\s*(прослушать|прочитать|посмотреть|ознакомиться|проверить сообщ)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    static final Map<String, String> SNOOZE_LABELS = new LinkedHashMap<String, String>();

    static {
        SNOOZE_LABELS.put("30m", "через 30 минут");
        SNOOZE_LABELS.put("2h", "через 2 часа");
        SNOOZE_LABELS.put("evening", "вечером");
        SNOOZE_LABELS.put("tomorrow", "завтра утром");
    }

    static final LocalTime EVENING = LocalTime.of(19, 0), MORNING = LocalTime.of(9, 0);
    static final Duration FIRST_ASK_AFTER = Duration.ofMinutes(15); // just told of a task — not «done?» right away
    // a task with a deadline: reminded not at once but in the calmest evening before it
    static final LocalTime CALM_EVENING = LocalTime.of(19, 0), LATE_EVENING = LocalTime.of(21, 0);
    static final int WAKE_HOUR = 8;
    static final Duration LAST_CALL = Duration.ofHours(6); // no calm evening before the deadline — 6 hours before it
    static final int TASK_WEIGHT = 10, EVENT_WEIGHT = 15; // a task or an event that day «weighs» like this many notifications
    static final int EVENING_FROM = 17, EVENING_TO = 23; // hours 17..22
    static final Duration HISTORY = Duration.ofDays(28);
    static final int MAX_EVENINGS = 14;

    public static Instant snoozeUntil(String choice, Instant now, ZoneId tz) {
        ZonedDateTime loc = now.atZone(tz);
        if ("30m".equals(choice)) return now.plus(Duration.ofMinutes(30));
        if ("evening".equals(choice)) {
            ZonedDateTime at = ZonedDateTime.of(loc.toLocalDate(), EVENING, tz);
            return Duration.between(loc, at).compareTo(Duration.ofMinutes(30)) > 0 ? at.toInstant() : at.plusDays(1).toInstant();
        }
        if ("tomorrow".equals(choice)) return ZonedDateTime.of(loc.toLocalDate().plusDays(1), MORNING, tz).toInstant();
        return now.plus(Duration.ofHours(2));
    }

    /** «Принял» — ask nearer the deadline (2 hours before), without one — in 3 hours. */
    public static Instant acceptUntil(Instant due, Instant now) {
        if (due != null && due.minus(Duration.ofHours(2)).isAfter(now.plus(Duration.ofMinutes(30)))) return due.minus(Duration.ofHours(2));
        if (due != null && due.isAfter(now.plus(Duration.ofMinutes(30)))) return due.minus(Duration.ofMinutes(15));
        return now.plus(Duration.ofHours(3));
    }

    private final Secretary app;

    Tasks(Secretary app) {
        this.app = app;
    }

    /** A mail or a chat read: if something is needed from the owner — a task. [id, created 1/0] or null. */
    public long[] fromItem(long itemId) {
        Row row = app.db.getMail(itemId);
        if (row == null || row.lng("importance") < 1) return null;
        String title = row.truthy("action") ? row.text("action") : row.truthy("needs_reply") ? "Ответить: " + Views.sender(row) : "";
        if (title.isEmpty() || alreadyHandled(row, title)) return null;
        Instant now = now();
        Instant due = SecDb.at(row, "deadline");
        Instant remind = due != null && due.isAfter(now) ? plan(due, now) : now.plus(FIRST_ASK_AFTER);
        return app.db.addTask(shorten(title, 200), row.text("summary"), row.text("account") + " · " + Views.sender(row),
                row.text("from_addr"), itemId, due, remind);
    }

    /** «A new task» with buttons in case it is already done or not needed: [html, Kb]. */
    public Object[] announce(long taskId) {
        Row t = app.db.getTask(taskId);
        ZoneId tz = app.cfg.tz;
        String text = "📝 <b>Новое дело:</b> " + esc(t.text("title"));
        if (t.truthy("due_at")) text += " — до " + fmtDt(fromDb(t.text("due_at")), tz);
        if (t.truthy("source")) text += "\n<i>" + esc(t.text("source")) + "</i>";
        Instant remind = fromDb(t.text("remind_at"));
        if (t.truthy("due_at") && Duration.between(now(), remind).compareTo(Duration.ofHours(1)) > 0)
            text += "\nНапомню " + fmtDt(remind, tz) + " — в самое спокойное время до срока.";
        else text += "\nСпрошу позже, сделал ли.";
        return new Object[]{text, Views.newTaskKb(taskId)};
    }

    /** While the chat was being read the owner already answered or opened it — «listen/read» is not needed. */
    private boolean alreadyHandled(Row row, String title) {
        if (!Views.isChat(row)) return false;
        List<Row> signals = app.db.signalsSince(row.text("from_addr"), fromDb(row.text("received_at")));
        boolean opened = false;
        for (Row s : signals) {
            if ("replied".equals(s.text("kind"))) return true;
            if ("opened".equals(s.text("kind"))) opened = true;
        }
        return opened && READ_ONLY.matcher(title).find();
    }

    public long[] add(String title, Instant due) {
        Instant remind = due != null && due.isAfter(now()) ? plan(due, null) : null;
        return app.db.addTask(shorten(title, 200), "", "по твоей просьбе", "", null, due, remind);
    }

    // ---------- when to remind of a task with a deadline ----------

    /**
     * Not the same day: in the calmest evening before the deadline — the fewest notifications, tasks and events.
     * No calm evening (all busy or the deadline near) — 6 hours before it.
     */
    public Instant plan(Instant due, Instant now) {
        if (now == null) now = now();
        if (Duration.between(now, due).compareTo(FIRST_ASK_AFTER) <= 0) return now;
        ZoneId tz = app.cfg.tz;
        Instant lastCall = awake(due.minus(LAST_CALL), now);
        List<Instant> evenings = new ArrayList<Instant>();
        LocalDate day = now.atZone(tz).toLocalDate().plusDays(1);
        while (evenings.size() < MAX_EVENINGS) {
            Instant at = ZonedDateTime.of(day, CALM_EVENING, tz).toInstant();
            if (at.isAfter(due.minus(LAST_CALL))) break;
            evenings.add(at);
            day = day.plusDays(1);
        }
        if (evenings.isEmpty()) return lastCall;
        Map<Instant, Double> load = new HashMap<Instant, Double>();
        double typical = eveningLoad(evenings, now, load);
        Instant best = null;
        for (Instant at : evenings) {
            if (load.get(at) > typical + TASK_WEIGHT) continue;
            if (best == null || load.get(at) < load.get(best)) best = at; // ties: the earlier evening (list order)
        }
        return best != null ? best : lastCall;
    }

    /** Not at night: from 23:00 to 8 in the morning — moved to 21:00 the evening before. */
    private Instant awake(Instant at, Instant now) {
        ZoneId tz = app.cfg.tz;
        ZonedDateTime loc = at.atZone(tz);
        if (loc.getHour() < WAKE_HOUR) at = ZonedDateTime.of(loc.toLocalDate().minusDays(1), LATE_EVENING, tz).toInstant();
        else if (loc.getHour() >= 23) at = ZonedDateTime.of(loc.toLocalDate(), LATE_EVENING, tz).toInstant();
        Instant min = now.plus(FIRST_ASK_AFTER);
        return at.isAfter(min) ? at : min;
    }

    /**
     * Each evening's load: how many notifications usually come that weekday evening plus the tasks and events already
     * set for that day. Returns how many notifications a usual evening has.
     */
    private double eveningLoad(List<Instant> evenings, Instant now, Map<Instant, Double> load) {
        SecDb db = app.db;
        ZoneId tz = app.cfg.tz;
        Map<LocalDate, Integer> perDay = new HashMap<LocalDate, Integer>();
        LocalDate first = null;
        for (String posted : db.phonePostedSince(now.minus(HISTORY))) {
            ZonedDateTime loc = fromDb(posted).atZone(tz);
            if (first == null) first = loc.toLocalDate();
            if (loc.getHour() >= EVENING_FROM && loc.getHour() < EVENING_TO) inc(perDay, loc.toLocalDate(), 1);
        }
        LocalDate today = now.atZone(tz).toLocalDate();
        List<LocalDate> days = new ArrayList<LocalDate>();
        if (first != null) for (LocalDate d = first; d.isBefore(today); d = d.plusDays(1)) days.add(d);
        double typical = 0;
        Map<Integer, List<Integer>> byWeekday = new HashMap<Integer, List<Integer>>();
        for (LocalDate d : days) {
            int n = get(perDay, d);
            typical += n;
            List<Integer> l = byWeekday.get(d.getDayOfWeek().getValue());
            if (l == null) byWeekday.put(d.getDayOfWeek().getValue(), l = new ArrayList<Integer>());
            l.add(n);
        }
        typical = days.isEmpty() ? 0.0 : typical / days.size();

        Map<LocalDate, Integer> busy = new HashMap<LocalDate, Integer>();
        for (Row t : db.openTasks(500))
            for (String col : new String[]{"remind_at", "due_at"})
                if (t.truthy(col)) inc(busy, fromDb(t.text(col)).atZone(tz).toLocalDate(), TASK_WEIGHT);
        for (Row e : db.eventsBetween(evenings.get(0).minus(Duration.ofDays(1)), evenings.get(evenings.size() - 1).plus(Duration.ofDays(1))))
            inc(busy, fromDb(e.text("start_at")).atZone(tz).toLocalDate(), EVENT_WEIGHT);

        for (Instant at : evenings) {
            ZonedDateTime z = at.atZone(tz);
            List<Integer> same = byWeekday.get(z.getDayOfWeek().getValue());
            double base;
            if (same != null && !same.isEmpty()) {
                double s = 0;
                for (int v : same) s += v;
                base = s / same.size();
            } else base = typical;
            load.put(at, base + get(busy, z.toLocalDate()));
        }
        return typical;
    }

    private static void inc(Map<LocalDate, Integer> m, LocalDate k, int by) {
        m.put(k, get(m, k) + by);
    }

    private static int get(Map<LocalDate, Integer> m, LocalDate k) {
        Integer v = m.get(k);
        return v == null ? 0 : v;
    }

    /** done / drop / accept / snooze (choice) / asked. A short answer for the owner. */
    public String act(long taskId, String action, String choice) {
        String r = actInner(taskId, action, choice);
        Secretary.log("tasks: #" + taskId + " " + action + (choice == null || choice.isEmpty() ? "" : " " + choice) + " → " + r);
        return r;
    }

    private String actInner(long taskId, String action, String choice) {
        SecDb db = app.db;
        ZoneId tz = app.cfg.tz;
        Row t = db.getTask(taskId);
        if (t == null) return "Не нашёл это дело.";
        Instant now = now();
        String title = esc(shorten(t.text("title"), 80));
        if ("asked".equals(action)) {
            db.updateTask(taskId, "asked_at", now);
            return "";
        }
        if ("done".equals(t.text("status")) || "dropped".equals(t.text("status"))) return "Это дело уже закрыто: " + title;
        if ("done".equals(action)) {
            db.updateTask(taskId, "status", "done");
            if (t.truthy("item_id")) db.setMailStatus(t.lng("item_id"), "done");
            return "✅ Готово: " + title;
        }
        if ("drop".equals(action)) {
            db.updateTask(taskId, "status", "dropped");
            return "✖️ Убрал: " + title;
        }
        if ("accept".equals(action)) {
            Instant due = SecDb.at(t, "due_at");
            // the deadline is far — ask in a calm evening before it, not at once and not at the last moment
            Instant at = due != null && Duration.between(now, due).compareTo(LAST_CALL) > 0 ? plan(due, now) : acceptUntil(due, now);
            db.updateTask(taskId, "status", "accepted", "remind_at", at, "asked_at", null);
            return "👍 Принято: " + title + ". Спрошу " + fmtDt(at, tz, now) + ".";
        }
        if ("snooze".equals(action)) {
            Instant at = snoozeUntil(choice, now, tz);
            db.updateTask(taskId, "remind_at", at, "asked_at", null);
            return "⏰ Отложил: " + title + ". Спрошу " + fmtDt(at, tz, now) + ".";
        }
        return "Не понял, что сделать с делом.";
    }

    public List<Row> due(Instant now) {
        return app.db.dueTasks(now == null ? now() : now, 30);
    }

    /** A hint for «Сделал?»: what was seen (for example, that the chat was opened). */
    public String hint(Row t) {
        if (!t.truthy("source_key")) return "";
        Row last = null;
        for (Row s : app.db.signalsSince(t.text("source_key"), fromDb(t.text("created_at"))))
            if ("opened".equals(s.text("kind"))) last = s;
        return last != null ? "Ты открывал этот чат " + fmtDt(fromDb(last.text("at")), app.cfg.tz) : "";
    }

    public Map<String, Object> asJson(Row t) {
        ZoneId tz = app.cfg.tz;
        Row item = t.truthy("item_id") ? app.db.getMail(t.lng("item_id")) : null;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", t.lng("id"));
        m.put("title", t.text("title"));
        m.put("details", t.text("details"));
        m.put("source", t.text("source"));
        m.put("due", t.truthy("due_at") ? fmtDt(fromDb(t.text("due_at")), tz) : "");
        m.put("status", t.text("status"));
        m.put("hint", hint(t));
        m.put("package", item != null && Views.isChat(item) ? item.text("folder") : ""); // the phone shows its app's icon
        return m;
    }

    // ---------- is a task done — by evidence ----------

    /** Close by itself and quietly say so with «Вернуть» — in case it was wrong. */
    public void closeAuto(Row t, String reason) {
        app.db.updateTask(t.lng("id"), "status", "done");
        String text = "🤖 Закрыл сам: «" + esc(shorten(t.text("title"), 100)) + "»";
        if (reason != null && !reason.isEmpty()) text += " — " + esc(shorten(reason, 160));
        app.send(text, Views.reopenKb(t.lng("id")), "app");
    }

    public String reopen(long taskId) {
        Row t = app.db.getTask(taskId);
        if (t == null) return "Не нашёл это дело.";
        app.db.updateTask(taskId, "status", "open", "remind_at", now().plus(FIRST_ASK_AFTER), "asked_at", null);
        return "↩️ Вернул дело: " + esc(shorten(t.text("title"), 100));
    }

    /** Look at the evidence of a chat or a sender and close the tasks that are clearly done. */
    public int resolve(String sourceKey) {
        SecDb db = app.db;
        List<Row> tasks = db.openTasksFor(sourceKey);
        if (tasks.isEmpty()) return 0;
        Instant since = null;
        for (Row t : tasks) {
            Instant c = fromDb(t.text("created_at"));
            if (since == null || c.isBefore(since)) since = c;
        }
        since = since.minus(Duration.ofMinutes(1));
        ZoneId tz = app.cfg.tz;
        Instant now = now();
        List<String> evidence = new ArrayList<String>();
        for (Row m : db.phoneMsgsForKey(sourceKey, since, 40))
            evidence.add("[" + SecUtil.dmhm(fromDb(m.text("posted_at")).atZone(tz)) + "] " + m.text("sender") + ": "
                    + SecUtil.head(m.text("text"), 400));
        List<Row> signals = db.signalsSince(sourceKey, since);
        Map<String, String> names = new HashMap<String, String>();
        names.put("opened", "владелец открыл чат");
        names.put("dismissed", "владелец смахнул уведомление");
        names.put("replied", "владелец ответил");
        for (Row s : signals) {
            String n = names.get(s.text("kind"));
            String line = "[" + SecUtil.dmhm(fromDb(s.text("at")).atZone(tz)) + "] " + (n != null ? n : s.text("kind"));
            evidence.add(line + (s.truthy("text") ? ": " + SecUtil.head(s.text("text"), 300) : ""));
        }
        if (evidence.isEmpty()) return 0;
        List<String> listing = new ArrayList<String>();
        for (Row t : tasks)
            listing.add("[" + t.lng("id") + "] " + t.text("title") + (t.truthy("source") ? " (" + t.text("source") + ")" : ""));
        Map<Long, Map<String, Object>> verdicts = new HashMap<Long, Map<String, Object>>();
        try {
            Map<String, Object> data = app.llm.json(false, false, Prompts.RESOLVE_SYSTEM,
                    Prompts.fill(Prompts.RESOLVE_USER, "now", SecUtil.isoMinutes(now, tz), "tasks", SecUtil.join("\n", listing),
                            "evidence", SecUtil.join("\n", evidence.subList(Math.max(0, evidence.size() - 40), evidence.size()))),
                    Prompts.RESOLVE_SCHEMA);
            List<Object> vs = MiniJson.arr(data.get("tasks"));
            if (vs != null) for (Object o : vs) {
                Map<String, Object> v = MiniJson.obj(o);
                Long id = v == null ? null : SecUtil.intOf(v.get("id"));
                if (id != null) verdicts.put(id, v);
            }
        } catch (LlmError e) {
            // the model unavailable — only the obvious: a task «ответить» and the owner wrote into the chat
            boolean replied = false;
            for (Row s : signals) if ("replied".equals(s.text("kind"))) replied = true;
            for (String e2 : evidence) {
                int k = e2.indexOf("] ");
                if ((k >= 0 ? e2.substring(k + 2) : e2).startsWith("Я:")) replied = true;
            }
            verdicts.clear();
            for (Row t : tasks)
                if (replied && SecUtil.lower(t.text("title")).startsWith("ответить")) {
                    Map<String, Object> v = new HashMap<String, Object>();
                    v.put("status", "done");
                    v.put("confidence", 0.9);
                    v.put("reason", "ты ответил в этом чате");
                    verdicts.put(t.lng("id"), v);
                }
        }
        Secretary.log("tasks: judged " + tasks.size() + " task(s) of " + sourceKey + " on " + evidence.size() + " piece(s) of evidence: " + verdicts);
        int closed = 0;
        for (Row t : tasks) {
            Map<String, Object> v = verdicts.get(t.lng("id"));
            if (v == null || !"done".equals(v.get("status"))) continue;
            Double conf = SecUtil.floatOf(v.get("confidence"));
            if (conf != null && conf >= AUTO_CLOSE_CONFIDENCE) {
                closeAuto(t, SecUtil.strOf(v.get("reason")));
                closed++;
            }
        }
        return closed;
    }
}
