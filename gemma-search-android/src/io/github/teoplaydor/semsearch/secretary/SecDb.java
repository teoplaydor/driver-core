package io.github.teoplaydor.semsearch.secretary;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import io.github.teoplaydor.semsearch.secretary.Sql.Row;

import static io.github.teoplaydor.semsearch.secretary.SecUtil.fromDb;
import static io.github.teoplaydor.semsearch.secretary.SecUtil.lower;
import static io.github.teoplaydor.semsearch.secretary.SecUtil.now;
import static io.github.teoplaydor.semsearch.secretary.SecUtil.toDb;

/** The server version's db.py: the same tables and queries, so its database and this one are one format. */
public final class SecDb {
    static final String SCHEMA = "CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT NOT NULL);\n"
            + "CREATE TABLE IF NOT EXISTS mails (id INTEGER PRIMARY KEY, account TEXT NOT NULL, folder TEXT NOT NULL,"
            + " uid TEXT NOT NULL, message_id TEXT NOT NULL DEFAULT '', references_hdr TEXT NOT NULL DEFAULT '',"
            + " reply_to TEXT NOT NULL DEFAULT '', received_at TEXT NOT NULL, from_addr TEXT NOT NULL DEFAULT '',"
            + " from_name TEXT NOT NULL DEFAULT '', subject TEXT NOT NULL DEFAULT '', body TEXT NOT NULL DEFAULT '',"
            + " links TEXT NOT NULL DEFAULT '', bulk INTEGER NOT NULL DEFAULT 0, importance INTEGER,"
            + " attempts INTEGER NOT NULL DEFAULT 0, category TEXT NOT NULL DEFAULT '', summary TEXT NOT NULL DEFAULT '',"
            + " action TEXT NOT NULL DEFAULT '', needs_reply INTEGER NOT NULL DEFAULT 0, deadline TEXT,"
            + " status TEXT NOT NULL DEFAULT 'info', notified INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL,"
            + " UNIQUE (account, folder, uid));\n"
            + "CREATE INDEX IF NOT EXISTS mails_created ON mails(created_at);\n"
            + "CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY, title TEXT NOT NULL, start_at TEXT NOT NULL,"
            + " end_at TEXT, url TEXT NOT NULL DEFAULT '', kind TEXT NOT NULL DEFAULT 'other', source TEXT NOT NULL DEFAULT '',"
            + " reminded TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT 'active', created_at TEXT NOT NULL);\n"
            + "CREATE INDEX IF NOT EXISTS events_start ON events(start_at);\n"
            + "CREATE TABLE IF NOT EXISTS reminders (id INTEGER PRIMARY KEY, text TEXT NOT NULL, at TEXT NOT NULL,"
            + " sent INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL);\n"
            + "CREATE TABLE IF NOT EXISTS senders (addr TEXT PRIMARY KEY, rule TEXT NOT NULL);\n"
            + "CREATE TABLE IF NOT EXISTS transactions (id INTEGER PRIMARY KEY, ts TEXT NOT NULL, source TEXT NOT NULL DEFAULT '',"
            + " direction TEXT NOT NULL, amount REAL NOT NULL, currency TEXT NOT NULL DEFAULT 'RUB',"
            + " merchant TEXT NOT NULL DEFAULT '', merchant_key TEXT NOT NULL DEFAULT '',"
            + " category TEXT NOT NULL DEFAULT 'Другое', card TEXT NOT NULL DEFAULT '', flagged INTEGER NOT NULL DEFAULT 0,"
            + " raw TEXT NOT NULL DEFAULT '', raw_hash TEXT NOT NULL DEFAULT '');\n"
            + "CREATE INDEX IF NOT EXISTS tx_ts ON transactions(ts);\n"
            + "CREATE TABLE IF NOT EXISTS merchants (key TEXT PRIMARY KEY, category TEXT NOT NULL);\n"
            + "CREATE TABLE IF NOT EXISTS drafts (id INTEGER PRIMARY KEY, mail_id INTEGER NOT NULL, text TEXT NOT NULL,"
            + " status TEXT NOT NULL DEFAULT 'draft', created_at TEXT NOT NULL);\n"
            + "CREATE TABLE IF NOT EXISTS phone_msgs (id INTEGER PRIMARY KEY, uid TEXT NOT NULL UNIQUE,"
            + " package TEXT NOT NULL DEFAULT '', app TEXT NOT NULL DEFAULT '', chat TEXT NOT NULL DEFAULT '',"
            + " sender TEXT NOT NULL DEFAULT '', text TEXT NOT NULL DEFAULT '', is_group INTEGER NOT NULL DEFAULT 0,"
            + " posted_at TEXT NOT NULL, created_at TEXT NOT NULL, item_id INTEGER);\n"
            + "CREATE INDEX IF NOT EXISTS phone_pending ON phone_msgs(item_id, package, chat);\n"
            + "CREATE TABLE IF NOT EXISTS tasks (id INTEGER PRIMARY KEY, title TEXT NOT NULL, details TEXT NOT NULL DEFAULT '',"
            + " source TEXT NOT NULL DEFAULT '', source_key TEXT NOT NULL DEFAULT '', item_id INTEGER, due_at TEXT,"
            + " remind_at TEXT NOT NULL, asked_at TEXT, status TEXT NOT NULL DEFAULT 'open', created_at TEXT NOT NULL,"
            + " updated_at TEXT NOT NULL);\n"
            + "CREATE INDEX IF NOT EXISTS tasks_status ON tasks(status, remind_at);\n"
            + "CREATE TABLE IF NOT EXISTS chat_signals (id INTEGER PRIMARY KEY, source_key TEXT NOT NULL, kind TEXT NOT NULL,"
            + " text TEXT NOT NULL DEFAULT '', at TEXT NOT NULL);\n"
            + "CREATE INDEX IF NOT EXISTS chat_signals_key ON chat_signals(source_key, at);\n"
            + "CREATE TABLE IF NOT EXISTS chat_log (id INTEGER PRIMARY KEY, ts TEXT NOT NULL, role TEXT NOT NULL,"
            + " html TEXT NOT NULL, buttons TEXT NOT NULL DEFAULT '[]', notify INTEGER NOT NULL DEFAULT 0);\n"
            + "CREATE TABLE IF NOT EXISTS llm_usage (day TEXT NOT NULL, model TEXT NOT NULL, calls INTEGER NOT NULL DEFAULT 0,"
            + " tokens_in INTEGER NOT NULL DEFAULT 0, tokens_out INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (day, model));\n";

