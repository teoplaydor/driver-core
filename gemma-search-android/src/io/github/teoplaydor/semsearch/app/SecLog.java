package io.github.teoplaydor.semsearch.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * The secretary's log, line by line with the phone's time and the thread: its page (what the page asked, its errors),
 * every API call and how long it took, the queue of work, the model (loading, llama.cpp's own lines, each request with
 * its speed and its answer), the notifications (which app, which chat, how many messages — not their text), the
 * «Тишина» verdicts, «Сделал?», alarms, downloads, every failure with its stack. A file in the app's files
 * (secretary/log.txt, the one before it log.1.txt), written by a thread of its own; {@link #share} puts both in
 * Documents/SemSearch and offers to send them.
 */
public final class SecLog {
    static final long MAX = 6L << 20;
    private static final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<String>();
    private static volatile File dir;
    private static Thread writer;
    private static final SimpleDateFormat TIME = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT);

    private SecLog() {
    }

    /** Starts the log (once per process) and writes what the phone is. */
    static synchronized void start(Context c) {
        if (writer != null) return;
        dir = new File(c.getFilesDir(), "secretary");
        dir.mkdirs();
        writer = new Thread(new Runnable() {
            public void run() {
                write();
            }
        }, "sec-log");
        writer.setDaemon(true);
        writer.start();
        i("==== start: " + about(c));
    }

    static void i(String s) {
        String line;
        synchronized (TIME) {
            line = TIME.format(new Date()) + " [" + Thread.currentThread().getName() + "] " + s;
        }
        Log.i(SecHub.TAG, s.length() > 3000 ? s.substring(0, 3000) : s);
        queue.offer(line);
    }

    static void e(String s, Throwable t) {
        i(s + ": " + Log.getStackTraceString(t).trim());
    }

    private static void write() {
        Writer w = null;
        long size = 0;
        while (true) {
            try {
                String line = queue.take();
                if (w == null) {
                    File f = file();
                    size = f.length();
                    w = new OutputStreamWriter(new FileOutputStream(f, true), "UTF-8");
                }
                w.write(line);
                w.write('\n');
                size += line.length() * 2L;
                if (queue.isEmpty()) w.flush();
                if (size > MAX) { // the next file; the one before it is kept
                    w.close();
                    w = null;
                    File before = before();
                    before.delete();
                    file().renameTo(before);
                }
            } catch (InterruptedException e) {
                return;
            } catch (IOException e) {
                Log.w(SecHub.TAG, "log: " + e);
                w = null;
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    static File file() {
        return new File(dir, "log.txt");
    }

    static File before() {
        return new File(dir, "log.1.txt");
    }

    /** Waits (a little) for the lines in the queue to be on disk. */
    static void flush() {
        for (int i = 0; i < 50 && !queue.isEmpty(); i++) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                return;
            }
        }
        try {
            Thread.sleep(50);
        } catch (InterruptedException ignored) {
        }
    }

    /** The last n lines (for the settings screen). */
    static String tail(int n) {
        flush();
        Deque<String> last = new ArrayDeque<String>();
        File f = file();
        if (!f.exists()) return "";
        try {
            BufferedReader r = new BufferedReader(new FileReader(f));
            try {
                for (String l; (l = r.readLine()) != null; ) {
                    last.addLast(l);
                    if (last.size() > n) last.removeFirst();
                }
            } finally {
                r.close();
            }
        } catch (IOException e) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String l : last) sb.append(l).append('\n');
        return sb.toString();
    }

    /** What the phone is: the app's version, the device, Android, the processor, memory, the accesses. */
    static String about(Context c) {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("SemSearch ").append(c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName);
        } catch (Exception e) {
            sb.append("SemSearch ?");
        }
        sb.append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" (").append(Build.DEVICE).append(")")
                .append(" · Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")");
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                sb.append(" · SoC ").append(Build.class.getField("SOC_MANUFACTURER").get(null)).append(' ')
                        .append(Build.class.getField("SOC_MODEL").get(null));
            } catch (Exception ignored) {
            }
        }
        sb.append(" · ABI ").append(Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?");
        sb.append(" · cores ").append(Runtime.getRuntime().availableProcessors()).append(" ").append(cpuFreqs());
        String features = cpuFeatures();
        if (!features.isEmpty()) sb.append(" · CPU features: ").append(features);
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            sb.append(String.format(Locale.ROOT, " · RAM %.1f GB, free %.1f GB%s, app heap %d MB", mi.totalMem / 1e9, mi.availMem / 1e9,
                    mi.lowMemory ? " (low!)" : "", am.getLargeMemoryClass()));
        } catch (Exception ignored) {
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        sb.append(" · notification access ").append(SecListener.hasAccess(c) ? "yes" : "NO")
                .append(", «Не беспокоить» access ").append(nm.isNotificationPolicyAccessGranted() ? "yes" : "no");
        try {
            sb.append(", notifications allowed ")
                    .append((Boolean) NotificationManager.class.getMethod("areNotificationsEnabled").invoke(nm) ? "yes" : "NO");
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    static String cpuFreqs() {
        StringBuilder sb = new StringBuilder("[");
        int n = Runtime.getRuntime().availableProcessors();
        for (int i = 0; i < n; i++) {
            String v = readLine("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq");
            if (i > 0) sb.append(' ');
            sb.append(v == null ? "?" : String.valueOf(Long.parseLong(v.trim()) / 1000));
        }
        return sb.append(" MHz]").toString();
    }

    static String cpuFeatures() {
        try {
            BufferedReader r = new BufferedReader(new FileReader("/proc/cpuinfo"));
            try {
                for (String l; (l = r.readLine()) != null; )
                    if (l.startsWith("Features")) return l.substring(l.indexOf(':') + 1).trim();
            } finally {
                r.close();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String readLine(String path) {
        try {
            BufferedReader r = new BufferedReader(new FileReader(path));
            try {
                return r.readLine();
            } finally {
                r.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** The last lines to the clipboard. */
    static void copy(Context c) {
        String t = tail(400);
        ((ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Журнал секретаря", t));
    }

    /**
     * Both files as one in Documents/SemSearch (secretary-log-2026-11-13-1503.txt) and the share sheet to send it;
     * null when it could not be written.
     */
    static Uri share(Activity a) {
        i("==== shared: " + about(a));
        flush();
        String name = "secretary-log-" + new SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(new Date()) + ".txt";
        Uri uri = null;
        OutputStream out = null;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                v.put("relative_path", Environment.DIRECTORY_DOCUMENTS + "/SemSearch");
                uri = a.getContentResolver().insert(MediaStore.Files.getContentUri("external_primary"), v);
                if (uri != null) out = a.getContentResolver().openOutputStream(uri);
            }
            if (out == null) {
                File d = new File(a.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "SemSearch");
                if (!d.exists() && !d.mkdirs()) throw new IOException("нет папки " + d);
                File f = new File(d, name);
                out = new FileOutputStream(f);
                uri = Uri.fromFile(f);
            }
            copy(before(), out);
            copy(file(), out);
            out.close();
            out = null;
        } catch (Exception e) {
            if (out != null) try {
                out.close();
            } catch (IOException ignored) {
            }
            Log.w(SecHub.TAG, "share log", e);
            return null;
        }
        if ("content".equals(uri.getScheme())) {
            Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, "Журнал секретаря " + name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                a.startActivity(Intent.createChooser(i, "Отправить журнал секретаря"));
            } catch (RuntimeException e) {
                // nothing to send it with: it is in Documents/SemSearch anyway
            }
        }
        return uri;
    }

    private static void copy(File f, OutputStream out) throws IOException {
        if (!f.exists()) return;
        InputStream in = new FileInputStream(f);
        try {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        } finally {
            in.close();
        }
    }
}
