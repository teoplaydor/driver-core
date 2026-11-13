package io.github.teoplaydor.semsearch.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.teoplaydor.semsearch.R;
import io.github.teoplaydor.semsearch.core.HfRepo;
import io.github.teoplaydor.semsearch.secretary.Api;
import io.github.teoplaydor.semsearch.secretary.LocalBrain;
import io.github.teoplaydor.semsearch.secretary.LocalLlm;
import io.github.teoplaydor.semsearch.secretary.Prompts;
import io.github.teoplaydor.semsearch.secretary.Schedule;
import io.github.teoplaydor.semsearch.secretary.SecConfig;
import io.github.teoplaydor.semsearch.secretary.SecDb;
import io.github.teoplaydor.semsearch.secretary.SecUtil;
import io.github.teoplaydor.semsearch.secretary.Secretary;
import io.github.teoplaydor.semsearch.secretary.Sql.Row;

/**
 * The secretary inside SemSearch: one per process. Its database (the server's schema, files/secretary), its model
 * (a GGUF downloaded from Hugging Face, run by libsecllm), the API its chat page speaks, the loop that reads quiet
 * chats and keeps the schedule (and an exact alarm for the next reminder or digest, so they come on time even when the
 * phone sleeps), and the notifications it writes: «Важное» — sounds even in «Тишина».
 */
public final class SecHub {
    static final String TAG = "Secretary";
    static final String PREFS = "secretary";
    static final String REPO_4B = "prism-ml/Ternary-Bonsai-4B-gguf", REPO_8B = "prism-ml/Ternary-Bonsai-8B-gguf";
    /** The GGUF of a repo, the likeliest first: the ternary formats this llama.cpp runs best. */
    static final String[] PREFER = {"pq2_0", "q2_0", "tq2_0", "tq1_0", "q2_k", "q4_0"};
    static final String CH_ALERT = "sec_important";
    static final int ASK_NOTIF = 7102, TEST_NOTIF = 7103;
    static final String CHAT_TAG = "sec_chat";

    private static SecHub instance;

    static synchronized SecHub get(Context c) {
        if (instance == null) instance = new SecHub(c.getApplicationContext());
        return instance;
    }

    final Context app;
    final SharedPreferences prefs;
    final Secretary sec;
    final LocalBrain brain;
    final Api api;
    /** The secretary's screen is open: its messages are not shown as notifications. */
    static volatile boolean visible;

    private SecHub(Context c) {
        app = c;
        prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SecLog.start(c);
        Secretary.logger = new Secretary.Log() {
            public void log(String s) {
                SecLog.i(s);
            }
        };
        SecConfig cfg = new SecConfig();
        applyPrefs(cfg);
        SecDb db = new SecDb(new SecSql(new File(c.getFilesDir(), "secretary/secretary.db")));
        final SecDb dbRef = db;
        LocalLlm.Options o = new LocalLlm.Options();
        o.context = prefs.getInt("context", 8192);
        brain = new LocalBrain(new LocalBrain.Source() {
            public File model() {
                return modelFile();
            }
        }, new LocalBrain.Usage() {
            public void add(String model, long in, long out) {
                dbRef.addUsage(model, in, out);
            }
        }, o);
        sec = new Secretary(cfg, db, brain);
        api = new Api(sec);
        File mf = modelFile();
        SecLog.i("hub: model " + (mf == null ? "not downloaded" : mf.getName() + " " + mf.length() / 1000000 + " MB")
                + ", context " + o.context + ", threads " + o.threads + ", «Тишина» " + (prefs.getBoolean("focus", false) ? "on" : "off")
                + ", about me " + cfg.aboutMe.length() + " chars, digests " + cfg.digestTimes + ", tz " + cfg.tz.getId()
                + ", chat messages " + db.one("SELECT count(*) n FROM chat_log").lng("n")
                + ", open tasks " + db.openTasks(500).size() + ", phone messages " + db.one("SELECT count(*) n FROM phone_msgs").lng("n"));
        sec.addSink(new Secretary.ChatSink() {
            public void onChat(long id, String html, boolean notify) {
                if (notify && !visible && id > prefs.getLong("seen_id", 0)) showChat(id, html);
            }
        });
    }