    public final Sql sql;

    public SecDb(Sql sql) {
        this.sql = sql;
        sql.script(SCHEMA);
        // the server's migrations: phone chats live in mails too (kind), and phone messages remember an alert
        if (!columns("mails").contains("kind")) sql.update("ALTER TABLE mails ADD COLUMN kind TEXT NOT NULL DEFAULT 'mail'");
        if (!columns("phone_msgs").contains("alerted"))
            sql.update("ALTER TABLE phone_msgs ADD COLUMN alerted INTEGER NOT NULL DEFAULT 0");
    }

    private Set<String> columns(String table) {
        Set<String> out = new TreeSet<String>();
        for (Row r : sql.q("PRAGMA table_info(" + table + ")")) out.add(r.text("name"));
        return out;
    }

    public List<Row> q(String s, Object... args) {
        return sql.q(s, args);
    }

    public Row one(String s, Object... args) {
        List<Row> r = sql.q(s, args);
        return r.isEmpty() ? null : r.get(0);
    }

    private static Object db(Instant t) {
        return t == null ? null : toDb(t);
    }

    // --- kv ---
    public String kvGet(String k) {
        Row r = one("SELECT v FROM kv WHERE k=?", k);
        return r == null ? null : r.text("v");
    }

    public void kvSet(String k, String v) {
        sql.insert("INSERT OR REPLACE INTO kv(k, v) VALUES(?, ?)", k, v); // not UPSERT: SQLite before 3.24 (Android < 11)
    }

