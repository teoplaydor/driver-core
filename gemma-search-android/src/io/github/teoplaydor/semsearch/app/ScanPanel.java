package io.github.teoplaydor.semsearch.app;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.print.pdf.PrintedPdfDocument;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.teoplaydor.semsearch.core.DocScan;

/**
 * A photo of a document made ready to print (core.DocScan): the sheet cut out and straightened, the text levelled,
 * strict black and white like a scanner's (or grey, or colour on white paper) — each switchable; then printed (an A4
 * PDF page through Android's printing), saved to Pictures/SemSearch, or shared. The original stays as it is.
 */
final class ScanPanel extends FrameLayout {
    /** The longer side of the result: A4 at 300 dpi. */
    static final int MAX_SIDE = 3508;
    /**
     * At least this many pixels of the photo are read (all of a 12 MP photo; a 50 MP one halved): 0.10.17 read a quarter
     * of a 50 MP photo, and the sheet came out at ~115 dpi.
     */
    static final long SOURCE_PIXELS = 12_000_000L;

    private final MainActivity a;
    private final IndexStore.Item item;
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ImageView picture;
    private final TextView status, pageChip, levelChip, edgesChip;
    private final View controls;
    private final LinearLayout editBar;
    private final CornerView editor;
    /** The corners were set by hand. */
    private boolean manual;
    private double angle;
    private final TextView[] modeChips = new TextView[3];
    private boolean closing;

    // the photo and the stages made from it (each kept while what it depends on is unchanged)
    private int[] src;
    private int sw, sh;
    private float[] page;
    private boolean usePage = true, level = true;
    private int mode = DocScan.BW;
    private DocScan.Image cut, levelled;
    private boolean cutWithPage, levelledOn;
    /** The result shown, and the settings it was made with. */
    volatile Bitmap result;
    private volatile boolean busy;
    private Uri saved;
    private String savedFor;

