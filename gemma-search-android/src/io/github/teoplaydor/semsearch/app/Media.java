package io.github.teoplaydor.semsearch.app;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.teoplaydor.semsearch.core.ImagePreprocessor;

/** MediaStore queries, bitmap decoding for the vision encoder, video frames and thumbnails. */
final class Media {
    private Media() {}

    static final class Entry {
        int kind;
        long id;
        Uri uri;
        long date;
        String name;
        int orientation;
        /** A screenshot or a scanned document: small text matters, worth the higher detail. */
        boolean textHeavy;
    }

    /** Screenshots and scans by their folder or file name (Samsung, Xiaomi, Pixel, CamScanner, …). */
    static boolean textHeavy(String bucket, String name) {
        String b = bucket == null ? "" : bucket.toLowerCase(java.util.Locale.ROOT);
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        return b.contains("screenshot") || b.contains("скриншот") || b.contains("снимки экрана") || b.contains("scan")
                || b.contains("скан") || b.contains("document") || b.contains("документ")
                || n.startsWith("screenshot") || n.startsWith("скриншот") || n.startsWith("scr_");
    }

    static List<Entry> recentImages(ContentResolver cr, int limit) {
        List<Entry> out = new ArrayList<Entry>();
        Uri base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] proj = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.ORIENTATION, "bucket_display_name"};
        Cursor c = cr.query(base, proj, null, null, MediaStore.Images.Media.DATE_ADDED + " DESC");
        if (c == null) return out;
        try {
            while (c.moveToNext() && out.size() < limit) {
                Entry e = new Entry();
                e.kind = IndexStore.KIND_PHOTO;
                e.id = c.getLong(0);
                e.uri = ContentUris.withAppendedId(base, e.id);
                e.date = c.getLong(1) * 1000L;
                e.name = c.getString(2);
                e.orientation = c.isNull(3) ? 0 : c.getInt(3);
                e.textHeavy = textHeavy(c.isNull(4) ? null : c.getString(4), e.name);
                out.add(e);
            }
        } finally {
            c.close();
        }
        return out;
    }

    static List<Entry> recentVideos(ContentResolver cr, int limit) {
        List<Entry> out = new ArrayList<Entry>();
        Uri base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] proj = {MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.DISPLAY_NAME};
        Cursor c = cr.query(base, proj, null, null, MediaStore.Video.Media.DATE_ADDED + " DESC");
        if (c == null) return out;
        try {
            while (c.moveToNext() && out.size() < limit) {
                Entry e = new Entry();
                e.kind = IndexStore.KIND_VIDEO;
                e.id = c.getLong(0);
                e.uri = ContentUris.withAppendedId(base, e.id);
                e.date = c.getLong(1) * 1000L;
                e.name = c.getString(2);
                out.add(e);
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** Every MediaStore id of a kind (to notice deleted files), or null when the query fails. */
    static java.util.Set<Long> allIds(ContentResolver cr, int kind) {
        Uri base = kind == IndexStore.KIND_VIDEO ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Cursor c;
        try {
            c = cr.query(base, new String[]{MediaStore.MediaColumns._ID}, null, null, null);
        } catch (Exception e) {
            return null;
        }
        if (c == null) return null;
        java.util.Set<Long> out = new java.util.HashSet<Long>();
        try {
            while (c.moveToNext()) out.add(c.getLong(0));
        } finally {
            c.close();
        }
        return out;
    }

    /** Key of a photo or video in {@link #aspects}. */
    static long aspectKey(int kind, long mediaId) {
        return mediaId * 2 + (kind == IndexStore.KIND_VIDEO ? 1 : 0);
    }

    /**
     * Width / height (rotation applied) of every photo and video MediaStore knows, by
     * {@link #aspectKey}: one cheap query per kind, so the gallery can lay out pictures uncropped
     * before any thumbnail is decoded.
     */
    static java.util.Map<Long, Float> aspects(ContentResolver cr) {
        java.util.Map<Long, Float> out = new java.util.HashMap<Long, Float>();
        readAspects(cr, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, IndexStore.KIND_PHOTO, out);
        readAspects(cr, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, IndexStore.KIND_VIDEO, out);
        return out;
    }

    private static void readAspects(ContentResolver cr, Uri base, int kind, java.util.Map<Long, Float> out) {
        Cursor c = null;
        try {
            // videos have an orientation column only from Android 10
            c = cr.query(base, new String[]{MediaStore.MediaColumns._ID, "width", "height", "orientation"}, null, null, null);
        } catch (Exception e) {
            c = null;
        }
        if (c == null) {
            try {
                c = cr.query(base, new String[]{MediaStore.MediaColumns._ID, "width", "height"}, null, null, null);
            } catch (Exception e) {
                return;
            }
        }
        if (c == null) return;
        try {
            int iw = c.getColumnIndex("width"), ih = c.getColumnIndex("height"), io = c.getColumnIndex("orientation");
            while (c.moveToNext()) {
                int w = c.isNull(iw) ? 0 : c.getInt(iw), h = c.isNull(ih) ? 0 : c.getInt(ih);
                if (w <= 0 || h <= 0) continue;
                int o = io >= 0 && !c.isNull(io) ? c.getInt(io) : 0;
                boolean turned = o == 90 || o == 270;
                out.put(aspectKey(kind, c.getLong(0)), turned ? h / (float) w : w / (float) h);
            }
        } catch (Exception ignored) {
            // keep what was read
        } finally {
            c.close();
        }
    }

    static int orientation(ContentResolver cr, long mediaId) {
        Cursor c = null;
        try {
            c = cr.query(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId),
                    new String[]{MediaStore.Images.Media.ORIENTATION}, null, null, null);
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getInt(0);
        } catch (Exception ignored) {
            // no orientation column: keep the stored pixels
        } finally {
            if (c != null) c.close();
        }
        return 0;
    }

    /** Full-screen quality for the viewer: about {@code maxPixels} pixels, upright. */
    static Bitmap full(ContentResolver cr, IndexStore.Item it, long maxPixels) throws Exception {
        Uri uri = Uri.parse(it.uri);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        InputStream in = cr.openInputStream(uri);
        try {
            BitmapFactory.decodeStream(in, null, o);
        } finally {
            if (in != null) in.close();
        }
        if (o.outWidth <= 0 || o.outHeight <= 0) throw new IllegalStateException("не удалось прочитать изображение");
        int sample = 1;
        while ((long) o.outWidth * o.outHeight / ((long) sample * sample) > maxPixels) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        in = cr.openInputStream(uri);
        Bitmap bmp;
        try {
            bmp = BitmapFactory.decodeStream(in, null, d);
        } finally {
            if (in != null) in.close();
        }
        if (bmp == null) throw new IllegalStateException("не удалось декодировать изображение");
        return rotate(bmp, orientation(cr, it.mediaId));
    }

    /**
     * Decodes an image subsampled to roughly {@code targetPixels} (at least), applying the
     * MediaStore/EXIF rotation so the encoder sees the photo upright.
     */
    static Bitmap decode(ContentResolver cr, Uri uri, int orientation, long targetPixels) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        InputStream in = cr.openInputStream(uri);
        try {
            BitmapFactory.decodeStream(in, null, o);
        } finally {
            if (in != null) in.close();
        }
        if (o.outWidth <= 0 || o.outHeight <= 0) throw new IllegalStateException("не удалось прочитать изображение");
        int sample = 1;
        while ((long) (o.outWidth / (sample * 2)) * (o.outHeight / (sample * 2)) >= targetPixels) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = Bitmap.Config.ARGB_8888;
        in = cr.openInputStream(uri);
        Bitmap bmp;
        try {
            bmp = BitmapFactory.decodeStream(in, null, d);
        } finally {
            if (in != null) in.close();
        }
        if (bmp == null) throw new IllegalStateException("не удалось декодировать изображение");
        return rotate(bmp, orientation);
    }

    static Bitmap rotate(Bitmap bmp, int degrees) {
        if (degrees % 360 == 0) return bmp;
        Matrix m = new Matrix();
        m.postRotate(degrees);
        Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        if (r != bmp) bmp.recycle();
        return r;
    }

    /** Evenly spaced frames, each scaled to fit {@code maxSide}. */
    static List<Bitmap> videoFrames(Context ctx, Uri uri, int count, int maxSide) throws Exception {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        List<Bitmap> frames = new ArrayList<Bitmap>();
        try {
            r.setDataSource(ctx, uri);
            String dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durMs = dur == null ? 0 : Long.parseLong(dur);
            String wS = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String hS = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            int w = wS == null ? 0 : Integer.parseInt(wS), h = hS == null ? 0 : Integer.parseInt(hS);
            Method scaled = null;
            if (Build.VERSION.SDK_INT >= 27 && w > 0 && h > 0) {
                try {
                    scaled = MediaMetadataRetriever.class.getMethod("getScaledFrameAtTime", long.class, int.class, int.class, int.class);
                } catch (NoSuchMethodException ignored) {
                }
            }
            for (int i = 0; i < count; i++) {
                long tUs = durMs <= 0 ? 0 : (long) ((i + 0.5) / count * durMs * 1000L);
                Bitmap f = null;
                if (scaled != null) {
                    float s = Math.min(1f, (float) maxSide / Math.max(w, h));
                    f = (Bitmap) scaled.invoke(r, tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)));
                }
                if (f == null) f = fit(r.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC), maxSide);
                if (f != null) frames.add(f);
                if (durMs <= 0) break;
            }
        } finally {
            r.release();
        }
        if (frames.isEmpty()) throw new IllegalStateException("не удалось получить кадры видео");
        return frames;
    }

    static Bitmap fit(Bitmap b, int maxSide) {
        if (b == null) return null;
        int m = Math.max(b.getWidth(), b.getHeight());
        if (m <= maxSide) return b;
        float s = (float) maxSide / m;
        Bitmap r = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * s)),
                Math.max(1, Math.round(b.getHeight() * s)), true);
        if (r != b) b.recycle();
        return r;
    }

    /** ContentResolver.loadThumbnail (API 29+, via reflection: we compile against API 23). */
    static Bitmap systemThumbnail(ContentResolver cr, Uri uri, int size) {
        if (Build.VERSION.SDK_INT < 29) return null;
        try {
            Class<?> sizeCls = Class.forName("android.util.Size");
            Object sz = sizeCls.getConstructor(int.class, int.class).newInstance(size, size);
            Method m = ContentResolver.class.getMethod("loadThumbnail", Uri.class, sizeCls, android.os.CancellationSignal.class);
            return (Bitmap) m.invoke(cr, uri, sz, null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Fast path for indexing: the system thumbnail service decodes only what is needed (and often
     * has it cached) and already applies EXIF rotation. Falls back to a full decode when the
     * thumbnail would be too small for the requested token budget.
     */
    static Bitmap decodeForIndex(ContentResolver cr, Uri uri, int orientation, long targetPixels) throws Exception {
        int side = (int) Math.ceil(Math.sqrt(targetPixels) * 1.3);
        Bitmap t = systemThumbnail(cr, uri, side);
        if (t != null) {
            if ((long) t.getWidth() * t.getHeight() >= targetPixels / 2) {
                if ("HARDWARE".equals(String.valueOf(t.getConfig()))) {
                    Bitmap sw = t.copy(Bitmap.Config.ARGB_8888, false);
                    t.recycle();
                    t = sw;
                }
                if (t != null) return t;
            } else {
                t.recycle();
            }
        }
        return decode(cr, uri, orientation, targetPixels);
    }

    /** Square-ish thumbnail for result tiles. */
    static Bitmap thumbnail(ContentResolver cr, IndexStore.Item it, int size) {
        Uri uri = Uri.parse(it.uri);
        Bitmap sys = systemThumbnail(cr, uri, size);
        if (sys != null) return sys;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            if (it.kind == IndexStore.KIND_VIDEO) {
                return MediaStore.Video.Thumbnails.getThumbnail(cr, it.mediaId, MediaStore.Video.Thumbnails.MINI_KIND, o);
            }
            return MediaStore.Images.Thumbnails.getThumbnail(cr, it.mediaId, MediaStore.Images.Thumbnails.MINI_KIND, o);
        } catch (Exception e) {
            return null;
        }
    }

    /** Adapts a Bitmap to the platform-independent image preprocessor. */
    static final class BitmapSource implements ImagePreprocessor.Source {
        private final Bitmap bmp;

        BitmapSource(Bitmap bmp) {
            this.bmp = bmp;
        }

        @Override
        public int width() {
            return bmp.getWidth();
        }

        @Override
        public int height() {
            return bmp.getHeight();
        }

        @Override
        public int[] argb(int w, int h) {
            Bitmap s = (w == bmp.getWidth() && h == bmp.getHeight()) ? bmp : Bitmap.createScaledBitmap(bmp, w, h, true);
            int[] px = new int[w * h];
            s.getPixels(px, 0, w, 0, 0, w, h);
            if (s != bmp) s.recycle();
            return px;
        }
    }
}