    /** The settings into the secretary's config (the owner's «about me», digest times, the time zone). */
    void applyPrefs(SecConfig cfg) {
        cfg.tz = ZoneId.systemDefault();
        cfg.aboutMe = prefs.getString("about_me", "");
        cfg.myName = prefs.getString("my_name", "");
        cfg.largeExpense = prefs.getInt("large_expense", 10000);
        List<LocalTime> times = parseTimes(prefs.getString("digest_times", "08:30, 19:00"));
        if (!times.isEmpty()) cfg.digestTimes = times;
    }

    void reloadPrefs() {
        applyPrefs(sec.cfg);
    }

    static List<LocalTime> parseTimes(String s) {
        List<LocalTime> out = new ArrayList<LocalTime>();
        for (String p : s.split("[,;\\s]+")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2})[:.](\\d{2})").matcher(p.trim());
            if (m.matches()) {
                int h = Integer.parseInt(m.group(1)), mi = Integer.parseInt(m.group(2));
                if (h < 24 && mi < 60) out.add(LocalTime.of(h, mi));
            }
        }
        Collections.sort(out);
        return out;
    }

    // ---------------- the model ----------------

    File modelDir() {
        return new File(app.getFilesDir(), "secretary/model");
    }

    File modelFile() {
        String n = prefs.getString("model_file", null);
        if (n == null) return null;
        File f = new File(modelDir(), n);
        return f.isFile() ? f : null;
    }

    /** The GGUF to take from a repo's files: by format preference, then the smallest. */
    static HfRepo.RemoteFile pickGguf(List<HfRepo.RemoteFile> files) {
        List<HfRepo.RemoteFile> ggufs = new ArrayList<HfRepo.RemoteFile>();
        for (HfRepo.RemoteFile f : files) {
            String p = f.path.toLowerCase(Locale.ROOT);
            if (p.endsWith(".gguf") && !p.contains("mmproj") && !p.contains("/")) ggufs.add(f);
        }
        if (ggufs.isEmpty()) for (HfRepo.RemoteFile f : files)
            if (f.path.toLowerCase(Locale.ROOT).endsWith(".gguf") && !f.path.toLowerCase(Locale.ROOT).contains("mmproj")) ggufs.add(f);
        for (String pref : PREFER) {
            HfRepo.RemoteFile best = null;
            for (HfRepo.RemoteFile f : ggufs) {
                String name = f.path.toLowerCase(Locale.ROOT).replace('-', '_');
                if (name.contains(pref) && (best == null || f.size < best.size)) best = f;
            }
            if (best != null) return best;
        }
        Collections.sort(ggufs, new Comparator<HfRepo.RemoteFile>() {
            public int compare(HfRepo.RemoteFile a, HfRepo.RemoteFile b) {
                return Long.compare(a.size, b.size);
            }
        });
        return ggufs.isEmpty() ? null : ggufs.get(0);
    }

    /** The download in hand (one at a time), for the settings screen. */
    static final class Download {
        volatile boolean running, cancel;
        volatile long done, total;
        volatile String file = "", error = "";
    }

    final Download download = new Download();

    /** Downloads the repo's GGUF (resumable) and makes it the model. Off the main thread; returns at once. */
    void startDownload(final String repo, final Runnable changed) {
        synchronized (download) {
            if (download.running) return;
            download.running = true;
            download.cancel = false;
            download.error = "";
            download.done = 0;
            download.total = 0;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    SecLog.i("download: listing " + repo);
                    HfRepo r = new HfRepo(repo, null);
                    List<HfRepo.RemoteFile> listed = r.listFiles();
                    StringBuilder names = new StringBuilder();
                    for (HfRepo.RemoteFile x : listed) names.append(x.path).append(" (").append(x.size / 1000000).append(" MB) ");
                    SecLog.i("download: files " + names);
                    HfRepo.RemoteFile f = pickGguf(listed);
                    if (f == null) throw new java.io.IOException("в " + repo + " нет файла .gguf");
                    SecLog.i("download: taking " + f.path);
                    download.file = f.path;
                    download.total = f.size;
                    File dst = new File(modelDir(), f.path.substring(f.path.lastIndexOf('/') + 1));
                    r.downloadFile(f, dst, new HfRepo.Progress() {
                        long last;
                        int tenth = -1;

                        public boolean onProgress(String file, long fileDone, long fileTotal, long allDone, long allTotal) {
                            download.done = fileDone;
                            int t = fileTotal > 0 ? (int) (fileDone * 10 / fileTotal) : -1;
                            if (t != tenth) {
                                tenth = t;
                                SecLog.i("download: " + fileDone / 1000000 + " of " + fileTotal / 1000000 + " MB");
                            }
                            if (fileTotal > 0) download.total = fileTotal;
                            long now = SystemClock.uptimeMillis();
                            if (now - last > 500) {
                                last = now;
                                if (changed != null) changed.run();
                            }
                            return !download.cancel;
                        }
                    });
                    // the other models go: one on the phone at a time
                    File[] old = modelDir().listFiles();
                    if (old != null) for (File x : old) if (!x.equals(dst) && x.getName().endsWith(".gguf")) x.delete();
                    prefs.edit().putString("model_file", dst.getName()).putString("model_repo", repo).apply();
                    brain.unload();
                    SecLog.i("download: done, " + dst.getName() + " " + dst.length() / 1000000 + " MB");
                } catch (Exception e) {
                    download.error = download.cancel ? "остановлено" : String.valueOf(e.getMessage());
                    SecLog.e("download failed", e);
                } finally {
                    download.running = false;
                    if (changed != null) changed.run();
                }
            }
        }, "sec-download").start();
    }

    /**
     * How fast the model is on this phone: the «Тишина» filter on 5 notifications (first with its system prompt read,
     * then from the cache) and a chat read into a card. Lines for the settings screen.
     */
    String benchmark() {
        StringBuilder out = new StringBuilder();
        String quick = "Уведомления:\n[0] WhatsApp · Анна\n   Анна: Ты сегодня придёшь на ужин? Ответь до шести\n\n"
                + "[1] Ozon · Скидки до 70%\n   Ozon: Только сегодня — распродажа электроники\n\n"
                + "[2] Telegram · Рабочий чат (групповой чат)\n   Олег: Всем привет, созвон переносится на 15:00\n\n"
                + "[3] Самокат · Самокат\n   Самокат: Курьер уже у двери\n\n"
                + "[4] ВКонтакте · ВКонтакте\n   ВКонтакте: Вашу запись оценили 5 человек";
        String system = Prompts.fill(Prompts.QUICK_SYSTEM, "about_me", sec.cfg.aboutMe.isEmpty() ? "(не указано)" : sec.cfg.aboutMe);
        try {
            long t0 = System.currentTimeMillis();
            Map<String, Object> v = brain.json(false, true, system, quick, Prompts.QUICK_SCHEMA);
            long first = System.currentTimeMillis() - t0;
            out.append("Загрузка + первый фильтр: ").append(first).append(" мс\n").append(brain.lastStats).append("\n\n");
            t0 = System.currentTimeMillis();
            v = brain.json(false, true, system, quick, Prompts.QUICK_SCHEMA);
            out.append("Фильтр «Тишины», 5 уведомлений: ").append(System.currentTimeMillis() - t0).append(" мс\n")
                    .append(brain.lastStats).append("\nРешения: ").append(v.get("items")).append("\n\n");
            String chat = Prompts.fill(Prompts.CHAT_TRIAGE_USER, "now", SecUtil.isoMinutes(SecUtil.now(), sec.cfg.tz), "app", "WhatsApp",
                    "chat", "Анна", "group", "", "context", "(нет)", "messages",
                    "[13.11 17:02] Анна: Привет! Ты сегодня придёшь на ужин?\n[13.11 17:03] Анна: Мама просила купить хлеб по дороге\n"
                            + "[13.11 17:05] Анна: Ответь до шести, пожалуйста");
            String sys2 = Prompts.fill(Prompts.CHAT_TRIAGE_SYSTEM, "about_me", sec.cfg.aboutMe.isEmpty() ? "(не указано)" : sec.cfg.aboutMe,
                    "tz", sec.cfg.tz.getId());
            t0 = System.currentTimeMillis();
            Map<String, Object> card = brain.json(false, false, sys2, chat, Prompts.TRIAGE_SCHEMA);
            out.append("Разбор чата в карточку: ").append(System.currentTimeMillis() - t0).append(" мс\n").append(brain.lastStats)
                    .append("\nКарточка: ").append(card.get("summary")).append(" · дело: ").append(card.get("action"))
                    .append(" · важность ").append(card.get("importance"));
        } catch (Exception e) {
            out.append("Не получилось: ").append(e.getMessage());
            SecLog.e("benchmark failed", e);
        }
        SecLog.i("benchmark:\n" + out);
        return out.toString();
    }

    // ---------------- the loop: quiet chats, the schedule ----------------

    private HandlerThread loopThread;
    private Handler loop;
    private int loopUsers;
    private volatile boolean phoneStepQueued;
    static final long STEP_MS = 20_000;

    synchronized void startLoop() {
        loopUsers++;
        if (loopThread != null) return;
        loopThread = new HandlerThread("sec-loop");
        loopThread.start();
        loop = new Handler(loopThread.getLooper());
        loop.post(step);
    }

    synchronized void stopLoop() {
        if (--loopUsers > 0 || loopThread == null) return;
        loopUsers = 0;
        loopThread.quitSafely();
        loopThread = null;
        loop = null;
    }

    private final Runnable step = new Runnable() {
        public void run() {
            stepNow();
            Handler h = loop;
            if (h != null) h.postDelayed(this, STEP_MS);
        }
    };

    /** One step: read quiet chats (in the background, one at a time), keep the schedule, set the next alarm. */
    private long lastBeat;
    private Instant lastAlarm;

    void stepNow() {
        long now = System.currentTimeMillis();
        if (now - lastBeat > 5 * 60_000) { // a heartbeat every 5 minutes: alive, what is waiting
            lastBeat = now;
            try {
                SecLog.i("loop: alive; chats waiting " + sec.db.phonePendingGroups().size() + ", tasks due " + sec.tasks.due(null).size()
                        + ", model " + (brain.loaded() ? "loaded" : "not loaded") + ", «Тишина» " + (focus() ? "on" : "off")
                        + ", listener " + (SecListener.instance != null ? "connected" : "NOT connected"));
            } catch (RuntimeException e) {
                SecLog.e("loop beat", e);
            }
        }
        try {
            if (!phoneStepQueued) {
                phoneStepQueued = true;
                sec.spawn(new Runnable() {
                    public void run() {
                        try {
                            Schedule.phoneStep(sec, null);
                        } finally {
                            phoneStepQueued = false;
                        }
                    }
                });
            }
            Schedule.tick(sec, null);
            scheduleAlarm();
        } catch (RuntimeException e) {
            SecLog.e("loop step failed", e);
        }
    }

    /** The next moment something is due (a reminder, «через 60/10 минут» of an event, a digest), or null. */
    Instant nextDue() {
        Instant now = SecUtil.now(), best = null;
        for (Row r : sec.db.pendingReminders()) {
            best = min(best, SecUtil.fromDb(r.text("at")));
            break;
        }
        for (Row ev : sec.db.eventsBetween(now.minus(Duration.ofMinutes(5)), now.plus(Duration.ofDays(2)))) {
            Instant start = SecUtil.fromDb(ev.text("start_at"));
            for (int o : sec.cfg.remindBefore)
                if (!SecDb.parseReminded(ev.text("reminded")).contains(o)) {
                    Instant at = start.minus(Duration.ofMinutes(o));
                    if (at.isAfter(now)) best = min(best, at);
                }
        }
        ZonedDateTime loc = now.atZone(sec.cfg.tz);
        for (int d = 0; d < 2; d++) {
            LocalDate day = loc.toLocalDate().plusDays(d);
            for (LocalTime t : sec.cfg.digestTimes) {
                Instant at = ZonedDateTime.of(day, t, sec.cfg.tz).toInstant();
                if (at.isAfter(now)) best = min(best, at);
            }
        }
        return best;
    }

    private static Instant min(Instant a, Instant b) {
        return a == null || b.isBefore(a) ? b : a;
    }

    void scheduleAlarm() {
        Instant at = nextDue();
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(app, 7101, new Intent(app, SecAlarm.class),
                PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000 /* FLAG_IMMUTABLE */);
        if (at == null) {
            am.cancel(pi);
            return;
        }
        long when = at.toEpochMilli() + 1000;
        if (!at.equals(lastAlarm)) {
            lastAlarm = at;
            SecLog.i("alarm: next at " + at.atZone(sec.cfg.tz).toLocalDateTime());
        }
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        }
    }

    /** The exact alarm: the schedule's step, even when the phone sleeps. */
    public static class SecAlarm extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            final PendingResult done = goAsync();
            final SecHub hub = get(c);
            SecLog.i("alarm: fired");
            new Thread(new Runnable() {
                public void run() {
                    try {
                        hub.stepNow();
                    } finally {
                        done.finish();
                    }
                }
            }, "sec-alarm").start();
        }
    }

    // ---------------- «Тишина» and the notifications the secretary writes ----------------

    boolean focus() {
        return prefs.getBoolean("focus", false);
    }

    /** The current «Важное» channel. Its name has a number: see ensureChannels. */
    String alertChannel() {
        return CH_ALERT + "_" + prefs.getInt("alert_ch", 1);
    }

    /**
     * «Важное» sounds through «Не беспокоить» only if made when the policy access was already there; a channel's
     * settings cannot be changed by recreating it under the same id — so a new number. Only the secretary's own old
     * channels go; the rest of SemSearch's stay.
     */
    void ensureChannels() {
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        String id = alertChannel();
        Boolean bypass = SecCompat.canBypassDnd(app, id);
        if (bypass != null && !bypass && nm.isNotificationPolicyAccessGranted()) {
            int next = prefs.getInt("alert_ch", 1) + 1;
            prefs.edit().putInt("alert_ch", next).apply();
            SecLog.i("channels: «Важное» made again (" + next + ") to sound through «Не беспокоить»");
            SecCompat.deleteChannel(app, id);
            id = alertChannel();
        }
        SecCompat.channel(app, id, "Секретарь: важное", "Важное от секретаря — со звуком, даже в режиме «Тишина»",
                SecCompat.IMPORTANCE_HIGH, true);
        for (String old : SecCompat.channelIds(app))
            if (old.startsWith(CH_ALERT + "_") && !old.equals(id)) SecCompat.deleteChannel(app, old);
    }

    PendingIntent openApp() {
        Intent open = new Intent(app, SecActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(app, 7100, open, 0x04000000 | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** A message the secretary wrote on its own: the first line a title (without «#12»), the rest the text. */
    void showChat(long id, String html) {
        ensureChannels();
        String text = SecCompat.htmlText(html.replace("\n", "<br>")).trim(); // its lines kept: the first is the title
        String title = "Секретарь", body = text;
        int nl = text.indexOf('\n');
        if (nl > 0) {
            title = text.substring(0, nl).replaceAll("\\s*·\\s*#\\d+", "").trim();
            body = text.substring(nl).trim();
        }
        Notification n = SecCompat.builder(app, alertChannel())
                .setSmallIcon(R.drawable.ic_secretary_small)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .build();
        try {
            ((NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE)).notify(CHAT_TAG, (int) id, n);
            SecLog.i("notify: chat message #" + id + " «" + title + "»");
        } catch (SecurityException e) {
            SecLog.e("notify: not allowed", e);
        }
    }

    /** «Проверить звук»: so the important comes. */
    void testAlert() {
        ensureChannels();
        Notification n = SecCompat.builder(app, alertChannel())
                .setSmallIcon(R.drawable.ic_secretary_small)
                .setContentTitle("Проверка")
                .setContentText("Так будет приходить важное — со звуком, даже в Тишине")
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build();
        try {
            ((NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE)).notify(TEST_NOTIF, n);
        } catch (SecurityException ignored) {
        }
    }

    /** The secretary's screen opened: its notifications are read. */
    void clearChatNotifications() {
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        for (Row r : sec.db.chatTail(50)) nm.cancel(CHAT_TAG, (int) r.lng("id"));
    }
}
