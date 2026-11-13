package io.github.teoplaydor.semsearch.secretary;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.teoplaydor.semsearch.secretary.Sql.Row;

import static io.github.teoplaydor.semsearch.secretary.SecUtil.esc;
import static io.github.teoplaydor.semsearch.secretary.SecUtil.fmtDt;
import static io.github.teoplaydor.semsearch.secretary.SecUtil.fromDb;
import static io.github.teoplaydor.semsearch.secretary.SecUtil.now;
import static io.github.teoplaydor.semsearch.secretary.SecUtil.shorten;

/** The server version's actions.py: what to do with the owner's message, button or command; the app's tab screens. */
public final class Actions {
    private Actions() {
    }

    public static final String HELP = "<b>Я твой секретарь.</b>\n"
            + "Сам слежу за почтой, сообщениями на телефоне, расписанием и расходами. Пишу сразу только про срочное,\n"
            + "остальное собираю в сводки.\n"
            + "\n"
            + "Можно писать или надиктовывать голосом:\n"
            + "• «что у меня завтра?»\n"
            + "• «напомни в пятницу в 10 позвонить в банк»\n"
            + "• «сколько я потратил на такси в этом месяце?»\n"
            + "• «уведомления озона больше не показывай»\n"
            + "\n"
            + "Дела («забрать заказ», «ответить Анне») я записываю сам, а телефон при разблокировке спрашивает\n"
            + "«Сделал?» — можно ответить «Сделал», «Отложить» или «Принял».\n"
            + "\n"
            + "Команды: «Сегодня» — расписание, «Дела» — что ждёт тебя, «Сводка» — сводка сейчас,\n"
            + "«Расходы» — за месяц, «Нейросеть» — сколько потрачено на неё.";

    public static final Set<String> COMMANDS = new HashSet<String>(Arrays.asList("today", "tasks", "digest", "spent", "usage", "help"));
    static final Map<String, String> SNOOZE_BUTTONS = new HashMap<String, String>(), TASK_BUTTONS = new HashMap<String, String>();

    static {
        SNOOZE_BUTTONS.put("t30m", "30m");
        SNOOZE_BUTTONS.put("t2h", "2h");
        SNOOZE_BUTTONS.put("teve", "evening");
        SNOOZE_BUTTONS.put("ttom", "tomorrow");
        TASK_BUTTONS.put("tdone", "done");
        TASK_BUTTONS.put("tacc", "accept");
        TASK_BUTTONS.put("tdrop", "drop");
    }

    public static void userText(Secretary app, String text, String only) throws LlmError {
        Long edit = app.pendingEdit;
        if (edit != null) {
            app.pendingEdit = null;
            Row draft = app.db.getDraft(edit);
            if (draft != null && "draft".equals(draft.text("status"))) {
                app.drafts.create(draft.lng("mail_id"), text, edit, only);
                return;
            }
        }
        app.send(app.assistant.handle(text), null, only);
    }

