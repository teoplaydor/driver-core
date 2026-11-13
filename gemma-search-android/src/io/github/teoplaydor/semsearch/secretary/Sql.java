package io.github.teoplaydor.semsearch.secretary;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * SQLite as the secretary uses it — the same few calls as Python's sqlite3 in the server version: rows by name, the
 * id of an inserted row, the count of changed ones. On the phone: Android's SQLiteDatabase (app side); in the tests on
 * a computer: the sqlite-jdbc driver. Arguments keep their types (Long, Double, String, null), as sqlite3 binds them.
 */
public interface Sql {
    List<Row> q(String sql, Object... args);

    /** INSERT: the new row's id, or -1 when nothing was inserted (INSERT OR IGNORE of a row already there). */
    long insert(String sql, Object... args);

    /** UPDATE / DELETE: how many rows changed. */
    int update(String sql, Object... args);

    /** Several statements separated by ";" (the schema). */
    void script(String sql);

    /** A row: column → Long, Double, String or null — as sqlite3.Row gives them. */
    final class Row extends LinkedHashMap<String, Object> {
        public String str(String k) {
            Object v = get(k);
            return v == null ? null : String.valueOf(v);
        }

        /** The text, "" for null. */
        public String text(String k) {
            Object v = get(k);
            return v == null ? "" : String.valueOf(v);
        }

        public long lng(String k) {
            Object v = get(k);
            if (v instanceof Number) return ((Number) v).longValue();
            if (v instanceof String) try {
                return Long.parseLong((String) v);
            } catch (NumberFormatException e) {
                return 0;
            }
            return 0;
        }

        public int num(String k) {
            return (int) lng(k);
        }

        public double dbl(String k) {
            Object v = get(k);
            if (v instanceof Number) return ((Number) v).doubleValue();
            if (v instanceof String) try {
                return Double.parseDouble((String) v);
            } catch (NumberFormatException e) {
                return 0;
            }
            return 0;
        }

        public boolean isNull(String k) {
            return get(k) == null;
        }

        /** Python's truth of the value: not None, not "", not 0. */
        public boolean truthy(String k) {
            Object v = get(k);
            if (v == null) return false;
            if (v instanceof String) return !((String) v).isEmpty();
            if (v instanceof Number) return ((Number) v).doubleValue() != 0;
            return true;
        }

        /** A whole number that may be NULL (Python's None). */
        public Long lngOrNull(String k) {
            Object v = get(k);
            return v instanceof Number ? ((Number) v).longValue() : null;
        }
    }
}
