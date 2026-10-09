package io.github.teoplaydor.semsearch.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.teoplaydor.semsearch.core.FaceModel;
import io.github.teoplaydor.semsearch.core.People;
import io.github.teoplaydor.semsearch.core.VectorMath;

/**
 * The faces found in the photos (SQLite, faces.db, apart from the index: rebuilding the index for another photo model
 * keeps them) and the people and pets the user named, with what they marked. Photos are IndexStore keys (kind and
 * MediaStore id), boxes are fractions of the photo.
 */
final class FaceStore {
    static final int PERSON = 0, THING = 1;

    /** A person or a pet/thing: what the user marked as it (yes) and as not it (no). */
    static final class Group {
        final long id;
        String name;
        final int kind;
        /** Person: face vectors by face id; thing: photo keys. */
        final Map<Long, float[]> yesFaces = new java.util.LinkedHashMap<Long, float[]>(), noFaces = new java.util.LinkedHashMap<Long, float[]>();
        final Set<Long> yesPhotos = new HashSet<Long>(), noPhotos = new HashSet<Long>();

        Group(long id, String name, int kind) {
            this.id = id;
            this.name = name;
            this.kind = kind;
        }
    }

    private final SQLiteDatabase db;
    /** Photos looked at, and how (the scan's version: a later one finds more, so photos are looked at again). */
    private final Map<Long, Integer> scanned = new HashMap<Long, Integer>();
    private final List<People.Face> faces = new ArrayList<People.Face>();
    private final Map<Long, List<People.Face>> byPhoto = new HashMap<Long, List<People.Face>>();
    private final List<Group> groups = new ArrayList<Group>();
    /** Faces the user hid: no one to name (a passer-by), left out of people and groups. */
    private final Set<Long> ignored = new HashSet<Long>();
    private int version;

    FaceStore(Context ctx) {
        SQLiteOpenHelper helper = new SQLiteOpenHelper(ctx, "faces.db", null, 3) {
            @Override
            public void onCreate(SQLiteDatabase d) {
                d.execSQL("CREATE TABLE scanned (photo INTEGER PRIMARY KEY, faces INTEGER, v INTEGER DEFAULT 1)");
                d.execSQL("CREATE TABLE faces (id INTEGER PRIMARY KEY AUTOINCREMENT, photo INTEGER, x REAL, y REAL, w REAL, h REAL,"
                        + " size INTEGER, score REAL, emb BLOB)");
                d.execSQL("CREATE INDEX faces_photo ON faces (photo)");
                d.execSQL("CREATE TABLE groups (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, kind INTEGER)");
                d.execSQL("CREATE TABLE marks (grp INTEGER, yes INTEGER, photo INTEGER, face INTEGER, emb BLOB)");
                d.execSQL("CREATE TABLE ignored (face INTEGER PRIMARY KEY)");
            }

            @Override
            public void onUpgrade(SQLiteDatabase d, int o, int n) {
                if (o < 2) d.execSQL("CREATE TABLE IF NOT EXISTS ignored (face INTEGER PRIMARY KEY)"); // 0.10.16
                if (o < 3) d.execSQL("ALTER TABLE scanned ADD COLUMN v INTEGER DEFAULT 1"); // 0.10.17
            }
        };
        db = helper.getWritableDatabase();
        Cursor c = db.rawQuery("SELECT photo, v FROM scanned", null);
        try {
            while (c.moveToNext()) scanned.put(c.getLong(0), c.isNull(1) ? 1 : c.getInt(1));
        } finally {
            c.close();
        }
        c = db.rawQuery("SELECT id, photo, x, y, w, h, size, score, emb FROM faces", null);
        try {
            while (c.moveToNext()) {
                track(new People.Face(c.getLong(0), c.getLong(1), c.getFloat(2), c.getFloat(3), c.getFloat(4), c.getFloat(5), c.getInt(6),
                        c.getFloat(7), VectorMath.fromBytes(c.getBlob(8))));
            }
        } finally {
            c.close();
        }
        c = db.rawQuery("SELECT face FROM ignored", null);
        try {
            while (c.moveToNext()) ignored.add(c.getLong(0));
        } finally {
            c.close();
        }
        Map<Long, Group> byId = new HashMap<Long, Group>();
        c = db.rawQuery("SELECT id, name, kind FROM groups ORDER BY id", null);
        try {
            while (c.moveToNext()) {
                Group g = new Group(c.getLong(0), c.getString(1), c.getInt(2));
                groups.add(g);
                byId.put(g.id, g);
            }
        } finally {
            c.close();
        }
        c = db.rawQuery("SELECT grp, yes, photo, face, emb FROM marks", null);
        try {
            while (c.moveToNext()) {
                Group g = byId.get(c.getLong(0));
                if (g == null) continue;
                boolean yes = c.getInt(1) != 0;
                // a face marked (a person), or a whole photo (a pet or thing, or a person whose face was not found)
                if (c.getLong(3) >= 0 && !c.isNull(4)) (yes ? g.yesFaces : g.noFaces).put(c.getLong(3), VectorMath.fromBytes(c.getBlob(4)));
                else (yes ? g.yesPhotos : g.noPhotos).add(c.getLong(2));
            }
        } finally {
            c.close();
        }
    }

