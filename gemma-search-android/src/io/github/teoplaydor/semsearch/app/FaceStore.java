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
    private final Set<Long> scanned = new HashSet<Long>();
    private final List<People.Face> faces = new ArrayList<People.Face>();
    private final Map<Long, List<People.Face>> byPhoto = new HashMap<Long, List<People.Face>>();
    private final List<Group> groups = new ArrayList<Group>();
    private int version;

    FaceStore(Context ctx) {
        SQLiteOpenHelper helper = new SQLiteOpenHelper(ctx, "faces.db", null, 1) {
            @Override
            public void onCreate(SQLiteDatabase d) {
                d.execSQL("CREATE TABLE scanned (photo INTEGER PRIMARY KEY, faces INTEGER)");
                d.execSQL("CREATE TABLE faces (id INTEGER PRIMARY KEY AUTOINCREMENT, photo INTEGER, x REAL, y REAL, w REAL, h REAL,"
                        + " size INTEGER, score REAL, emb BLOB)");
                d.execSQL("CREATE INDEX faces_photo ON faces (photo)");
                d.execSQL("CREATE TABLE groups (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, kind INTEGER)");
                d.execSQL("CREATE TABLE marks (grp INTEGER, yes INTEGER, photo INTEGER, face INTEGER, emb BLOB)");
            }

            @Override
            public void onUpgrade(SQLiteDatabase d, int o, int n) {
            }
        };
        db = helper.getWritableDatabase();
        Cursor c = db.rawQuery("SELECT photo FROM scanned", null);
        try {
            while (c.moveToNext()) scanned.add(c.getLong(0));
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
                if (g.kind == PERSON) (yes ? g.yesFaces : g.noFaces).put(c.getLong(3), VectorMath.fromBytes(c.getBlob(4)));
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
        return scanned.contains(photo);
    }

    synchronized int scannedCount() {
        return scanned.size();
    }

    /** The faces found in a photo of {@code w}×{@code h} pixels (or none, or -1 faces: it could not be read). */
    synchronized void addScan(long photo, List<FaceModel.Face> found, int w, int h) {
        db.beginTransaction();
        try {
            ContentValues s = new ContentValues();
            s.put("photo", photo);
            s.put("faces", found == null ? -1 : found.size());
            db.insertWithOnConflict("scanned", null, s, SQLiteDatabase.CONFLICT_REPLACE);
            if (found != null) {
                for (FaceModel.Face f : found) {
                    float x = clamp(f.x / w), y = clamp(f.y / h), fw = Math.min(1 - x, f.w / w), fh = Math.min(1 - y, f.h / h);
                    int size = Math.round(Math.min(f.w, f.h));
                    ContentValues v = new ContentValues();
                    v.put("photo", photo);
                    v.put("x", x);
                    v.put("y", y);
                    v.put("w", fw);
                    v.put("h", fh);
                    v.put("size", size);
                    v.put("score", f.score);
                    v.put("emb", VectorMath.toBytes(f.emb));
                    long id = db.insert("faces", null, v);
                    track(new People.Face(id, photo, x, y, fw, fh, size, f.score, f.emb));
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        scanned.add(photo);
        version++;
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
