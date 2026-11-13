package io.github.teoplaydor.semsearch.secretary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.teoplaydor.semsearch.core.MiniJson;

/** Buttons under a message, rows of {text, data} — the server's inline keyboards, as its app receives them. */
public final class Kb {
    public final List<List<String[]>> rows = new ArrayList<List<String[]>>();

    public Kb row(String... textData) {
        List<String[]> r = new ArrayList<String[]>();
        for (int i = 0; i + 1 < textData.length; i += 2) r.add(new String[]{textData[i], textData[i + 1]});
        rows.add(r);
        return this;
    }

    public Kb add(List<String[]> r) {
        rows.add(r);
        return this;
    }

    /** [[{"text","data"}…]…] — "[]" for none. */
    public static String json(Kb kb) {
        return MiniJson.write(list(kb));
    }

    public static List<Object> list(Kb kb) {
        List<Object> out = new ArrayList<Object>();
        if (kb == null) return out;
        for (List<String[]> r : kb.rows) {
            List<Object> row = new ArrayList<Object>();
            for (String[] b : r) row.add(btn(b[0], b[1]));
            out.add(row);
        }
        return out;
    }

    public static Map<String, Object> btn(String text, String data) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("text", text);
        m.put("data", data);
        return m;
    }
}
