import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import org.robolectric.Robolectric;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * A documents provider as a folder picked in Android's file picker gives it: a tree of a directory on disk, its
 * children listed by DocumentsContract's tree addresses (document id = the path under the root), files opened by them.
 */
public final class FakeDocs extends ContentProvider {
    static final String AUTHORITY = "io.github.teoplaydor.test.docs";
    static volatile File root;

    static void install(File dir) {
        root = dir;
        Robolectric.buildContentProvider(FakeDocs.class).create(AUTHORITY);
    }

    /** The tree address of the root folder (what ACTION_OPEN_DOCUMENT_TREE returns). */
    static Uri tree() {
        return DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root");
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private static File fileOf(String docId) {
        return docId.equals("root") ? root : new File(root, docId.substring("root/".length()));
    }

    @Override
    public Cursor query(Uri uri, String[] proj, String sel, String[] args, String sort) {
        List<String> seg = uri.getPathSegments(); // tree/<tree>/document/<doc>[/children]
        String doc = seg.size() >= 4 ? seg.get(3) : seg.get(1);
        MatrixCursor c = new MatrixCursor(proj);
        File dir = fileOf(doc);
        File[] list = seg.get(seg.size() - 1).equals("children") ? dir.listFiles() : new File[]{dir};
        if (list == null) return c;
        for (File f : list) {
            String id = f.equals(root) ? "root" : "root/" + root.toURI().relativize(f.toURI()).getPath().replaceAll("/$", "");
            Object[] row = new Object[proj.length];
            for (int k = 0; k < proj.length; k++) {
                switch (proj[k]) {
                    case "document_id": row[k] = id; break;
                    case "_display_name": row[k] = f.getName(); break;
                    case "mime_type": row[k] = f.isDirectory() ? "vnd.android.document/directory" : mime(f.getName()); break;
                    case "last_modified": row[k] = f.lastModified(); break;
                    case "_size": row[k] = f.length(); break;
                    default: row[k] = null;
                }
            }
            c.addRow(row);
        }
        return c;
    }

    static String mime(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".txt")) return "text/plain";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (n.endsWith(".html")) return "text/html";
        if (n.endsWith(".wav")) return "audio/x-wav";
        if (n.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        List<String> seg = uri.getPathSegments();
        File f = fileOf(seg.get(seg.size() - 1));
        if (!f.isFile()) throw new FileNotFoundException(String.valueOf(uri));
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        List<String> seg = uri.getPathSegments();
        return mime(seg.get(seg.size() - 1));
    }

    @Override
    public Uri insert(Uri uri, ContentValues v) {
        return null;
    }

    @Override
    public int delete(Uri uri, String s, String[] a) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] a) {
        return 0;
    }
}
