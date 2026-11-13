package io.github.teoplaydor.semsearch.secretary;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.teoplaydor.semsearch.core.MiniJson;
import io.github.teoplaydor.semsearch.secretary.Sql.Row;

import static io.github.teoplaydor.semsearch.secretary.SecUtil.now;

/** The server version's triage.py (its shared part): a verdict of the model saved with our rules over it. */
public final class Triage {
    private Triage() {
    }

    /** The urgent (mails and chats) is sent on its own, once. */
    public static void notifyUrgent(Secretary app) {
        for (Row r : app.db.unnotifiedUrgent(24)) {
            app.send(Views.mailCard(r, app.cfg.tz, "🔴 Срочно"), Views.mailKb(r), null);
            app.db.markNotified(r.lng("id"));
        }
    }

    /** Save the model's reading (of a mail or a chat) with our rules over it. Returns the final importance. */
    public static int applyVerdict(Secretary app, long itemId, Map<String, Object> data, String rule) {
        SecDb db = app.db;
        Instant now = now();
        int importance = clamp(data.get("importance"), 0, 3);
        Object cat = data.get("category");
        String category = cat instanceof String && Prompts.MAIL_CATEGORIES.contains(cat) ? (String) cat : "other";
        String action = SecUtil.strOf(data.get("action"));
        boolean needsReply = SecUtil.truthy(data.get("needs_reply"));
        Instant deadline = SecUtil.parseIso(data.get("deadline"), app.cfg.tz);

        if ("vip".equals(rule)) importance = Math.max(importance, needsReply || !action.isEmpty() ? 3 : 2);
        else if ("promo".equals(category)) { // an ad, even «the stream is starting» — not a task and not an event
            importance = 0;
            action = "";
            needsReply = false;
            deadline = null;
        }
        if (importance >= 2 && !action.isEmpty() && deadline != null && deadline.isAfter(now.minus(Duration.ofHours(1)))
                && deadline.isBefore(now.plus(Duration.ofHours(24)))) importance = 3;
        String status = importance >= 2 && (!action.isEmpty() || needsReply) ? "open" : "info";

        db.saveTriage(itemId, importance, category, SecUtil.strOf(data.get("summary")), action, needsReply, deadline, status);
        if (importance >= 1) {
            List<Object> events = MiniJson.arr(data.get("events"));
            saveEvents(app, itemId, events);
            // something is needed from the owner — a task. Quietly into the app's chat (no notification): the phone
            // already showed the message itself, and «done?» comes later at unlock.
            long[] task = app.tasks.fromItem(itemId);
            if (task != null && task[1] == 1 && importance < 3) {
                Object[] a = app.tasks.announce(task[0]);
                app.send((String) a[0], (Kb) a[1], "app");
            }
        }
        return importance;
    }

    static void saveEvents(Secretary app, long itemId, List<Object> events) {
        if (events == null) return;
        Instant now = now();
        for (Object o : events) {
            Map<String, Object> ev = MiniJson.obj(o);
            if (ev == null) continue;
            String title = SecUtil.strOf(ev.get("title"));
            Instant start = SecUtil.parseIso(ev.get("start"), app.cfg.tz);
            if (title.isEmpty() || start == null || start.isBefore(now.minus(Duration.ofHours(2)))) continue;
            if (Boolean.FALSE.equals(ev.get("registered"))) continue; // an announcement for all — not into the schedule
            Instant end = SecUtil.parseIso(ev.get("end"), app.cfg.tz);
            Object k = ev.get("kind");
            String kind = k instanceof String && Views.KIND_ICON.containsKey(k) ? (String) k : "other";
            app.db.upsertEvent(title, start, end, SecUtil.strOf(ev.get("url")), kind, "item:" + itemId);
        }
    }

    static String configRule(String addr, List<String> vip, List<String> mute) {
        int at = addr.lastIndexOf('@');
        String domain = "@" + (at >= 0 ? addr.substring(at + 1) : addr);
        if (vip.contains(addr) || vip.contains(domain)) return "vip";
        if (mute.contains(addr) || mute.contains(domain)) return "mute";
        return null;
    }

    static int clamp(Object value, int lo, int hi) {
        Long v = value instanceof Double ? Long.valueOf((long) (double) (Double) value) : SecUtil.intOf(value);
        if (v == null) return 1;
        return (int) Math.max(lo, Math.min(hi, v));
    }
}
