package io.github.teoplaydor.semsearch.secretary;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.teoplaydor.semsearch.secretary.Sql.Row;

import static io.github.teoplaydor.semsearch.secretary.SecUtil.esc;
import static io.github.teoplaydor.semsearch.secretary.SecUtil.fromDb;
import static io.github.teoplaydor.semsearch.secretary.SecUtil.now;

/** The server version's scheduler.py: reminders, events' «in 10 minutes», digests and the weekly report on time. */
public final class Schedule {
    private Schedule() {
    }

    static final Duration DIGEST_GRACE = Duration.ofHours(2); // the phone was off at digest time — later, but not much

    /** The notifications' part of the server's phone loop: read the quiet chats; once a day forget old messages. */
    public static void phoneStep(Secretary app, Instant now) {
        app.phone.processReady(now);
        String day = SecUtil.toDb(now()).substring(0, 10);
        if (!day.equals(app.db.kvGet("phone_prune"))) {
            app.db.kvSet("phone_prune", day);
            app.db.phonePrune(30);
        }
    }

    /** How many minutes before the start it is time to remind (and not reminded yet). */
    static List<Integer> dueOffsets(Instant start, Set<Integer> reminded, List<Integer> offsets, Instant now) {
        List<Integer> out = new ArrayList<Integer>();
        if (!now.isBefore(start.plus(Duration.ofMinutes(5)))) return out;
        for (int o : offsets) if (!reminded.contains(o) && !now.isBefore(start.minus(Duration.ofMinutes(o)))) out.add(o);
        return out;
    }

    public static void tick(Secretary app, Instant now) {
        SecDb db = app.db;
        SecConfig cfg = app.cfg;
        if (now == null) now = now();
        for (Row r : db.dueReminders(now)) {
            Secretary.log("schedule: reminder #" + r.lng("id") + " due " + r.text("at"));
            app.send("⏰ <b>Напоминание:</b> " + esc(r.text("text")));
            db.markReminderSent(r.lng("id"));
        }
        if (!cfg.remindBefore.isEmpty()) {
            int max = 0;
            for (int o : cfg.remindBefore) max = Math.max(max, o);
            Instant horizon = now.plus(Duration.ofMinutes(max + 1));
            for (Row ev : db.eventsBetween(now.minus(Duration.ofMinutes(5)), horizon)) {
                Instant start = fromDb(ev.text("start_at"));
                Set<Integer> reminded = SecDb.parseReminded(ev.text("reminded"));
                List<Integer> due = dueOffsets(start, reminded, cfg.remindBefore, now);
                if (!due.isEmpty()) {
                    long left = Math.max(0, Math.round(Duration.between(now, start).getSeconds() / 60.0));
                    app.send(Views.eventReminder(ev, left));
                    Set<Integer> all = new HashSet<Integer>(reminded);
                    all.addAll(due);
                    db.setEventReminded(ev.lng("id"), all);
                }
            }
        }
        ZonedDateTime loc = now.atZone(cfg.tz);
        String date = loc.toLocalDate().toString();
        for (LocalTime slot : cfg.digestTimes) {
            ZonedDateTime slotDt = loc.toLocalDate().atTime(slot).atZone(cfg.tz);
            String key = "digest:" + date + ":" + SecUtil.two(slot.getHour()) + ":" + SecUtil.two(slot.getMinute());
            if (!loc.isBefore(slotDt) && loc.isBefore(slotDt.plus(DIGEST_GRACE)) && db.kvGet(key) == null) {
                boolean sent = Digests.send(app, slot.getHour() < 15 ? "morning" : "evening", false, null);
                Secretary.log("schedule: digest " + key + (sent ? " sent" : " empty"));
                db.kvSet(key, "1");
            }
        }
        if (cfg.weeklyDay != null && cfg.weeklyAt != null) {
            ZonedDateTime slotDt = loc.toLocalDate().atTime(cfg.weeklyAt).atZone(cfg.tz);
            String key = "weekly:" + date;
            if (loc.getDayOfWeek().getValue() == cfg.weeklyDay && !loc.isBefore(slotDt) && loc.isBefore(slotDt.plus(DIGEST_GRACE))
                    && db.kvGet(key) == null) {
                String report = app.finance.weekReport();
                if (report != null) app.send(report);
                db.kvSet(key, "1");
            }
        }
    }
}