    // --- mail and phone chats (one table, told apart by kind) ---

    /** What a mail or a chat batch brings (the server's FetchedMail). */
    public static final class Item {
        public String uid = "", messageId = "", references = "", replyTo = "", fromAddr = "", fromName = "", subject = "",
                body = "";
        public Instant date;
        public List<String> links = new ArrayList<String>();
        public boolean bulk;
    }

    /** The new row's id, or -1 if this one is already there. */
    public long insertMail(String account, String folder, Item m, String kind) {
        return sql.insert("INSERT OR IGNORE INTO mails(account, folder, uid, message_id, references_hdr, reply_to,"
                        + " received_at, from_addr, from_name, subject, body, links, bulk, created_at, kind)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                account, folder, m.uid, m.messageId, m.references, m.replyTo, toDb(m.date), lower(m.fromAddr), m.fromName,
                m.subject, m.body, SecUtil.join("\n", m.links), m.bulk ? 1L : 0L, toDb(now()), kind);
    }

    public Row getMail(long id) {
        return one("SELECT * FROM mails WHERE id=?", id);
    }

    public void saveTriage(long id, int importance, String category, String summary, String action, boolean needsReply,
                           Instant deadline, String status) {
        sql.update("UPDATE mails SET importance=?, category=?, summary=?, action=?, needs_reply=?, deadline=?, status=? WHERE id=?",
                (long) importance, category, summary, action, needsReply ? 1L : 0L, db(deadline), status, id);
    }

    public boolean setMailStatus(long id, String status) {
        return sql.update("UPDATE mails SET status=? WHERE id=?", status, id) > 0;
    }

    public List<Row> unnotifiedUrgent(int hours) {
        String since = toDb(now().minus(Duration.ofHours(hours)));
        return q("SELECT * FROM mails WHERE importance >= 3 AND notified = 0 AND status != 'ignored' AND created_at > ? ORDER BY id",
                since);
    }

    public void markNotified(long id) {
        sql.update("UPDATE mails SET notified=1 WHERE id=?", id);
    }

    public List<Row> mailsSince(Instant since) {
        return q("SELECT * FROM mails WHERE created_at > ? AND importance IS NOT NULL ORDER BY importance DESC, id", toDb(since));
    }

    public List<Row> recentImportant(int days, int limit) {
        return q("SELECT * FROM mails WHERE importance >= 2 AND created_at > ? ORDER BY id DESC LIMIT ?",
                toDb(now().minus(Duration.ofDays(days))), (long) limit);
    }

    // --- senders: rules «mute» / «vip» / «plain» for an address, a domain, a chat or an app ---
    public String senderRule(String addr) {
        addr = lower(addr);
        int at = addr.lastIndexOf('@');
        String domain = "@" + (at >= 0 ? addr.substring(at + 1) : addr);
        Row r = one("SELECT rule FROM senders WHERE addr IN (?, ?) ORDER BY addr = ? DESC", addr, domain, addr);
        return r == null ? null : r.text("rule");
    }

    public void setSenderRule(String addr, String rule) {
        if (rule == null) sql.update("DELETE FROM senders WHERE addr=?", lower(addr));
        else sql.insert("INSERT OR REPLACE INTO senders(addr, rule) VALUES(?, ?)", lower(addr), rule);
    }

    // --- events and reminders ---

    /** One class often comes in several messages — similar ones within half an hour are glued. [id, created 1/0] */
    public long[] upsertEvent(String title, Instant start, Instant end, String url, String kind, String source) {
        Duration window = Duration.ofMinutes(30);
        for (Row ev : q("SELECT * FROM events WHERE status='active' AND start_at BETWEEN ? AND ?",
                toDb(start.minus(window)), toDb(start.plus(window)))) {
            boolean sameUrl = url != null && !url.isEmpty() && ev.text("url").equals(url);
            boolean similar = SecUtil.ratio(lower(ev.text("title")), lower(title)) >= 0.6;
            if (sameUrl || similar) {
                sql.update("UPDATE events SET url = CASE WHEN url='' THEN ? ELSE url END, end_at = COALESCE(end_at, ?) WHERE id=?",
                        url, db(end), ev.lng("id"));
                return new long[]{ev.lng("id"), 0};
            }
        }
        long id = sql.insert("INSERT INTO events(title, start_at, end_at, url, kind, source, created_at) VALUES(?,?,?,?,?,?,?)",
                title, toDb(start), db(end), url, kind, source, toDb(now()));
        return new long[]{id, 1};
    }

