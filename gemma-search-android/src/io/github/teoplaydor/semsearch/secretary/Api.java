package io.github.teoplaydor.semsearch.secretary;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.teoplaydor.semsearch.core.MiniJson;
import io.github.teoplaydor.semsearch.secretary.Sql.Row;

/**
 * The server version's webapp.py: the API its chat page (app.html) speaks — here answered on the phone itself.
 * Each call takes the path with its query and a JSON body (null for GET) and returns the JSON answer and its status.
 */
public final class Api {
    static final String APP = "app";
    /** The longest a «new messages?» call waits (the page asks again): short, so a WebView's thread is not held long. */
    static final int MAX_WAIT = 10;
    static final Map<String, String> COMMAND_LABELS = new HashMap<String, String>();
    static final Set<String> TASK_ACTIONS = new HashSet<String>(Arrays.asList("done", "accept", "drop", "snooze", "asked"));
    static final Set<String> KEEP_BUTTONS = new HashSet<String>(Arrays.asList("edit", "full", "tsnz")); // the message stays as it was

    static {
        COMMAND_LABELS.put("today", "Сегодня");
        COMMAND_LABELS.put("tasks", "Дела");
        COMMAND_LABELS.put("digest", "Сводка");
        COMMAND_LABELS.put("spent", "Расходы");
        COMMAND_LABELS.put("usage", "Нейросеть");
        COMMAND_LABELS.put("help", "Помощь");
    }

    /** An answer: HTTP status and JSON. */
    public static final class Reply {
        public final int status;
        public final String json;

        Reply(int status, Object body) {
            this.status = status;
            this.json = MiniJson.write(body);
        }
    }

    private final Secretary app;

