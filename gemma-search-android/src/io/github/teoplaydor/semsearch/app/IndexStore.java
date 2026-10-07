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

    /** Like {@link #search} but with separate query vectors for media (photos/videos) and notes. */
    public synchronized List<Hit> search(float[] qMedia, float[] qNotes, int dims, boolean photos, boolean videos,
                                         boolean notes, int limit, long excludeId) {
        int di = 0;
        for (int i = 0; i < DIMS.length; i++) if (DIMS[i] == dims) di = i;
        int d = Math.min(dims, Math.min(qMedia.length, qNotes.length));
        float qmn = VectorMath.prefixNorm(qMedia, 0, d), qnn = VectorMath.prefixNorm(qNotes, 0, d);
        List<Hit> hits = new ArrayList<Hit>();
        for (Item it : items) {
            if (it.id == excludeId || it.emb.length < d) continue;
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
