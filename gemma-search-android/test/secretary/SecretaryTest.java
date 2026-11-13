import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.teoplaydor.semsearch.core.MiniJson;
import io.github.teoplaydor.semsearch.secretary.Actions;
import io.github.teoplaydor.semsearch.secretary.Api;
import io.github.teoplaydor.semsearch.secretary.Digests;
import io.github.teoplaydor.semsearch.secretary.Finance;
import io.github.teoplaydor.semsearch.secretary.LlmError;
import io.github.teoplaydor.semsearch.secretary.Phone;
import io.github.teoplaydor.semsearch.secretary.Prompts;
import io.github.teoplaydor.semsearch.secretary.Schedule;
import io.github.teoplaydor.semsearch.secretary.SecConfig;
import io.github.teoplaydor.semsearch.secretary.SecDb;
import io.github.teoplaydor.semsearch.secretary.SecUtil;
import io.github.teoplaydor.semsearch.secretary.Secretary;
import io.github.teoplaydor.semsearch.secretary.Sql.Row;
import io.github.teoplaydor.semsearch.secretary.Tasks;
import io.github.teoplaydor.semsearch.secretary.Triage;

/**
 * The server version's tests (tests/*.py), ported: the same situations, the same expectations — notifications, the
 * «Тишина» filter, chats read into cards, tasks and «Сделал?», evidence that closes tasks, money, digests, the
 * schedule, the app's API. What the server sent to Telegram on its own is here a chat message with a notification.
 * Mail-only tests wait for mail on the phone. The model is a fake with prepared answers, as in conftest.py.
 */
public class SecretaryTest {
    static int bad = 0, total = 0;
    static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    /** Friday 13.11.2026, noon in Moscow. */
    static final Instant NOW = ZonedDateTime.of(2026, 11, 13, 12, 0, 0, 0, MSK).toInstant();
    static final long T0 = 1_761_140_000_000L;

