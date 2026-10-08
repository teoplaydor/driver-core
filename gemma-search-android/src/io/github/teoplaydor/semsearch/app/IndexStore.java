package io.github.teoplaydor.semsearch.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

import io.github.teoplaydor.semsearch.core.VectorMath;

/** Persistent vector index (SQLite) mirrored in memory for brute-force cosine search. */
public final class IndexStore {
    public static final int KIND_PHOTO = 0, KIND_VIDEO = 1, KIND_NOTE = 2;
    public static final int[] DIMS = {128, 256, 512, 768};

    public static final class Item {
        public long id;
        public int kind;
        public long mediaId;
        public String uri, title, body;
        public long date;
        public float[] emb;
        float[] norms = new float[DIMS.length];

        void computeNorms() {
            for (int i = 0; i < DIMS.length; i++) {
                norms[i] = VectorMath.prefixNorm(emb, 0, Math.min(DIMS[i], emb.length));
            }
        }
    }

    public static final class Hit {
        public final Item item;
        public final float score;

        Hit(Item item, float score) {
            this.item = item;
            this.score = score;
        }
    }

    private final SQLiteDatabase db;
    private final List<Item> items = new ArrayList<Item>();
    private final HashSet<Long> photoIds = new HashSet<Long>(), videoIds = new HashSet<Long>();
    /** Photos and videos kept out of the gallery, search and albums ({@link #key}s): 18+ (Engine). */
    private HashSet<Long> hidden = new HashSet<Long>();

    public IndexStore(Context ctx) {
        SQLiteOpenHelper helper = new SQLiteOpenHelper(ctx, "index.db", null, 1) {
            @Override
            public void onCreate(SQLiteDatabase d) {
                d.execSQL("CREATE TABLE items (id INTEGER PRIMARY KEY AUTOINCREMENT, kind INTEGER, media_id INTEGER,"
                        + " uri TEXT, title TEXT, body TEXT, date INTEGER, emb BLOB)");
            }

            @Override
            public void onUpgrade(SQLiteDatabase d, int o, int n) {
            }
        };
        db = helper.getWritableDatabase();
        Cursor c = db.rawQuery("SELECT id, kind, media_id, uri, title, body, date, emb FROM items", null);
        try {
            while (c.moveToNext()) {
                Item it = new Item();
                it.id = c.getLong(0);
                it.kind = c.getInt(1);
                it.mediaId = c.getLong(2);
                it.uri = c.getString(3);
                it.title = c.getString(4);
                it.body = c.getString(5);
                it.date = c.getLong(6);
                byte[] b = c.getBlob(7);
                it.emb = b == null ? new float[0] : VectorMath.fromBytes(b);
                it.computeNorms();
                track(it, true);
                items.add(it);
            }
        } finally {
            c.close();
        }
    }

    private void track(Item it, boolean add) {
        HashSet<Long> set = it.kind == KIND_PHOTO ? photoIds : it.kind == KIND_VIDEO ? videoIds : null;
        if (set == null) return;
        if (add) set.add(it.mediaId); else set.remove(it.mediaId);
    }

    public synchronized Item add(int kind, long mediaId, String uri, String title, String body, long date, float[] emb) {
        ContentValues v = new ContentValues();
        v.put("kind", kind);
        v.put("media_id", mediaId);
        v.put("uri", uri);
        v.put("title", title);
        v.put("body", body);
        v.put("date", date);
        v.put("emb", VectorMath.toBytes(emb));
        Item it = new Item();
        it.id = db.insert("items", null, v);
        it.kind = kind;
        it.mediaId = mediaId;
        it.uri = uri;
        it.title = title;
        it.body = body;
        it.date = date;
        it.emb = emb;
        it.computeNorms();
        items.add(it);
        track(it, true);
        return it;
    }

    public synchronized void updateEmbedding(Item it, float[] emb) {
        ContentValues v = new ContentValues();
        v.put("emb", VectorMath.toBytes(emb));
        db.update("items", v, "id = ?", new String[]{String.valueOf(it.id)});
        it.emb = emb;
        it.computeNorms();
    }

    public synchronized void delete(Item it) {
        db.delete("items", "id = ?", new String[]{String.valueOf(it.id)});
        items.remove(it);
        track(it, false);
    }