    public List<Row> eventsBetween(Instant start, Instant end) {
        return q("SELECT * FROM events WHERE status='active' AND start_at >= ? AND start_at < ? ORDER BY start_at",
                toDb(start), toDb(end));
    }

    public List<Row> eventsCreatedSince(Instant since) {
        return q("SELECT * FROM events WHERE status='active' AND created_at > ? AND start_at > ? ORDER BY start_at",
                toDb(since), toDb(now()));
    }

    public void setEventReminded(long id, Set<Integer> offsets) {
        List<String> parts = new ArrayList<String>();
        for (Integer o : new TreeSet<Integer>(offsets)) parts.add(String.valueOf(o));
        sql.update("UPDATE events SET reminded=? WHERE id=?", SecUtil.join(",", parts), id);
    }

    public boolean cancelEvent(long id) {
        return sql.update("UPDATE events SET status='cancelled' WHERE id=?", id) > 0;
    }

    public long addReminder(String text, Instant at) {
        return sql.insert("INSERT INTO reminders(text, at, created_at) VALUES(?,?,?)", text, toDb(at), toDb(now()));
    }

    public List<Row> dueReminders(Instant t) {
        return q("SELECT * FROM reminders WHERE sent=0 AND at <= ? ORDER BY at", toDb(t));
    }

    public List<Row> pendingReminders() {
        return q("SELECT * FROM reminders WHERE sent=0 ORDER BY at");
    }

    public void markReminderSent(long id) {
        sql.update("UPDATE reminders SET sent=1 WHERE id=?", id);
    }

    public static Set<Integer> parseReminded(String value) {
        Set<Integer> out = new TreeSet<Integer>();
        for (String x : (value == null ? "" : value).split(",")) {
            String t = x.trim();
            if (!t.isEmpty() && t.matches("\\d+")) out.add(Integer.parseInt(t));
        }
        return out;
    }

    // --- money ---

    /** The same bank notification already written: the phone sends it again while it hangs in the shade. */
    public boolean txSeenHash(String rawHash, Instant at) {
        return one("SELECT 1 FROM transactions WHERE raw_hash=? AND ts BETWEEN ? AND ?", rawHash,
                toDb(at.minus(Duration.ofHours(48))), toDb(at.plus(Duration.ofHours(48)))) != null;
    }

    /** A bank may send both a push and an SMS about one purchase. */
    public boolean txDuplicate(double amount, String direction, String merchantKey, Instant at) {
        return one("SELECT 1 FROM transactions WHERE ts BETWEEN ? AND ? AND direction=? AND abs(amount - ?) < 0.01 AND merchant_key=?",
                toDb(at.minus(Duration.ofMinutes(3))), toDb(at.plus(Duration.ofMinutes(3))), direction, amount, merchantKey) != null;
    }

    public String lastCategory(String merchantKey) {
        Row r = one("SELECT category FROM transactions WHERE merchant_key=? AND category != 'Другое' ORDER BY id DESC LIMIT 1",
                merchantKey);
        return r == null ? null : r.text("category");
    }

