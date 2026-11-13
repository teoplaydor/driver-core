package io.github.teoplaydor.semsearch.secretary;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.teoplaydor.semsearch.core.MiniJson;
import io.github.teoplaydor.semsearch.secretary.Sql.Row;

import static io.github.teoplaydor.semsearch.secretary.SecUtil.fromDb;
import static io.github.teoplaydor.semsearch.secretary.SecUtil.head;
import static io.github.teoplaydor.semsearch.secretary.SecUtil.lower;
import static io.github.teoplaydor.semsearch.secretary.SecUtil.now;

/**
 * The server version's phone.py: the phone's notifications — messengers, SMS, shops, deliveries, services.
 * Confirmation codes are dropped at once and never stored; banks go to spending; the rest is gathered by chat and read
 * by the model when the chat goes quiet — a card like a mail's: the urgent at once, the rest into the digest.
 * In «Тишина» it also tells, for each notification right now, what the phone does with it.
 */
public final class Phone {
    static final int MAX_TEXT = 4000;
    static final int MAX_BATCH = 50; // this many messages in a chat — read without waiting for a pause
    static final Duration GIVE_UP_AFTER = Duration.ofHours(1); // the model silent this long — shown without reading

    /** Service noise that is surely not needed: the system, the app store, VPN, the secretary itself. */
    public static final Set<String> DEFAULT_IGNORE = new HashSet<String>(Arrays.asList(
            "android", "com.android.systemui", "com.android.vending", "com.android.providers.downloads",
            "com.android.settings", "com.google.android.gms", "com.google.android.packageinstaller",
            "org.amnezia.vpn", "com.arlosoft.macrodroid", "local.secretary", "io.github.teoplaydor.semsearch"));
    static final Set<String> BANK_PACKAGES = new HashSet<String>(Arrays.asList(
            "ru.sberbankmobile", "com.yandex.bank", "com.idamob.tinkoff.android", "ru.alfabank.mobile.android",
            "ru.vtb24.mobilebanking.android", "ru.ozon.fintech.finance", "ru.gazprombank.android.mobilebank.app",
            "ru.raiffeisennews", "ru.mts.money", "ru.pochta.bank", "ru.yoo.money"));
    static final String[] BANK_WORDS = {"банк", "bank", "сбер", "sber", "тинькофф", "tinkoff", "яндекс пэй", "yandex pay", "юmoney"};
    static final Pattern REDACTED_RE = Pattern.compile("content hidden|содержимое скрыто|скрыт[оа]? систем",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    static final Set<String> BANK_SMS = new HashSet<String>(Arrays.asList("900", "vtb", "tinkoff", "t-bank", "alfa-bank",
            "alfabank", "yandex.bank", "sberbank", "mts-bank", "gazprombank", "pochtabank", "raiffeisen", "ozon bank", "yoomoney"));

    /** Messengers: personal messages of close people from them are always shown with sound, without asking the model. */
    public static final Set<String> MESSENGERS = new HashSet<String>(Arrays.asList(
            "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram", "ru.oneme.app",
            "com.vkontakte.android", "com.whatsapp", "com.whatsapp.w4b", "com.viber.voip", "com.discord",
            "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms"));
    static final Pattern CLOSE_NAME_RE = Pattern.compile(
            "(?<![а-яёa-z])(мам[аы]?|мамул[яь]|мамочка|пап[аы]?|папул[яь]|папочка|мать|отец|батя|бабушк[аи]|бабул[яь]"
                    + "|дедушк[аи]|деда|дедул[яь]|брат|братик|братишка|сестр[аы]|сестр[её]нка|жена|ж[её]нушка|муж|сын|сынок|дочь"
                    + "|доча|дочка|доченька|любим(ая|ый|ка)|девушка|парень|родн(ая|ой)|солнышко|зая|зайка|котик|кот[её]нок)"
                    + "(?![а-яёa-z])|❤|💕|💖|💗|💘|💝|💞|🥰|😘", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    public static final Pattern SERVICE_NAME_RE = Pattern.compile(
            "бот|bot|служб|поддержк|support|уведомлен|код|заказ|курьер|доставк|магазин|банк|новост|канал|рассылк"
                    + "|telegram|вконтакте|max(?![a-z])|непрочитан", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    static final int CLOSE_DAYS = 2; // opened the chat (or answered) on different days in a month — one of their own

    /** One message of a notification. */
    public static final class Msg {
        public final String uid, pkg, app, chat, sender, text, title;
        public final boolean isGroup;
        public final Instant posted;

        Msg(String uid, String pkg, String app, String chat, String sender, String text, boolean isGroup, Instant posted, String title) {
            this.uid = uid;
            this.pkg = pkg;
            this.app = app;
            this.chat = chat;
            this.sender = sender;
            this.text = text;
            this.isGroup = isGroup;
            this.posted = posted;
            this.title = title;
        }

        /** Title + text: banks often write the main thing («Покупка», the sum) in the title. */
        public String fullText() {
            if (!title.isEmpty() && !text.contains(title) && !title.equals(app)) return title + "\n" + text;
            return text;
        }
    }

    static String clean(Object value) {
        String s = value == null ? "" : String.valueOf(value);
        return s.replaceAll("[\\u200b-\\u200f\\u2060\\ufeff]", "").trim();
    }

    static Instant ms(Object value) {
        Long ms = SecUtil.intOf(value);
        return ms != null && ms > 0 ? Instant.ofEpochMilli(ms) : null;
    }

    static String uid(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('|');
            sb.append(parts[i]);
        }
        return SecUtil.sha1Hex(sb.toString());
    }

    /** The key of a chat for the rules «don't show / always important» (it lies in the card's from_addr). */
    public static String chatKey(String pkg, String chat) {
        return lower("chat:" + pkg + ":" + chat);
    }

    /** One notification → messages. A messenger gives the chat's whole messages, not the cut line. */
    public static List<Msg> normalize(Map<String, Object> item) {
        String pkg = head(clean(item.get("package")), 100);
        String app = head(clean(item.get("app")), 60);
        if (app.isEmpty()) app = pkg;
        String title = head(clean(item.get("title")), 120);
        String conv = clean(item.get("conversation"));
        String chat = head(!conv.isEmpty() ? conv : !title.isEmpty() ? title : app, 120);
        boolean isGroup = SecUtil.truthy(item.get("is_group"));
        Instant posted = ms(item.get("posted"));
        if (posted == null) posted = now();
        List<Msg> out = new ArrayList<Msg>();
        List<Object> messages = MiniJson.arr(item.get("messages"));
        if (messages != null) {
            for (Object o : messages) {
                Map<String, Object> m = MiniJson.obj(o);
                if (m == null) continue;
                String text = head(clean(m.get("text")), MAX_TEXT);
                if (text.isEmpty()) continue;
                String s = head(clean(m.get("sender")), 80);
                String sender = SecUtil.truthy(m.get("self")) ? "Я" : !s.isEmpty() ? s : chat;
                Instant when = ms(m.get("time"));
                if (when == null) when = posted;
                out.add(new Msg(uid(pkg, chat, sender, when.getEpochSecond(), head(text, 500)), pkg, app, chat, sender, text,
                        isGroup, when, title));
            }
        }
        if (out.isEmpty()) {
            List<String> ls = new ArrayList<String>();
            List<Object> lines = MiniJson.arr(item.get("lines"));
            if (lines != null) for (Object x : lines) if (!clean(x).isEmpty()) ls.add(clean(x));
            String big = clean(item.get("big_text")), joined = SecUtil.join("\n", ls);
            String text = head(!big.isEmpty() ? big : !joined.isEmpty() ? joined : clean(item.get("text")), MAX_TEXT);
            if (!text.isEmpty()) {
                // an update of the same notification with the same text is not a new message
                Object key = SecUtil.truthy(item.get("key")) ? item.get("key") : chat;
                out.add(new Msg(uid(pkg, key, head(text, 500)), pkg, app, chat, !title.isEmpty() ? title : app, text, isGroup,
                        posted, title));
            }
        }
        return out;
    }

    public static boolean isBank(String pkg, String app, String sender) {
        String label = lower(app);
        if (BANK_PACKAGES.contains(pkg) || BANK_SMS.contains(lower(sender))) return true;
        for (String w : BANK_WORDS) if (label.contains(w)) return true;
        return false;
    }

    static final Map<String, String> REMOVED_KIND = new HashMap<String, String>();

    static {
        REMOVED_KIND.put("click", "opened");
        REMOVED_KIND.put("app_cancel", "opened");
        REMOVED_KIND.put("cancel", "dismissed");
    }

    private final Secretary app;
    /** Chats where evidence appeared of whether the owner did a task. */
    private final Set<String> toCheck = new LinkedHashSet<String>();

    Phone(Secretary app) {
        this.app = app;
    }

    /** A notification disappeared: tapped / the messenger removed it because the chat was read / swiped away. */
    private void removed(Map<String, Object> item) {
        String kind = REMOVED_KIND.get(String.valueOf(item.get("reason")));
        String pkg = head(clean(item.get("package")), 100);
        String appName = head(clean(item.get("app")), 60);
        if (appName.isEmpty()) appName = pkg;
        String conv = clean(item.get("conversation")), title = clean(item.get("title"));
        String chat = head(!conv.isEmpty() ? conv : !title.isEmpty() ? title : appName, 120);
        if (kind == null || ignored(pkg, appName)) return;
        String key = chatKey(pkg, chat);
        app.db.addSignal(key, kind, "", ms(item.get("posted")));
        if ("opened".equals(kind)) synchronized (toCheck) {
            toCheck.add(key);
        }
    }

    boolean ignored(String pkg, String appName) {
        Set<String> names = new HashSet<String>(Arrays.asList(lower(pkg), lower(appName)));
        for (String x : app.cfg.ignoreApps) if (names.contains(lower(x))) return true;
        return DEFAULT_IGNORE.contains(pkg) || "mute".equals(app.db.senderRule("app:" + lower(appName)))
                || "mute".equals(app.db.senderRule("app:" + pkg));
    }

    /**
     * A close person: «⭐ Важный», a name like «Мама» or «Девушка моя», or the owner opens this chat on different days.
     * Group chats and services never are.
     */
    public boolean isClose(String pkg, String chat, String rule) {
        if (rule == null) rule = app.db.senderRule(chatKey(pkg, chat));
        if ("vip".equals(rule) || "mute".equals(rule) || "plain".equals(rule)) return "vip".equals(rule);
        if (!MESSENGERS.contains(pkg) || SERVICE_NAME_RE.matcher(chat).find()) return false;
        if (CLOSE_NAME_RE.matcher(chat).find()) return true;
        return app.db.contactDays(chatKey(pkg, chat), now().minus(Duration.ofDays(30))) >= CLOSE_DAYS;
    }

    boolean isClose(String pkg, String chat) {
        return isClose(pkg, chat, null);
    }

    /** The secretary's own bot's notifications (there is none on the phone; kept from the server). */
    private boolean ownBot(Msg m) {
        return lower(m.pkg).contains("gram") && app.botNames.contains(lower(m.chat));
    }

    private boolean bankExtra(String appName) {
        for (String x : app.cfg.bankApps) if (lower(x).equals(lower(appName))) return true;
        return false;
    }

    /** Takes a batch of notifications: {"stored", "bank", "skipped"}. */
    public Map<String, Integer> ingest(List<Object> items) {
        Map<String, Integer> stats = new LinkedHashMap<String, Integer>();
        stats.put("stored", 0);
        stats.put("bank", 0);
        stats.put("skipped", 0);
        for (Object o : items) {
            Map<String, Object> item = MiniJson.obj(o);
            if (item == null) continue;
            if ("removed".equals(item.get("type"))) {
                removed(item);
                continue;
            }
            for (final Msg msg : normalize(item)) {
                final String text = msg.fullText();
                if (ignored(msg.pkg, msg.app) || ownBot(msg) || Finance.looksSecret(text)) {
                    stats.put("skipped", stats.get("skipped") + 1);
                    continue;
                }
                boolean bank = isBank(msg.pkg, msg.app, msg.sender) || bankExtra(msg.app);
                if (bank && !Finance.findAmounts(text).isEmpty()) {
                    app.spawn(new Runnable() {
                        public void run() {
                            app.finance.handleNotification(text, msg.app, msg.posted);
                        }
                    });
                    stats.put("bank", stats.get("bank") + 1);
                    continue;
                }
                if ("mute".equals(app.db.senderRule(chatKey(msg.pkg, msg.chat)))) {
                    stats.put("skipped", stats.get("skipped") + 1);
                    continue;
                }
                if (app.db.addPhoneMsg(msg.uid, msg.pkg, msg.app, msg.chat, msg.sender, msg.text, msg.isGroup, msg.posted)) {
                    stats.put("stored", stats.get("stored") + 1);
                    if ("Я".equals(msg.sender)) { // the owner wrote into this chat — maybe that was the task
                        String key = chatKey(msg.pkg, msg.chat);
                        app.db.addSignal(key, "replied", msg.text, msg.posted);
                        synchronized (toCheck) {
                            toCheck.add(key);
                        }
                    }
                }
            }
        }
        return stats;
    }

    /**
     * What the phone does with each notification right now («Тишина»): urgent — show with sound, normal — quietly into
     * the digest, junk — off the shade, skip — leave it.
     */
    public Map<String, String> verdicts(List<Object> items) {
        SecDb db = app.db;
        Map<String, String> out = new LinkedHashMap<String, String>();
        List<Object[]> undecided = new ArrayList<Object[]>(); // key, item, msgs
        for (Object o : items) {
            Map<String, Object> item = MiniJson.obj(o);
            if (item == null || !SecUtil.truthy(item.get("key"))) continue;
            String key = String.valueOf(item.get("key"));
            List<Msg> msgs = normalize(item);
            if (msgs.isEmpty()) {
                out.put(key, "normal");
                continue;
            }
            Msg first = msgs.get(0);
            StringBuilder texts = new StringBuilder();
            boolean fromOthers = false, allMine = true;
            for (Msg m : msgs) {
                if (texts.length() > 0) texts.append(' ');
                texts.append(m.fullText());
                if (!"Я".equals(m.sender)) {
                    fromOthers = true;
                    allMine = false;
                }
            }
            String chatRule = db.senderRule(chatKey(first.pkg, first.chat));
            if (ownBot(first)) out.put(key, "skip");
            else if (Finance.looksSecret(texts.toString()) || REDACTED_RE.matcher(texts).find())
                out.put(key, "urgent"); // a code is awaited right now (Android 15+ hides its text — known by the stub)
            else if (ignored(first.pkg, first.app) || "mute".equals(chatRule)) out.put(key, "junk");
            else if ("vip".equals(chatRule)) out.put(key, "urgent");
            else if (!first.isGroup && fromOthers && isClose(first.pkg, first.chat, chatRule))
                out.put(key, "urgent"); // mom, the other half, friends — always with sound, even a sticker
            else if ((isBank(first.pkg, first.app, first.sender) || bankExtra(first.app)) && !Finance.findAmounts(texts.toString()).isEmpty())
                out.put(key, "normal"); // operations go to spending; of large ones the secretary writes itself
            else if (allMine) out.put(key, "normal");
            else undecided.add(new Object[]{key, item, msgs});
        }

        if (!undecided.isEmpty()) {
            List<String> listing = new ArrayList<String>();
            for (int i = 0; i < undecided.size(); i++) {
                @SuppressWarnings("unchecked") List<Msg> msgs = (List<Msg>) undecided.get(i)[2];
                Msg m0 = msgs.get(0);
                String group = m0.isGroup ? " (групповой чат)" : "";
                List<String> lines = new ArrayList<String>();
                for (Msg m : msgs.subList(Math.max(0, msgs.size() - 3), msgs.size()))
                    lines.add("   " + m.sender + ": " + head(m.fullText(), 300));
                listing.add("[" + i + "] " + m0.app + " · " + m0.chat + group + "\n" + SecUtil.join("\n", lines));
            }
            Map<Integer, String> levels = new HashMap<Integer, String>();
            try {
                Map<String, Object> data = app.llm.json(false, true,
                        Prompts.fill(Prompts.QUICK_SYSTEM, "about_me", aboutMe()),
                        Prompts.fill(Prompts.QUICK_USER, "items", SecUtil.join("\n\n", listing)), Prompts.QUICK_SCHEMA);
                List<Object> xs = MiniJson.arr(data.get("items"));
                if (xs != null) for (Object x : xs) {
                    Map<String, Object> e = MiniJson.obj(x);
                    if (e == null) continue;
                    Object lv = e.get("level");
                    Long i = SecUtil.intOf(e.get("i"));
                    if (i != null && ("urgent".equals(lv) || "normal".equals(lv) || "junk".equals(lv))) levels.put(i.intValue(), (String) lv);
                }
            } catch (LlmError e) {
                Secretary.log("quick look at notifications failed: " + e.getMessage());
                levels.clear();
            }
            for (int i = 0; i < undecided.size(); i++) {
                Object[] u = undecided.get(i);
                @SuppressWarnings("unchecked") Map<String, Object> item = (Map<String, Object>) u[1];
                @SuppressWarnings("unchecked") List<Msg> msgs = (List<Msg>) u[2];
                // the model silent — a personal message from a person is better shown than missed
                boolean person = SecUtil.truthy(item.get("messages")) && !msgs.get(0).isGroup && anyFromOthers(msgs);
                String lv = levels.get(i);
                out.put((String) u[0], lv != null ? lv : person ? "urgent" : "normal");
            }
        }

        for (Object o : items) {
            Map<String, Object> item = MiniJson.obj(o);
            if (item != null && "urgent".equals(out.get(String.valueOf(item.get("key"))))) {
                List<String> uids = new ArrayList<String>();
                for (Msg m : normalize(item)) uids.add(m.uid);
                db.phoneMarkAlerted(uids);
            }
        }
        return out;
    }

    private static boolean anyFromOthers(List<Msg> msgs) {
        for (Msg m : msgs) if (!"Я".equals(m.sender)) return true;
        return false;
    }

    String aboutMe() {
        return app.cfg.aboutMe == null || app.cfg.aboutMe.trim().isEmpty() ? "(не указано)" : app.cfg.aboutMe;
    }

    /** Reads the chats that went quiet (or waited long enough), then looks whether tasks got done. */
    public void processReady(Instant now) {
        if (now == null) now = now();
        for (Row g : app.db.phonePendingGroups()) {
            boolean quiet = Duration.between(fromDb(g.text("last_at")), now).compareTo(Duration.ofSeconds(app.cfg.quietSeconds)) >= 0;
            boolean waited = Duration.between(fromDb(g.text("first_at")), now).compareTo(Duration.ofSeconds(app.cfg.maxWaitSeconds)) >= 0;
            if (quiet || waited || g.lng("n") >= MAX_BATCH) {
                triageChat(g.text("package"), g.text("chat"), now);
                synchronized (toCheck) {
                    toCheck.add(chatKey(g.text("package"), g.text("chat"))); // «order received» closes a task too
                }
            }
        }
        Triage.notifyUrgent(app);
        // evidence gathered — see whether the tasks of these chats are done
        List<String> keys;
        synchronized (toCheck) {
            keys = new ArrayList<String>(toCheck);
            toCheck.clear();
        }
        for (String key : keys) {
            try {
                app.tasks.resolve(key);
            } catch (RuntimeException e) {
                Secretary.log("checking tasks of " + key + ": " + e);
            }
        }
    }

    void triageChat(String pkg, String chat, Instant now) {
        SecDb db = app.db;
        List<Row> msgs = db.phonePending(pkg, chat);
        if (msgs.isEmpty()) return;
        List<Long> ids = new ArrayList<Long>();
        boolean allMine = true, anyGroup = false, anyAlerted = false;
        for (Row m : msgs) {
            ids.add(m.lng("id"));
            if (!"Я".equals(m.text("sender"))) allMine = false;
            if (m.truthy("is_group")) anyGroup = true;
            if (m.truthy("alerted")) anyAlerted = true;
        }
        String key = chatKey(pkg, chat);
        String rule = db.senderRule(key);
        if ("mute".equals(rule) || allMine) {
            db.phoneMark(ids, 0);
            return;
        }
        String appName = msgs.get(msgs.size() - 1).text("app");
        String transcript = transcript(msgs);
        String context = transcript(db.phoneRecentDone(pkg, chat, 10));
        if (context.isEmpty()) context = "(нет)";
        Map<String, Object> data;
        try {
            data = app.llm.json(false, false,
                    Prompts.fill(Prompts.CHAT_TRIAGE_SYSTEM, "about_me", aboutMe(), "tz", app.cfg.tz.getId()),
                    Prompts.fill(Prompts.CHAT_TRIAGE_USER, "now", SecUtil.isoMinutes(now, app.cfg.tz), "app", appName, "chat", chat,
                            "group", anyGroup ? " (групповой)" : "", "context", context, "messages", transcript),
                    Prompts.TRIAGE_SCHEMA);
        } catch (LlmError e) {
            Secretary.log("reading chat " + appName + "/" + chat + " failed: " + e.getMessage());
            app.alert("llm", "⚠️ Нейросеть не отвечает: " + SecUtil.esc(e.getMessage()));
            if (Duration.between(fromDb(msgs.get(0).text("created_at")), now).compareTo(GIVE_UP_AFTER) < 0) return; // next time
            data = new LinkedHashMap<String, Object>();
            data.put("importance", 2L);
            data.put("category", "other");
            data.put("summary", "(не удалось разобрать автоматически)");
            data.put("action", "");
            data.put("needs_reply", false);
            data.put("deadline", "");
            data.put("events", new ArrayList<Object>());
        }
        SecDb.Item item = new SecDb.Item();
        item.uid = "phone:" + ids.get(0) + "-" + ids.get(ids.size() - 1);
        item.date = fromDb(msgs.get(msgs.size() - 1).text("posted_at"));
        item.fromAddr = key;
        item.fromName = chat;
        item.subject = chat;
        item.body = SecUtil.tail(transcript, 6000);
        long itemId = db.insertMail(appName, pkg, item, "chat");
        if (itemId < 0) { // this batch is already there
            db.phoneMark(ids, 0);
            return;
        }
        Triage.applyVerdict(app, itemId, data, rule);
        if (anyAlerted) db.markNotified(itemId); // the phone already showed it with sound — not a second time
        db.phoneMark(ids, itemId);
    }

    String transcript(List<Row> msgs) {
        List<String> out = new ArrayList<String>();
        for (Row m : msgs)
            out.add("[" + SecUtil.dmhm(fromDb(m.text("posted_at")).atZone(app.cfg.tz)) + "] " + m.text("sender") + ": " + m.text("text"));
        return SecUtil.join("\n", out);
    }
}