    /** A button press. A short answer if one is needed (null — none). */
    public static String button(Secretary app, String action, long itemId, String only) throws LlmError {
        SecDb db = app.db;
        if ("draft".equals(action)) {
            app.drafts.create(itemId, "", null, only);
            return null;
        }
        if ("full".equals(action)) {
            Row row = db.getMail(itemId);
            app.send(row != null ? Views.fullMail(row) : "Не нашёл.", null, only);
            return null;
        }
        if ("done".equals(action)) {
            db.setMailStatus(itemId, "done");
            db.closeItemTasks(itemId, "done");
            return "✅ #" + itemId + " закрыто.";
        }
        if ("close".equals(action) || "plain".equals(action)) {
            Row row = db.getMail(itemId);
            if (row == null) return "Не нашёл.";
            db.setSenderRule(row.text("from_addr"), "close".equals(action) ? "vip" : "plain");
            String name = esc(row.text("from_name"));
            if ("close".equals(action)) return "⭐ " + name + " — близкий: сообщения всегда со звуком.";
            return name + " — больше не в близких: показываю, только если важно.";
        }
        if ("mute".equals(action) || "vip".equals(action)) {
            Row row = db.getMail(itemId);
            if (row == null) return "Не нашёл.";
            String who = Views.isChat(row) ? "чат «" + esc(row.text("from_name")) + "»" : esc(row.text("from_addr"));
            db.setSenderRule(row.text("from_addr"), action);
            if ("mute".equals(action)) {
                db.setMailStatus(itemId, "ignored");
                db.closeItemTasks(itemId, "dropped");
                return "🔕 " + who + " — больше не показываю. Вернуть можно, попросив меня.";
            }
            return "⭐ " + who + " — теперь всегда важно.";
        }
        if (TASK_BUTTONS.containsKey(action)) return app.tasks.act(itemId, TASK_BUTTONS.get(action), "");
        if ("treo".equals(action)) return app.tasks.reopen(itemId);
        if (SNOOZE_BUTTONS.containsKey(action)) return app.tasks.act(itemId, "snooze", SNOOZE_BUTTONS.get(action));
        if ("tsnz".equals(action)) {
            Row t = db.getTask(itemId);
            if (t == null) return "Не нашёл это дело.";
            app.send("Когда спросить ещё раз: <b>" + esc(t.text("title")) + "</b>?", Views.snoozeKb(itemId), only);
            return null;
        }
        if ("send".equals(action)) return app.drafts.send(itemId);
        if ("edit".equals(action)) {
            app.pendingEdit = itemId;
            return "✏️ Напиши или надиктуй, что поменять в ответе.";
        }
        if ("cancel".equals(action)) {
            db.setDraftStatus(itemId, "cancelled");
            return "Черновик отменён.";
        }
        return null;
    }

    public static void command(Secretary app, String name, String only) {
        if ("today".equals(name)) {
            Object[] a = Digests.agenda(app, true);
            app.send((String) a[0], (Kb) a[1], only);
        } else if ("tasks".equals(name)) {
            List<Row> tasks = app.db.openTasks(20);
            if (tasks.isEmpty()) app.send("Дел нет — ничего не висит. 🎉", null, only);
            else {
                List<String> lines = new ArrayList<String>();
                lines.add("<b>✅ Дела (" + tasks.size() + ")</b>");
                for (Row t : tasks) lines.add(Views.taskLine(t, app.cfg.tz, null));
                app.send(SecUtil.join("\n", lines), Views.tasksKb(tasks), only);
            }
        } else if ("digest".equals(name)) {
            int hour = now().atZone(app.cfg.tz).getHour();
            Digests.send(app, hour < 15 ? "morning" : "evening", true, only);
        } else if ("spent".equals(name)) {
            app.send(app.finance.monthReport(), null, only);
        } else if ("usage".equals(name)) {
            app.send(usageReport(app), null, only);
        } else {
            app.send(HELP, null, only);
        }
    }

