package io.github.teoplaydor.semsearch.app;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Thin line icons drawn in a 24×24 box (no icon font or vector assets needed). */
final class Icon extends Drawable {
    static final int SEARCH = 0, CLOSE = 1, BACK = 2, TUNE = 3, IMAGE = 4, SHARE = 5, OPEN = 6, SIMILAR = 7, PLAY = 8,
            PLUS = 9, NOTE = 10, TRASH = 11, CHECK = 12, CHEVRON = 13, STOP = 14, DOWNLOAD = 15, VIDEO = 16, INFO = 17,
            GRID = 18, TAG = 19, ALBUMS = 20, HIDE = 21, SHOW = 22, LOCK = 23, PERSON = 24;

    private final int kind;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float strokePx;
    private final Path path = new Path();
    private final RectF r = new RectF();

    Icon(int kind, int color, float strokePx) {
        this.kind = kind;
        this.strokePx = strokePx;
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    void setColor(int color) {
        p.setColor(color);
        invalidateSelf();
    }

    @Override
    public int getIntrinsicWidth() {
        return Math.round(strokePx * 14);
    }

    @Override
    public int getIntrinsicHeight() {
        return Math.round(strokePx * 14);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height()) / 24f;
        if (s <= 0) return;
        c.save();
        c.translate(b.exactCenterX() - 12 * s, b.exactCenterY() - 12 * s);
        c.scale(s, s);
        p.setStrokeWidth(strokePx / s);
        p.setStyle(Paint.Style.STROKE);
        path.reset();
        switch (kind) {
            case SHOW: // an eye
            case HIDE: // an eye, struck through
                path.moveTo(2.5f, 12);
                path.quadTo(12, 2.5f, 21.5f, 12);
                path.quadTo(12, 21.5f, 2.5f, 12);
                path.close();
                c.drawPath(path, p);
                c.drawCircle(12, 12, 3, p);
                if (kind == HIDE) line(c, 4.5f, 4.5f, 19.5f, 19.5f);
                break;
            case PERSON: // a head and shoulders: who is on the photo
                c.drawCircle(12, 8.5f, 3.8f, p);
                path.moveTo(4.5f, 20);
                path.quadTo(4.5f, 14, 12, 14);
                path.quadTo(19.5f, 14, 19.5f, 20);
                c.drawPath(path, p);
                break;
            case LOCK: // a padlock: the hidden folder
                r.set(5.5f, 11, 18.5f, 20);
                c.drawRoundRect(r, 2.5f, 2.5f, p);
                path.moveTo(8.5f, 11);
                path.lineTo(8.5f, 8);
                path.quadTo(8.5f, 4.5f, 12, 4.5f);
                path.quadTo(15.5f, 4.5f, 15.5f, 8);
                path.lineTo(15.5f, 11);
                c.drawPath(path, p);
                line(c, 12, 14.5f, 12, 16.5f);
                break;
            case ALBUMS: // two pictures stacked: albums
                r.set(7.5f, 4, 20, 16.5f);
                c.drawRoundRect(r, 2.5f, 2.5f, p);
                path.moveTo(4, 9);
                path.lineTo(4, 18.5f);
                path.quadTo(4, 20, 5.5f, 20);
                path.lineTo(15, 20);
                c.drawPath(path, p);
                path.reset();
                path.moveTo(10, 14);
                path.lineTo(13, 10.5f);
                path.lineTo(15.5f, 13);
                path.lineTo(17, 11.5f);
                path.lineTo(18.5f, 13.5f);
                c.drawPath(path, p);
                break;
            case TAG: // a price tag: what the picture is labelled with
                path.moveTo(4, 5.5f);
                path.lineTo(4, 11);
                path.lineTo(13, 20);
                path.lineTo(20, 13);
                path.lineTo(11, 4);
                path.lineTo(5.5f, 4);
                path.quadTo(4, 4, 4, 5.5f);
                path.close();
                c.drawPath(path, p);
                c.drawCircle(8.2f, 8.2f, 1.3f, p);
                break;
            case GRID: // a staggered gallery: tall and short tiles
                r.set(4.5f, 4.5f, 11, 13.5f);
                c.drawRoundRect(r, 2, 2, p);
                r.set(4.5f, 16.5f, 11, 19.5f);
                c.drawRoundRect(r, 1.5f, 1.5f, p);
                r.set(13, 4.5f, 19.5f, 8.5f);
                c.drawRoundRect(r, 1.5f, 1.5f, p);
                r.set(13, 11.5f, 19.5f, 19.5f);
                c.drawRoundRect(r, 2, 2, p);
                break;
            case SEARCH:
                c.drawCircle(11, 11, 6.5f, p);
                line(c, 16, 16, 20, 20);
                break;
            case CLOSE:
                line(c, 6.5f, 6.5f, 17.5f, 17.5f);
                line(c, 17.5f, 6.5f, 6.5f, 17.5f);
                break;
            case BACK:
                poly(c, 14.5f, 5.5f, 8, 12, 14.5f, 18.5f);
                break;
            case CHEVRON:
                poly(c, 10, 6, 16, 12, 10, 18);
                break;
            case TUNE:
                line(c, 4, 8, 7, 8);
                c.drawCircle(9.5f, 8, 2.4f, p);
                line(c, 12, 8, 20, 8);
                line(c, 4, 16, 12, 16);
                c.drawCircle(14.5f, 16, 2.4f, p);
                line(c, 17, 16, 20, 16);
                break;
            case IMAGE:
                r.set(4, 5, 20, 19);
                c.drawRoundRect(r, 3.2f, 3.2f, p);
                c.drawCircle(9, 9.8f, 1.5f, p);
                poly(c, 4.5f, 16.5f, 9.5f, 12.5f, 13, 15.5f, 15.5f, 13.5f, 19.5f, 16.8f);
                break;
            case VIDEO:
                r.set(3.5f, 6, 15.5f, 18);
                c.drawRoundRect(r, 3, 3, p);
                poly(c, 15.5f, 10.5f, 20.5f, 7.5f, 20.5f, 16.5f, 15.5f, 13.5f);
                break;
            case SHARE:
                c.drawCircle(17, 6, 2.3f, p);
                c.drawCircle(7, 12, 2.3f, p);
                c.drawCircle(17, 18, 2.3f, p);
                line(c, 9, 11, 15, 7.2f);
                line(c, 9, 13, 15, 16.8f);
                break;
            case OPEN:
                poly(c, 18, 13.5f, 18, 18.5f, 5.5f, 18.5f, 5.5f, 6, 10.5f, 6);
                poly(c, 13.5f, 5, 19, 5, 19, 10.5f);
                line(c, 19, 5, 11.5f, 12.5f);
                break;
            case SIMILAR:
                path.moveTo(11, 4);
                path.cubicTo(11, 8.5f, 13.5f, 11, 18, 11);
                path.cubicTo(13.5f, 11, 11, 13.5f, 11, 18);
                path.cubicTo(11, 13.5f, 8.5f, 11, 4, 11);
                path.cubicTo(8.5f, 11, 11, 8.5f, 11, 4);
                path.close();
                c.drawPath(path, p);
                path.reset();
                path.moveTo(18, 15.5f);
                path.cubicTo(18, 17.5f, 18.5f, 18, 20.5f, 18);
                path.cubicTo(18.5f, 18, 18, 18.5f, 18, 20.5f);
                path.cubicTo(18, 18.5f, 17.5f, 18, 15.5f, 18);
                path.cubicTo(17.5f, 18, 18, 17.5f, 18, 15.5f);
                path.close();
                c.drawPath(path, p);
                break;
            case PLAY:
                p.setStyle(Paint.Style.FILL_AND_STROKE);
                path.moveTo(9, 6.8f);
                path.lineTo(17.5f, 12);
                path.lineTo(9, 17.2f);
                path.close();
                c.drawPath(path, p);
                break;
            case STOP:
                p.setStyle(Paint.Style.FILL_AND_STROKE);
                r.set(7.5f, 7.5f, 16.5f, 16.5f);
                c.drawRoundRect(r, 1.5f, 1.5f, p);
                break;
            case PLUS:
                line(c, 12, 5.5f, 12, 18.5f);
                line(c, 5.5f, 12, 18.5f, 12);
                break;
            case NOTE:
                r.set(5.5f, 4, 18.5f, 20);
                c.drawRoundRect(r, 2.6f, 2.6f, p);
                line(c, 9, 9, 15, 9);
                line(c, 9, 12.5f, 15, 12.5f);
                line(c, 9, 16, 12.5f, 16);
                break;
            case TRASH:
                line(c, 5, 7, 19, 7);
                poly(c, 9.5f, 7, 10, 4.5f, 14, 4.5f, 14.5f, 7);
                poly(c, 7, 7, 8, 19.5f, 16, 19.5f, 17, 7);
                break;
            case CHECK:
                poly(c, 5.5f, 12.5f, 10, 17, 18.5f, 7.5f);
                break;
            case DOWNLOAD:
                line(c, 12, 4.5f, 12, 15);
                poly(c, 7.5f, 10.5f, 12, 15, 16.5f, 10.5f);
                line(c, 5.5f, 19, 18.5f, 19);
                break;
            case INFO:
                c.drawCircle(12, 12, 8, p);
                line(c, 12, 11, 12, 16);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(12, 8, 0.9f, p);
                break;
            default:
                break;
        }
        c.restore();
    }

    private void line(Canvas c, float x0, float y0, float x1, float y1) {
        c.drawLine(x0, y0, x1, y1, p);
    }

    private void poly(Canvas c, float... xy) {
        path.reset();
        path.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
        c.drawPath(path, p);
    }

    @Override
    public void setAlpha(int alpha) {
        p.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        p.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
