package io.github.teoplaydor.semsearch.secretary;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.teoplaydor.semsearch.secretary.Sql.Row;

import static io.github.teoplaydor.semsearch.secretary.SecUtil.esc;
import static io.github.teoplaydor.semsearch.secretary.SecUtil.fmtDt;
import static io.github.teoplaydor.semsearch.secretary.SecUtil.fromDb;
import static io.github.teoplaydor.semsearch.secretary.SecUtil.shorten;

/** The server version's views.py: how the secretary's messages look — texts (Telegram-style HTML) and buttons. */
public final class Views {
    private Views() {
    }

    public static final Map<String, String> KIND_ICON = new HashMap<String, String>();

    static {
        KIND_ICON.put("webinar", "🎥");
        KIND_ICON.put("class", "🎓");
        KIND_ICON.put("meeting", "👥");
        KIND_ICON.put("deadline", "⏰");
        KIND_ICON.put("other", "📌");
    }

    static String icon(String kind) {
        String i = KIND_ICON.get(kind);
        return i == null ? "📌" : i;
    }

    public static String link(String url, String label) {
        return "<a href=\"" + SecUtil.escAttr(url) + "\">" + esc(label) + "</a>";
    }

    public static String sender(Row r) {
        return r.truthy("from_name") ? r.text("from_name") : r.text("from_addr");
    }

    public static boolean isChat(Row r) {
        return "chat".equals(r.text("kind"));
    }

    public static String mailCard(Row r, ZoneId tz, String title) {
        List<String> lines = new ArrayList<String>();
        lines.add("<b>" + title + "</b> · " + esc(r.text("account")));
        if (isChat(r)) lines.add("Чат: " + esc(r.text("from_name")));
        else {
            lines.add("От: " + esc(sender(r)));
            lines.add("Тема: " + esc(shorten(r.text("subject"), 150)));
        }
        lines.add("");
        lines.add(esc(r.truthy("summary") ? r.text("summary") : "(без описания)"));
        if (r.truthy("action")) lines.add("👉 " + esc(r.text("action")));
        if (r.truthy("deadline")) lines.add("⏰ до " + fmtDt(fromDb(r.text("deadline")), tz));
        return SecUtil.join("\n", lines);
    }

    public static Kb mailKb(Row r) {
        long id = r.lng("id");
        String reply = isChat(r) ? "✍️ Черновик" : "✍️ Ответить";
        return new Kb().row(reply, "draft:" + id, "📄 Полностью", "full:" + id)
                .row("✅ Сделано", "done:" + id, "🔕 Не важно", "mute:" + id, "⭐ Важный", "vip:" + id);
    }

    public static String fullMail(Row r) {
        if (isChat(r)) {
            String body = SecUtil.tail(r.text("body"), 3500);
            return "<b>#" + r.lng("id") + "</b> · " + esc(r.text("account")) + " · " + esc(r.text("from_name")) + "\n\n" + esc(body);
        }
        List<String> text = new ArrayList<String>();
        text.add("<b>#" + r.lng("id") + "</b> · " + esc(r.text("account")));
        text.add("От: " + esc(sender(r)) + " &lt;" + esc(r.text("from_addr")) + "&gt;");
        text.add("Тема: " + esc(r.text("subject")));
        text.add("");
        String body = r.text("body");
        text.add(esc(SecUtil.head(body, 3300) + (SecUtil.len(body) > 3300 ? "…" : "")));
        List<String> links = new ArrayList<String>();
        for (String ln : r.text("links").split("\n")) if (!ln.isEmpty() && links.size() < 8) links.add(ln);
        if (!links.isEmpty()) {
            text.add("");
            text.add("Ссылки:");
            for (String ln : links) text.add(esc(shorten(ln, 200)));
        }
        return SecUtil.join("\n", text);
    }

