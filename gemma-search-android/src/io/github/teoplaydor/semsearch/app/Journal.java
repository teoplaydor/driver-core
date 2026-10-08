package io.github.teoplaydor.semsearch.app;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * What the accelerator check and the NPU process did, line by line with the time: a file in the app's cache that
 * both processes append to (single writes in append mode), so it survives a crash of the NPU process — the check's
 * report ends with it. Cleared when a check starts; it stops growing at 2 MB.
 */
final class Journal {
    private static final long MAX = 2L << 20;

    private Journal() {
    }

    static File file(Context c) {
        return new File(c.getCacheDir(), "journal.txt");
    }

    static void clear(Context c) {
        file(c).delete();
    }

    /** One line: "14:02:11.123 app  text" (a line break in the text indents the rest). */
    static void add(Context c, String who, String text) {
        if (c == null || text == null) return;
        File f = file(c);
        if (f.length() > MAX) return;
        String t = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(new Date());
        String line = t + " " + who + "  " + text.trim().replace("\n", "\n                   ") + "\n";
        try {
            FileOutputStream out = new FileOutputStream(f, true);
            try {
                out.write(line.getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (IOException ignored) {
            // a log only
        }
    }

    /** The last {@code maxBytes} of the journal (from a line start). */
    static String tail(Context c, int maxBytes) {
        File f = file(c);
        if (!f.exists()) return "";
        try {
            RandomAccessFile r = new RandomAccessFile(f, "r");
            try {
                long len = r.length(), from = Math.max(0, len - maxBytes);
                byte[] b = new byte[(int) (len - from)];
                r.seek(from);
                r.readFully(b);
                String s = new String(b, "UTF-8");
                if (from > 0) {
                    int nl = s.indexOf('\n');
                    s = "… (начало журнала не поместилось)\n" + (nl >= 0 ? s.substring(nl + 1) : s);
                }
                return s;
            } finally {
                r.close();
            }
        } catch (IOException e) {
            return "";
        }
    }
}
