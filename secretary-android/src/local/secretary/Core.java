package local.secretary;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteProgram;
import android.database.sqlite.SQLiteStatement;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import io.github.teoplaydor.semsearch.secretary.Api;
import io.github.teoplaydor.semsearch.secretary.GeminiBrain;
import io.github.teoplaydor.semsearch.secretary.LlmError;
import io.github.teoplaydor.semsearch.secretary.Schedule;
import io.github.teoplaydor.semsearch.secretary.SecConfig;
import io.github.teoplaydor.semsearch.secretary.SecDb;
import io.github.teoplaydor.semsearch.secretary.SecUtil;
import io.github.teoplaydor.semsearch.secretary.Secretary;
import io.github.teoplaydor.semsearch.secretary.Sql;

/**
 * Секретарь целиком на телефоне — то, что раньше делал сервер: разбор уведомлений и чатов, «Тишина», дела и
 * «Сделал?», расходы, сводки и напоминания, разговор с владельцем. Нейросеть — Gemini напрямую с телефона по ключу
 * из настроек (модели те же, что были в config.toml: быстрая для сортировки, умная для разговора и черновиков, голос).
 * База — та же схема SQLite, что у сервера. Циклы сервера (чаты каждые 20 с, расписание каждые 30 с) крутятся, пока
 * живёт служба уведомлений или открыт чат, а напоминания и сводки ещё и точным будильником — даже во сне телефона.
 */
final class Core {
    static final String TAG = "Secretary";
    private static Core instance;

    static synchronized Core get(Context c) {
        if (instance == null) instance = new Core(c.getApplicationContext());
        return instance;
    }

    static synchronized Core instance() {
        return instance;
    }

    final Context app;
    final SharedPreferences prefs;
    final Secretary sec;
    final GeminiBrain brain;
    final Api api;

    private Core(Context c) {
        app = c;
        prefs = Listener.prefs(c);
        Journal.start(c);
        Secretary.logger = Journal::line;
        SecConfig cfg = new SecConfig();
        applyPrefs(cfg);
        SecDb db = new SecDb(new Db(c.getDatabasePath("secretary.db")));
        brain = new GeminiBrain(this::geminiConfig, db::addUsage);
        sec = new Secretary(cfg, db, brain);
        api = new Api(sec);
        Journal.line("core: " + Journal.about(c) + "; key " + (geminiConfig().geminiKey.isEmpty() ? "NOT set" : "set")
                + ", models " + geminiConfig().fastModel + " / " + geminiConfig().smartModel + " / " + geminiConfig().audioModel
                + ", about me " + cfg.aboutMe.length() + " chars, digests " + cfg.digestTimes);
    }

    // ---------------- настройки: то, что было в config.toml ----------------

    static final String K_KEY = "gemini_key", K_FAST = "fast_model", K_SMART = "smart_model", K_AUDIO = "audio_model",
            K_THINK = "gemini_thinking", K_OPENAI_KEY = "openai_key", K_OPENAI_URL = "openai_base_url", K_ABOUT = "about_me",
            K_NAME = "my_name", K_DIGESTS = "digest_times", K_LARGE = "large_expense", K_QUIET = "quiet_seconds",
            K_WAIT = "max_wait_seconds", K_IGNORE = "ignore_apps", K_BANKS = "bank_apps", K_REMIND = "remind_before";

    GeminiBrain.Config geminiConfig() {
        GeminiBrain.Config g = new GeminiBrain.Config();
        g.geminiKey = prefs.getString(K_KEY, "").trim();
        g.fastModel = prefs.getString(K_FAST, g.fastModel);
        g.smartModel = prefs.getString(K_SMART, g.smartModel);
        g.audioModel = prefs.getString(K_AUDIO, g.audioModel);
        g.thinking = prefs.getString(K_THINK, "");
        g.openaiKey = prefs.getString(K_OPENAI_KEY, "");
        g.openaiBaseUrl = prefs.getString(K_OPENAI_URL, g.openaiBaseUrl);
        return g;
    }