    public long addTx(Instant ts, String source, String direction, double amount, String currency, String merchant,
                      String merchantKey, String category, String card, boolean flagged, String raw, String rawHash) {
        return sql.insert("INSERT INTO transactions(ts, source, direction, amount, currency, merchant, merchant_key, category,"
                        + " card, flagged, raw, raw_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                toDb(ts), source, direction, amount, currency, merchant, merchantKey, category, card, flagged ? 1L : 0L, raw, rawHash);
    }

    public String merchantCategory(String key) {
        Row r = one("SELECT category FROM merchants WHERE key=?", key);
        return r == null ? null : r.text("category");
    }

    public int setMerchantCategory(String key, String category) {
        sql.insert("INSERT OR REPLACE INTO merchants(key, category) VALUES(?, ?)", key, category);
        return sql.update("UPDATE transactions SET category=? WHERE merchant_key=?", category, key);
    }

    public List<Row> txRecent(Instant start, Instant end, String category, int limit) {
        if (category != null && !category.isEmpty())
            return q("SELECT * FROM transactions WHERE ts >= ? AND ts < ? AND direction='expense' AND category=? ORDER BY ts DESC LIMIT ?",
                    toDb(start), toDb(end), category, (long) limit);
        return q("SELECT * FROM transactions WHERE ts >= ? AND ts < ? ORDER BY ts DESC LIMIT ?", toDb(start), toDb(end), (long) limit);
    }

    public List<Row> txBetween(Instant start, Instant end) {
        return q("SELECT * FROM transactions WHERE ts >= ? AND ts < ? ORDER BY ts", toDb(start), toDb(end));
    }

    // --- phone notifications ---
    public boolean addPhoneMsg(String uid, String pkg, String app, String chat, String sender, String text, boolean isGroup,
                               Instant posted) {
        return sql.insert("INSERT OR IGNORE INTO phone_msgs(uid, package, app, chat, sender, text, is_group, posted_at, created_at)"
                + " VALUES(?,?,?,?,?,?,?,?,?)", uid, pkg, app, chat, sender, text, isGroup ? 1L : 0L, toDb(posted), toDb(now())) > 0;
    }

    public List<Row> phonePendingGroups() {
        return q("SELECT package, app, chat, COUNT(*) n, MIN(created_at) first_at, MAX(created_at) last_at"
                + " FROM phone_msgs WHERE item_id IS NULL GROUP BY package, chat ORDER BY first_at");
    }

    public List<Row> phonePending(String pkg, String chat) {
        return q("SELECT * FROM phone_msgs WHERE item_id IS NULL AND package=? AND chat=? ORDER BY posted_at, id", pkg, chat);
    }

    public List<Row> phoneRecentDone(String pkg, String chat, int limit) {
        List<Row> rows = q("SELECT * FROM phone_msgs WHERE item_id IS NOT NULL AND package=? AND chat=?"
                + " ORDER BY posted_at DESC, id DESC LIMIT ?", pkg, chat, (long) limit);
        Collections.reverse(rows);
        return rows;
    }

    public void phoneMarkAlerted(List<String> uids) {
        for (String u : uids) sql.update("UPDATE phone_msgs SET alerted=1 WHERE uid=?", u);
    }

    public void phoneMark(List<Long> ids, long itemId) {
        for (Long i : ids) sql.update("UPDATE phone_msgs SET item_id=? WHERE id=?", itemId, i);
    }

    public int phonePrune(int days) {
        return sql.update("DELETE FROM phone_msgs WHERE created_at < ?", toDb(now().minus(Duration.ofDays(days))));
    }

    // --- tasks ---

    /** Five notifications of a delivery about one order are one task, not five. */
    public Row findSimilarTask(String title, String sourceKey, Long itemId) {
        String since = toDb(now().minus(Duration.ofHours(48)));
        for (Row t : q("SELECT * FROM tasks WHERE status IN ('open','accepted') AND created_at > ?", since)) {
            if (itemId != null && itemId != 0 && itemId.equals(t.lngOrNull("item_id"))) return t;
            boolean sameSource = sourceKey != null && !sourceKey.isEmpty() && t.text("source_key").equals(sourceKey);
            if (SecUtil.ratio(lower(t.text("title")), lower(title)) >= (sameSource ? 0.5 : 0.8)) return t;
        }
        return null;
    }

    /** [id, created 1/0] */
    public long[] addTask(String title, String details, String source, String sourceKey, Long itemId, Instant due, Instant remind) {
        Row old = findSimilarTask(title, sourceKey, itemId);
        if (old != null) {
            if (due != null && !old.truthy("due_at"))
                sql.update("UPDATE tasks SET due_at=?, updated_at=? WHERE id=?", toDb(due), toDb(now()), old.lng("id"));
            return new long[]{old.lng("id"), 0};
        }
        String t = toDb(now());
        long id = sql.insert("INSERT INTO tasks(title, details, source, source_key, item_id, due_at, remind_at, created_at, updated_at)"
                        + " VALUES(?,?,?,?,?,?,?,?,?)", title, details == null ? "" : details, source == null ? "" : source,
                lower(sourceKey), itemId, db(due), toDb(remind != null ? remind : now()), t, t);
        return new long[]{id, 1};
    }

    public Row getTask(long id) {
        return one("SELECT * FROM tasks WHERE id=?", id);
    }

    public List<Row> openTasks(int limit) {
        return q("SELECT * FROM tasks WHERE status IN ('open','accepted') ORDER BY due_at IS NULL, due_at, remind_at, id LIMIT ?",
                (long) limit);
    }

    /** Open tasks to think about now (not postponed, not planned for later). */
    public List<Row> activeTasks(Instant t) {
        return q("SELECT * FROM tasks WHERE status IN ('open','accepted') AND remind_at <= ? ORDER BY due_at IS NULL, due_at, remind_at, id",
                toDb(t));
    }

    /** When notifications came (not the owner's own messages) — to find calm evenings. */
    public List<String> phonePostedSince(Instant since) {
        List<String> out = new ArrayList<String>();
        for (Row r : q("SELECT posted_at FROM phone_msgs WHERE posted_at >= ? AND sender != 'Я' ORDER BY posted_at", toDb(since)))
            out.add(r.text("posted_at"));
        return out;
    }

    /** Time to ask «done?»: the reminder time has come and nobody asked lately. */
    public List<Row> dueTasks(Instant t, int askEveryMinutes) {
        return q("SELECT * FROM tasks WHERE status IN ('open','accepted') AND remind_at <= ? AND (asked_at IS NULL OR asked_at <= ?)"
                        + " ORDER BY due_at IS NULL, due_at, remind_at, id",
                toDb(t), toDb(t.minus(Duration.ofMinutes(askEveryMinutes))));
    }

    /** Pairs of column, value (an Instant is written as a database time); updated_at is set too. */
    public boolean updateTask(long id, Object... pairs) {
        StringBuilder cols = new StringBuilder();
        List<Object> vals = new ArrayList<Object>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            cols.append(pairs[i]).append("=?, ");
            Object v = pairs[i + 1];
            vals.add(v instanceof Instant ? toDb((Instant) v) : v);
        }
        cols.append("updated_at=?");
        vals.add(toDb(now()));
        vals.add(id);
        return sql.update("UPDATE tasks SET " + cols + " WHERE id=?", vals.toArray()) > 0;
    }

