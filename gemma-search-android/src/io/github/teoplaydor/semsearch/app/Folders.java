package io.github.teoplaydor.semsearch.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.teoplaydor.semsearch.core.TextExtract;

/**
 * The folders the user gave the app (Android's folder picker: the app reads those and nothing else, the permission
 * lasts): their documents (TextExtract's kinds) and sounds, every level down. A file's id is a hash of its address,
 * with a bit MediaStore's ids never have.
 */
final class Folders {
    static final String PREF = "doc_folders";
    /** How deep and how many files a scan goes (a folder of a whole disk stops there). */
    static final int MAX_DEPTH = 8, MAX_FILES = 20000;
    /** The bit of a file's id (MediaStore's ids are small numbers). */
    static final long FILE_BIT = 1L << 62;

    private Folders() {
    }

    /** The folders chosen (tree addresses), in the order chosen. */
    static List<Uri> chosen(SharedPreferences prefs) {
        List<Uri> out = new ArrayList<Uri>();
        String s = prefs.getString(PREF, "");
        for (String line : s.split("\n")) if (!line.trim().isEmpty()) out.add(Uri.parse(line.trim()));
        return out;
    }

    /** A folder picked: its permission kept for good, the folder remembered. */
    static void add(Context c, SharedPreferences prefs, Uri tree) {
        try {
            c.getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            // a provider that gives no lasting permission: it works while the app runs
        }
        List<Uri> all = chosen(prefs);
        if (!all.contains(tree)) all.add(tree);
        save(prefs, all);
    }

    static void remove(Context c, SharedPreferences prefs, Uri tree) {
        try {
            c.getContentResolver().releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            // never had one
        }
        List<Uri> all = chosen(prefs);
        all.remove(tree);
        save(prefs, all);
    }

    private static void save(SharedPreferences prefs, List<Uri> all) {
        StringBuilder b = new StringBuilder();
        for (Uri u : all) b.append(u).append('\n');
        prefs.edit().putString(PREF, b.toString()).apply();
    }

    /** "Download", "Documents/Работа": what the picker showed. */
    static String label(Uri tree) {
        String id;
        try {
            id = DocumentsContract.getTreeDocumentId(tree);
        } catch (IllegalArgumentException e) {
            return tree.getLastPathSegment();
        }
        int colon = id.indexOf(':');
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        if (path.isEmpty()) return colon > 0 && id.startsWith("primary") ? "Память телефона" : id;
        return path;
    }

    /** Sound files by name or type (a voice message, a recording, a song). */
    static boolean audio(String name, String mime) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (m.startsWith("audio/")) return true;
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (String e : new String[]{".mp3", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".wav", ".flac", ".amr", ".3gp", ".wma", ".mka"}) {
            if (n.endsWith(e)) return true;
        }
        return false;
    }

    /** A stable id of a file (its address hashed, with {@link #FILE_BIT}). */
    static long id(Uri doc) {
        String s = doc.toString();
        long h = 1125899906842597L;
        for (int i = 0; i < s.length(); i++) h = 31 * h + s.charAt(i);
        return FILE_BIT | (h & (FILE_BIT - 1));
    }

    /** Result of a scan: the files, and whether every folder was read (only then may what is missing be dropped). */
    static final class Scan {
        final List<Media.Entry> files = new ArrayList<Media.Entry>();
        boolean complete = true;
        final Set<Long> ids = new HashSet<Long>();
    }

    /**
     * Every document (and sound file, when {@code sound}) in the chosen folders, newest first. A folder that cannot
     * be read (its permission taken back, a card removed) makes the scan incomplete.
     */
    static Scan scan(Context c, SharedPreferences prefs, boolean sound) {
        Scan out = new Scan();
        ContentResolver cr = c.getContentResolver();
        for (Uri tree : chosen(prefs)) {
            try {
                String root = DocumentsContract.getTreeDocumentId(tree);
                walk(cr, tree, root, 0, sound, out);
            } catch (Exception e) {
                out.complete = false;
            }
        }
        java.util.Collections.sort(out.files, new java.util.Comparator<Media.Entry>() {
            @Override
            public int compare(Media.Entry a, Media.Entry b) {
                return Long.compare(b.date, a.date);
            }
        });
        return out;
    }

    private static void walk(ContentResolver cr, Uri tree, String doc, int depth, boolean sound, Scan out) {
        if (depth > MAX_DEPTH || out.files.size() >= MAX_FILES) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, doc);
        String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE};
        Cursor cur = cr.query(children, cols, null, null, null);
        if (cur == null) {
            out.complete = false;
            return;
        }
        List<String> dirs = new ArrayList<String>();
        try {
            while (cur.moveToNext()) {
                String id = cur.getString(0), name = cur.getString(1), mime = cur.getString(2);
                if (name != null && name.startsWith(".")) continue; // hidden files and folders
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    dirs.add(id);
                    continue;
                }
                boolean isSound = sound && audio(name, mime);
                if (!isSound && TextExtract.type(name, mime) == TextExtract.NONE) continue;
                Media.Entry e = new Media.Entry();
                e.kind = isSound ? IndexStore.KIND_AUDIO : IndexStore.KIND_FILE;
                e.uri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                e.id = id(e.uri);
                e.name = name;
                e.mime = mime;
                e.date = cur.isNull(3) ? 0 : cur.getLong(3);
                e.size = cur.isNull(4) ? -1 : cur.getLong(4);
                out.files.add(e);
                out.ids.add(e.id);
                if (out.files.size() >= MAX_FILES) break;
            }
        } finally {
            cur.close();
        }
        for (String d : dirs) walk(cr, tree, d, depth + 1, sound, out);
    }
}
