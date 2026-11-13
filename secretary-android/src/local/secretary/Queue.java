package local.secretary;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** Очередь уведомлений на телефоне: копится, пока нет связи, и уходит на сервер пачками. */
final class Queue extends SQLiteOpenHelper {
    static final int MAX_ITEMS = 5000;
    private static Queue instance;

    static synchronized Queue get(Context c) {
        if (instance == null) instance = new Queue(c.getApplicationContext());
        return instance;
    }

    private Queue(Context c) {
        super(c, "queue.db", null, 1);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE q (id INTEGER PRIMARY KEY AUTOINCREMENT, json TEXT NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {}

    synchronized void add(String json) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("json", json);
        db.insert("q", null, v);
        // Без связи неделю — старое выкидываем, чтобы не забить телефон.
        db.execSQL("DELETE FROM q WHERE id NOT IN (SELECT id FROM q ORDER BY id DESC LIMIT " + MAX_ITEMS + ")");
    }

    static final class Item {
        final long id;
        final String json;
        Item(long id, String json) { this.id = id; this.json = json; }
    }

    synchronized List<Item> peek(int limit) {
        List<Item> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id, json FROM q ORDER BY id LIMIT " + limit, null)) {
            while (c.moveToNext()) out.add(new Item(c.getLong(0), c.getString(1)));
        }
        return out;
    }

    synchronized void delete(List<Item> items) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Item i : items) db.delete("q", "id=?", new String[]{String.valueOf(i.id)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    synchronized int count() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM q", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }
}