    void applyPrefs(SecConfig cfg) {
        cfg.tz = ZoneId.systemDefault();
        cfg.aboutMe = prefs.getString(K_ABOUT, "");
        cfg.myName = prefs.getString(K_NAME, "");
        cfg.largeExpense = prefs.getInt(K_LARGE, 10000);
        cfg.quietSeconds = prefs.getInt(K_QUIET, 90);
        cfg.maxWaitSeconds = prefs.getInt(K_WAIT, 600);
        List<LocalTime> t = parseTimes(prefs.getString(K_DIGESTS, "08:30, 19:00"));
        if (!t.isEmpty()) cfg.digestTimes = t;
        cfg.ignoreApps = list(prefs.getString(K_IGNORE, ""));
        cfg.bankApps = list(prefs.getString(K_BANKS, ""));
        List<Integer> remind = new ArrayList<>();
        for (String x : prefs.getString(K_REMIND, "60, 10").split("[,;\\s]+"))
            if (x.matches("\\d+")) remind.add(Integer.parseInt(x));
        cfg.remindBefore = remind;
    }

    void reloadPrefs() {
        applyPrefs(sec.cfg);
    }

    static List<String> list(String s) {
        List<String> out = new ArrayList<>();
        for (String x : s.split("[,;\\n]+")) if (!x.trim().isEmpty()) out.add(x.trim());
        return out;
    }

