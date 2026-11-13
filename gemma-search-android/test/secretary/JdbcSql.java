import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import io.github.teoplaydor.semsearch.secretary.Sql;

/** The secretary's Sql on a computer: sqlite-jdbc, one connection, the same typed values as Android's SQLite gives. */
public final class JdbcSql implements Sql {
    private final Connection c;

    public JdbcSql(String url) {
        try {
            c = DriverManager.getConnection(url);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public static JdbcSql memory() {
        return new JdbcSql("jdbc:sqlite::memory:");
    }

    private PreparedStatement prep(String sql, Object... args) throws SQLException {
        PreparedStatement p = c.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a == null) p.setNull(i + 1, Types.NULL);
            else if (a instanceof Long || a instanceof Integer) p.setLong(i + 1, ((Number) a).longValue());
            else if (a instanceof Double || a instanceof Float) p.setDouble(i + 1, ((Number) a).doubleValue());
            else if (a instanceof Boolean) p.setLong(i + 1, (Boolean) a ? 1 : 0);
            else p.setString(i + 1, a.toString());
        }
        return p;
    }

    public synchronized List<Row> q(String sql, Object... args) {
        try (PreparedStatement p = prep(sql, args); ResultSet rs = p.executeQuery()) {
            ResultSetMetaData md = rs.getMetaData();
            List<Row> out = new ArrayList<Row>();
            while (rs.next()) {
                Row r = new Row();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    Object v = rs.getObject(i);
                    if (v instanceof Integer) v = ((Integer) v).longValue();
                    if (v instanceof Float) v = ((Float) v).doubleValue();
                    r.put(md.getColumnLabel(i), v);
                }
                out.add(r);
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(sql + ": " + e.getMessage(), e);
        }
    }

    public synchronized long insert(String sql, Object... args) {
        try (PreparedStatement p = prep(sql, args)) {
            int n = p.executeUpdate();
            if (n == 0) return -1;
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT last_insert_rowid()")) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(sql + ": " + e.getMessage(), e);
        }
    }

    public synchronized int update(String sql, Object... args) {
        try (PreparedStatement p = prep(sql, args)) {
            return p.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(sql + ": " + e.getMessage(), e);
        }
    }

    public synchronized void script(String sql) {
        try (Statement s = c.createStatement()) {
            for (String part : sql.split(";\n")) if (!part.trim().isEmpty()) s.execute(part);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
