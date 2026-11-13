package io.github.teoplaydor.semsearch.app;

import android.database.Cursor;
import android.database.sqlite.SQLiteCursor;
import android.database.sqlite.SQLiteCursorDriver;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteProgram;
import android.database.sqlite.SQLiteQuery;
import android.database.sqlite.SQLiteStatement;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import io.github.teoplaydor.semsearch.secretary.Sql;

/**
 * The secretary's database on the phone: Android's SQLite. Arguments are bound with their types (rawQuery would bind
 * them all as text, and «importance >= 3» or «LIMIT 10» would compare text) — through a cursor factory for queries.
 */
final class SecSql implements Sql {
    private final SQLiteDatabase db;

    SecSql(File file) {
        File dir = file.getParentFile();
        if (dir != null) dir.mkdirs();
        db = SQLiteDatabase.openOrCreateDatabase(file, null);
        db.enableWriteAheadLogging();
    }

    static void bind(SQLiteProgram p, Object[] args) {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a == null) p.bindNull(i + 1);
            else if (a instanceof Long || a instanceof Integer) p.bindLong(i + 1, ((Number) a).longValue());
            else if (a instanceof Boolean) p.bindLong(i + 1, (Boolean) a ? 1 : 0);
            else if (a instanceof Double || a instanceof Float) p.bindDouble(i + 1, ((Number) a).doubleValue());
            else p.bindString(i + 1, a.toString());
        }
    }

    @Override
    public List<Row> q(String sql, final Object... args) {
        Cursor c = db.rawQueryWithFactory(new SQLiteDatabase.CursorFactory() {
            @Override
            public Cursor newCursor(SQLiteDatabase d, SQLiteCursorDriver driver, String table, SQLiteQuery query) {
                bind(query, args);
                return new SQLiteCursor(driver, table, query);
            }
        }, sql, null, null);
        List<Row> out = new ArrayList<Row>();
        try {
            String[] names = c.getColumnNames();
            while (c.moveToNext()) {
                Row r = new Row();
                for (int i = 0; i < names.length; i++) {
                    switch (c.getType(i)) {
                        case Cursor.FIELD_TYPE_NULL:
                            r.put(names[i], null);
                            break;
                        case Cursor.FIELD_TYPE_INTEGER:
                            r.put(names[i], c.getLong(i));
                            break;
                        case Cursor.FIELD_TYPE_FLOAT:
                            r.put(names[i], c.getDouble(i));
                            break;
                        case Cursor.FIELD_TYPE_BLOB:
                            r.put(names[i], c.getBlob(i));
                            break;
                        default:
                            r.put(names[i], c.getString(i));
                    }
                }
                out.add(r);
            }
        } finally {
            c.close();
        }
        return out;
    }

    @Override
    public long insert(String sql, Object... args) {
        SQLiteStatement s = db.compileStatement(sql);
        try {
            bind(s, args);
            return s.executeInsert(); // -1 when INSERT OR IGNORE inserted nothing
        } finally {
            s.close();
        }
    }

    @Override
    public int update(String sql, Object... args) {
        SQLiteStatement s = db.compileStatement(sql);
        try {
            bind(s, args);
            return s.executeUpdateDelete();
        } finally {
            s.close();
        }
    }

    @Override
    public void script(String sql) {
        for (String part : sql.split(";\n")) if (!part.trim().isEmpty()) db.execSQL(part);
    }
}