    public List<Row> openTasksFor(String sourceKey) {
        return q("SELECT * FROM tasks WHERE status IN ('open','accepted') AND source_key=? ORDER BY id", lower(sourceKey));
    }

    public void addSignal(String sourceKey, String kind, String text, Instant at) {
        sql.insert("INSERT INTO chat_signals(source_key, kind, text, at) VALUES(?,?,?,?)", lower(sourceKey), kind,
                SecUtil.head(text == null ? "" : text, 500), toDb(at != null ? at : now()));
    }

    /** On how many different days the owner opened this chat or wrote into it. */
    public int contactDays(String sourceKey, Instant since) {
        Row r = one("SELECT count(DISTINCT substr(at, 1, 10)) n FROM chat_signals WHERE source_key=? AND at >= ?"
                + " AND kind IN ('opened', 'replied')", lower(sourceKey), toDb(since));
        return r == null ? 0 : r.num("n");
    }

    /** Personal chats people wrote from in the period — by the number of messages. */
    public List<Row> personalChats(Instant since) {
        return q("SELECT package, chat, max(app) app, count(*) n, max(posted_at) last_at FROM phone_msgs"
                + " WHERE is_group=0 AND sender != 'Я' AND posted_at >= ? GROUP BY package, chat ORDER BY n DESC", toDb(since));
    }

