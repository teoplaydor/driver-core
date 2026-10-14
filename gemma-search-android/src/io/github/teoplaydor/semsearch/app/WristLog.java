package io.github.teoplaydor.semsearch.app;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * What happened between the watch and the phone, line by line with the phone's time: the connection, the watch's
 * HELLO, every packet and what the phone made of it (a turn and why it pressed or did not), what the phone told the
 * watch, the voice notes; and while the log is asked for ({@link WristLink#debug}) the watch's sensor sample by sample
 * with the watch's own times ({@code S}) and the watch's own lines ({@code W}). Enough to play a session back through
 * the same turn finder (tools/wrist_replay.mjs) and see why a turn did or did not switch the track.
 *
 * <p>A file in the app's files (wrist-log.txt; the one before it, wrist-log.1.txt), written by a thread of its own so
 * the Bluetooth thread never waits; {@link #share} puts both in Documents/SemSearch and offers to send them.
 */
public final class WristLog {
    /** A file this big starts the next. */
    static final long MAX = 8L << 20;

    private static final class Line {
        final long at;
        final String tag, text;

        Line(long at, String tag, String text) {
            this.at = at;
            this.tag = tag;
            this.text = text;
        }
    }

    private static final LinkedBlockingQueue<Line> QUEUE = new LinkedBlockingQueue<Line>(20000);
    private static volatile File dir;
    private static Thread writer;
    private static volatile boolean idle = true;

    private WristLog() {
    }

    public static File file(Context c) {
        return new File(c.getFilesDir(), "wrist-log.txt");
    }

    static File before(Context c) {
        return new File(c.getFilesDir(), "wrist-log.1.txt");
    }

    /** One line: "2026-10-14 15:03:43.123 TAG text". */
    public static void add(Context c, String tag, String text) {
        if (c == null) return;
        if (dir == null) dir = c.getApplicationContext().getFilesDir();
        idle = false;
        if (!QUEUE.offer(new Line(System.currentTimeMillis(), tag, text == null ? "" : text.replace('\n', ' ')))) return;
        start();
    }

    private static synchronized void start() {
        if (writer != null) return;
        writer = new Thread(new Runnable() {
            @Override
            public void run() {
                SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);
                Date d = new Date();
                Writer out = null;
                File file = null;
                while (true) {
                    Line l;
                    try {
                        l = QUEUE.poll(out != null ? 300 : 60_000, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        if (l == null) {
                            // nothing for a moment: everything so far on disk
                            if (out != null) {
                                out.close();
                                out = null;
                            }
                            idle = QUEUE.isEmpty();
                            continue;
                        }
                        if (out == null) {
                            file = new File(dir, "wrist-log.txt");
                            if (file.length() > MAX) {
                                File old = new File(dir, "wrist-log.1.txt");
                                old.delete();
                                file.renameTo(old);
                            }
                            out = new OutputStreamWriter(new FileOutputStream(file, true), "UTF-8");
                        }
                        d.setTime(l.at);
                        out.write(f.format(d));
                        out.write(' ');
                        out.write(l.tag);
                        out.write(' ');
                        out.write(l.text);
                        out.write('\n');
                    } catch (IOException e) {
                        out = null; // a log only: lost lines, the next ones tried again
                    }
                }
            }
        }, "wrist-log");
        writer.setDaemon(true);
        writer.start();
    }

    /** Waits (at most a few seconds) until every line so far is in the file. */
    public static void flush() {
        long end = System.currentTimeMillis() + 5000;
        while ((!QUEUE.isEmpty() || !idle) && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** How big the log is now (both files), bytes. */
    public static long size(Context c) {
        return file(c).length() + before(c).length();
    }

    static void clear(Context c) {
        flush();
        file(c).delete();
        before(c).delete();
    }

    /**
     * Both files as one in Documents/SemSearch (wrist-log-2026-10-14-1503.txt) and the share sheet to send it; null —
     * could not (the reason in the toast).
     */
    public static Uri share(Activity a) {
        flush();
        String name = "wrist-log-" + new SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(new Date()) + ".txt";
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
            copy(before(a), out);
            copy(file(a), out);
            out.close();
            out = null;
        } catch (Exception e) {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // closed
                }
            }
            return null;
        }
        if ("content".equals(uri.getScheme())) {
            Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, "Журнал часов SemSearch " + name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                a.startActivity(Intent.createChooser(i, "Отправить журнал часов"));
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