    public static String draftView(Row r, String text) {
        if (isChat(r)) {
            return "<b>✍️ Черновик ответа</b> · " + esc(r.text("account")) + " · " + esc(r.text("from_name")) + "\n\n"
                    + "<code>" + esc(text) + "</code>\n\n"
                    + "Нажми на текст — он скопируется. Отправь сам в " + esc(r.text("account")) + ".";
        }
        return "<b>✍️ Черновик ответа</b> на #" + r.lng("id") + "\n"
                + "Кому: " + esc(r.truthy("reply_to") ? r.text("reply_to") : r.text("from_addr")) + "\n"
                + "Тема: Re: " + esc(shorten(r.text("subject"), 120)) + "\n\n"
                + "<blockquote>" + esc(text) + "</blockquote>\n"
                + "Отправлю только после кнопки «Отправить».";
    }

    public static Kb draftKb(long draftId, boolean canSend) {
        List<String[]> row = new ArrayList<String[]>();
        if (canSend) row.add(new String[]{"📤 Отправить", "send:" + draftId});
        row.add(new String[]{"✏️ Изменить", "edit:" + draftId});
        row.add(new String[]{"✖️ Отмена", "cancel:" + draftId});
        return new Kb().add(row);
    }

    public static String eventLine(Row ev, ZoneId tz, Instant now, boolean timeOnly) {
        Instant start = fromDb(ev.text("start_at"));
        String when = timeOnly ? SecUtil.hm(start.atZone(tz)) : fmtDt(start, tz, now);
        String line = icon(ev.text("kind")) + " " + when + " " + esc(ev.text("title"));
        if (ev.truthy("url")) line += " — " + link(ev.text("url"), "ссылка");
        return line;
    }

    public static String eventReminder(Row ev, long minutesLeft) {
        String head = minutesLeft <= 1 ? "Начинается" : "Через " + minutesLeft + " мин";
        String text = "🔔 <b>" + head + ":</b> " + icon(ev.text("kind")) + " " + esc(ev.text("title"));
        if (ev.truthy("url")) text += "\n" + link(ev.text("url"), "Подключиться");
        return text;
    }

    public static String taskLine(Row t, ZoneId tz, Instant now) {
        String line = ("accepted".equals(t.text("status")) ? "👍" : "•") + " " + esc(shorten(t.text("title"), 120));
        if (t.truthy("due_at")) line += " — до " + fmtDt(fromDb(t.text("due_at")), tz, now);
        if (t.truthy("source")) line += " <i>(" + esc(shorten(t.text("source"), 40)) + ")</i>";
        return line;
    }

    /** A row per task: ✅ done, ⏰ later, 📄 where it came from. */
    public static Kb tasksKb(List<Row> tasks) {
        if (tasks == null || tasks.isEmpty()) return null;
        Kb kb = new Kb();
        for (Row t : tasks) {
            List<String[]> row = new ArrayList<String[]>();
            row.add(new String[]{"✅ " + shorten(t.text("title"), 22), "tdone:" + t.lng("id")});
            row.add(new String[]{"⏰", "tsnz:" + t.lng("id")});
            if (t.truthy("item_id")) row.add(new String[]{"📄", "full:" + t.lng("item_id")});
            kb.add(row);
        }
        return kb;
    }

    public static Kb reopenKb(long taskId) {
        return new Kb().row("↩️ Вернуть", "treo:" + taskId);
    }

    public static Kb newTaskKb(long taskId) {
        return new Kb().row("✅ Уже сделал", "tdone:" + taskId, "✖️ Не нужно", "tdrop:" + taskId);
    }

    public static Kb snoozeKb(long taskId) {
        return new Kb().row("⏰ 30 мин", "t30m:" + taskId, "⏰ 2 часа", "t2h:" + taskId)
                .row("🌙 Вечером", "teve:" + taskId, "☀️ Завтра", "ttom:" + taskId)
                .row("👍 Принял", "tacc:" + taskId, "✖️ Не нужно", "tdrop:" + taskId);
    }

    public static String openItemLine(Row r, ZoneId tz) {
        String what = r.truthy("action") ? r.text("action") : r.text("summary");
        String line = "#" + r.lng("id") + " " + esc(shorten(sender(r), 30)) + ": " + esc(shorten(what, 120));
        if (r.truthy("deadline")) line += " — до " + fmtDt(fromDb(r.text("deadline")), tz);
        return line;
    }
}
