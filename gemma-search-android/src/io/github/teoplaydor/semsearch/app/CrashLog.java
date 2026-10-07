package io.github.teoplaydor.semsearch.app;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * What happened when the app closed unexpectedly, for a report the person can copy into a chat:
 * our own record of a Java crash (written synchronously by an uncaught-exception handler), Android's
 * record of the process's end (native crash in ONNX Runtime or a driver, out of memory, ANR — API 30+),
 * and the risky step the engine was in at the time.
 */
final class CrashLog {
    private static boolean installed;

    private CrashLog() {}

    static File file(Context c) {
        return new File(c.getFilesDir(), "last-crash.txt");
    }

    static synchronized void install(final Context app) {
        if (installed) return;
        installed = true;
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    String text = stamp() + " · поток " + t.getName() + "\n" + sw;
                    FileOutputStream out = new FileOutputStream(file(app));
                    try {
                        out.write(text.getBytes("UTF-8"));
                    } finally {
                        out.close();
                    }
                } catch (Throwable ignored) {
                    // nothing more to do while crashing
                }
                if (previous != null) previous.uncaughtException(t, e);
            }
        });
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /**
     * A report about the previous run if it ended badly and the person has not seen it yet, else null.
     * Marks it as seen.
     */
    static String takeUnseen(Context c) {
        SharedPreferences p = prefs(c);
        long seen = p.getLong("crash_seen", 0);
        File f = file(c);
        boolean javaCrash = f.exists() && f.lastModified() > seen;
        String exit = lastExit(c, seen);
        if (!javaCrash && exit == null) return null;
        p.edit().putLong("crash_seen", System.currentTimeMillis()).apply();
        return report(c);
    }

    /** Everything known about the last unexpected end, for "copy details". */
    static String report(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append("SemSearch ").append(BuildInfo.version(c)).append(", Android ").append(Build.VERSION.SDK_INT).append(", ")
                .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", ")
                .append(Runtime.getRuntime().availableProcessors()).append(" ядер\n");
        SharedPreferences p = prefs(c);
        sb.append("Модель для фото: ").append(FastModel.NAMES[Math.max(0, Math.min(2, p.getInt("photo_model", 0)))]).append('\n');
        String died = p.getString("died_during", "");
        if (!died.isEmpty()) sb.append("Шаг в момент сбоя: ").append(died).append('\n');
        String exit = lastExit(c, 0);
        if (exit != null) sb.append(exit).append('\n');
        File f = file(c);
        if (f.exists()) {
            try {
                byte[] b = new byte[(int) Math.min(f.length(), 64 * 1024)];
                FileInputStream in = new FileInputStream(f);
                try {
                    int n = in.read(b);
                    sb.append("\nПоследнее исключение:\n").append(new String(b, 0, Math.max(0, n), "UTF-8"));
                } finally {
                    in.close();
                }
            } catch (Exception ignored) {
                // unreadable
            }
        }
        return sb.toString();
    }

    /** ApplicationExitInfo (API 30) of the last end newer than {@code since} that the person would have noticed. */
    static String lastExit(Context c, long since) {
        if (Build.VERSION.SDK_INT < 30) return null;
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            List<?> infos = (List<?>) ActivityManager.class.getMethod("getHistoricalProcessExitReasons",
                    String.class, int.class, int.class).invoke(am, c.getPackageName(), 0, 5);
            if (infos == null) return null;
            for (Object info : infos) {
                Class<?> k = info.getClass();
                long ts = (Long) k.getMethod("getTimestamp").invoke(info);
                if (ts <= since) continue;
                int reason = (Integer) k.getMethod("getReason").invoke(info);
                int importance = (Integer) k.getMethod("getImportance").invoke(info);
                String why = reasonName(reason);
                boolean crash = reason == 4 || reason == 5 || reason == 6 || reason == 7;
                boolean visible = importance <= 200; // foreground / visible: the person saw it close
                if (why == null || !(crash || visible)) continue;
                String desc = (String) k.getMethod("getDescription").invoke(info);
                long pss = (Long) k.getMethod("getPss").invoke(info), rss = (Long) k.getMethod("getRss").invoke(info);
                return String.format(Locale.ROOT, "Завершение %s: %s%s, память %d МБ (RSS %d МБ)",
                        new SimpleDateFormat("dd.MM HH:mm", Locale.ROOT).format(new Date(ts)), why,
                        desc != null && !desc.isEmpty() ? " — " + desc : "", pss / 1024, rss / 1024);
            }
        } catch (Exception ignored) {
            // not available on this phone
        }
        return null;
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case 2: return "остановлено сигналом";
            case 3: return "не хватило памяти";
            case 4: return "сбой (исключение)";
            case 5: return "сбой в нативном коде (ONNX Runtime или драйвер)";
            case 6: return "приложение не отвечало";
            case 7: return "ошибка запуска";
            case 9: return "слишком большой расход ресурсов";
            default: return null; // closed by the person, updated, etc.
        }
    }
}