    static Map<String, Object> block(String html, List<Object> buttons) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("html", html);
        m.put("buttons", buttons == null ? new ArrayList<Object>() : buttons);
        return m;
    }

    static Map<String, Object> section(String title) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("section", title);
        out.put("html", "");
        out.put("buttons", new ArrayList<Object>());
        return out;
    }

    static Map<String, Object> taskBlock(Row t, ZoneId tz) {
        Kb kb = new Kb();
        List<String[]> row = new ArrayList<String[]>();
        row.add(new String[]{"Сделал", "tdone:" + t.lng("id")});
        row.add(new String[]{"Отложить", "tsnz:" + t.lng("id")});
        if (t.truthy("item_id")) row.add(new String[]{"Откуда", "full:" + t.lng("item_id")});
        kb.add(row);
        return block(taskCard(t, tz), Kb.list(kb));
    }

    /** Close people — their messages always with sound. Below — who else writes often: can be added. */
    static List<Map<String, Object>> peopleBlocks(Secretary app) {
        SecDb db = app.db;
        List<Map<String, Object>> close = new ArrayList<Map<String, Object>>(), others = new ArrayList<Map<String, Object>>();
        for (Row r : db.personalChats(now().minus(Duration.ofDays(30)))) {
            String key = Phone.chatKey(r.text("package"), r.text("chat"));
            String rule = db.senderRule(key);
            Long item = db.latestItem(key);
            if ("mute".equals(rule) || item == null) continue;
            String line = "<b>" + esc(r.text("chat")) + "</b> · " + esc(r.text("app")) + "\n<i>" + r.lng("n") + " сообщ. за месяц</i>";
            if (app.phone.isClose(r.text("package"), r.text("chat"), rule))
                close.add(block(line, Kb.list(new Kb().row("Убрать из близких", "plain:" + item))));
            else if (others.size() < 15 && Phone.MESSENGERS.contains(r.text("package")) && !Phone.SERVICE_NAME_RE.matcher(r.text("chat")).find())
                others.add(block(line, Kb.list(new Kb().row("⭐ Близкий", "close:" + item))));
        }
        String intro = "Сообщения близких показываю всегда и со звуком, даже в «Тишине» и даже если это стикер. "
                + "Близких узнаю сам: по имени («Мама», «Девушка моя») и по тому, что ты заходишь в чат в разные дни.";
        List<Map<String, Object>> blocks = new ArrayList<Map<String, Object>>();
        blocks.add(block(intro, null));
        if (close.isEmpty()) blocks.add(block("Пока никого.", null));
        else blocks.addAll(close);
        if (!others.isEmpty()) {
            blocks.add(section("Ещё пишут"));
            blocks.addAll(others);
        }
        return blocks;
    }

    /** A task on the «Дела» tab: the title, under it in grey — the deadline, where from and when the phone asks again. */
    static String taskCard(Row t, ZoneId tz) {
        Instant now = now();
        List<String> meta = new ArrayList<String>();
        if (t.truthy("due_at")) meta.add("до " + fmtDt(fromDb(t.text("due_at")), tz, now));
        if (t.truthy("source")) meta.add(esc(shorten(t.text("source"), 40)));
        Instant remind = fromDb(t.text("remind_at"));
        if (remind.isAfter(now)) meta.add("спрошу " + fmtDt(remind, tz, now));
        else if ("accepted".equals(t.text("status"))) meta.add("принял");
        String title = "<b>" + esc(shorten(t.text("title"), 120)) + "</b>";
        return title + (meta.isEmpty() ? "" : "\n<i>" + SecUtil.join(" · ", meta) + "</i>");
    }

    static Map<String, Object> screen(String title, List<Map<String, Object>> blocks) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("title", title);
        m.put("blocks", blocks);
        return m;
    }

    static List<Map<String, Object>> one(String html, List<Object> buttons) {
        List<Map<String, Object>> l = new ArrayList<Map<String, Object>>();
        l.add(block(html, buttons));
        return l;
    }

    /** A tab screen: fresh each time, not in the chat history. {"title", "blocks": [{"html", "buttons"}]} */
    public static Map<String, Object> view(Secretary app, String name, long itemId) {
        ZoneId tz = app.cfg.tz;
        if ("tasks".equals(name)) {
            List<Row> tasks = app.db.openTasks(100);
            if (tasks.isEmpty()) return screen("Дела", one("Ничего не висит.", null));
            Instant now = now();
            // postponed and planned for later — lower and apart: no need to think of them now
            List<Row> active = new ArrayList<Row>(), later = new ArrayList<Row>();
            for (Row t : tasks) (fromDb(t.text("remind_at")).isAfter(now) ? later : active).add(t);
            Collections.sort(later, new Comparator<Row>() {
                public int compare(Row a, Row b) {
                    return a.text("remind_at").compareTo(b.text("remind_at"));
                }
            });
            List<Map<String, Object>> blocks = new ArrayList<Map<String, Object>>();
            for (Row t : active) blocks.add(taskBlock(t, tz));
            if (blocks.isEmpty()) blocks.add(block("Сейчас ничего не висит.", null));
            if (!later.isEmpty()) {
                blocks.add(section("Позже · " + later.size()));
                for (Row t : later) blocks.add(taskBlock(t, tz));
            }
            return screen(active.isEmpty() ? "Дела" : "Дела · " + active.size(), blocks);
        }
        if ("today".equals(name)) {
            Object[] a = Digests.agenda(app, false);
            return screen("Сегодня", one((String) a[0], Kb.list((Kb) a[1])));
        }
        if ("digest".equals(name)) {
            int hour = now().atZone(tz).getHour();
            Object[] built = Digests.build(app, hour < 15 ? "morning" : "evening", null); // a preview: «since the last digest»
            if (built == null) return screen("Сводка", one("Ничего нового с последней сводки.", null));
            return screen("Сводка", one((String) built[0], Kb.list((Kb) built[1])));
        }
        if ("spent".equals(name)) return screen("Расходы", app.finance.screenMonth());
        if ("cat".equals(name)) {
            Object[] r = app.finance.screenCategory((int) itemId);
            @SuppressWarnings("unchecked") List<Map<String, Object>> blocks = (List<Map<String, Object>>) r[1];
            return screen(esc(r[0]), blocks);
        }
        if ("ops".equals(name)) return screen("История операций", app.finance.screenHistory());
        if ("people".equals(name)) return screen("Близкие", peopleBlocks(app));
        if ("usage".equals(name)) return screen("Нейросеть", one(usageReport(app), null));
        if ("item".equals(name)) {
            Row row = app.db.getMail(itemId);
            if (row == null) return screen("Подробно", one("Не нашёл.", null));
            List<Object> buttons = new ArrayList<Object>();
            for (List<String[]> r : Views.mailKb(row).rows) {
                List<Object> keep = new ArrayList<Object>();
                for (String[] b : r) if (!b[1].startsWith("full:")) keep.add(Kb.btn(b[0], b[1]));
                buttons.add(keep);
            }
            return screen(esc(row.text("account")), one(Views.fullMail(row), buttons));
        }
        return screen("Помощь", one(HELP, null));
    }

    /** The server version's usage report: requests, tokens and the cost estimate per model (prices in GeminiBrain). */
    static String usageReport(Secretary app) {
        Instant now = now();
        List<String> lines = new ArrayList<String>();
        lines.add("<b>Нейросеть</b>");
        String[][] periods = {{"Сегодня", SecUtil.toDb(now).substring(0, 10)},
                {"За 30 дней", SecUtil.toDb(now.minus(Duration.ofDays(30))).substring(0, 10)}};
        for (String[] p : periods) {
            List<Row> rows = app.db.usageSince(p[1]);
            double total = 0;
            boolean priced = false;
            lines.add("\n<b>" + p[0] + "</b>");
            for (Row r : rows) {
                Double cost = GeminiBrain.estimateCost(r.text("model"), r.lng("tokens_in"), r.lng("tokens_out"));
                if (cost != null) {
                    total += cost;
                    priced = true;
                }
                String price = cost != null ? "≈ $" + SecUtil.fixed(cost, 3) : "цена неизвестна";
                String tokens = SecUtil.groupThousands(String.valueOf(r.lng("tokens_in") + r.lng("tokens_out")));
                lines.add("• " + esc(r.text("model")) + ": " + r.lng("calls") + " запр., " + tokens + " ток., " + price);
            }
            lines.add(rows.isEmpty() ? "Запросов не было." : priced ? "Итого ≈ $" + SecUtil.fixed(total, 2) : "Всё на телефоне — бесплатно.");
        }
        return SecUtil.join("\n", lines);
    }
}