    public synchronized void clearMedia() {
        db.delete("items", "kind != ?", new String[]{String.valueOf(KIND_NOTE)});
        List<Item> keep = new ArrayList<Item>();
        for (Item it : items) if (it.kind == KIND_NOTE) keep.add(it);
        items.clear();
        items.addAll(keep);
        photoIds.clear();
        videoIds.clear();
    }

    /** A photo's or video's lasting name: its kind and MediaStore id (the row id changes when the index is rebuilt). */
    public static long key(int kind, long mediaId) {
        return ((long) kind << 48) ^ mediaId;
    }

    public static long key(Item it) {
        return key(it.kind, it.mediaId);
    }

    private int hiddenVersion;

    public synchronized void setHidden(java.util.Set<Long> keys) {
        if (keys.equals(hidden)) return;
        hidden = new HashSet<Long>(keys);
        hiddenVersion++;
    }

    /** Changes whenever what is hidden changes (screens showing the gallery refresh then). */
    public synchronized int hiddenVersion() {
        return hiddenVersion;
    }

    public synchronized boolean isHidden(Item it) {
        return it.kind != KIND_NOTE && !hidden.isEmpty() && hidden.contains(key(it));
    }

    /** The hidden photos and videos, newest first. */
    public synchronized List<Item> hiddenItems() {
        List<Item> out = new ArrayList<Item>();
        for (Item it : items) if (isHidden(it)) out.add(it);
        Collections.sort(out, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return Long.compare(b.date, a.date);
            }
        });
        return out;
    }

    public synchronized boolean hasMedia(int kind, long mediaId) {
        return (kind == KIND_PHOTO ? photoIds : videoIds).contains(mediaId);
    }

    public synchronized int count(int kind) {
        int n = 0;
        for (Item it : items) if (it.kind == kind) n++;
        return n;
    }

    /** Photos and/or videos (and notes), newest first: the gallery view. */
    public synchronized List<Item> recent(boolean photos, boolean videos, boolean notes, int limit) {
        List<Item> out = new ArrayList<Item>();
        for (Item it : items) {
            if (isHidden(it)) continue;
            if ((it.kind == KIND_PHOTO && photos) || (it.kind == KIND_VIDEO && videos) || (it.kind == KIND_NOTE && notes)) out.add(it);
        }
        Collections.sort(out, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return Long.compare(b.date, a.date);
            }
        });
        return out.size() > limit ? new ArrayList<Item>(out.subList(0, limit)) : out;
    }

    /** Every photo and video, hidden ones too (what the gallery's statistics are taken over). */
    public synchronized List<Item> media() {
        List<Item> out = new ArrayList<Item>();
        for (Item it : items) if (it.kind != KIND_NOTE) out.add(it);
        return out;
    }

    public synchronized List<Item> notes() {
        List<Item> out = new ArrayList<Item>();
        for (Item it : items) if (it.kind == KIND_NOTE) out.add(it);
        Collections.sort(out, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return Long.compare(b.date, a.date);
            }
        });
        return out;
    }

    /** Cosine search over the first {@code dims} components (Matryoshka truncation). */
    public synchronized List<Hit> search(float[] q, int dims, boolean photos, boolean videos, boolean notes,
                                         int limit, long excludeId) {
        return search(q, q, dims, photos, videos, notes, limit, excludeId);
    }

    /**
     * Photos/videos and notes searched with their own query vectors and lengths. When both come from one
     * model ({@code sameSpace}) the cosines are merged directly; otherwise (SigLIP pictures, EmbeddingGemma
     * notes) the scores are not comparable and the two rankings are interleaved by reciprocal rank.
     */
    public synchronized List<Hit> search(float[] qMedia, int mediaDims, float[] qNotes, int notesDims, boolean photos,
                                         boolean videos, boolean notes, boolean sameSpace, int limit, long excludeId) {
        List<Hit> media = (photos || videos) && qMedia != null
                ? rank(qMedia, mediaDims, photos, videos, false, excludeId) : new ArrayList<Hit>();
        List<Hit> nt = notes && qNotes != null ? rank(qNotes, notesDims, false, false, true, excludeId) : new ArrayList<Hit>();
        List<Hit> all = new ArrayList<Hit>(media.size() + nt.size());
        if (sameSpace || media.isEmpty() || nt.isEmpty()) {
            all.addAll(media);
            all.addAll(nt);
            Collections.sort(all, BY_SCORE);
        } else {
            final java.util.IdentityHashMap<Hit, Double> rrf = new java.util.IdentityHashMap<Hit, Double>();
            for (int i = 0; i < media.size(); i++) rrf.put(media.get(i), 1.0 / (10 + i));
            for (int i = 0; i < nt.size(); i++) rrf.put(nt.get(i), 1.0 / (10 + i));
            all.addAll(media);
            all.addAll(nt);
            Collections.sort(all, new Comparator<Hit>() {
                @Override
                public int compare(Hit a, Hit b) {
                    return Double.compare(rrf.get(b), rrf.get(a));
                }
            });
        }
        return all.size() > limit ? new ArrayList<Hit>(all.subList(0, limit)) : all;
    }

    /**
     * Search results in two tiers: what clearly stands out from a typical item, and the less sure rest (shown on
     * request). There is no fixed count: as many as stand out.
     */
    public static final class Tiers {
        public final List<Hit> sure = new ArrayList<Hit>(), more = new ArrayList<Hit>();
        /** Nothing stood out: {@link #sure} holds the nearest few instead. */
        public boolean nearestOnly;
    }

    /**
     * An item stands out at {@link #SURE_Z} robust deviations above the median similarity of its group (photos and
     * videos, or notes): the median and the median absolute deviation (×1.4826) over every item, so the items that do
     * match do not widen the spread. From {@link #MORE_Z}, less sure. A group under {@link #SMALL_GROUP} items has no
     * typical item to tell: all of it, by score.
     */
    public static final double SURE_Z = 3.5, MORE_Z = 2.0;
    static final int SMALL_GROUP = 20, NEAREST = 6, MORE_MAX = 600;
    /** The least spread taken as real (cosines): a gallery of near-copies has none. */
    static final float MIN_SPREAD = 0.02f;

    public synchronized Tiers searchTiers(float[] qMedia, int mediaDims, float[] qNotes, int notesDims, boolean photos,
                                          boolean videos, boolean notes, boolean sameSpace, long excludeId) {
        List<Hit> media = (photos || videos) && qMedia != null
                ? rank(qMedia, mediaDims, photos, videos, false, excludeId) : new ArrayList<Hit>();
        List<Hit> nt = notes && qNotes != null ? rank(qNotes, notesDims, false, false, true, excludeId) : new ArrayList<Hit>();
        Tiers tm = tiers(media), tn = tiers(nt), out = new Tiers();
        List<Hit> sureM = new ArrayList<Hit>(), sureN = new ArrayList<Hit>(), moreM = new ArrayList<Hit>(tm.more),
                moreN = new ArrayList<Hit>(tn.more);
        boolean any = (!tm.nearestOnly && !tm.sure.isEmpty()) || (!tn.nearestOnly && !tn.sure.isEmpty());
        if (any) {
            // a group with nothing standing out gives its nearest to "more", not to the results
            if (tm.nearestOnly) moreM.addAll(0, tm.sure);
            else sureM.addAll(tm.sure);
            if (tn.nearestOnly) moreN.addAll(0, tn.sure);
            else sureN.addAll(tn.sure);
        } else {
            out.nearestOnly = !tm.sure.isEmpty() || !tn.sure.isEmpty();
            sureM.addAll(tm.sure);
            sureN.addAll(tn.sure);
        }
        out.sure.addAll(merge(sureM, sureN, sameSpace));
        out.more.addAll(merge(moreM, moreN, sameSpace));
        return out;
    }

    /** One group's tiers ({@code sorted} by score, best first). */
    static Tiers tiers(List<Hit> sorted) {
        Tiers t = new Tiers();
        int n = sorted.size();
        if (n == 0) return t;
        if (n < SMALL_GROUP) {
            t.sure.addAll(sorted);
            return t;
        }
        float median = sorted.get(n / 2).score;
        float[] dev = new float[n];
        for (int i = 0; i < n; i++) dev[i] = Math.abs(sorted.get(i).score - median);
        java.util.Arrays.sort(dev);
        double sd = Math.max(MIN_SPREAD, 1.4826 * dev[n / 2]);
        for (Hit h : sorted) {
            double z = (h.score - median) / sd;
            if (z >= SURE_Z) t.sure.add(h);
            else if (z >= MORE_Z && t.more.size() < MORE_MAX) t.more.add(h);
            else break;
        }
        if (t.sure.isEmpty()) {
            t.nearestOnly = true;
            for (int i = 0; i < Math.min(NEAREST, n); i++) t.sure.add(sorted.get(i));
            t.more.removeAll(t.sure);
        }
        return t;
    }

    /** Photos/videos and notes together: by score in one space, else interleaved by reciprocal rank. */
    private static List<Hit> merge(List<Hit> media, List<Hit> nt, boolean sameSpace) {
        List<Hit> all = new ArrayList<Hit>(media.size() + nt.size());
        all.addAll(media);
        all.addAll(nt);
        if (sameSpace || media.isEmpty() || nt.isEmpty()) {
            Collections.sort(all, BY_SCORE);
            return all;
        }
        final java.util.IdentityHashMap<Hit, Double> rrf = new java.util.IdentityHashMap<Hit, Double>();
        for (int i = 0; i < media.size(); i++) rrf.put(media.get(i), 1.0 / (10 + i));
        for (int i = 0; i < nt.size(); i++) rrf.put(nt.get(i), 1.0 / (10 + i));
        Collections.sort(all, new Comparator<Hit>() {
            @Override
            public int compare(Hit a, Hit b) {
                return Double.compare(rrf.get(b), rrf.get(a));
            }
        });
        return all;
    }

    private static final Comparator<Hit> BY_SCORE = new Comparator<Hit>() {
        @Override
        public int compare(Hit a, Hit b) {
            return Float.compare(b.score, a.score);
        }
    };

    private List<Hit> rank(float[] q, int dims, boolean photos, boolean videos, boolean notes, long excludeId) {
        int d = Math.min(dims, q.length);
        int di = -1;
        for (int i = 0; i < DIMS.length; i++) if (DIMS[i] == d) di = i;
        float qn = VectorMath.prefixNorm(q, 0, d);
        List<Hit> hits = new ArrayList<Hit>();
        for (Item it : items) {
            if (it.id == excludeId || it.emb.length < d || isHidden(it)) continue;
            if ((it.kind == KIND_PHOTO && !photos) || (it.kind == KIND_VIDEO && !videos) || (it.kind == KIND_NOTE && !notes)) {
                continue;
            }
            float bn = di >= 0 ? it.norms[di] : VectorMath.prefixNorm(it.emb, 0, d);
            hits.add(new Hit(it, VectorMath.cosinePrefix(q, qn, it.emb, 0, bn, d)));
        }
        Collections.sort(hits, BY_SCORE);
        return hits;
    }

    /** Like {@link #search} but with separate query vectors for media (photos/videos) and notes. */
    public synchronized List<Hit> search(float[] qMedia, float[] qNotes, int dims, boolean photos, boolean videos,
                                         boolean notes, int limit, long excludeId) {
        int di = 0;
        for (int i = 0; i < DIMS.length; i++) if (DIMS[i] == dims) di = i;
        int d = Math.min(dims, Math.min(qMedia.length, qNotes.length));
        float qmn = VectorMath.prefixNorm(qMedia, 0, d), qnn = VectorMath.prefixNorm(qNotes, 0, d);
        List<Hit> hits = new ArrayList<Hit>();
        for (Item it : items) {
            if (it.id == excludeId || it.emb.length < d || isHidden(it)) continue;
            if ((it.kind == KIND_PHOTO && !photos) || (it.kind == KIND_VIDEO && !videos) || (it.kind == KIND_NOTE && !notes)) {
                continue;
            }
            float bn = DIMS[di] == d ? it.norms[di] : VectorMath.prefixNorm(it.emb, 0, d);
            boolean note = it.kind == KIND_NOTE;
            hits.add(new Hit(it, VectorMath.cosinePrefix(note ? qNotes : qMedia, note ? qnn : qmn, it.emb, 0, bn, d)));
        }
        Collections.sort(hits, new Comparator<Hit>() {
            @Override
            public int compare(Hit a, Hit b) {
                return Float.compare(b.score, a.score);
            }
        });
        return hits.size() > limit ? new ArrayList<Hit>(hits.subList(0, limit)) : hits;
    }
}