    private void track(People.Face f) {
        faces.add(f);
        List<People.Face> l = byPhoto.get(f.photo);
        if (l == null) byPhoto.put(f.photo, l = new ArrayList<People.Face>());
        l.add(f);
    }

    /** Changes whenever faces, people or marks change. */
    synchronized int version() {
        return version;
    }

    synchronized boolean scanned(long photo) {
        return scanned.containsKey(photo);
    }

    /** The version of the scan the photo was looked at with (0: not yet). */
    synchronized int scannedWith(long photo) {
        Integer v = scanned.get(photo);
        return v == null ? 0 : v;
    }

    synchronized int scannedCount() {
        return scanned.size();
    }

    /**
     * The faces found in a photo of {@code w}×{@code h} pixels (or none, or null: it could not be read), by scan
     * version {@code v}. Looked at again: the faces it has keep their ids, names and marks; only new ones join.
     */
    synchronized void addScan(long photo, List<FaceModel.Face> found, int w, int h, int v) {
        db.beginTransaction();
        try {
            List<People.Face> had = byPhoto.get(photo);
            int n = had == null ? 0 : had.size();
            if (found != null) {
                for (FaceModel.Face f : found) {
                    float x = clamp(f.x / w), y = clamp(f.y / h), fw = Math.min(1 - x, f.w / w), fh = Math.min(1 - y, f.h / h);
                    boolean known = false;
                    if (had != null) for (People.Face o : had) known |= iou(x, y, fw, fh, o.x, o.y, o.w, o.h) > 0.4;
                    if (known) continue;
                    int size = Math.round(Math.min(f.w, f.h));
                    ContentValues row = new ContentValues();
                    row.put("photo", photo);
                    row.put("x", x);
                    row.put("y", y);
                    row.put("w", fw);
                    row.put("h", fh);
                    row.put("size", size);
                    row.put("score", f.score);
                    row.put("emb", VectorMath.toBytes(f.emb));
                    long id = db.insert("faces", null, row);
                    track(new People.Face(id, photo, x, y, fw, fh, size, f.score, f.emb));
                    n++;
                }
            }
            ContentValues s = new ContentValues();
            s.put("photo", photo);
            s.put("faces", found == null && had == null ? -1 : n);
            s.put("v", v);
            db.insertWithOnConflict("scanned", null, s, SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        scanned.put(photo, v);
        version++;
    }

    private static double iou(float ax, float ay, float aw, float ah, float bx, float by, float bw, float bh) {
        double x1 = Math.max(ax, bx), y1 = Math.max(ay, by), x2 = Math.min(ax + aw, bx + bw), y2 = Math.min(ay + ah, by + bh);
        double inter = x2 > x1 && y2 > y1 ? (x2 - x1) * (y2 - y1) : 0;
        double union = (double) aw * ah + (double) bw * bh - inter;
        return union <= 0 ? 0 : inter / union;
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    synchronized List<People.Face> faces() {
        return new ArrayList<People.Face>(faces);
    }

    synchronized List<People.Face> facesOf(long photo) {
        List<People.Face> l = byPhoto.get(photo);
        return l == null ? new ArrayList<People.Face>() : new ArrayList<People.Face>(l);
    }

    synchronized People.Face face(long id) {
        for (People.Face f : faces) if (f.id == id) return f;
        return null;
    }

    synchronized int faceCount() {
        return faces.size();
    }

    synchronized boolean ignored(long face) {
        return ignored.contains(face);
    }

    synchronized int ignoredCount() {
        return ignored.size();
    }

    /** The face is hidden: nobody to name. */
    synchronized void ignore(long face) {
        ContentValues v = new ContentValues();
        v.put("face", face);
        db.insertWithOnConflict("ignored", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        ignored.add(face);
        version++;
    }

    /** Several faces hidden at once (someone unnamed nobody needs to name). */
    synchronized void ignoreAll(long[] faces) {
        db.beginTransaction();
        try {
            for (long f : faces) {
                ContentValues v = new ContentValues();
                v.put("face", f);
                db.insertWithOnConflict("ignored", null, v, SQLiteDatabase.CONFLICT_IGNORE);
                ignored.add(f);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        version++;
    }

    /** Every hidden face back. */
    synchronized void unignoreAll() {
        db.delete("ignored", null, null);
        ignored.clear();
        version++;
    }

    // ------------------------------------------------------------------ people and pets

    synchronized List<Group> groups() {
        return new ArrayList<Group>(groups);
    }

    synchronized Group group(long id) {
        for (Group g : groups) if (g.id == id) return g;
        return null;
    }

    /** The group of this kind with this name (any case), made when there is none. */
    synchronized Group named(String name, int kind) {
        for (Group g : groups) if (g.kind == kind && g.name.equalsIgnoreCase(name.trim())) return g;
        ContentValues v = new ContentValues();
        v.put("name", name.trim());
        v.put("kind", kind);
        Group g = new Group(db.insert("groups", null, v), name.trim(), kind);
        groups.add(g);
        version++;
        return g;
    }

    synchronized void rename(long id, String name) {
        Group g = group(id);
        if (g == null) return;
        ContentValues v = new ContentValues();
        v.put("name", name.trim());
        db.update("groups", v, "id = ?", new String[]{String.valueOf(id)});
        g.name = name.trim();
        version++;
    }

    synchronized void delete(long id) {
        Group g = group(id);
        if (g == null) return;
        db.delete("marks", "grp = ?", new String[]{String.valueOf(id)});
        db.delete("groups", "id = ?", new String[]{String.valueOf(id)});
        groups.remove(g);
        version++;
    }

    /** A face is (or is not) this person; the opposite mark of the face goes. */
    synchronized void markFace(Group g, People.Face f, boolean yes) {
        db.delete("marks", "grp = ? AND face = ?", new String[]{String.valueOf(g.id), String.valueOf(f.id)});
        g.yesFaces.remove(f.id);
        g.noFaces.remove(f.id);
        ContentValues v = new ContentValues();
        v.put("grp", g.id);
        v.put("yes", yes ? 1 : 0);
        v.put("photo", f.photo);
        v.put("face", f.id);
        v.put("emb", VectorMath.toBytes(f.emb));
        db.insert("marks", null, v);
        (yes ? g.yesFaces : g.noFaces).put(f.id, f.emb);
        version++;
    }

    /** A photo is (or is not) this pet or thing; the opposite mark of the photo goes. */
    synchronized void markPhoto(Group g, long photo, boolean yes) {
        db.delete("marks", "grp = ? AND photo = ?", new String[]{String.valueOf(g.id), String.valueOf(photo)});
        ContentValues v = new ContentValues();
        v.put("grp", g.id);
        v.put("yes", yes ? 1 : 0);
        v.put("photo", photo);
        v.put("face", -1);
        db.insert("marks", null, v);
        g.yesPhotos.remove(photo);
        g.noPhotos.remove(photo);
        (yes ? g.yesPhotos : g.noPhotos).add(photo);
        version++;
    }
}
