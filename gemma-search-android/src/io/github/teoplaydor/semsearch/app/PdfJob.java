package io.github.teoplaydor.semsearch.app;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.teoplaydor.semsearch.core.DocScan;
import io.github.teoplaydor.semsearch.core.PdfPages;

/**
 * Photos of documents made into one PDF, a page each, in the order they were chosen: each as «Скан для печати» makes it
 * by default (the document cut out and straightened, its text levelled, black and white as from a scanner), written as
 * it goes into Documents/SemSearch (the app's own Documents folder where the gallery refuses it). A sheet shows how far
 * it has got, then what came of it: open it, share it.
 */
final class PdfJob {
    private final MainActivity a;
    private final List<IndexStore.Item> photos;
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;
    private Sheet sheet;
    private TextView line;
    private ProgressLine bar;
    private LinearLayout actions;

    /** Done (successfully or not), and what came of it: the PDF's address, the file where the gallery refused it. */
    volatile boolean done;
    volatile Uri uri;
    volatile File file;
    volatile int pages;
    volatile String error;

    PdfJob(MainActivity a, List<IndexStore.Item> photos) {
        this.a = a;
        this.photos = photos;
    }

    Sheet start(ViewGroup root) {
        Context c = a;
        sheet = new Sheet(c, "Скан в PDF");
        line = Ui.text(c, "Готовлю " + pagesWord(photos.size()) + "…", 14, Ui.TEXT2, Ui.REGULAR);
        line.setLineSpacing(0, 1.3f);
        sheet.body().addView(line);
        bar = new ProgressLine(c);
        bar.setProgress(0);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, Ui.dp(c, 2));
        bl.topMargin = Ui.dp(c, 14);
        sheet.body().addView(bar, bl);
        actions = new LinearLayout(c);
        actions.setPadding(0, Ui.dp(c, 18), 0, 0);
        TextView cancel = Sheet.button(c, "Отмена", false);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sheet.dismiss();
            }
        });
        actions.addView(cancel, new LinearLayout.LayoutParams(-1, Ui.dp(c, 50)));
        sheet.body().addView(actions);
        sheet.setOnClosed(new Runnable() {
            @Override
            public void run() {
                // closed before it was done: stopped, nothing left behind
                if (!done) cancelled = true;
                work.shutdown();
            }
        });
        sheet.show(root);
        work.submit(new Runnable() {
            @Override
            public void run() {
                make();
            }
        });
        return sheet;
    }

    private void make() {
        String name = "scan_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date()) + ".pdf";
        Target t = null;
        int failed = 0;
        try {
            t = open(name);
            OutputStream o = new BufferedOutputStream(t.out, 1 << 16);
            PdfPages pdf = new PdfPages(o);
            int n = photos.size();
            for (int i = 0; i < n && !cancelled; i++) {
                progress(i, n, "Страница " + (i + 1) + " из " + n + ": ищу документ, выпрямляю…");
                try {
                    Bitmap b = a.loadForScan(photos.get(i), ScanPanel.SOURCE_PIXELS);
                    int w = b.getWidth(), h = b.getHeight();
                    int[] px = new int[w * h];
                    b.getPixels(px, 0, w, 0, 0, w, h);
                    b.recycle();
                    DocScan.Sheet s = DocScan.findSheet(px, w, h);
                    DocScan.Image page = DocScan.process(px, w, h, s, true, DocScan.BW, ScanPanel.MAX_SIDE);
                    px = null;
                    pdf.addBlackWhite(page.px, page.w, page.h);
                } catch (OutOfMemoryError | Exception e) {
                    // a photo gone or unreadable: the others still make the PDF
                    failed++;
                }
            }
            if (cancelled) {
                o.close();
                t.discard(a);
                return;
            }
            pdf.close();
            o.close();
            pages = pdf.pages();
            if (pages == 0) {
                t.discard(a);
                finish(null, "ни одно фото не открылось");
                return;
            }
            t.publish(a);
            uri = t.uri;
            file = t.file;
            long size = t.size(a);
            String where = t.file != null ? t.file.getPath() : "Documents/SemSearch/" + name;
            finish(String.format(Locale.ROOT, "Готово: %s, %s\n%s%s", pagesWord(pages), size >= 1 << 20
                    ? String.format(Locale.ROOT, "%.1f МБ", size / 1048576.0) : Math.max(1, size / 1024) + " КБ", where,
                    failed > 0 ? "\nНе открылось фото: " + failed : ""), null);
        } catch (Throwable e) {
            if (t != null) t.discard(a);
            finish(null, e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    private void progress(final int i, final int n, final String text) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                line.setText(text);
                bar.setProgress(i / (float) n);
            }
        });
    }

    private void finish(final String result, final String err) {
        error = err;
        ui.post(new Runnable() {
            @Override
            public void run() {
                done = true;
                Context c = a;
                bar.setProgress(1);
                bar.setVisibility(View.GONE);
                actions.removeAllViews();
                if (result == null) {
                    line.setText("Не получилось: " + err);
                    TextView close = Sheet.button(c, "Закрыть", false);
                    close.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            sheet.dismiss();
                        }
                    });
                    actions.addView(close, new LinearLayout.LayoutParams(-1, Ui.dp(c, 50)));
                    return;
                }
                line.setText(result);
                line.setTextColor(Ui.TEXT);
                TextView open = Sheet.button(c, "Открыть", false), share = Sheet.button(c, "Поделиться", true);
                open.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        send(Intent.ACTION_VIEW, "Открыть PDF");
                    }
                });
                share.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        send(Intent.ACTION_SEND, "Поделиться PDF");
                    }
                });
                LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, Ui.dp(c, 50), 1);
                LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, Ui.dp(c, 50), 1);
                l2.leftMargin = Ui.dp(c, 10);
                actions.addView(open, l1);
                actions.addView(share, l2);
            }
        });
    }

    private void send(String action, String title) {
        Intent i = new Intent(action);
        if (Intent.ACTION_VIEW.equals(action)) {
            i.setDataAndType(uri, "application/pdf");
        } else {
            i.setType("application/pdf");
            i.putExtra(Intent.EXTRA_STREAM, uri);
        }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            a.startActivity(Intent.createChooser(i, title));
        } catch (Exception e) {
            a.toast(Intent.ACTION_VIEW.equals(action) ? "Нет приложения для PDF" : "Некуда отправить");
        }
    }

    static String pagesWord(int n) {
        int m10 = n % 10, m100 = n % 100;
        String w = m10 == 1 && m100 != 11 ? "страница" : m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14) ? "страницы" : "страниц";
        return n + " " + w;
    }

    /** Where the PDF goes: the gallery's Documents (pending until written) or a file of the app's. */
    private static final class Target {
        OutputStream out;
        Uri uri;
        File file;

        void publish(Context c) {
            if (file != null) return;
            ContentValues v = new ContentValues();
            v.put("is_pending", 0);
            c.getContentResolver().update(uri, v, null, null);
        }

        void discard(Context c) {
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {
                // gone already
            }
            if (file != null) file.delete();
            else if (uri != null) {
                try {
                    c.getContentResolver().delete(uri, null, null);
                } catch (Exception ignored) {
                    // left pending: the system removes it
                }
            }
        }

        long size(Context c) {
            if (file != null) return file.length();
            try {
                android.content.res.AssetFileDescriptor d = c.getContentResolver().openAssetFileDescriptor(uri, "r");
                long n = d.getLength();
                d.close();
                return n;
            } catch (Exception e) {
                return 0;
            }
        }
    }

    private Target open(String name) throws Exception {
        Target t = new Target();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
            v.put("relative_path", Environment.DIRECTORY_DOCUMENTS + "/SemSearch"); // API 29 columns: the build compiles against 23
            v.put("is_pending", 1);
            try {
                t.uri = a.getContentResolver().insert(MediaStore.Files.getContentUri("external_primary"), v);
                if (t.uri != null) t.out = a.getContentResolver().openOutputStream(t.uri);
            } catch (Exception ignored) {
                // no shared Documents to write to: the app's folder below
                t.uri = null;
            }
            if (t.out != null) return t;
        }
        File dir = new File(a.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "SemSearch");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("нет папки " + dir);
        t.file = new File(dir, name);
        t.out = new FileOutputStream(t.file);
        t.uri = Uri.fromFile(t.file);
        return t;
    }
}
