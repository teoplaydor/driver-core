package io.github.teoplaydor.semsearch.secretary;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The secretary in one piece — the server version's app.py on the phone: the database, the model, and the parts
 * (notifications, tasks, money, the conversation). Its messages go into the app's chat (the server also sent them to
 * Telegram; here the app is the only channel: a message the secretary writes on its own becomes a notification).
 * Work that waits for the model runs on one background thread, as the server's event loop ran it.
 */
public final class Secretary {
    public final SecConfig cfg;
    public final SecDb db;
    public final Brain llm;
    public final Phone phone;
    public final Tasks tasks;
    public final Finance finance;
    public final Assistant assistant;
    public final Drafts drafts;
    /** Names of the secretary's own chat bot (none on the phone: kept for the rule that skips its own messages). */
    public final Set<String> botNames = Collections.synchronizedSet(new HashSet<String>());
    /** A draft waiting for the owner's corrections by text or voice. */
    public volatile Long pendingEdit;

    /** Who is told of a new message in the chat (the app: a notification when it is the secretary's own). */
    public interface ChatSink {
        void onChat(long id, String html, boolean notify);
    }

    private final List<ChatSink> sinks = new ArrayList<ChatSink>();
    private final Object chatLock = new Object();
    private long chatVersion;
    private final ExecutorService worker, interactive;
    private final AtomicInteger pending = new AtomicInteger();

    public Secretary(SecConfig cfg, SecDb db, Brain llm) {
        this.cfg = cfg;
        this.db = db;
        this.llm = llm;
        this.phone = new Phone(this);
        this.tasks = new Tasks(this);
        this.finance = new Finance(this);
        this.assistant = new Assistant(this);
        this.drafts = new Drafts(this);
        this.worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "secretary");
                t.setDaemon(true);
                return t;
            }
        });
        this.interactive = Executors.newSingleThreadExecutor(new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "secretary-chat");
                t.setDaemon(true);
                return t;
            }
        });
    }

    public void addSink(ChatSink s) {
        synchronized (sinks) {
            sinks.add(s);
        }
    }

    public void removeSink(ChatSink s) {
        synchronized (sinks) {
            sinks.remove(s);
        }
    }

    /**
     * A message to the owner. only == null — the secretary writes on its own: into the chat and as a notification;
     * "app" — an answer where the owner asked (the chat only).
     */
    public void send(String text, Kb kb, String only) {
        boolean notify = only == null;
        long id = db.addChat("bot", text, Kb.json(kb), notify);
        chatChanged();
        List<ChatSink> copy;
        synchronized (sinks) {
            copy = new ArrayList<ChatSink>(sinks);
        }
        for (ChatSink s : copy) {
            try {
                s.onChat(id, text, notify);
            } catch (RuntimeException e) {
                log("chat sink", e);
            }
        }
    }

    public void send(String text) {
        send(text, null, null);
    }

    public long addUserMessage(String html) {
        long id = db.addChat("me", html, "[]", false);
        chatChanged();
        return id;
    }

    public void chatChanged() {
        synchronized (chatLock) {
            chatVersion++;
            chatLock.notifyAll();
        }
    }

    public long chatVersion() {
        synchronized (chatLock) {
            return chatVersion;
        }
    }

    /** Waits (up to ms) for the chat to change after `version` — the app's long poll. */
    public void awaitChat(long version, long ms) {
        long end = System.currentTimeMillis() + ms;
        synchronized (chatLock) {
            while (chatVersion == version) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) return;
                try {
                    chatLock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** A message about a failure — no more often than once in a few hours. */
    public void alert(String key, String text) {
        String last = db.kvGet("alert:" + key);
        if (last != null && Duration.between(SecUtil.fromDb(last), SecUtil.now()).compareTo(Duration.ofHours(6)) < 0) return;
        db.kvSet("alert:" + key, SecUtil.toDb(SecUtil.now()));
        try {
            send(text);
        } catch (RuntimeException e) {
            log("alert", e);
        }
    }

    /** Work in the background, one at a time (the server's app.spawn). */
    public void spawn(final Runnable r) {
        submit(worker, "background", r);
    }

    /**
     * Work the owner waits for (an answer in the chat, a draft, a button): its own thread, not behind the reading of
     * notifications; the model puts it before the background too.
     */
    public void spawnInteractive(final Runnable r) {
        submit(interactive, "chat", r);
    }

    private void submit(ExecutorService ex, final String what, final Runnable r) {
        final int queued = pending.incrementAndGet();
        final long t0 = System.currentTimeMillis();
        if (queued > 1) log(what + ": queued behind " + (queued - 1));
        ex.execute(new Runnable() {
            public void run() {
                long waited = System.currentTimeMillis() - t0;
                if (waited > 1000) log(what + ": started after waiting " + waited + " ms");
                try {
                    r.run();
                } catch (Throwable t) {
                    log(what + " work failed", t);
                } finally {
                    pending.decrementAndGet();
                }
            }
        });
    }

    /** Waits until all the background work (and what it started) is done — for tests and for closing. */
    public void drain() {
        while (pending.get() > 0) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public void close() {
        worker.shutdown();
        interactive.shutdown();
    }

    /** The log (the app sets one; on a computer — stderr). */
    public static volatile Log logger = new Log() {
        public void log(String s) {
            System.err.println("secretary: " + s);
        }
    };

    public interface Log {
        void log(String s);
    }

    public static void log(String s) {
        logger.log(s);
    }

    /** A failure with its stack, into the log. */
    public static void log(String s, Throwable t) {
        java.io.StringWriter w = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(w));
        logger.log(s + ": " + w.toString().trim());
    }
}