    static List<LocalTime> parseTimes(String s) {
        List<LocalTime> out = new ArrayList<>();
        for (String p : s.split("[,;\\s]+")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2})[:.](\\d{2})").matcher(p.trim());
            if (m.matches() && Integer.parseInt(m.group(1)) < 24 && Integer.parseInt(m.group(2)) < 60)
                out.add(LocalTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
        }
        Collections.sort(out);
        return out;
    }

    // ---------------- то, что было /hook/phone на сервере ----------------

    /** Пачка уведомлений: записать; в «Тишине» — ещё и сказать, что с каждым сделать. Ответ — как у сервера. */
    Map<String, Object> phoneHook(List<Object> items, boolean verdicts) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.putAll(sec.phone.ingest(items));
        if (verdicts) out.put("verdicts", sec.phone.verdicts(items));
        return out;
    }

    /** Голосовое: Gemini слышит его, текст — в чат, как от сервера. */
    void voice(byte[] audio, String mime) throws LlmError, IOException {
        long t0 = System.currentTimeMillis();
        String text = brain.transcribe(audio, mime);
        Journal.line("voice: " + audio.length + " bytes heard as " + text.length() + " chars in " + (System.currentTimeMillis() - t0) + " ms");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text);
        api.handle("/api/voice", io.github.teoplaydor.semsearch.core.MiniJson.write(body));
    }

    // ---------------- циклы сервера: чаты, расписание ----------------

    private HandlerThread loopThread;
    private Handler loop;
    private int loopUsers;
    private volatile boolean phoneQueued;
    private long lastTick, lastBeat;
    private Instant lastAlarm;

    synchronized void startLoop() {
        loopUsers++;
        if (loopThread != null) return;
        loopThread = new HandlerThread("core-loop");
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
        @Override public void run() {
            stepNow();
            Handler h = loop;
            if (h != null) h.postDelayed(this, 20_000);
        }
    };

    /** Чаты, где пауза, — нейросети (по одному, в фоне); раз в 30 с — расписание; раз в сутки — чистка. */
    void stepNow() {
        long now = System.currentTimeMillis();
        if (now - lastBeat > 5 * 60_000) {
            lastBeat = now;
            Journal.line("loop: alive; chats waiting " + sec.db.phonePendingGroups().size() + ", tasks due " + sec.tasks.due(null).size()
                    + ", «Тишина» " + (Listener.focus(app) ? "on" : "off") + ", listener " + (Listener.instance != null ? "connected" : "NOT connected"));
        }
        try {
            if (!phoneQueued) {
                phoneQueued = true;
                sec.spawn(() -> {
                    try {
                        Schedule.phoneStep(sec, null);
                    } finally {
                        phoneQueued = false;
                    }
                });
            }
            if (now - lastTick >= 29_000) {
                lastTick = now;
                Schedule.tick(sec, null);
            }
            scheduleAlarm();
        } catch (RuntimeException e) {
            Journal.error("loop step", e);
        }
    }

    /** Когда ближайшее: напоминание, «через 60/10 минут» о событии, сводка. */
    Instant nextDue() {
        Instant now = SecUtil.now(), best = null;
        for (Sql.Row r : sec.db.pendingReminders()) {
            best = SecUtil.fromDb(r.text("at"));
            break;
        }
        for (Sql.Row ev : sec.db.eventsBetween(now.minus(Duration.ofMinutes(5)), now.plus(Duration.ofDays(2)))) {
            Instant start = SecUtil.fromDb(ev.text("start_at"));
            for (int o : sec.cfg.remindBefore) {
                Instant at = start.minus(Duration.ofMinutes(o));
                if (at.isAfter(now) && !SecDb.parseReminded(ev.text("reminded")).contains(o) && (best == null || at.isBefore(best))) best = at;
            }
        }
        ZonedDateTime loc = now.atZone(sec.cfg.tz);
        for (int d = 0; d < 2; d++)
            for (LocalTime t : sec.cfg.digestTimes) {
                Instant at = ZonedDateTime.of(loc.toLocalDate().plusDays(d), t, sec.cfg.tz).toInstant();
                if (at.isAfter(now) && (best == null || at.isBefore(best))) best = at;
            }
        return best;
    }

    void scheduleAlarm() {
        Instant at = nextDue();
        AlarmManager am = app.getSystemService(AlarmManager.class);
        PendingIntent pi = PendingIntent.getBroadcast(app, 1, new Intent(app, Alarm.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (at == null) {
            am.cancel(pi);
            return;
        }
        if (!at.equals(lastAlarm)) {
            lastAlarm = at;
            Journal.line("alarm: next at " + at.atZone(sec.cfg.tz).toLocalDateTime());
        }
        long when = at.toEpochMilli() + 1000;
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        }
    }

    /** Точный будильник: шаг расписания, даже когда телефон спит. */
    public static class Alarm extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            PendingResult done = goAsync();
            Core core = get(c);
            Journal.line("alarm: fired");
            new Thread(() -> {
                try {
                    core.lastTick = 0;
                    core.stepNow();
                } finally {
                    done.finish();
                }
            }, "alarm").start();
        }
    }

    // ---------------- база: SQLite телефона ----------------

    /** Та же схема, что у сервера (SecDb). Аргументы с их типами: rawQuery связал бы всё как текст. */
    static final class Db implements Sql {
        private final SQLiteDatabase db;

        Db(File file) {
            File dir = file.getParentFile();
            if (dir != null) dir.mkdirs();
            db = SQLiteDatabase.openOrCreateDatabase(file, null);
            db.enableWriteAheadLogging();
        }

        static void bind(SQLiteProgram p, Object[] args) {
            for (int i = 0; i < args.length; i++) {
                Object a = args[i];
                if (a == null) p.bindNull(i + 1);
                else if (a instanceof Long || a instanceof Integer) p.bindLong(i + 1, ((Number) a).longValue());
                else if (a instanceof Boolean) p.bindLong(i + 1, (Boolean) a ? 1 : 0);
                else if (a instanceof Double || a instanceof Float) p.bindDouble(i + 1, ((Number) a).doubleValue());
                else p.bindString(i + 1, a.toString());
            }
        }

        @Override public List<Row> q(String sql, Object... args) {
            Cursor c = db.rawQueryWithFactory((d, driver, table, query) -> {
                bind(query, args);
                return new SQLiteCursor(driver, table, query);
            }, sql, null, null);
            List<Row> out = new ArrayList<>();
            try {
                String[] names = c.getColumnNames();
                while (c.moveToNext()) {
                    Row r = new Row();
                    for (int i = 0; i < names.length; i++) {
                        switch (c.getType(i)) {
                            case Cursor.FIELD_TYPE_NULL: r.put(names[i], null); break;
                            case Cursor.FIELD_TYPE_INTEGER: r.put(names[i], c.getLong(i)); break;
                            case Cursor.FIELD_TYPE_FLOAT: r.put(names[i], c.getDouble(i)); break;
                            case Cursor.FIELD_TYPE_BLOB: r.put(names[i], c.getBlob(i)); break;
                            default: r.put(names[i], c.getString(i));
                        }
                    }
                    out.add(r);
                }
            } finally {
                c.close();
            }
            return out;
        }

        @Override public long insert(String sql, Object... args) {
            try (SQLiteStatement s = db.compileStatement(sql)) {
                bind(s, args);
                return s.executeInsert();
            }
        }

        @Override public int update(String sql, Object... args) {
            try (SQLiteStatement s = db.compileStatement(sql)) {
                bind(s, args);
                return s.executeUpdateDelete();
            }
        }

        @Override public void script(String sql) {
            for (String part : sql.split(";\n")) if (!part.trim().isEmpty()) db.execSQL(part);
        }
    }

    // ---------------- журнал ----------------

    /**
     * Подробный журнал: страница, каждый запрос, нейросеть (что спросили, сколько ждали, что ответила), уведомления
     * (приложение, чат, сколько сообщений — без текста), «Тишина», «Сделал?», будильники, ошибки со стеком.
     * files/journal.txt (и предыдущий journal.1.txt); «Отправить журнал» кладёт оба в Documents/Секретарь.
     */
    static final class Journal {
        static final long MAX = 6L << 20;
        private static final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
        private static volatile File dir;
        private static Thread writer;
        private static final SimpleDateFormat TIME = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT);

        static synchronized void start(Context c) {
            if (writer != null) return;
            dir = c.getFilesDir();
            writer = new Thread(Journal::write, "journal");
            writer.setDaemon(true);
            writer.start();
        }

        static void line(String s) {
            String l;
            synchronized (TIME) {
                l = TIME.format(new Date()) + " [" + Thread.currentThread().getName() + "] " + s;
            }
            Log.i(TAG, s.length() > 3000 ? s.substring(0, 3000) : s);
            queue.offer(l);
        }

        static void error(String s, Throwable t) {
            line(s + ": " + Log.getStackTraceString(t).trim());
        }

        private static File file() {
            return new File(dir, "journal.txt");
        }

        private static File before() {
            return new File(dir, "journal.1.txt");
        }

        private static void write() {
            Writer w = null;
            long size = 0;
            while (true) {
                try {
                    String l = queue.take();
                    if (w == null) {
                        size = file().length();
                        w = new OutputStreamWriter(new FileOutputStream(file(), true), "UTF-8");
                    }
                    w.write(l);
                    w.write('\n');
                    size += l.length() * 2L;
                    if (queue.isEmpty()) w.flush();
                    if (size > MAX) {
                        w.close();
                        w = null;
                        before().delete();
                        file().renameTo(before());
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (IOException e) {
                    w = null;
                }
            }
        }

        static String about(Context c) {
            String v;
            try {
                v = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (Exception e) {
                v = "?";
            }
            return "Секретарь " + v + " · " + Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE
                    + " (SDK " + Build.VERSION.SDK_INT + ") · доступ к уведомлениям " + (Listener.hasAccess(c) ? "есть" : "НЕТ");
        }

        /** Оба файла одним в Documents/Секретарь и окно «Отправить». */
        static Uri share(Activity a) {
            line("==== shared: " + about(a));
            for (int i = 0; i < 50 && !queue.isEmpty(); i++) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ignored) {
                }
            }
            String name = "secretary-journal-" + new SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(new Date()) + ".txt";
            Uri uri = null;
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/Секретарь");
                uri = a.getContentResolver().insert(MediaStore.Files.getContentUri("external"), v);
                try (OutputStream out = a.getContentResolver().openOutputStream(uri)) {
                    copy(before(), out);
                    copy(file(), out);
                }
            } catch (Exception e) {
                Log.w(TAG, "journal share", e);
                return null;
            }
            Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, "Журнал секретаря " + name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                a.startActivity(Intent.createChooser(i, "Отправить журнал"));
            } catch (RuntimeException ignored) {
                // нечем отправить — файл всё равно в Documents/Секретарь
            }
            return uri;
        }

        private static void copy(File f, OutputStream out) throws IOException {
            if (!f.exists()) return;
            try (InputStream in = new FileInputStream(f)) {
                byte[] b = new byte[1 << 16];
                for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            }
        }
    }
}