    static void ok(boolean c, String what) {
        total++;
        System.out.println((c ? "ok   " : "FAIL ") + what);
        if (!c) bad++;
    }

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) out.put((String) kv[i], kv[i + 1]);
        return out;
    }

    static List<Object> l(Object... xs) {
        return new ArrayList<Object>(Arrays.asList(xs));
    }

    static Secretary make() {
        SecConfig cfg = new SecConfig();
        cfg.tz = MSK;
        cfg.aboutMe = "Работаю менеджером, учусь на курсе Python.";
        cfg.myName = "Иван";
        cfg.largeExpense = 10000;
        return new Secretary(cfg, new SecDb(JdbcSql.memory()), new FakeBrain());
    }

    static FakeBrain llm(Secretary a) {
        return (FakeBrain) a.llm;
    }

    /** What the secretary wrote on its own (the server sent these to Telegram): chat messages with a notification. */
    static List<String> pushed(Secretary a) {
        List<String> out = new ArrayList<String>();
        for (Row r : a.db.q("SELECT * FROM chat_log WHERE notify=1 ORDER BY id")) out.add(r.text("html"));
        return out;
    }

    static boolean anyContains(List<String> l, String s) {
        for (String x : l) if (x.contains(s)) return true;
        return false;
    }

    static int countContains(List<String> l, String s) {
        int n = 0;
        for (String x : l) if (x.contains(s)) n++;
        return n;
    }

    static Row lastChat(Secretary a) {
        return a.db.chatTail(1).get(0);
    }

    static Instant msk(int y, int mo, int d, int h, int mi) {
        return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, MSK).toInstant();
    }

    static String iso(Instant t) {
        return t.atZone(MSK).toOffsetDateTime().toString();
    }

    static Instant now() {
        return SecUtil.now();
    }

    // ---------- items the phone sends ----------

    static Map<String, Object> waItem(String chat, boolean group, String key, Object[]... messages) {
        List<Object> ms = new ArrayList<Object>();
        for (int i = 0; i < messages.length; i++)
            ms.add(m("sender", messages[i][0], "text", messages[i][1], "time", T0 + i * 1000L, "self", messages[i][0] == null));
        return m("package", "com.whatsapp", "app", "WhatsApp", "title", chat, "text", "2 новых сообщения", "key", key,
                "conversation", group ? chat : "", "is_group", group, "posted", T0, "messages", ms);
    }

    static Map<String, Object> wa(String key, String sender, String text, String chat, boolean group) {
        if (chat == null) chat = sender;
        return m("key", key, "package", "com.whatsapp", "app", "WhatsApp", "title", chat, "text", text,
                "conversation", group ? chat : "", "is_group", group, "posted", T0,
                "messages", l(m("sender", sender, "text", text, "time", T0, "self", false)));
    }

    static Map<String, Object> plain(String key, String pkg, String app, String title, String text) {
        return m("key", key, "package", pkg, "app", app, "title", title, "text", text, "posted", T0);
    }

    static Map<String, Object> tg(String key, String chat, String text, boolean group, long posted) {
        return m("key", key, "package", "org.telegram.messenger", "app", "Telegram", "title", chat, "text", text,
                "conversation", group ? chat : "", "is_group", group, "posted", posted,
                "messages", l(m("sender", chat, "text", text, "time", posted, "self", false)));
    }

    static Map<String, Object> verdict(int importance, String action, boolean needsReply, String summary, String deadline,
                                       List<Object> events, String category) {
        return m("importance", (long) importance, "category", category, "summary", summary, "action", action,
                "needs_reply", needsReply, "deadline", deadline, "events", events == null ? l() : events);
    }

    static SecDb.Item item(String uid, String fromAddr, String fromName, String subject, String body) {
        SecDb.Item it = new SecDb.Item();
        it.uid = uid;
        it.date = now();
        it.fromAddr = fromAddr;
        it.fromName = fromName;
        it.subject = subject;
        it.body = body;
        return it;
    }

    public static void main(String[] args) throws Exception {
        SecUtil.clock = Clock.fixed(NOW, MSK);
        util();
        phone();
        quiet();
        triage();
        tasks();
        closeAndPlan();
        resolve();
        finance();
        digestSchedule();
        webapp();
        assistantActions();
        System.out.println(bad == 0 ? "SECRETARY OK (" + total + " checks)" : bad + " FAILED of " + total);
        System.exit(bad == 0 ? 0 : 1);
    }

    // ---------- util ----------
    static void util() {
        ok(SecUtil.parseIso("2026-10-23T19:00:00+03:00", MSK).atZone(MSK).getHour() == 19, "parse_iso: with an offset");
        ok(SecUtil.parseIso("2026-10-23 19:00", MSK).atZone(MSK).getHour() == 19, "parse_iso: no zone — local time");
        ZonedDateTime d = SecUtil.parseIso("2026-10-23", MSK).atZone(MSK);
        ok(d.getHour() == 23 && d.getMinute() == 59, "parse_iso: a date — 23:59");
        ok(SecUtil.parseIso("2026-10-23T16:00:00Z", MSK).atZone(MSK).getHour() == 19, "parse_iso: Z");
        ok(SecUtil.parseIso("", MSK) == null && SecUtil.parseIso("в четверг", MSK) == null && SecUtil.parseIso(null, MSK) == null,
                "parse_iso: empty, words, None — None");
        Instant n = msk(2026, 10, 22, 12, 0);
        ok("сегодня 19:00".equals(SecUtil.fmtDt(msk(2026, 10, 22, 19, 0), MSK, n))
                        && "завтра 12:00".equals(SecUtil.fmtDt(n.plus(Duration.ofDays(1)), MSK, n))
                        && "сб 24.10 12:00".equals(SecUtil.fmtDt(n.plus(Duration.ofDays(2)), MSK, n)),
                "fmt_dt: сегодня / завтра / сб 24.10");
        double[] want = {0.7878787878787878, 0.935064935064935, 1.0, 0.2222222222222222, 0.0, 0.6666666666666666, 1.0};
        String a150 = new String(new char[150]).replace('\0', 'a'), b100 = new String(new char[100]).replace('\0', 'b');
        String b120 = new String(new char[120]).replace('\0', 'b'), a130 = new String(new char[130]).replace('\0', 'a');
        String[][] pairs = {{"вебинар: асинхронный python", "напоминание: вебинар асинхронный python"},
                {"забрать заказ самоката в пункте выдачи", "забрать заказ самоката из пункта выдачи"},
                {"оплатить интернет", "оплатить интернет"}, {"сдать отчёт", "позвонить в банк"}, {a150 + b100, b120 + a130},
                {"😀ab", "😀ac"}, {"", ""}};
        boolean same = true;
        for (int i = 0; i < pairs.length; i++) same &= Math.abs(SecUtil.ratio(pairs[i][0], pairs[i][1]) - want[i]) < 1e-12;
        ok(same, "difflib ratio: the same numbers as Python (autojunk of 200+ too)");
        ok("1 250 ₽".equals(SecUtil.fmtMoney(1250)) && "6 000 ₽".equals(SecUtil.fmtMoney(6000)) && "2 ₽".equals(SecUtil.fmtMoney(2.5)),
                "money: thousands by spaces, halves to even as Python");
        try {
            Prompts.fill("{a} {b}", "a", 1);
            ok(false, "fill: a missing value is an error");
        } catch (IllegalArgumentException e) {
            ok(true, "fill: a missing value is an error");
        }
        ok(Prompts.QUICK_SYSTEM.contains("{about_me}") && Prompts.TRIAGE_SCHEMA.grammar.contains("root ::=")
                && Prompts.FIN_SCHEMA.grammar.contains("Кафе и рестораны"), "prompts verbatim, a grammar for each schema");
    }

    // ---------- test_phone.py ----------
    static void phone() throws Exception {
        String longText = "Привет! ";
        for (int i = 0; i < 40; i++) longText += "очень длинное сообщение ";
        List<Phone.Msg> msgs = Phone.normalize(waItem("Анна", false, "0|com.whatsapp|1", new Object[]{"Анна", longText},
                new Object[]{null, "ок, сейчас гляну"}));
        ok(msgs.size() == 2 && "Анна".equals(msgs.get(0).sender) && "Я".equals(msgs.get(1).sender) && "Анна".equals(msgs.get(1).chat),
                "normalize: a messenger gives whole messages with senders");
        ok(msgs.get(0).text.equals(longText.trim()), "…in full, not cut");
        List<Phone.Msg> again = Phone.normalize(waItem("Анна", false, "другой ключ", new Object[]{"Анна", longText},
                new Object[]{null, "ок, сейчас гляну"}));
        ok(msgs.get(0).uid.equals(again.get(0).uid) && msgs.get(1).uid.equals(again.get(1).uid), "the same message in a new notification — the same uid");
        List<Phone.Msg> big = Phone.normalize(m("package", "ru.ozon.app.android", "app", "Ozon", "title", "Заказ", "text", "Ваш заказ…",
                "big_text", "Ваш заказ 123 прибыл в пункт выдачи, хранение 5 дней", "posted", T0));
        ok(big.get(0).text.startsWith("Ваш заказ 123 прибыл") && "Заказ".equals(big.get(0).sender), "normalize: big_text first");
        List<Phone.Msg> lines = Phone.normalize(m("package", "x", "app", "Почта", "title", "3 письма", "lines", l("Иван: отчёт", "Банк: выписка")));
        ok("Иван: отчёт\nБанк: выписка".equals(lines.get(0).text), "normalize: lines");
        ok(Phone.normalize(m("package", "x", "app", "X", "title", "пусто")).isEmpty(), "normalize: nothing — nothing");

        Secretary app = make();
        app.botNames.addAll(Arrays.asList("помощник", "lodatmoshelperbot"));
        llm(app).jsonResponses.add(m("is_transaction", true, "direction", "expense", "amount", 350L, "currency", "RUB",
                "merchant", "PYATEROCHKA", "category", "Продукты", "card", ""));
        Map<String, Integer> stats = app.phone.ingest(l(
                waItem("Анна", false, "0|com.whatsapp|1", new Object[]{"Анна", "Привет, во сколько завтра встречаемся?"}),
                m("package", "com.android.systemui", "app", "Система", "title", "Зарядка", "text", "Заряжено 100%"),
                m("package", "org.telegram.messenger", "app", "Telegram", "title", "Помощник", "text", "Сводка…"),
                m("package", "ru.sberbankmobile", "app", "СберБанк", "title", "Покупка", "text", "350 ₽ PYATEROCHKA"),
                m("package", "com.google.android.apps.messaging", "app", "Сообщения", "title", "900", "text", "Код: 4821. Никому не сообщайте"),
                "мусор"));
        ok(stats.get("stored") == 1 && stats.get("bank") == 1 && stats.get("skipped") == 3, "ingest: stored 1, bank 1, skipped 3 — " + stats);
        ok(app.phone.ingest(l(waItem("Анна", false, "0|com.whatsapp|1", new Object[]{"Анна", "Привет, во сколько завтра встречаемся?"})))
                .get("stored") == 0, "ingest: a repeat is not stored again");
        List<Row> rows = app.db.q("SELECT * FROM phone_msgs");
        ok(rows.size() == 1 && "Анна".equals(rows.get(0).text("chat")), "one message of Анна in the table");
        app.drain();
        Row tx = app.db.one("SELECT * FROM transactions");
        ok(tx != null && tx.dbl("amount") == 350 && "Покупка\n350 ₽ PYATEROCHKA".equals(tx.text("raw")),
                "a bank notification goes to spending (title + text)");

        app = make();
        app.phone.ingest(l(waItem("Анна", false, "k", new Object[]{"Анна", "Привет, во сколько завтра встречаемся? Ответь срочно"})));
        app.phone.processReady(now());
        ok(llm(app).calls.isEmpty(), "a chat still going — waits for a pause");
        llm(app).jsonResponses.add(verdict(3, "Ответить Анне про время встречи", true, "Анна спрашивает про встречу", "", null, "personal"));
        app.phone.processReady(now().plusSeconds(120));
        Row card = app.db.one("SELECT * FROM mails WHERE kind='chat'");
        ok(card != null && "WhatsApp".equals(card.text("account")) && "Анна".equals(card.text("from_name"))
                && card.lng("importance") == 3 && "open".equals(card.text("status")), "after the pause — a card: urgent, open");
        ok(card.text("body").contains("Анна: Привет, во сколько"), "…its text is the chat's transcript");
        List<String> p = pushed(app);
        Row urgentMsg = app.db.one("SELECT * FROM chat_log WHERE notify=1 ORDER BY id DESC");
        ok(!p.isEmpty() && p.get(p.size() - 1).contains("Срочно") && p.get(p.size() - 1).contains("Чат: Анна")
                && urgentMsg.text("buttons").contains("✍️ Черновик"), "the urgent card at once, with «✍️ Черновик»");
        ok(app.db.q("SELECT * FROM phone_msgs WHERE item_id IS NULL").isEmpty(), "the messages marked as read");
        String user = llm(app).calls.get(0)[2];
        ok(user.contains("WhatsApp") && user.contains("Анна: Привет"), "the request has the app and the messages");

        app = make();
        app.phone.ingest(l(waItem("Анна", false, "k", new Object[]{"Анна", "раз"})));
        llm(app).jsonResponses.add(verdict(1, "", false, "s", "", null, "personal"));
        app.phone.processReady(now().plusSeconds(app.cfg.maxWaitSeconds + 1));
        ok(llm(app).calls.size() == 1, "a long conversation is read without a pause");

        app = make();
        app.phone.ingest(l(waItem("Мама", false, "k", new Object[]{null, "я написал сам"})));
        app.db.setSenderRule(Phone.chatKey("com.whatsapp", "Спам-чат"), "mute");
        app.phone.ingest(l(waItem("Спам-чат", false, "k2", new Object[]{"Бот", "Акция!"})));
        app.phone.processReady(now().plus(Duration.ofMinutes(5)));
        ok(llm(app).calls.isEmpty() && app.db.q("SELECT * FROM mails").isEmpty(), "own messages and muted chats — no model, no card");

        app = make();
        app.phone.ingest(l(waItem("Анна", false, "k", new Object[]{"Анна", "Ты где?"})));
        llm(app).jsonResponses.add(new LlmError("недоступна"));
        app.phone.processReady(now().plus(Duration.ofMinutes(5)));
        ok(app.db.q("SELECT * FROM mails").isEmpty(), "the model fails — tried again later");
        llm(app).jsonResponses.add(new LlmError("недоступна"));
        app.phone.processReady(now().plus(Duration.ofHours(2)));
        Row it = app.db.one("SELECT * FROM mails");
        ok(it != null && it.lng("importance") == 2 && it.text("summary").contains("не удалось"), "…after an hour shown without reading");

        app = make();
        app.phone.ingest(l(waItem("Анна", false, "k", new Object[]{"Анна", "Во сколько встречаемся?"})));
        llm(app).jsonResponses.add(verdict(2, "", true, "Анна спрашивает про встречу", "", null, "personal"));
        app.phone.processReady(now().plus(Duration.ofMinutes(5)));
        Row chatItem = app.db.one("SELECT * FROM mails WHERE kind='chat'");
        llm(app).textResponse = "Давай в 19:00 у входа";
        app.drafts.create(chatItem.lng("id"), "", null, null);
        Row last = lastChat(app);
        ok(last.text("html").contains("<code>Давай в 19:00 у входа</code>") && last.text("html").contains("Отправь сам в WhatsApp"),
                "a chat draft: copyable text, sent by hand");
        List<Object> kb = MiniJson.arr(MiniJson.parse(last.text("buttons")));
        List<Object> row0 = MiniJson.arr(kb.get(0));
        ok(row0.size() == 2 && "✏️ Изменить".equals(MiniJson.obj(row0.get(0)).get("text")) && "✖️ Отмена".equals(MiniJson.obj(row0.get(1)).get("text")),
                "…no «Отправить» button");
        long draftId = Long.parseLong(((String) MiniJson.obj(row0.get(0)).get("data")).split(":")[1]);
        ok(app.drafts.send(draftId).contains("скопируй"), "…and «send» says to copy it");

        app = make();
        llm(app).jsonResponses.add(m("reply", "Ок.", "actions", l(m("type", "mute_app", "app", "Ozon"))));
        ok(app.assistant.handle("уведомления озона не показывай").contains("Ozon"), "the assistant mutes an app");
        Map<String, Integer> st = app.phone.ingest(l(m("package", "ru.ozon.app.android", "app", "Ozon", "title", "Скидки", "text", "-50%")));
        ok(st.get("stored") == 0 && st.get("bank") == 0 && st.get("skipped") == 1, "…its notifications are skipped");

        // an old database gets the kind column
        java.io.File f = java.io.File.createTempFile("old", ".db");
        f.delete();
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + f);
        c.createStatement().execute("CREATE TABLE mails (id INTEGER PRIMARY KEY, account TEXT NOT NULL, folder TEXT NOT NULL, "
                + "uid TEXT NOT NULL, received_at TEXT NOT NULL, created_at TEXT NOT NULL, importance INTEGER)");
        c.createStatement().execute("INSERT INTO mails(account, folder, uid, received_at, created_at) VALUES('Gmail','INBOX','1','x','x')");
        c.close();
        SecDb old = new SecDb(new JdbcSql("jdbc:sqlite:" + f));
        ok("mail".equals(old.one("SELECT kind FROM mails").text("kind")), "an old database gets the kind column");
        f.delete();
    }

    // ---------- test_quiet_mode.py ----------
    static void quiet() {
        Secretary app = make();
        app.botNames.add("помощник");
        app.db.setSenderRule(Phone.chatKey("com.whatsapp", "Начальник"), "vip");
        app.db.setSenderRule(Phone.chatKey("com.whatsapp", "Чат дома"), "mute");
        List<Object> items = l(
                wa("k1", "Начальник", "зайди", null, false),
                wa("k2", "Сосед", "кто паркуется у ворот?", "Чат дома", true),
                plain("k3", "com.google.android.apps.messaging", "Сообщения", "900", "Код: 4821. Никому не говорите"),
                plain("k4", "ru.sberbankmobile", "СберБанк", "Покупка", "350 ₽ PYATEROCHKA"),
                plain("k5", "org.telegram.messenger", "Telegram", "Помощник", "Сводка"),
                wa("k6", "Анна", "ты сегодня придёшь на ужин?", null, false),
                plain("k7", "ru.ozon.app.android", "Ozon", "Скидки до 70%", "Только сегодня"),
                plain("k8", "com.android.systemui", "Система", "USB", "Зарядка"));
        llm(app).jsonResponses.add(m("items", l(m("i", 0L, "level", "urgent"), m("i", 1L, "level", "junk"))));
        Map<String, String> v = app.phone.verdicts(items);
        ok(m("k1", "urgent", "k2", "junk", "k3", "urgent", "k4", "normal", "k5", "skip", "k6", "urgent", "k7", "junk", "k8", "junk").equals(v),
                "Тишина: rules first, the model for the rest — " + v);
        String user = llm(app).calls.get(0)[2];
        ok(user.contains("[0] WhatsApp · Анна") && user.contains("[1] Ozon") && !user.contains("Начальник"), "…the model sees only the undecided");
        ok(!user.contains("Код"), "…codes never go to the model");

        app = make();
        v = app.phone.verdicts(l(plain("k1", "com.google.android.apps.messaging", "Сообщения", "Сбербанк", "Sensitive notification content hidden")));
        ok(m("k1", "urgent").equals(v) && llm(app).calls.isEmpty(), "a code hidden by Android 15 is shown");

        app = make();
        llm(app).jsonResponses.add(new LlmError("недоступна"));
        v = app.phone.verdicts(l(wa("k1", "Анна", "привет", null, false), wa("k2", "Олег", "всем привет", "Друзья", true),
                plain("k3", "ru.ozon.app.android", "Ozon", "Заказ", "В пути")));
        ok(m("k1", "urgent", "k2", "normal", "k3", "normal").equals(v), "the model fails — people shown, the rest hidden");

        app = make();
        Map<String, Object> item = wa("k1", "Анна", "ты сегодня придёшь на ужин? ответь срочно", null, false);
        app.phone.ingest(l(item));
        llm(app).jsonResponses.add(m("items", l(m("i", 0L, "level", "urgent"))));
        ok("urgent".equals(app.phone.verdicts(l(item)).get("k1")), "urgent in Тишина");
        llm(app).jsonResponses.add(verdict(3, "Ответить Анне", true, "Анна зовёт на ужин", "", null, "personal"));
        app.phone.processReady(now().plus(Duration.ofMinutes(5)));
        Row card = app.db.one("SELECT * FROM mails WHERE kind='chat'");
        ok(card.lng("importance") == 3 && card.lng("notified") == 1 && app.db.chatTail(5).isEmpty(),
                "the card for the digest, but no second ring");
    }

    // ---------- test_triage.py (its shared rules, on chats) ----------
    static void triage() {
        Secretary app = make();
        app.db.setSenderRule(Phone.chatKey("com.whatsapp", "Пётр"), "vip");
        app.phone.ingest(l(wa("k", "Пётр", "Посмотри договор", null, false)));
        llm(app).jsonResponses.add(verdict(1, "", true, "Суть", "", null, "work"));
        app.phone.processReady(now().plus(Duration.ofMinutes(5)));
        ok(app.db.one("SELECT importance FROM mails").lng("importance") == 3, "a vip chat is escalated");

        app = make();
        app.phone.ingest(l(wa("k", "Пётр", "Отчёт", null, false)));
        llm(app).jsonResponses.add(verdict(2, "Сдать отчёт", false, "Суть", iso(now().plus(Duration.ofHours(5))), null, "work"));
        app.phone.processReady(now().plus(Duration.ofMinutes(5)));
        ok(app.db.one("SELECT importance FROM mails").lng("importance") == 3 && pushed(app).size() == 1, "a deadline within a day — urgent");

        app = make();
        Instant start = now().plus(Duration.ofDays(1));
        Map<String, Object> ev = m("title", "Вебинар: асинхронный Python", "start", iso(start), "kind", "webinar", "url", "");
        Map<String, Object> ev2 = m("title", "Напоминание: вебинар асинхронный Python", "start", iso(start.plus(Duration.ofMinutes(5))),
                "kind", "webinar", "url", "https://zoom.us/j/1");
        app.phone.ingest(l(wa("k1", "Школа", "Вебинар завтра", null, false)));
        llm(app).jsonResponses.add(verdict(1, "", false, "Вебинар", "", l(ev), "study"));
        app.phone.processReady(now().plus(Duration.ofMinutes(5)));
        app.phone.ingest(l(wa("k2", "Школа", "Напоминаем о вебинаре", null, false)));
        llm(app).jsonResponses.add(verdict(1, "", false, "Вебинар", "", l(ev2), "study"));
        app.phone.processReady(now().plus(Duration.ofMinutes(10)));
        List<Row> events = app.db.q("SELECT * FROM events");
        ok(events.size() == 1 && "https://zoom.us/j/1".equals(events.get(0).text("url")), "a webinar from two messages is one event, with the link");

        app = make();
        app.phone.ingest(l(wa("k", "Магазин", "Реклама", null, false)));
        llm(app).jsonResponses.add(verdict(0, "", false, "Реклама", "", l(m("title", "Реклама", "start", iso(start), "kind", "webinar")), "promo"));
        app.phone.processReady(now().plus(Duration.ofMinutes(5)));
        ok(app.db.q("SELECT * FROM events").isEmpty(), "junk's events are not scheduled");

        app = make();
        app.phone.ingest(l(wa("k", "Пётр", "Текст про отчёт", null, false)));
        llm(app).jsonResponses.add(verdict(1, "", false, "Суть", "", null, "work"));
        app.phone.processReady(now().plus(Duration.ofMinutes(5)));
        String[] call = llm(app).calls.get(0);
        ok(call[1].contains("учусь на курсе Python") && call[1].contains("Europe/Moscow") && call[2].contains("Текст про отчёт"),
                "the request has the owner's profile, the time zone and the text");

        // a promo webinar is not scheduled; an announcement for all is not; a registration is
        app = make();
        long id = app.db.insertMail("Mail", "INBOX", item("1", "school@ex.ru", "Школа", "Открытый стрим", "Приходи"), "mail");
        Map<String, Object> e = m("title", "День открытых дверей", "start", iso(now().plus(Duration.ofDays(1))), "kind", "webinar");
        int imp = Triage.applyVerdict(app, id, verdict(3, "Посмотреть стрим", false, "Стрим", "", l(e), "promo"), null);
        ok(imp == 0 && app.db.openTasks(20).isEmpty(), "promo: importance 0, no task");
        Map<String, Object> notReg = new LinkedHashMap<String, Object>(e);
        notReg.put("registered", false);
        Triage.applyVerdict(app, id, verdict(1, "", false, "Анонс", "", l(notReg), "study"), null);
        ok(app.db.eventsBetween(now(), now().plus(Duration.ofDays(2))).isEmpty(), "an announcement for all — not into the schedule");
        Map<String, Object> reg = new LinkedHashMap<String, Object>(e);
        reg.put("registered", true);
        Triage.applyVerdict(app, id, verdict(1, "", false, "Ты записан", "", l(reg), "study"), null);
        ok(app.db.eventsBetween(now(), now().plus(Duration.ofDays(2))).size() == 1, "registered — into the schedule");
    }

    // ---------- test_tasks.py ----------
    static Map<String, Object> samokat(String key, String text) {
        return m("key", key, "package", "ru.samokat.android", "app", "Самокат", "title", "Самокат", "text", text, "posted", T0);
    }

    static int minutes = 5;

    static void triageSamokat(Secretary app, String text, Map<String, Object> v) {
        app.phone.ingest(l(samokat(text, text)));
        llm(app).jsonResponses.add(v);
        minutes += 5;
        app.phone.processReady(now().plus(Duration.ofMinutes(minutes)));
    }

    static void tasks() throws Exception {
        Secretary app = make();
        String due = iso(now().plus(Duration.ofDays(2)));
        triageSamokat(app, "Заказ ждёт в пункте выдачи до 25.10", verdict(1, "Забрать заказ Самоката в пункте выдачи", false, "Заказ Самоката", due, null, "shopping"));
        triageSamokat(app, "Напоминаем: заказ ждёт вас", verdict(1, "Забрать заказ Самоката из пункта выдачи", false, "Заказ Самоката", "", null, "shopping"));
        triageSamokat(app, "Заказ собирается", verdict(1, "", false, "Заказ Самоката", "", null, "shopping"));
        List<Row> open = app.db.openTasks(20);
        ok(open.size() == 1, "a delivery's notifications — one task (" + open.size() + ")");
        Row t = open.get(0);
        ok("Забрать заказ Самоката в пункте выдачи".equals(t.text("title")) && t.truthy("due_at") && t.text("source").startsWith("Самокат"),
                "…its title, deadline and source");
        app.drain();
        List<Row> news = new ArrayList<Row>();
        for (Row r : app.db.chatTail(10)) if (r.text("html").contains("Новое дело")) news.add(r);
        ok(news.size() == 1 && news.get(0).text("buttons").contains("tdone:" + t.lng("id")) && news.get(0).lng("notify") == 0
                && news.get(0).text("html").contains("Напомню"), "told quietly, once, with «Уже сделал»");
        ok(pushed(app).isEmpty(), "…no notification");
        Instant remind = SecUtil.fromDb(t.text("remind_at"));
        ok(remind.atZone(MSK).toLocalDate().isAfter(now().atZone(MSK).toLocalDate())
                && !remind.isAfter(SecUtil.fromDb(t.text("due_at")).minus(Duration.ofHours(6))), "asked not today but in a calm evening before the deadline");
        ok(app.tasks.due(now().plus(Duration.ofMinutes(16))).isEmpty() && app.tasks.due(remind).size() == 1
                && app.tasks.due(remind).get(0).lng("id") == t.lng("id"), "…and then «Сделал?» comes");

        app = make();
        Map<String, Object> voice = m("key", "v1", "package", "org.telegram.messenger", "app", "Telegram", "title", "Ярослав", "posted", T0,
                "messages", l(m("sender", "Ярослав", "text", "🎤 Голосовое сообщение", "time", T0)));
        app.phone.ingest(l(voice));
        app.phone.ingest(l(m("type", "removed", "package", "org.telegram.messenger", "app", "Telegram", "title", "Ярослав",
                "reason", "click", "posted", now().toEpochMilli())));
        llm(app).jsonResponses.add(verdict(2, "Прослушать голосовое Ярослава", false, "Голосовое", "", null, "personal"));
        llm(app).jsonResponses.add(m("tasks", l()));
        app.phone.processReady(now().plus(Duration.ofMinutes(5)));
        ok(app.db.openTasks(20).isEmpty(), "already opened — no «listen» task");
        String key = Phone.chatKey("org.telegram.messenger", "Ярослав");
        app.db.addSignal(key, "opened", "", null);
        long mid = app.db.one("SELECT id FROM mails WHERE kind='chat'").lng("id");
        app.db.saveTriage(mid, 2, "personal", "", "Ответить Ярославу", true, null, "open");
        long[] r = app.tasks.fromItem(mid);
        ok(r != null && r[1] == 1, "…but «answer» stays: opening is not answering");

        app = make();
        triageSamokat(app, "Скидка 50%", verdict(0, "Купить со скидкой", false, "Скидка", "", null, "promo"));
        ok(app.db.openTasks(20).isEmpty(), "junk — no task");

        Instant n = msk(2026, 10, 22, 14, 0);
        ok(Tasks.snoozeUntil("30m", n, MSK).equals(n.plus(Duration.ofMinutes(30))) && Tasks.snoozeUntil("2h", n, MSK).equals(n.plus(Duration.ofHours(2)))
                && Tasks.snoozeUntil("evening", n, MSK).equals(msk(2026, 10, 22, 19, 0))
                && Tasks.snoozeUntil("evening", msk(2026, 10, 22, 18, 45), MSK).equals(msk(2026, 10, 23, 19, 0))
                && Tasks.snoozeUntil("tomorrow", n, MSK).equals(msk(2026, 10, 23, 9, 0)), "snooze times");
        ok(Tasks.acceptUntil(n.plus(Duration.ofDays(1)), n).equals(n.plus(Duration.ofDays(1)).minus(Duration.ofHours(2)))
                && Tasks.acceptUntil(null, n).equals(n.plus(Duration.ofHours(3))), "«Принял» times");

        app = make();
        long[] a = app.tasks.add("Оплатить интернет", null);
        ok(a[1] == 1 && app.tasks.add("оплатить интернет", null)[1] == 0, "the same task is not added twice");
        Instant now = now();
        ok(app.tasks.due(now.plusSeconds(1)).size() == 1, "due at once");
        app.tasks.act(a[0], "asked", "");
        ok(app.tasks.due(now.plus(Duration.ofMinutes(5))).isEmpty() && app.tasks.due(now.plus(Duration.ofMinutes(31))).size() == 1,
                "asked — not again for 30 minutes");
        ok(app.tasks.act(a[0], "snooze", "2h").contains("Отложил") && app.tasks.due(now.plus(Duration.ofHours(1))).isEmpty()
                && app.tasks.due(now.plus(Duration.ofMinutes(121))).size() == 1, "snoozed for 2 hours");
        ok(app.tasks.act(a[0], "accept", "").contains("Принято") && "accepted".equals(app.db.getTask(a[0]).text("status"))
                && app.tasks.due(now.plus(Duration.ofHours(2))).isEmpty(), "accepted");
        ok(app.tasks.act(a[0], "done", "").contains("Готово") && app.db.openTasks(20).isEmpty()
                && app.tasks.act(a[0], "done", "").contains("уже закрыто"), "done, and closed stays closed");

        app = make();
        long tid = app.tasks.add("Записаться к врачу", null)[0];
        Actions.command(app, "tasks", "app");
        Row msg = lastChat(app);
        ok(msg.text("html").contains("Записаться к врачу") && msg.text("buttons").contains("tdone:" + tid), "«Дела» in the chat with buttons");
        ok(Actions.button(app, "tsnz", tid, "app") == null && lastChat(app).text("buttons").contains("teve:"), "⏰ asks when");
        ok(Actions.button(app, "teve", tid, "app").contains("Отложил") && Actions.button(app, "tdone", tid, "app").contains("Готово"),
                "evening, then done");

        app = make();
        triageSamokat(app, "Курьер у двери", verdict(3, "Встретить курьера", false, "Курьер", "", null, "shopping"));
        Actions.button(app, "done", app.db.one("SELECT id FROM mails WHERE kind='chat'").lng("id"), "app");
        ok(app.db.openTasks(20).isEmpty(), "«✅ Сделано» on the card closes its task");

        app = make();
        llm(app).jsonResponses.add(m("reply", "Ок.", "actions", l(m("type", "add_task", "text", "Купить корм коту"))));
        ok(app.assistant.handle("добавь дело купить корм коту").contains("Записал дело"), "the assistant adds a task");
        long taskId = app.db.openTasks(20).get(0).lng("id");
        llm(app).jsonResponses.add(m("reply", "Отлично.", "actions", l(m("type", "done", "id", taskId))));
        ok(app.assistant.handle("купил корм").contains("Готово") && llm(app).calls.get(1)[2].contains("[дело " + taskId + "] Купить корм коту"),
                "…and closes it by its number");

        app = make();
        Api api = new Api(app);
        long pid = app.tasks.add("Забрать посылку", null)[0];
        Map<String, Object> data = MiniJson.obj(MiniJson.parse(api.handle("/api/tasks?due=1", null).json));
        ok("Забрать посылку".equals(MiniJson.obj(MiniJson.arr(data.get("tasks")).get(0)).get("title")), "API: tasks due");
        api.handle("/api/task", "{\"id\": " + pid + ", \"action\": \"asked\"}");
        ok(MiniJson.arr(MiniJson.obj(MiniJson.parse(api.handle("/api/tasks?due=1", null).json)).get("tasks")).isEmpty(), "API: asked — not due");
        Api.Reply rr = api.handle("/api/task", "{\"id\": " + pid + ", \"action\": \"snooze\", \"choice\": \"evening\"}");
        ok(((String) MiniJson.obj(MiniJson.parse(rr.json)).get("reply")).contains("Отложил") && lastChat(app).text("html").contains("Отложил"),
                "API: snooze, the answer in the chat too");
        ok(api.handle("/api/task", "{\"id\": " + pid + ", \"action\": \"rm\"}").status == 400, "API: an unknown action — 400");
    }

    // ---------- test_close_and_plan.py ----------
    static void closeAndPlan() throws Exception {
        Secretary app = make();
        String key = Phone.chatKey("org.telegram.messenger", "Федор");
        app.db.addSignal(key, "opened", "", now().minus(Duration.ofDays(1)));
        app.db.addSignal(key, "opened", "", now().minus(Duration.ofDays(3)));
        app.db.setSenderRule(Phone.chatKey("org.telegram.messenger", "Мама"), "plain");
        List<Object> items = l(tg("k1", "Даша Девушка Моя", "😎 Стикер", false, T0), tg("k2", "Федор", "Фотография", false, T0),
                tg("k3", "Мама", "Фотография", false, T0), tg("k4", "Мамедов Руслан", "ок", false, T0),
                tg("k5", "Бот в МАКСЕ", "Готово", false, T0), tg("k6", "Мама", "всем привет", true, T0));
        llm(app).jsonResponses.add(m("items", l(m("i", 0L, "level", "normal"), m("i", 1L, "level", "normal"),
                m("i", 2L, "level", "junk"), m("i", 3L, "level", "normal"))));
        Map<String, String> v = app.phone.verdicts(items);
        ok("urgent".equals(v.get("k1")) && "urgent".equals(v.get("k2")), "close people — always urgent, no model");
        ok("normal".equals(v.get("k3")) && "normal".equals(v.get("k4")), "«not close» and a look-alike surname — the model decides");
        ok("junk".equals(v.get("k5")) && "normal".equals(v.get("k6")), "bots and groups are never «close»");
        String user = llm(app).calls.get(0)[2];
        ok(!user.contains("Даша") && !user.contains("Федор"), "close people never go to the model");

        app = make();
        long recent = now().toEpochMilli();
        app.phone.ingest(l(tg("k1", "Даша Девушка Моя", "привет", false, recent), tg("k2", "Лёха", "го", false, recent)));
        for (String chat : new String[]{"Даша Девушка Моя", "Лёха"})
            app.db.insertMail("Telegram", "org.telegram.messenger", item("phone:" + chat, Phone.chatKey("org.telegram.messenger", chat), chat, chat, "…"), "chat");
        Map<String, Object> people = Actions.view(app, "people", 0);
        List<Map<String, Object>> close = new ArrayList<Map<String, Object>>(), others = new ArrayList<Map<String, Object>>();
        for (Object o : MiniJson.arr(people.get("blocks"))) {
            Map<String, Object> b = MiniJson.obj(o);
            List<Object> bs = MiniJson.arr(b.get("buttons"));
            if (bs.isEmpty()) continue;
            String d = (String) MiniJson.obj(MiniJson.arr(bs.get(0)).get(0)).get("data");
            if (d.startsWith("plain:")) close.add(b);
            if (d.startsWith("close:")) others.add(b);
        }
        ok(close.size() == 1 && ((String) close.get(0).get("html")).contains("Даша") && others.size() == 1
                && ((String) others.get(0).get("html")).contains("Лёха"), "«Близкие»: who is close, who else writes");
        String data = (String) MiniJson.obj(MiniJson.arr(MiniJson.arr(others.get(0).get("buttons")).get(0)).get(0)).get("data");
        ok(Actions.button(app, "close", Long.parseLong(data.split(":")[1]), "app").contains("близкий")
                && app.phone.isClose("org.telegram.messenger", "Лёха", null), "⭐ makes him close");

        app = make();
        Instant n = msk(2026, 10, 25, 12, 0), due = msk(2026, 10, 28, 12, 0);
        for (String title : new String[]{"Сдать отчёт", "Позвонить в банк"})
            app.db.addTask(title, "", "", "", null, null, msk(2026, 10, 26, 19, 0));
        app.db.upsertEvent("Вебинар", msk(2026, 10, 26, 20, 0), null, "", "webinar", "manual");
        ok(app.tasks.plan(due, n).equals(msk(2026, 10, 27, 19, 0)), "a deadline on Wednesday — the calmer Tuesday evening");
        ok(app.tasks.plan(msk(2026, 10, 25, 22, 0), n).equals(msk(2026, 10, 25, 16, 0)), "due tonight — 6 hours before");
        ok(app.tasks.plan(msk(2026, 10, 26, 10, 0), n).equals(msk(2026, 10, 25, 21, 0)), "6 hours before is night — 21:00 the evening before");
        ok(app.tasks.plan(n.plus(Duration.ofMinutes(5)), n).equals(n), "already burning — now");

        app = make();
        long a = app.tasks.add("Забрать посылку", null)[0], b = app.tasks.add("Пополнить Beget", null)[0];
        app.tasks.act(b, "snooze", "tomorrow");
        Map<String, Object> view = Actions.view(app, "tasks", 0);
        List<Object> blocks = MiniJson.arr(view.get("blocks"));
        ok("Дела · 1".equals(view.get("title")) && ((String) MiniJson.obj(blocks.get(0)).get("html")).contains("Забрать посылку")
                && "Позже · 1".equals(MiniJson.obj(blocks.get(1)).get("section"))
                && ((String) MiniJson.obj(blocks.get(2)).get("html")).contains("Пополнить Beget"), "a snoozed task lower, apart, not counted");
        List<Object> act = MiniJson.arr(MiniJson.obj(MiniJson.parse(new Api(app).handle("/api/tasks?active=1", null).json)).get("tasks"));
        ok(act.size() == 1 && ((Number) MiniJson.obj(act.get(0)).get("id")).longValue() == a, "API: active tasks without the snoozed");

        app = make();
        for (int i = 0; i < 3; i++)
            llm(app).jsonResponses.add(m("is_transaction", true, "direction", "expense", "amount", 627L, "currency", "RUB",
                    "merchant", "Самокат", "category", "Продукты", "card", "null"));
        String text = "Покупка в Самокат 627 ₽ — Осталось: 13 103,70 ₽ Счёт карты МИР 7526";
        Instant posted = now().minus(Duration.ofHours(2));
        long tx = app.finance.handleNotification(text, "СберБанк", posted);
        ok(app.finance.handleNotification(text, "СберБанк", posted) == -1, "the same bank notification again from the shade — not twice");
        Row row = app.db.one("SELECT * FROM transactions WHERE id=?", tx);
        ok(SecUtil.fromDb(row.text("ts")).equals(posted) && "".equals(row.text("card"))
                && app.db.one("SELECT count(*) n FROM transactions").lng("n") == 1, "…time of the notification, «null» card dropped");

        app = make();
        Object[][] ops = {{"Самокат", "Продукты", 627}, {"Пятёрочка", "Продукты", 350}, {"Яндекс Go", "Такси", 412},
                {"ВкусВилл", "Продукты", 980}, {"Лавка", "Продукты", 215}, {"Магнит", "Продукты", 99}};
        for (int i = 0; i < ops.length; i++)
            app.db.addTx(now().minus(Duration.ofMinutes(10L * i)), "Сбер", "expense", ((Integer) ops[i][2]).doubleValue(), "RUB",
                    (String) ops[i][0], SecUtil.lower((String) ops[i][0]), (String) ops[i][1], "", false, (String) ops[i][0], "");
        app.db.addTx(now(), "Сбер", "transfer_in", 6000, "RUB", "Ярослав Б.", "", "Переводы", "", false, "перевод", "");
        Map<String, Object> sv = Actions.view(app, "spent", 0);
        StringBuilder html = new StringBuilder();
        Map<String, Object> food = null;
        for (Object o : MiniJson.arr(sv.get("blocks"))) {
            Map<String, Object> bl = MiniJson.obj(o);
            html.append(bl.get("html")).append('\n');
            if (((String) bl.get("html")).startsWith("<b>Продукты</b>")) food = bl;
        }
        ok(html.toString().contains("Самокат") && html.toString().contains("−627 ₽") && html.toString().contains("Яндекс Go")
                && html.toString().contains("+6 000 ₽"), "«Расходы»: operations with signs");
        String foodData = (String) MiniJson.obj(MiniJson.arr(MiniJson.arr(food.get("buttons")).get(0)).get(0)).get("data");
        ok(((String) food.get("html")).contains("и ещё 1") && foodData.startsWith("cat:"), "a category shows 4, the rest by a button");
        Map<String, Object> cat = Actions.view(app, "cat", Long.parseLong(foodData.split(":")[1]));
        StringBuilder ch = new StringBuilder();
        for (Object o : MiniJson.arr(cat.get("blocks"))) ch.append(MiniJson.obj(o).get("html"));
        ok("Продукты".equals(cat.get("title")) && ch.toString().contains("Магнит"), "…the category's all operations");
        Map<String, Object> hist = Actions.view(app, "ops", 0);
        String first = (String) MiniJson.obj(MiniJson.arr(hist.get("blocks")).get(0)).get("html");
        ok(first.startsWith("<b>Сегодня</b>") && first.contains("Самокат · Продукты"), "history by day, with categories");
    }

    // ---------- test_resolve.py ----------
    static long makeChatTask(Secretary app, String title) {
        long id = app.db.insertMail("WhatsApp", "com.whatsapp", item("c1", Phone.chatKey("com.whatsapp", "Анна"), "Анна", "Анна", "..."), "chat");
        app.db.saveTriage(id, 2, "personal", "Анна зовёт на ужин", title, true, null, "open");
        return app.tasks.fromItem(id)[0];
    }

    static Map<String, Object> waSelf(String key, String text, long t) {
        return m("key", key, "package", "com.whatsapp", "app", "WhatsApp", "title", "Анна", "text", text, "posted", t,
                "messages", l(m("sender", "", "text", text, "time", t, "self", true)));
    }

    static void resolve() throws Exception {
        long t1 = now().plus(Duration.ofMinutes(1)).toEpochMilli();
        Secretary app = make();
        long task = makeChatTask(app, "Ответить Анне про ужин");
        app.phone.ingest(l(waSelf("k1", "Да, буду в 7", t1)));
        llm(app).jsonResponses.add(m("tasks", l(m("id", task, "status", "done", "confidence", 0.95, "reason", "ты ответил: «Да, буду в 7»"))));
        app.phone.processReady(now());
        app.drain();
        ok("done".equals(app.db.getTask(task).text("status")), "an answer in the chat closes «Ответить»");
        Row note = lastChat(app);
        ok(note.text("html").contains("Закрыл сам") && note.text("html").contains("буду в 7") && note.text("buttons").contains("treo:" + task)
                && note.lng("notify") == 0, "…told quietly, with «Вернуть»");
        String user = llm(app).calls.get(0)[2];
        ok(user.contains("Я: Да, буду в 7") && user.contains("[" + task + "] Ответить Анне"), "the judge sees the evidence and the task");
        ok(Actions.button(app, "treo", task, "app").contains("Вернул") && "open".equals(app.db.getTask(task).text("status")), "↩️ returns it");

        app = make();
        task = makeChatTask(app, "Ответить Анне про ужин");
        app.phone.ingest(l(m("type", "removed", "package", "com.whatsapp", "app", "WhatsApp", "title", "Анна", "reason", "app_cancel", "posted", t1)));
        llm(app).jsonResponses.add(m("tasks", l(m("id", task, "status", "unclear", "confidence", 0.4, "reason", "открыл чат, ответа не видно"))));
        app.phone.processReady(now());
        ok("open".equals(app.db.getTask(task).text("status"))
                && ((String) app.tasks.asJson(app.db.getTask(task)).get("hint")).startsWith("Ты открывал этот чат"), "low confidence — kept, with a hint");

        app = make();
        long reply = makeChatTask(app, "Ответить Анне про ужин");
        long other = app.db.addTask("Купить торт к ужину", "", "", Phone.chatKey("com.whatsapp", "Анна"), null, null, null)[0];
        app.phone.ingest(l(waSelf("k1", "ок", t1)));
        llm(app).jsonResponses.add(new LlmError("недоступна"));
        app.phone.processReady(now());
        app.drain();
        ok("done".equals(app.db.getTask(reply).text("status")) && "open".equals(app.db.getTask(other).text("status")),
                "the model down — only the obvious «answer» closed");

        app = make();
        String key = Phone.chatKey("ru.samokat.android", "Самокат");
        task = app.db.addTask("Забрать заказ Самоката", "", "", key, null, null, null)[0];
        app.phone.ingest(l(m("key", "s1", "package", "ru.samokat.android", "app", "Самокат", "title", "Самокат", "text", "Заказ получен. Спасибо!", "posted", t1)));
        llm(app).jsonResponses.add(verdict(0, "", false, "Заказ получен", "", null, "shopping"));
        llm(app).jsonResponses.add(m("tasks", l(m("id", task, "status", "done", "confidence", 0.9, "reason", "Самокат: «Заказ получен»"))));
        app.phone.processReady(now().plus(Duration.ofMinutes(5)));
        app.drain();
        ok("done".equals(app.db.getTask(task).text("status")), "«order received» closes «pick up the order»");
    }

    // ---------- test_finance.py ----------
    static Map<String, Object> tx(double amount, String direction, String merchant, String category) {
        return m("is_transaction", true, "direction", direction, "amount", amount, "currency", "RUB", "merchant", merchant,
                "category", category, "card", "1234");
    }

    static Map<String, Object> tx() {
        return tx(350.0, "expense", "PYATEROCHKA 1234", "Продукты");
    }

    static void finance() {
        ok(Finance.looksSecret("Код: 4821. Никому не сообщайте его") && Finance.looksSecret("Код подтверждения для входа 123456")
                && Finance.looksSecret("Никому не говорите этот код") && !Finance.looksSecret("MIR-1234 14:05 Покупка 350р PYATEROCHKA Баланс: 12 345.67р"),
                "confirmation codes are recognized");
        ok(Finance.findAmounts("Покупка 1 250,50 ₽ PYATEROCHKA Баланс: 12 345.67р").equals(Arrays.asList(1250.5, 12345.67))
                && Finance.findAmounts("Оплата 350р").equals(Arrays.asList(350.0))
                && Finance.findAmounts("Перевод 5 000 ₽ от Анна С.").equals(Arrays.asList(5000.0))
                && Finance.findAmounts("Покупка 12.99 USD Netflix").equals(Arrays.asList(12.99))
                && Finance.findAmounts("Ваш заказ 123456 готов").isEmpty()
                && Finance.findAmounts("Списание 300 рублей").equals(Arrays.asList(300.0))
                && Finance.findAmounts("Покупка 350 ₽").equals(Arrays.asList(350.0)), "sums found in bank texts");
        ok("pyaterochka".equals(Finance.merchantKey("PYATEROCHKA 1234")) && "pyaterochka".equals(Finance.merchantKey("Pyaterochka  5678"))
                && "яндекс go".equals(Finance.merchantKey("Яндекс Go")), "merchant keys");

        Secretary app = make();
        String text = "MIR-1234 14:05 Покупка 350р PYATEROCHKA 1234 Баланс: 12 345.67р";
        llm(app).jsonResponses.add(tx());
        long id = app.finance.handleNotification(text, "Сбер", null);
        Row row = app.db.one("SELECT * FROM transactions WHERE id=?", id);
        ok(id > 0 && row.dbl("amount") == 350 && "Продукты".equals(row.text("category")) && row.lng("flagged") == 0 && pushed(app).isEmpty(),
                "a purchase written, no message");
        ok(app.finance.handleNotification(text, "Сбер", null) == -1, "the same text again — not twice");
        llm(app).jsonResponses.add(tx());
        ok(app.finance.handleNotification("Покупка 350 ₽ PYATEROCHKA", "SMS 900", null) == -1 && llm(app).calls.size() == 2, "push + SMS of one purchase — one");

        app = make();
        ok(app.finance.handleNotification("Код: 4821. Никому не сообщайте. Оплата 500р", "Сбер", null) == -1 && llm(app).calls.isEmpty()
                && app.db.one("SELECT COUNT(*) n FROM transactions").lng("n") == 0, "a secret never goes to the model nor is stored");
        ok(app.finance.handleNotification("Ваша заявка одобрена", "Сбер", null) == -1 && llm(app).calls.isEmpty(), "not about money — ignored");

        app = make();
        llm(app).jsonResponses.add(tx(999.0, "expense", "PYATEROCHKA 1234", "Продукты"));
        id = app.finance.handleNotification("Покупка 350р PYATEROCHKA Баланс: 12 345.67р", "Сбер", null);
        row = app.db.one("SELECT * FROM transactions WHERE id=?", id);
        ok(row.lng("flagged") == 1 && row.dbl("amount") == 350 && pushed(app).get(0).contains("Не уверен"), "a sum not in the text — corrected and flagged");

        app = make();
        llm(app).jsonResponses.add(tx(15000.0, "expense", "DNS", "Дом"));
        app.finance.handleNotification("Покупка 15 000р DNS", "Сбер", null);
        ok(pushed(app).get(0).contains("Крупное списание") && pushed(app).get(0).contains("15 000 ₽"), "a large expense — told at once");

        app = make();
        llm(app).jsonResponses.add(new LlmError("нет связи"));
        id = app.finance.handleNotification("Покупка 350р PYATEROCHKA", "Сбер", null);
        row = app.db.one("SELECT * FROM transactions WHERE id=?", id);
        ok(row.dbl("amount") == 350 && "expense".equals(row.text("direction")) && row.lng("flagged") == 1, "the model fails — still written, flagged");

        app = make();
        app.db.setMerchantCategory("pyaterochka", "Кафе и рестораны");
        llm(app).jsonResponses.add(tx());
        id = app.finance.handleNotification("Покупка 350р PYATEROCHKA 1234", "Сбер", null);
        ok("Кафе и рестораны".equals(app.db.one("SELECT category FROM transactions WHERE id=?", id).text("category")), "the owner's category wins");

        app = make();
        llm(app).jsonResponses.add(tx());
        llm(app).jsonResponses.add(tx(700.0, "expense", "Яндекс Go", "Такси"));
        llm(app).jsonResponses.add(tx(5000.0, "transfer_out", "Анна С.", "Переводы"));
        app.finance.handleNotification("Покупка 350р PYATEROCHKA", "Сбер", null);
        app.finance.handleNotification("Оплата 700 ₽ Яндекс Go", "Яндекс", null);
        app.finance.handleNotification("Перевод 5 000 ₽ Анна С.", "Сбер", null);
        String report = app.finance.monthReport();
        ok(report.contains("1 050 ₽") && report.contains("Такси: 700 ₽") && report.contains("Переводы людям: 5 000 ₽")
                && app.finance.dayLine().contains("Сегодня потрачено"), "the month's report and today's line");
    }

    // ---------- test_digest_scheduler.py ----------
    static long addMail(Secretary app, String uid, int importance, String summary, String status, String action) {
        long id = app.db.insertMail("Gmail", "INBOX", item(uid, "a" + uid + "@x.ru", "Отправитель " + uid, "Тема", "..."), "mail");
        app.db.saveTriage(id, importance, "other", summary, action, false, null, status);
        return id;
    }

    static void digestSchedule() {
        Secretary app = make();
        ok(Digests.build(app, "morning", null) == null, "nothing — no digest");
        addMail(app, "1", 2, "Счёт за интернет", "info", "");
        addMail(app, "2", 1, "Заказ передан в доставку", "info", "");
        addMail(app, "3", 0, "Скидки", "info", "");
        long openId = addMail(app, "4", 2, "Просит отчёт", "open", "Отправить отчёт");
        long tid = app.tasks.fromItem(openId)[0];
        app.db.upsertEvent("Занятие по Python", now().plus(Duration.ofHours(3)), null, "https://zoom.us/j/1", "class", "mail:1");
        Object[] built = Digests.build(app, "morning", now());
        String text = (String) built[0];
        boolean all = true;
        for (String part : new String[]{"Утренняя сводка", "Счёт за интернет", "Заказ передан", "Отсеял рассылок и рекламы: 1",
                "Ждут тебя (1)", "Отправить отчёт", "Занятие по Python", "zoom.us"})
            if (!text.contains(part)) {
                all = false;
                System.out.println("     missing: " + part);
            }
        ok(all, "the morning digest's sections");
        List<String[]> row = ((io.github.teoplaydor.semsearch.secretary.Kb) built[1]).rows.get(0);
        ok(row.size() == 3 && ("tdone:" + tid).equals(row.get(0)[1]) && ("tsnz:" + tid).equals(row.get(1)[1]) && ("full:" + openId).equals(row.get(2)[1]),
                "…buttons of the task");

        app = make();
        addMail(app, "1", 2, "Счёт за интернет", "info", "");
        ok(Digests.send(app, "evening", false, null), "a digest sent");
        ok(Digests.build(app, "evening", null) == null, "…the next one shows only what is new");

        app = make();
        ok(!Digests.send(app, "morning", true, null) && pushed(app).get(0).contains("Тихо"), "asked by hand with nothing new — «Тихо»");

        Instant start = Instant.parse("2026-10-23T16:00:00Z");
        List<Integer> off = Arrays.asList(60, 10);
        ok(dueOffsets(start, new int[]{}, off, start.minus(Duration.ofMinutes(90))).isEmpty()
                && dueOffsets(start, new int[]{}, off, start.minus(Duration.ofMinutes(59))).equals(Arrays.asList(60))
                && dueOffsets(start, new int[]{60}, off, start.minus(Duration.ofMinutes(30))).isEmpty()
                && dueOffsets(start, new int[]{60}, off, start.minus(Duration.ofMinutes(9))).equals(Arrays.asList(10))
                && dueOffsets(start, new int[]{}, off, start.minus(Duration.ofMinutes(5))).equals(Arrays.asList(60, 10))
                && dueOffsets(start, new int[]{}, off, start.plus(Duration.ofMinutes(10))).isEmpty(), "when to remind before an event");

        app = make();
        Instant n = now();
        app.db.upsertEvent("Вебинар", n.plus(Duration.ofMinutes(50)), null, "https://zoom.us/j/1", "webinar", "x");
        Schedule.tick(app, n);
        Schedule.tick(app, n.plusSeconds(30));
        ok(countContains(pushed(app), "Через") == 1 && anyContains(pushed(app), "Вебинар"), "an hour before — once");
        Schedule.tick(app, n.plus(Duration.ofMinutes(41)));
        ok(countContains(pushed(app), "Через") == 2, "…and 10 minutes before");

        app = make();
        app.db.addReminder("Позвонить в банк", n.minus(Duration.ofMinutes(1)));
        Schedule.tick(app, n);
        Schedule.tick(app, n);
        ok(countContains(pushed(app), "Позвонить в банк") == 1, "a reminder once");

        app = make();
        addMail(app, "1", 2, "Счёт за интернет", "info", "");
        Instant slot = ZonedDateTime.of(n.atZone(MSK).toLocalDate(), LocalTime.of(19, 5), MSK).toInstant();
        Schedule.tick(app, slot);
        Schedule.tick(app, slot.plus(Duration.ofMinutes(1)));
        ok(countContains(pushed(app), "Вечерняя сводка") == 1, "the evening digest once at its time");

        app = make();
        app.db.upsertEvent("Встреча", now().plus(Duration.ofHours(2)), null, "", "meeting", "manual");
        Object[] ag = Digests.agenda(app, true);
        ok(((String) ag[0]).contains("Встреча") && ag[1] == null, "«Сегодня»");
    }

    static List<Integer> dueOffsets(Instant start, int[] reminded, List<Integer> offsets, Instant now) {
        java.util.Set<Integer> r = new java.util.HashSet<Integer>();
        for (int x : reminded) r.add(x);
        try {
            java.lang.reflect.Method mm = Schedule.class.getDeclaredMethod("dueOffsets", Instant.class, java.util.Set.class, List.class, Instant.class);
            mm.setAccessible(true);
            @SuppressWarnings("unchecked") List<Integer> out = (List<Integer>) mm.invoke(null, start, r, offsets, now);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- test_webapp.py ----------
    static List<Object> messages(Api api, String q) throws Exception {
        return MiniJson.arr(MiniJson.obj(MiniJson.parse(api.handle("/api/messages?" + q, null).json)).get("messages"));
    }

    static void webapp() throws Exception {
        Secretary app = make();
        final Api api = new Api(app);
        app.send("<b>🔴 Срочно</b> письмо");
        Map<String, Object> msg = MiniJson.obj(messages(api, "after=-1").get(0));
        ok("bot".equals(msg.get("role")) && ((String) msg.get("html")).contains("Срочно") && Boolean.TRUE.equals(msg.get("notify")),
                "the secretary's own message — with a notification");

        app = make();
        final Api api2 = new Api(app);
        final List<Object>[] got = new List[1];
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    got[0] = messages(api2, "after=0&wait=10");
                } catch (Exception e) {
                    got[0] = null;
                }
            }
        });
        t.start();
        Thread.sleep(100);
        ok(t.isAlive(), "the long poll waits");
        long t0 = System.currentTimeMillis();
        app.send("Привет", null, "app");
        t.join(2000);
        ok(!t.isAlive() && got[0] != null && got[0].size() == 1 && "Привет".equals(MiniJson.obj(got[0].get(0)).get("html"))
                && Boolean.FALSE.equals(MiniJson.obj(got[0].get(0)).get("notify")), "…wakes at a new message (" + (System.currentTimeMillis() - t0) + " ms)");

        app = make();
        Api api3 = new Api(app);
        llm(app).jsonResponses.add(m("reply", "Завтра свободно.", "actions", l()));
        ok(api3.handle("/api/message", "{\"text\": \"что у меня <завтра>?\"}").status == 200, "a message accepted");
        app.drain();
        List<Object> ms = messages(api3, "after=0");
        ok(ms.size() == 2 && "что у меня &lt;завтра&gt;?".equals(MiniJson.obj(ms.get(0)).get("html"))
                && "Завтра свободно.".equals(MiniJson.obj(ms.get(1)).get("html")), "…the question escaped, the answer back");
        ok(api3.handle("/api/message", "{\"text\": \"  \"}").status == 400, "an empty message — 400");

        app = make();
        Api api4 = new Api(app);
        llm(app).jsonResponses.add(m("reply", "Завтра вебинар в 19:00.", "actions", l()));
        api4.handle("/api/voice", "{\"text\": \"что у меня завтра\"}");
        app.drain();
        ms = messages(api4, "after=0");
        ok("🎙 <i>что у меня завтра</i>".equals(MiniJson.obj(ms.get(0)).get("html")) && "Завтра вебинар в 19:00.".equals(MiniJson.obj(ms.get(1)).get("html")),
                "a voice message heard on the phone, answered");

        app = make();
        Api api5 = new Api(app);
        app.db.upsertEvent("Встреча", now().plus(Duration.ofHours(2)), null, "", "meeting", "manual");
        api5.handle("/api/command", "{\"name\": \"today\"}");
        app.drain();
        ms = messages(api5, "after=0");
        ok("Сегодня".equals(MiniJson.obj(ms.get(0)).get("html")) && ((String) MiniJson.obj(ms.get(1)).get("html")).contains("Встреча"), "a command");
        ok(api5.handle("/api/command", "{\"name\": \"rm -rf\"}").status == 400, "an unknown command — 400");
        long mailId = app.db.insertMail("Gmail", "INBOX", item("1", "boss@company.ru", "Пётр", "Договор", "..."), "mail");
        long draftId = app.db.addDraft(mailId, "Ок");
        long msgId = app.db.addChat("bot", "черновик", "[[{\"text\": \"✖️ Отмена\", \"data\": \"cancel:" + draftId + "\"}]]", false);
        int before = app.db.chatTail(100).size();
        Map<String, Object> d = MiniJson.obj(MiniJson.parse(api5.handle("/api/button", "{\"action\": \"cancel\", \"id\": " + draftId + ", \"msg\": " + msgId + "}").json));
        Map<String, Object> edited = MiniJson.obj(d.get("message"));
        ok("cancelled".equals(app.db.getDraft(draftId).text("status")) && "черновик\n<i>Черновик отменён.</i>".equals(edited.get("html"))
                && MiniJson.arr(edited.get("buttons")).isEmpty() && "[]".equals(app.db.getChat(msgId).text("buttons"))
                && app.db.chatTail(100).size() == before, "a button edits its own message, no new one");

        app = make();
        Api api6 = new Api(app);
        long tid = app.tasks.add("Забрать посылку", null)[0];
        before = app.db.chatTail(100).size();
        Map<String, Object> v = MiniJson.obj(MiniJson.parse(api6.handle("/api/view?name=tasks", null).json));
        Map<String, Object> b0 = MiniJson.obj(MiniJson.arr(v.get("blocks")).get(0));
        List<Object> btns = MiniJson.arr(MiniJson.arr(b0.get("buttons")).get(0));
        ok("Дела · 1".equals(v.get("title")) && ((String) b0.get("html")).contains("Забрать посылку")
                && ("tdone:" + tid).equals(MiniJson.obj(btns.get(0)).get("data")) && ("tsnz:" + tid).equals(MiniJson.obj(btns.get(1)).get("data")),
                "the «Дела» screen");
        ok(((String) MiniJson.obj(MiniJson.parse(api6.handle("/api/button", "{\"action\": \"t2h\", \"id\": " + tid + ", \"msg\": 0}").json)).get("reply")).contains("Отложил")
                && ((String) MiniJson.obj(MiniJson.parse(api6.handle("/api/button", "{\"action\": \"tdone\", \"id\": " + tid + ", \"msg\": 0}").json)).get("reply")).contains("Готово"),
                "buttons on a screen answer with a toast");
        v = MiniJson.obj(MiniJson.parse(api6.handle("/api/view?name=tasks", null).json));
        ok("Ничего не висит.".equals(MiniJson.obj(MiniJson.arr(v.get("blocks")).get(0)).get("html")), "…the screen fresh");
        boolean allViews = true;
        for (String name : new String[]{"today", "digest", "spent", "usage", "help", "item", "people", "ops"})
            allViews &= !MiniJson.arr(MiniJson.obj(MiniJson.parse(api6.handle("/api/view?name=" + name, null).json)).get("blocks")).isEmpty();
        ok(allViews && app.db.chatTail(100).size() == before, "every screen has blocks; screens are not in the chat history");

        app = make();
        Api api7 = new Api(app);
        llm(app).jsonResponses.add(new RuntimeException("сломалось"));
        api7.handle("/api/message", "{\"text\": \"привет\"}");
        app.drain();
        ok(lastChat(app).text("html").contains("Не получилось"), "a failure is told in the chat");
    }

    // ---------- test_web_mail_assistant.py: the assistant's errands ----------
    static void assistantActions() throws Exception {
        Secretary app = make();
        String at = iso(now().plus(Duration.ofDays(1)));
        llm(app).jsonResponses.add(m("reply", "Хорошо.", "actions", l(
                m("type", "add_reminder", "text", "Позвонить в банк", "at", at),
                m("type", "add_event", "title", "Вебинар по SQL", "start", at, "url", "https://zoom.us/j/2"),
                m("type", "mute_sender", "email", "@shop.ru"),
                m("type", "set_category", "merchant", "PYATEROCHKA", "category", "Продукты"),
                m("type", "send_money", "amount", 1000L),
                m("type", "add_reminder", "text", "без времени"))));
        String reply = app.assistant.handle("напомни завтра позвонить в банк");
        ok(reply.contains("Хорошо.") && reply.contains("Напомню") && reply.contains("Добавил") && reply.contains("Не понял"),
                "the assistant's errands: reminder, event, and «не понял» for one without time");
        ok("Позвонить в банк".equals(app.db.pendingReminders().get(0).text("text")) && "mute".equals(app.db.senderRule("news@shop.ru"))
                && "Продукты".equals(app.db.merchantCategory("pyaterochka")), "…done in the database; «send money» refused");
        String[] call = llm(app).calls.get(0);
        ok(call[2].contains("Расписание на 7 дней") && call[2].contains("напомни завтра") && "true".equals(call[0]),
                "…the context and the message, to the «smart» model");
    }
}