    ScanPanel(MainActivity activity, IndexStore.Item it) {
        super(activity);
        a = activity;
        item = it;
        Context c = activity;
        setBackgroundColor(Ui.BG);
        setClickable(true);
        setTranslationZ(Ui.dp(c, 20)); // over the viewer it is opened from

        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        addView(column, new LayoutParams(-1, -1));

        LinearLayout bar = new LinearLayout(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 16), Ui.dp(c, 6));
        ImageView back = Ui.icon(c, Icon.BACK, Ui.TEXT, 44);
        back.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                close();
            }
        });
        bar.addView(back, new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)));
        TextView title = Ui.text(c, "Скан для печати", 20, Ui.TEXT, Ui.SEMIBOLD);
        title.setPadding(Ui.dp(c, 6), 0, 0, 0);
        bar.addView(title);
        column.addView(bar);

        FrameLayout frame = new FrameLayout(c);
        frame.setPadding(Ui.dp(c, 16), Ui.dp(c, 4), Ui.dp(c, 16), Ui.dp(c, 4));
        picture = new ImageView(c);
        picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
        frame.addView(picture, new LayoutParams(-1, -1));
        editor = new CornerView(c);
        editor.setVisibility(GONE);
        frame.addView(editor, new LayoutParams(-1, -1));
        column.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));

        status = Ui.text(c, "Ищу лист на фото…", 13, Ui.TEXT2, Ui.REGULAR);
        status.setGravity(Gravity.CENTER);
        status.setPadding(Ui.dp(c, 16), Ui.dp(c, 8), Ui.dp(c, 16), Ui.dp(c, 4));
        column.addView(status);

        LinearLayout toggles = new LinearLayout(c);
        toggles.setGravity(Gravity.CENTER);
        toggles.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), 0);
        pageChip = chip("Лист", new Runnable() {
            @Override
            public void run() {
                if (page == null) return;
                usePage = !usePage;
                redo();
            }
        });
        levelChip = chip("Текст ровно", new Runnable() {
            @Override
            public void run() {
                level = !level;
                redo();
            }
        });
        edgesChip = chip("Края…", new Runnable() {
            @Override
            public void run() {
                editEdges();
            }
        });
        toggles.addView(pageChip);
        toggles.addView(edgesChip);
        toggles.addView(levelChip);
        LinearLayout controlsBox = new LinearLayout(c);
        controlsBox.setOrientation(LinearLayout.VERTICAL);
        controlsBox.addView(toggles);
        controls = controlsBox;

        LinearLayout modes = new LinearLayout(c);
        modes.setGravity(Gravity.CENTER);
        modes.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), 0);
        String[] names = {"Ч/б", "Серый", "Цвет"};
        for (int i = 0; i < 3; i++) {
            final int m = i;
            modeChips[i] = chip(names[i], new Runnable() {
                @Override
                public void run() {
                    mode = m;
                    redo();
                }
            });
            modes.addView(modeChips[i]);
        }
        controlsBox.addView(modes);

        LinearLayout buttons = new LinearLayout(c);
        buttons.setPadding(Ui.dp(c, 16), Ui.dp(c, 8), Ui.dp(c, 16), Ui.dp(c, 20));
        TextView print = Sheet.button(c, "Печать", true), save = Sheet.button(c, "Сохранить", false), share = Sheet.button(c, "Поделиться", false);
        print.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                print();
            }
        });
        save.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                save(false);
            }
        });
        share.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                save(true);
            }
        });
        // printing first, on its own line; saving and sharing under it (three in a row do not fit their words)
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 50));
        lp.setMargins(Ui.dp(c, 16), Ui.dp(c, 14), Ui.dp(c, 16), 0);
        controlsBox.addView(print, lp);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, Ui.dp(c, 50), 1);
        LinearLayout.LayoutParams l3 = new LinearLayout.LayoutParams(0, Ui.dp(c, 50), 1);
        l3.leftMargin = Ui.dp(c, 8);
        buttons.addView(save, l2);
        buttons.addView(share, l3);
        controlsBox.addView(buttons);
        column.addView(controlsBox);
        // the corners by hand: «Готово» / «Отмена» in place of the rest
        editBar = new LinearLayout(c);
        editBar.setPadding(Ui.dp(c, 16), Ui.dp(c, 14), Ui.dp(c, 16), Ui.dp(c, 20));
        editBar.setVisibility(GONE);
        TextView cancel = Sheet.button(c, "Отмена", false), done = Sheet.button(c, "Готово", true);
        cancel.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                endEdit(false);
            }
        });
        done.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                endEdit(true);
            }
        });
        LinearLayout.LayoutParams e1 = new LinearLayout.LayoutParams(0, Ui.dp(c, 50), 1);
        LinearLayout.LayoutParams e2 = new LinearLayout.LayoutParams(0, Ui.dp(c, 50), 1);
        e2.leftMargin = Ui.dp(c, 8);
        editBar.addView(cancel, e1);
        editBar.addView(done, e2);
        column.addView(editBar);
        updateChips();
    }

    private TextView chip(String label, final Runnable r) {
        Context c = getContext();
        TextView t = Ui.text(c, label, 13.5f, Ui.TEXT, Ui.MEDIUM);
        t.setPadding(Ui.dp(c, 14), Ui.dp(c, 8), Ui.dp(c, 14), Ui.dp(c, 8));
        t.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (busy) return;
                r.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = Ui.dp(c, 4);
        lp.rightMargin = Ui.dp(c, 4);
        t.setLayoutParams(lp);
        Ui.pressable(t);
        return t;
    }

    private void updateChips() {
        Context c = getContext();
        on(pageChip, page != null && usePage, page != null);
        on(levelChip, level, true);
        for (int i = 0; i < 3; i++) on(modeChips[i], mode == i, true);
        pageChip.setText(page == null && src != null ? "Лист не найден" : "Лист");
        on(edgesChip, manual, src != null);
    }

    private void on(TextView t, boolean on, boolean enabled) {
        t.setBackground(Ui.round(getContext(), on ? Ui.ACCENT_SOFT : Ui.SURFACE2, 18));
        t.setTextColor(!enabled ? Ui.TEXT3 : on ? Ui.TEXT : Ui.TEXT2);
    }

    void open(ViewGroup parent) {
        parent.addView(this, new ViewGroup.LayoutParams(-1, -1));
        setAlpha(0f);
        animate().alpha(1f).setDuration(220).start();
        busy = true;
        work.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    Bitmap b = a.loadForScan(item, SOURCE_PIXELS);
                    sw = b.getWidth();
                    sh = b.getHeight();
                    int[] px = new int[sw * sh];
                    b.getPixels(px, 0, sw, 0, 0, sw, sh);
                    b.recycle();
                    src = px;
                    page = DocScan.findPage(src, sw, sh);
                    usePage = page != null;
                    make();
                } catch (final Throwable e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            status.setText("Не получилось открыть фото: " + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    /** The settings changed: the result again (on the worker; the stages that stay are reused). */
    private void redo() {
        if (src == null) return;
        busy = true;
        updateChips();
        status.setText("Делаю…");
        work.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    make();
                } catch (final Throwable e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            status.setText("Не получилось: " + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private void make() {
        long t0 = System.currentTimeMillis();
        boolean withPage = usePage && page != null;
        if (cut == null || cutWithPage != withPage) {
            cut = withPage ? DocScan.warp(src, sw, sh, page, MAX_SIDE) : DocScan.fit(src, sw, sh, MAX_SIDE);
            cutWithPage = withPage;
            levelled = null;
        }
        if (levelled == null || levelledOn != level) {
            if (level) {
                angle = DocScan.skew(cut.px, cut.w, cut.h);
                levelled = DocScan.rotate(cut, angle);
            } else {
                angle = 0;
                levelled = cut;
            }
            levelledOn = level;
        }
        int[] out = DocScan.scan(levelled.px, levelled.w, levelled.h, mode);
        final Bitmap bmp = Bitmap.createBitmap(out, levelled.w, levelled.h, Bitmap.Config.ARGB_8888);
        final String note = (withPage ? (manual ? "Края заданы вручную" : "Лист вырезан и выпрямлен")
                : page == null ? "Лист не найден — всё фото, «Края…» — задать углы" : "Всё фото")
                + (level ? (angle != 0 ? String.format(Locale.ROOT, ", текст повёрнут на %.1f°", -angle) : ", текст ровный") : "")
                + " · " + levelled.w + "×" + levelled.h + " · " + (System.currentTimeMillis() - t0) + " мс";
        ui.post(new Runnable() {
            @Override
            public void run() {
                Bitmap old = result;
                result = bmp;
                picture.setImageBitmap(bmp);
                if (old != null && old != bmp) old.recycle();
                busy = false;
                status.setText(note);
                updateChips();
            }
        });
    }

    /** What the result was made with: a saved file is used again only for the same. */
    private String settings() {
        return usePage + ":" + level + ":" + mode;
    }

    /** Saves to Pictures/SemSearch (PNG for black and white and grey, JPEG for colour); then shares it when asked. */
    private void save(final boolean thenShare) {
        final Bitmap b = result;
        if (b == null || busy) return;
        if (saved != null && settings().equals(savedFor)) {
            if (thenShare) share(saved);
            else a.toast("Уже сохранено в Pictures/SemSearch");
            return;
        }
        final String what = settings();
        final boolean png = mode != DocScan.COLOR;
        busy = true;
        status.setText("Сохраняю…");
        work.submit(new Runnable() {
            @Override
            public void run() {
                Uri uri = null;
                String err = null;
                String name = "scan_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date()) + (png ? ".png" : ".jpg");
                try {
                    uri = write(name, b, png);
                } catch (Throwable e) {
                    err = e.getMessage();
                }
                final Uri u = uri;
                final String error = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        if (u == null) {
                            status.setText("Не сохранилось: " + error);
                            return;
                        }
                        saved = u;
                        savedFor = what;
                        status.setText("Сохранено: Pictures/SemSearch");
                        if (thenShare) share(u);
                        else a.toast("Сохранено в Pictures/SemSearch");
                    }
                });
            }
        });
    }

    /** Into the gallery (MediaStore, Pictures/SemSearch); where that is refused, the app's own Pictures folder. */
    private Uri write(String name, Bitmap b, boolean png) throws Exception {
        Context c = getContext();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, png ? "image/png" : "image/jpeg");
            v.put("relative_path", Environment.DIRECTORY_PICTURES + "/SemSearch"); // API 29 columns: the build compiles against 23
            v.put("is_pending", 1);
            Uri uri = null;
            try {
                uri = c.getContentResolver().insert(MediaStore.Images.Media.getContentUri("external_primary"), v);
            } catch (Exception ignored) {
                // no gallery to write to: the app's folder below
            }
            if (uri != null) {
                OutputStream o = c.getContentResolver().openOutputStream(uri);
                try {
                    b.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 92, o);
                } finally {
                    if (o != null) o.close();
                }
                ContentValues done = new ContentValues();
                done.put("is_pending", 0);
                c.getContentResolver().update(uri, done, null, null);
                return uri;
            }
        }
        File dir = new File(c.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "SemSearch");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("нет папки " + dir);
        File f = new File(dir, name);
        FileOutputStream o = new FileOutputStream(f);
        try {
            b.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 92, o);
        } finally {
            o.close();
        }
        savedFile = f;
        return Uri.fromFile(f);
    }

    /** The file written where the gallery refused it (tests look at it). */
    File savedFile;

    private void share(Uri uri) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(mode == DocScan.COLOR ? "image/jpeg" : "image/png");
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            a.startActivity(Intent.createChooser(i, "Поделиться сканом"));
        } catch (Exception e) {
            a.toast("Некуда отправить");
        }
    }

    /** Android's printing: one page, the scan fitted into the paper's printable area. */
    private void print() {
        final Bitmap b = result;
        if (b == null || busy) return;
        PrintManager pm = (PrintManager) getContext().getSystemService(Context.PRINT_SERVICE);
        if (pm == null) {
            a.toast("Печать недоступна на этом телефоне");
            return;
        }
        final Context c = getContext();
        PrintAttributes attrs = new PrintAttributes.Builder()
                .setMediaSize(b.getWidth() > b.getHeight() ? PrintAttributes.MediaSize.ISO_A4.asLandscape() : PrintAttributes.MediaSize.ISO_A4)
                .setColorMode(mode == DocScan.COLOR ? PrintAttributes.COLOR_MODE_COLOR : PrintAttributes.COLOR_MODE_MONOCHROME)
                .build();
        pm.print("SemSearch — скан", new PrintDocumentAdapter() {
            private PrintAttributes current;

            @Override
            public void onLayout(PrintAttributes oldA, PrintAttributes newA, CancellationSignal cancel, LayoutResultCallback cb, Bundle extras) {
                current = newA;
                if (cancel.isCanceled()) {
                    cb.onLayoutCancelled();
                    return;
                }
                cb.onLayoutFinished(new PrintDocumentInfo.Builder("scan.pdf").setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(1).build(), !newA.equals(oldA));
            }

            @Override
            public void onWrite(PageRange[] pages, ParcelFileDescriptor dest, CancellationSignal cancel, WriteResultCallback cb) {
                PrintedPdfDocument pdf = new PrintedPdfDocument(c, current);
                try {
                    PdfDocument.Page p = pdf.startPage(0);
                    Canvas canvas = p.getCanvas();
                    RectF area = new RectF(p.getInfo().getContentRect());
                    float k = Math.min(area.width() / b.getWidth(), area.height() / b.getHeight());
                    float w = b.getWidth() * k, h = b.getHeight() * k;
                    RectF dst = new RectF(area.centerX() - w / 2, area.centerY() - h / 2, area.centerX() + w / 2, area.centerY() + h / 2);
                    canvas.drawColor(Color.WHITE);
                    canvas.drawBitmap(b, null, dst, new Paint(Paint.FILTER_BITMAP_FLAG));
                    pdf.finishPage(p);
                    FileOutputStream o = new FileOutputStream(dest.getFileDescriptor());
                    try {
                        pdf.writeTo(o);
                    } finally {
                        o.close();
                    }
                    cb.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
                } catch (Exception e) {
                    cb.onWriteFailed(e.getMessage());
                } finally {
                    pdf.close();
                }
            }
        }, attrs);
    }

    /** «Назад» inside the panel: leaves the corner editing first. True when that is what it did. */
    boolean back() {
        if (editor.getVisibility() != VISIBLE) return false;
        endEdit(false);
        return true;
    }

    /** The corners by hand, on the photo: those found (or a frame a little inside the photo) to drag. */
    private void editEdges() {
        if (src == null) return;
        int max = 1600;
        double k = Math.min(1.0, (double) max / Math.max(sw, sh));
        int dw = Math.max(1, (int) Math.round(sw * k)), dh = Math.max(1, (int) Math.round(sh * k));
        int[] small = k < 1 ? io.github.teoplaydor.semsearch.core.FaceModel.resize(src, sw, sh, dw, dh) : src;
        float[] q = page != null ? page.clone() : new float[]{0.06f * sw, 0.06f * sh, 0.94f * sw, 0.06f * sh, 0.94f * sw, 0.94f * sh,
                0.06f * sw, 0.94f * sh};
        editor.set(Bitmap.createBitmap(small, dw, dh, Bitmap.Config.ARGB_8888), (float) (dw / (double) sw), q);
        picture.setVisibility(GONE);
        editor.setVisibility(VISIBLE);
        controls.setVisibility(GONE);
        editBar.setVisibility(VISIBLE);
        status.setText("Перетащите углы на углы листа");
    }

    private void endEdit(boolean apply) {
        editor.setVisibility(GONE);
        picture.setVisibility(VISIBLE);
        controls.setVisibility(VISIBLE);
        editBar.setVisibility(GONE);
        if (apply) {
            page = editor.corners();
            manual = true;
            usePage = true;
            cut = null;
            redo();
        } else {
            updateChips();
            status.setText("");
        }
    }

    /**
     * The photo with the sheet's four corners to drag (the nearest handle to the finger), a magnifier above the finger
     * while dragging.
     */
    static final class CornerView extends View {
        private Bitmap shown;
        /** Shown pixels per photo pixel. */
        private float scale;
        private float[] q;
        private final RectF dest = new RectF();
        private int active = -1;
        private float fx, fy;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), handle = new Paint(Paint.ANTI_ALIAS_FLAG),
                ring = new Paint(Paint.ANTI_ALIAS_FLAG), img = new Paint(Paint.FILTER_BITMAP_FLAG);

        CornerView(Context c) {
            super(c);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(Ui.dp(c, 2.5f));
            line.setColor(Ui.ACCENT);
            handle.setColor(0x663D8BFF);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(Ui.dp(c, 2));
            ring.setColor(Color.WHITE);
        }

        void set(Bitmap b, float scale, float[] corners) {
            shown = b;
            this.scale = scale;
            q = corners;
            invalidate();
        }

        float[] corners() {
            return q.clone();
        }

        /** Test hook: a corner moved to (x, y) of the photo. */
        void setCorner(int i, float x, float y) {
            q[2 * i] = x;
            q[2 * i + 1] = y;
            invalidate();
        }

        private void layoutDest() {
            float k = Math.min(getWidth() / (float) shown.getWidth(), getHeight() / (float) shown.getHeight());
            float w = shown.getWidth() * k, h = shown.getHeight() * k;
            dest.set((getWidth() - w) / 2, (getHeight() - h) / 2, (getWidth() + w) / 2, (getHeight() + h) / 2);
        }

        /** Photo pixels to the screen: × scale (to the shown bitmap) × the fit. */
        private float k() {
            return dest.width() / shown.getWidth() * scale;
        }

        @Override
        protected void onDraw(Canvas c) {
            if (shown == null) return;
            layoutDest();
            c.drawBitmap(shown, null, dest, img);
            float k = k();
            android.graphics.Path p = new android.graphics.Path();
            for (int i = 0; i < 4; i++) {
                float x = dest.left + q[2 * i] * k, y = dest.top + q[2 * i + 1] * k;
                if (i == 0) p.moveTo(x, y);
                else p.lineTo(x, y);
            }
            p.close();
            c.drawPath(p, line);
            float r = Ui.dp(getContext(), 14);
            for (int i = 0; i < 4; i++) {
                float x = dest.left + q[2 * i] * k, y = dest.top + q[2 * i + 1] * k;
                c.drawCircle(x, y, r, handle);
                c.drawCircle(x, y, r, ring);
            }
            if (active >= 0) {
                // the magnifier: 3× around the corner, away from the finger
                float mr = Ui.dp(getContext(), 56);
                float cx = fx < getWidth() / 2f ? getWidth() - mr - Ui.dp(getContext(), 12) : mr + Ui.dp(getContext(), 12);
                float cy = mr + Ui.dp(getContext(), 12);
                float hx = dest.left + q[2 * active] * k, hy = dest.top + q[2 * active + 1] * k;
                c.save();
                android.graphics.Path clip = new android.graphics.Path();
                clip.addCircle(cx, cy, mr, android.graphics.Path.Direction.CW);
                c.clipPath(clip);
                c.drawColor(Color.BLACK);
                c.translate(cx, cy);
                c.scale(3, 3);
                c.translate(-hx, -hy);
                c.drawBitmap(shown, null, dest, img);
                c.drawPath(p, line);
                c.restore();
                c.drawCircle(cx, cy, mr, ring);
                c.drawLine(cx - 10, cy, cx + 10, cy, ring);
                c.drawLine(cx, cy - 10, cx, cy + 10, ring);
            }
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent e) {
            if (shown == null) return false;
            layoutDest();
            float k = k();
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN: {
                    float best = Ui.dp(getContext(), 48);
                    active = -1;
                    for (int i = 0; i < 4; i++) {
                        float d = (float) Math.hypot(e.getX() - (dest.left + q[2 * i] * k), e.getY() - (dest.top + q[2 * i + 1] * k));
                        if (d < best) {
                            best = d;
                            active = i;
                        }
                    }
                    fx = e.getX();
                    fy = e.getY();
                    invalidate();
                    return active >= 0;
                }
                case android.view.MotionEvent.ACTION_MOVE:
                    if (active < 0) return false;
                    fx = e.getX();
                    fy = e.getY();
                    q[2 * active] = Math.max(0, Math.min(shown.getWidth() / scale, (fx - dest.left) / k));
                    q[2 * active + 1] = Math.max(0, Math.min(shown.getHeight() / scale, (fy - dest.top) / k));
                    invalidate();
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    active = -1;
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }
    }

    boolean isClosing() {
        return closing;
    }

    void close() {
        if (closing) return;
        closing = true;
        work.shutdownNow();
        animate().alpha(0f).setDuration(180).withEndAction(new Runnable() {
            @Override
            public void run() {
                ViewGroup p = (ViewGroup) getParent();
                if (p != null) p.removeView(ScanPanel.this);
                a.scanClosed();
            }
        }).start();
    }
}