    public Long latestItem(String fromAddr) {
        Row r = one("SELECT max(id) id FROM mails WHERE from_addr=?", lower(fromAddr));
        return r == null ? null : r.lngOrNull("id");
    }

    public List<Row> signalsSince(String sourceKey, Instant since) {
        return q("SELECT * FROM chat_signals WHERE source_key=? AND at >= ? ORDER BY at", lower(sourceKey), toDb(since));
    }

    /** A chat's messages by its key «chat:package:name» (the case of the name does not matter). */
    public List<Row> phoneMsgsForKey(String key, Instant since, int limit) {
        String[] parts = key.split(":", 3);
        if (parts.length != 3 || !"chat".equals(parts[0])) return new ArrayList<Row>();
        List<Row> mine = new ArrayList<Row>();
        for (Row r : q("SELECT * FROM phone_msgs WHERE package=? AND posted_at >= ? ORDER BY posted_at DESC, id DESC",
                parts[1], toDb(since))) {
            if (lower("chat:" + r.text("package") + ":" + r.text("chat")).equals(lower(key))) {
                mine.add(r);
                if (mine.size() >= limit) break;
            }
        }
        Collections.reverse(mine);
        return mine;
    }

    public int closeItemTasks(long itemId, String status) {
        return sql.update("UPDATE tasks SET status=?, updated_at=? WHERE item_id=? AND status IN ('open','accepted')",
                status, toDb(now()), itemId);
    }

    // --- the chat with the owner in the app ---
    public long addChat(String role, String html, String buttons, boolean notify) {
        return sql.insert("INSERT INTO chat_log(ts, role, html, buttons, notify) VALUES(?,?,?,?,?)", toDb(now()), role, html,
                buttons == null ? "[]" : buttons, notify ? 1L : 0L);
    }

    public List<Row> chatAfter(long afterId, int limit) {
        return q("SELECT * FROM chat_log WHERE id > ? ORDER BY id LIMIT ?", afterId, (long) limit);
    }

    public List<Row> chatTail(int limit) {
        List<Row> rows = q("SELECT * FROM chat_log ORDER BY id DESC LIMIT ?", (long) limit);
        Collections.reverse(rows);
        return rows;
    }

    public Row getChat(long id) {
        return one("SELECT * FROM chat_log WHERE id=?", id);
    }

    public void updateChat(long id, String html, String buttons) {
        sql.update("UPDATE chat_log SET html=?, buttons=? WHERE id=?", html, buttons, id);
    }

    // --- drafts ---
    public long addDraft(long mailId, String text) {
        return sql.insert("INSERT INTO drafts(mail_id, text, created_at) VALUES(?,?,?)", mailId, text, toDb(now()));
    }

    public Row getDraft(long id) {
        return one("SELECT * FROM drafts WHERE id=?", id);
    }

    public void setDraftStatus(long id, String status) {
        sql.update("UPDATE drafts SET status=? WHERE id=?", status, id);
    }

    // --- what the model cost: here — tokens and time on the phone ---
    public void addUsage(String model, long tokensIn, long tokensOut) {
        String day = SecUtil.toDb(now()).substring(0, 10);
        if (sql.update("UPDATE llm_usage SET calls=calls+1, tokens_in=tokens_in+?, tokens_out=tokens_out+? WHERE day=? AND model=?",
                tokensIn, tokensOut, day, model) == 0)
            sql.insert("INSERT INTO llm_usage(day, model, calls, tokens_in, tokens_out) VALUES(?,?,1,?,?)", day, model, tokensIn, tokensOut);
    }

    public List<Row> usageSince(String day) {
        return q("SELECT model, SUM(calls) calls, SUM(tokens_in) tokens_in, SUM(tokens_out) tokens_out FROM llm_usage"
                + " WHERE day >= ? GROUP BY model ORDER BY model", day);
    }

    static Instant at(Row r, String col) {
        return r.truthy(col) ? fromDb(r.text(col)) : null;
    }
}
