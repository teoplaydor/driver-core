package io.github.teoplaydor.semsearch.secretary;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import io.github.teoplaydor.semsearch.core.MiniJson;
import io.github.teoplaydor.semsearch.secretary.Sql.Row;

import static io.github.teoplaydor.semsearch.secretary.SecUtil.esc;
import static io.github.teoplaydor.semsearch.secretary.SecUtil.fmtDt;
import static io.github.teoplaydor.semsearch.secretary.SecUtil.fromDb;
import static io.github.teoplaydor.semsearch.secretary.SecUtil.now;

/** The server version's assistant.py: questions about the owner's affairs and simple errands (remind, add to the schedule…). */
public final class Assistant {
    private final Secretary app;
    private final Deque<String[]> history = new ArrayDeque<String[]>();

    Assistant(Secretary app) {
        this.app = app;
    }

    public String context() {
        SecDb db = app.db;
        ZoneId tz = app.cfg.tz;
        Instant now = now();
        ZonedDateTime loc = now.atZone(tz);
        List<String> out = new ArrayList<String>();
        out.add("Сейчас: " + SecUtil.WEEKDAYS[loc.getDayOfWeek().getValue() - 1] + " " + SecUtil.two(loc.getDayOfMonth()) + "."
                + SecUtil.two(loc.getMonthValue()) + "." + loc.getYear() + " " + SecUtil.hm(loc) + " (" + tz.getId() + ")");
        out.add("");
        out.add("Расписание на 7 дней:");
        List<Row> events = db.eventsBetween(now.minus(Duration.ofHours(1)), now.plus(Duration.ofDays(7)));
        for (Row e : events)
            out.add("- [событие " + e.lng("id") + "] " + fmtDt(fromDb(e.text("start_at")), tz, now) + " " + e.text("title")
                    + (e.truthy("url") ? " (" + e.text("url") + ")" : ""));
        if (events.isEmpty()) out.add("- пусто");
        out.add("");
        out.add("Напоминания:");
        List<Row> rem = db.pendingReminders();
        for (Row r : rem.subList(0, Math.min(15, rem.size()))) out.add("- " + fmtDt(fromDb(r.text("at")), tz, now) + " " + r.text("text"));
        if (rem.isEmpty()) out.add("- нет");
        out.add("");
        out.add("Дела, которые ждут владельца (номер — id дела):");
        List<Row> tasks = db.openTasks(20);
        for (Row t : tasks)
            out.add("- [дело " + t.lng("id") + "] " + t.text("title") + (t.truthy("source") ? " (" + t.text("source") + ")" : "")
                    + (t.truthy("due_at") ? ", до " + fmtDt(fromDb(t.text("due_at")), tz, now) : "")
                    + ("accepted".equals(t.text("status")) ? ", владелец принял" : ""));
        if (tasks.isEmpty()) out.add("- нет");
        out.add("");
        out.add("Важные письма и сообщения за 3 дня:");
        List<Row> imp = db.recentImportant(3, 25);
        for (Row m : imp)
            out.add("- #" + m.lng("id") + " " + fmtDt(fromDb(m.text("received_at")), tz, now) + " (" + m.text("account") + ") "
                    + Views.sender(m) + ": " + SecUtil.shorten(m.truthy("summary") ? m.text("summary") : m.text("subject"), 200));
        if (imp.isEmpty()) out.add("- нет");
        out.add("");
        out.add(app.finance.contextSummary());
        return SecUtil.join("\n", out);
    }

    public String handle(String text) throws LlmError {
        List<String> h = new ArrayList<String>();
        synchronized (history) {
            for (String[] e : history) h.add(e[0] + ": " + e[1]);
        }
        Map<String, Object> data = app.llm.json(true, false,
                Prompts.fill(Prompts.ASSISTANT_SYSTEM, "about_me", app.cfg.aboutMe, "tz", app.cfg.tz.getId(),
                        "categories", SecUtil.join(", ", Prompts.FIN_CATEGORIES)),
                Prompts.fill(Prompts.ASSISTANT_USER, "context", context(), "history", h.isEmpty() ? "(нет)" : SecUtil.join("\n", h),
                        "text", text),
                Prompts.ASSISTANT_SCHEMA);
        String reply = SecUtil.strOf(data.get("reply"));
        List<String> done = new ArrayList<String>();
        List<Object> actions = MiniJson.arr(data.get("actions"));
        if (actions != null) for (Object o : actions) {
            Map<String, Object> a = MiniJson.obj(o);
            if (a == null) continue;
            String d = apply(a);
            if (d != null && !d.isEmpty()) done.add(d);
        }
        synchronized (history) {
            history.addLast(new String[]{"Владелец", text});
            history.addLast(new String[]{"Секретарь", reply});
            while (history.size() > 8) history.removeFirst();
        }
        List<String> parts = new ArrayList<String>();
        if (!reply.isEmpty()) parts.add(esc(reply));
        parts.addAll(done);
        return parts.isEmpty() ? "Готово." : SecUtil.join("\n\n", parts);
    }

