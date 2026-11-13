package io.github.teoplaydor.semsearch.secretary;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.teoplaydor.semsearch.secretary.Sql.Row;

import static io.github.teoplaydor.semsearch.secretary.SecUtil.esc;
import static io.github.teoplaydor.semsearch.secretary.SecUtil.fmtDt;
import static io.github.teoplaydor.semsearch.secretary.SecUtil.fromDb;
import static io.github.teoplaydor.semsearch.secretary.SecUtil.now;
import static io.github.teoplaydor.semsearch.secretary.SecUtil.shorten;

/** The server version's digest.py: in the morning — what today holds, in the evening — what was and what tomorrow is. No model. */
public final class Digests {
    private Digests() {
    }

    static final int MAX_IMPORTANT = 10, MAX_INFO = 8;

    static Instant dayStart(Instant now, ZoneId tz, int offsetDays) {
        return now.atZone(tz).toLocalDate().atStartOfDay(tz).plusDays(offsetDays).toInstant();
    }

    /** kind: morning | evening. [html, Kb] or null — nothing to tell. */
    public static Object[] build(Secretary app, String kind, Instant now) {
        SecDb db = app.db;
        ZoneId tz = app.cfg.tz;
        if (now == null) now = now();
        String last = db.kvGet("last_digest_at");
        Instant since = last != null ? fromDb(last) : now.minus(Duration.ofHours(24));

        List<Row> mails = db.mailsSince(since);
        List<Row> important = new ArrayList<Row>(), info = new ArrayList<Row>();
        int junk = 0;
        for (Row m : mails) {
            if (m.isNull("importance")) continue;
            long imp = m.lng("importance");
            if (imp == 2 && !m.truthy("notified")) important.add(m);
            if (imp == 1) info.add(m);
            if (imp == 0) junk++;
        }
        List<Row> openItems = db.openTasks(8);

        List<Row> events;
        String eventsTitle;
        if ("morning".equals(kind)) {
            events = db.eventsBetween(now, dayStart(now, tz, 1));
            eventsTitle = "📅 Сегодня";
        } else {
            events = db.eventsBetween(dayStart(now, tz, 1), dayStart(now, tz, 2));
            eventsTitle = "📅 Завтра";
        }
        Set<Long> shown = new HashSet<Long>();
        for (Row e : events) shown.add(e.lng("id"));
        List<Row> newEvents = new ArrayList<Row>();
        for (Row e : db.eventsCreatedSince(since)) if (!shown.contains(e.lng("id"))) newEvents.add(e);
        String money = "evening".equals(kind) ? app.finance.dayLine() : null;

        if (important.isEmpty() && info.isEmpty() && openItems.isEmpty() && events.isEmpty() && newEvents.isEmpty() && money == null)
            return null;

        String head = "morning".equals(kind) ? "☀️ <b>Утренняя сводка</b>" : "🌙 <b>Вечерняя сводка</b>";
        List<String> out = new ArrayList<String>();
        java.time.ZonedDateTime loc = now.atZone(tz);
        out.add(head + " · " + SecUtil.two(loc.getDayOfMonth()) + "." + SecUtil.two(loc.getMonthValue()));
        if (!events.isEmpty()) {
            out.add("");
            out.add("<b>" + eventsTitle + "</b>");
            for (Row e : events) out.add(Views.eventLine(e, tz, now, true));
        }
        if (!newEvents.isEmpty()) {
            out.add("");
            out.add("<b>🗓 Добавил в расписание</b>");
            for (Row e : newEvents.subList(0, Math.min(8, newEvents.size()))) out.add(Views.eventLine(e, tz, now, false));
        }
        if (!openItems.isEmpty()) {
            out.add("");
            out.add("<b>👉 Ждут тебя (" + openItems.size() + ")</b>");
            for (Row t : openItems) out.add(Views.taskLine(t, tz, now));
        }
        if (!important.isEmpty()) {
            out.add("");
            out.add("<b>📬 Важное (" + important.size() + ")</b>");
            for (Row m : important.subList(0, Math.min(MAX_IMPORTANT, important.size())))
                out.add("• #" + m.lng("id") + " " + esc(shorten(Views.sender(m), 30)) + ": "
                        + esc(shorten(m.truthy("summary") ? m.text("summary") : m.text("subject"), 160)));
            if (important.size() > MAX_IMPORTANT) out.add("…и ещё " + (important.size() - MAX_IMPORTANT));
        }
        if (!info.isEmpty()) {
            out.add("");
            out.add("<b>📰 К сведению (" + info.size() + ")</b>");
            for (Row m : info.subList(0, Math.min(MAX_INFO, info.size())))
                out.add("• " + esc(shorten(Views.sender(m), 25)) + ": "
                        + esc(shorten(m.truthy("summary") ? m.text("summary") : m.text("subject"), 110)));
            if (info.size() > MAX_INFO) out.add("…и ещё " + (info.size() - MAX_INFO));
        }
        if (money != null) {
            out.add("");
            out.add(money);
        }
        if (junk > 0) {
            out.add("");
            out.add("🗑 Отсеял рассылок и рекламы: " + junk);
        }
        return new Object[]{SecUtil.join("\n", out), Views.tasksKb(openItems)};
    }

    public static boolean send(Secretary app, String kind, boolean manual, String only) {
        Object[] built = build(app, kind, null);
        if (built == null) {
            if (manual || !app.cfg.skipEmptyDigest) app.send("Тихо: ничего нового, что требует внимания.", null, only);
            return false;
        }
        app.send((String) built[0], (Kb) built[1], only);
        app.db.kvSet("last_digest_at", SecUtil.toDb(now()));
        return true;
    }

    /** «Сегодня»: the schedule for today and tomorrow, reminders and open tasks. [html, Kb] */
    public static Object[] agenda(Secretary app, boolean withTasks) {
        SecDb db = app.db;
        ZoneId tz = app.cfg.tz;
        Instant now = now();
        List<Row> events = db.eventsBetween(now.minus(Duration.ofHours(1)), dayStart(now, tz, 2));
        List<Row> reminders = db.pendingReminders();
        List<Row> tasks = withTasks ? db.openTasks(8) : new ArrayList<Row>();
        List<String> out = new ArrayList<String>();
        out.add("<b>📅 Сегодня и завтра</b>");
        for (Row e : events) out.add(Views.eventLine(e, tz, now, false));
        if (events.isEmpty()) out.add("Ничего не запланировано.");
        if (!reminders.isEmpty()) {
            out.add("");
            out.add("<b>⏰ Напоминания</b>");
            for (Row r : reminders.subList(0, Math.min(10, reminders.size())))
                out.add("• " + fmtDt(fromDb(r.text("at")), tz, now) + " — " + esc(r.text("text")));
        }
        if (!tasks.isEmpty()) {
            out.add("");
            out.add("<b>👉 Ждут тебя</b>");
            for (Row t : tasks) out.add(Views.taskLine(t, tz, now));
        }
        return new Object[]{SecUtil.join("\n", out), Views.tasksKb(tasks)};
    }
}