    public Api(Secretary app) {
        this.app = app;
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static Map<String, Object> messageJson(Row r) {
        Object buttons;
        try {
            buttons = MiniJson.parse(r.truthy("buttons") ? r.text("buttons") : "[]");
        } catch (IOException e) {
            buttons = new ArrayList<Object>();
        }
        return map("id", r.lng("id"), "ts", fromDbIso(r.text("ts")), "role", r.text("role"), "html", r.text("html"),
                "buttons", buttons, "notify", r.truthy("notify"));
    }

    /** from_db(ts).isoformat(): 2026-11-13T10:00:00+00:00 */
    static String fromDbIso(String ts) {
        Instant t = SecUtil.fromDb(ts);
        return t.atOffset(ZoneOffset.UTC).toLocalDateTime().toString().replaceFirst("^(\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d)$", "$1:00")
                + "+00:00";
    }

    /** Runs in the background; a failure becomes a message in the chat. */
    void run(final Work w, final String what) {
        app.spawnInteractive(new Runnable() {
            public void run() {
                long t0 = System.currentTimeMillis();
                try {
                    w.run();
                    Secretary.log("app: " + what + " done in " + (System.currentTimeMillis() - t0) + " ms");
                } catch (Exception e) {
                    Secretary.log("app: " + what + " failed after " + (System.currentTimeMillis() - t0) + " ms", e);
                    app.send("⚠️ Не получилось: " + SecUtil.esc(e.getMessage() == null ? e.toString() : e.getMessage()), null, APP);
                }
            }
        });
    }

    interface Work {
        void run() throws Exception;
    }

    static Map<String, String> query(String path) {
        Map<String, String> q = new HashMap<String, String>();
        int i = path.indexOf('?');
        if (i < 0) return q;
        for (String kv : path.substring(i + 1).split("&")) {
            int e = kv.indexOf('=');
            try {
                if (e > 0) q.put(java.net.URLDecoder.decode(kv.substring(0, e), "UTF-8"), java.net.URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
            } catch (java.io.UnsupportedEncodingException ignored) {
            }
        }
        return q;
    }

    static long longOf(Object v, long def) {
        Long l = SecUtil.intOf(v);
        return l == null ? def : l;
    }

    /** One call: "/api/messages?after=5&wait=25" (body null) or "/api/message" with {"text": …}. */
    public Reply handle(String path, String body) {
        long t0 = System.currentTimeMillis();
        Reply r;
        try {
            r = handleInner(path, body);
        } catch (RuntimeException e) {
            Secretary.log("api " + path + " failed", e);
            r = new Reply(500, map("error", String.valueOf(e)));
        }
        long ms = System.currentTimeMillis() - t0;
        // the chat's polling quietly unless it brought something; everything else always
        boolean poll = path.startsWith("/api/messages") || path.startsWith("/api/tasks?active");
        if (!poll || r.status != 200 || r.json.length() > 20)
            Secretary.log("api " + (body == null ? "GET " : "POST ") + path + (body == null ? "" : " " + SecUtil.head(body, 300))
                    + " → " + r.status + " in " + ms + " ms, " + SecUtil.head(r.json, poll ? 120 : 300));
        return r;
    }

    private Reply handleInner(String path, String body) {
        String route = path.indexOf('?') >= 0 ? path.substring(0, path.indexOf('?')) : path;
        Map<String, String> q = query(path);
        Map<String, Object> data = new HashMap<String, Object>();
        if (body != null) {
            try {
                Map<String, Object> d = MiniJson.obj(MiniJson.parse(body));
                if (d != null) data = d;
            } catch (IOException e) {
                return new Reply(400, map("error", "bad json"));
            }
        }
        try {
            if ("/api/messages".equals(route)) return messages(q);
            if ("/api/message".equals(route)) return postMessage(data);
            if ("/api/voice".equals(route)) return postVoiceText(data);
            if ("/api/button".equals(route)) return postButton(data);
            if ("/api/view".equals(route)) return new Reply(200, Actions.view(app, q.get("name") == null ? "" : q.get("name"), longOf(q.get("id"), 0)));
            if ("/api/command".equals(route)) return postCommand(data);
            if ("/api/status".equals(route)) return new Reply(200, map("ok", true, "telegram", false));
            if ("/api/tasks".equals(route)) return tasksList(q);
            if ("/api/task".equals(route)) return taskAct(data);
        } catch (NumberFormatException e) {
            return new Reply(400, map("error", "bad params"));
        }
        return new Reply(404, map("error", "not found"));
    }

    Reply messages(Map<String, String> q) {
        long after = q.containsKey("after") ? Long.parseLong(q.get("after")) : -1;
        int wait = Math.min(MAX_WAIT, Math.max(0, q.containsKey("wait") ? Integer.parseInt(q.get("wait")) : 0));
        long version = app.chatVersion();
        List<Row> rows = after < 0 ? app.db.chatTail(100) : app.db.chatAfter(after, 200);
        if (rows.isEmpty() && wait > 0) {
            app.awaitChat(version, wait * 1000L);
            rows = app.db.chatAfter(after, 200);
        }
        List<Object> out = new ArrayList<Object>();
        for (Row r : rows) out.add(messageJson(r));
        return new Reply(200, map("messages", out));
    }

    Reply postMessage(Map<String, Object> data) {
        final String text = SecUtil.head(SecUtil.strOf(data.get("text")), 4000);
        if (text.isEmpty()) return new Reply(400, map("error", "empty"));
        long id = app.addUserMessage(SecUtil.esc(text));
        run(new Work() {
            public void run() throws Exception {
                Actions.userText(app, text, APP);
            }
        }, "message");
        return new Reply(200, map("ok", true, "id", id));
    }

    /** A voice message, already heard on the phone (GigaAM): {"text"}. */
    Reply postVoiceText(Map<String, Object> data) {
        final String text = SecUtil.strOf(data.get("text"));
        run(new Work() {
            public void run() throws Exception {
                if (text.isEmpty()) {
                    app.send("Не расслышал, повтори, пожалуйста.", null, APP);
                    return;
                }
                app.addUserMessage("🎙 <i>" + SecUtil.esc(text) + "</i>");
                Actions.userText(app, text, APP);
            }
        }, "voice");
        return new Reply(200, map("ok", true));
    }

    /**
     * Like inline buttons in Telegram: a button edits its own message in place, no new message appears.
     * msg=0 — a button on a tab screen: the page refreshes the screen itself.
     */
    Reply postButton(Map<String, Object> data) {
        final String action = String.valueOf(data.get("action"));
        final long itemId = longOf(data.get("id"), 0), msgId = longOf(data.get("msg"), 0);
        if ("draft".equals(action)) { // the model writes the draft — it takes a while, it comes as its own message
            run(new Work() {
                public void run() throws Exception {
                    Actions.button(app, action, itemId, APP);
                }
            }, "draft");
            return new Reply(200, map("ok", true, "reply", "", "message", null));
        }
        String reply;
        try {
            reply = Actions.button(app, action, itemId, APP);
            if (reply == null) reply = "";
        } catch (Exception e) {
            Secretary.log("button " + action + ": " + e);
            return new Reply(200, map("ok", false, "error", String.valueOf(e.getMessage())));
        }
        Object message = null;
        Row row = msgId != 0 ? app.db.getChat(msgId) : null;
        if (row != null && !KEEP_BUTTONS.contains(action)) {
            String html = row.text("html") + (reply.isEmpty() ? "" : "\n<i>" + reply + "</i>");
            app.db.updateChat(msgId, html, "[]");
            message = messageJson(app.db.getChat(msgId));
        }
        return new Reply(200, map("ok", true, "reply", reply, "message", message));
    }

    Reply postCommand(Map<String, Object> data) {
        final String name = String.valueOf(data.get("name"));
        if (!Actions.COMMANDS.contains(name)) return new Reply(400, map("error", "unknown"));
        String label = COMMAND_LABELS.get(name);
        app.addUserMessage(SecUtil.esc(label != null ? label : name));
        run(new Work() {
            public void run() {
                Actions.command(app, name, APP);
            }
        }, "command " + name);
        return new Reply(200, map("ok", true));
    }

    /** ?due=1 — tasks to ask «done?» about (at unlock); ?active=1 — without the postponed (the tab's counter). */
    Reply tasksList(Map<String, String> q) {
        List<Row> rows;
        if ("1".equals(q.get("due"))) rows = app.tasks.due(null);
        else if ("1".equals(q.get("active"))) rows = app.db.activeTasks(SecUtil.now());
        else rows = app.db.openTasks(20);
        List<Object> out = new ArrayList<Object>();
        for (Row t : rows) out.add(app.tasks.asJson(t));
        return new Reply(200, map("tasks", out));
    }

    Reply taskAct(Map<String, Object> data) {
        String action = String.valueOf(data.get("action"));
        if (!TASK_ACTIONS.contains(action)) return new Reply(400, map("error", "bad action"));
        String reply = app.tasks.act(longOf(data.get("id"), 0), action, data.get("choice") == null ? "" : String.valueOf(data.get("choice")));
        if (!reply.isEmpty() && !"asked".equals(action)) app.send(reply, null, APP); // the phone's answer goes into the chat history too
        return new Reply(200, map("ok", true, "reply", reply));
    }
}