    /** One errand. Only safe things inside the secretary itself. */
    String apply(Map<String, Object> a) {
        SecDb db = app.db;
        ZoneId tz = app.cfg.tz;
        Object kind = a.get("type");
        try {
            if ("add_reminder".equals(kind)) {
                Instant at = SecUtil.parseIso(a.get("at"), tz);
                String text = SecUtil.strOf(a.get("text"));
                if (at == null || text.isEmpty()) return "⚠️ Не понял, когда и о чём напомнить.";
                db.addReminder(text, at);
                return "⏰ Напомню " + fmtDt(at, tz) + ": " + esc(text);
            }
            if ("add_event".equals(kind)) {
                Instant start = SecUtil.parseIso(a.get("start"), tz);
                String title = SecUtil.strOf(a.get("title"));
                if (start == null || title.isEmpty()) return "⚠️ Не понял время или название события.";
                long[] r = db.upsertEvent(title, start, SecUtil.parseIso(a.get("end"), tz),
                        SecUtil.truthy(a.get("url")) ? String.valueOf(a.get("url")) : "", "other", "manual");
                return "📅 " + (r[1] == 1 ? "Добавил" : "Уже есть") + ": " + fmtDt(start, tz) + " " + esc(title);
            }
            if ("cancel_event".equals(kind)) {
                Long id = SecUtil.intOf(a.get("id"));
                if (id == null) return null;
                return db.cancelEvent(id) ? "🗑 Убрал из расписания." : "⚠️ Не нашёл событие.";
            }
            if ("done".equals(kind)) {
                Long id = SecUtil.intOf(a.get("id"));
                if (id == null) return null;
                return esc(app.tasks.act(id, "done", ""));
            }
            if ("add_task".equals(kind)) {
                String title = SecUtil.strOf(SecUtil.truthy(a.get("text")) ? a.get("text") : a.get("title"));
                if (title.isEmpty()) return null;
                long[] r = app.tasks.add(title, SecUtil.parseIso(a.get("at"), tz));
                return "📝 " + (r[1] == 1 ? "Записал дело" : "Такое дело уже есть") + ": " + esc(title);
            }
            if ("mute_sender".equals(kind) || "vip_sender".equals(kind)) {
                String email = SecUtil.lower(SecUtil.strOf(a.get("email")));
                if (!email.contains("@")) return null;
                db.setSenderRule(email, "mute_sender".equals(kind) ? "mute" : "vip");
                return "mute_sender".equals(kind) ? "🔕 Больше не показываю " + esc(email) + "." : "⭐ " + esc(email) + " — всегда важно.";
            }
            if ("mute_app".equals(kind)) {
                String name = SecUtil.strOf(a.get("app"));
                if (name.isEmpty()) return null;
                db.setSenderRule("app:" + SecUtil.lower(name), "mute");
                return "🔕 Уведомления «" + esc(name) + "» больше не разбираю.";
            }
            if ("set_category".equals(kind)) {
                String merchant = SecUtil.truthy(a.get("merchant")) ? String.valueOf(a.get("merchant")) : "";
                String category = SecUtil.truthy(a.get("category")) ? String.valueOf(a.get("category")) : "";
                String key = Finance.merchantKey(merchant);
                if (key.isEmpty() || !Prompts.FIN_CATEGORIES.contains(category)) return "⚠️ Не понял магазин или категорию.";
                int n = db.setMerchantCategory(key, category);
                return "🏷 " + esc(merchant) + " → " + esc(category) + " (исправил операций: " + n + ")";
            }
        } catch (RuntimeException e) {
            Secretary.log("errand " + a + " not done: " + e);
            return null;
        }
        return null;
    }
}
