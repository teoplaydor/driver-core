import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import org.robolectric.Robolectric;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MediaStore stand-in for the "media" authority: images and videos as rows (id, date, name, size, album)
 * with an optional file behind each, served fresh to every query like the real provider.
 */
public final class FakeMediaStore extends ContentProvider {
    public static final class Row {
        final long id;
        final boolean video;
        final long dateAdded;
        final String name;
        final int width, height;
        final File file;
        final String bucket;

        public Row(long id, boolean video, long dateAdded, String name, int width, int height, File file) {
            this(id, video, dateAdded, name, width, height, file, "Camera");
        }

        public Row(long id, boolean video, long dateAdded, String name, int width, int height, File file, String bucket) {
            this.bucket = bucket;
            this.id = id;
            this.video = video;
            this.dateAdded = dateAdded;
            this.name = name;
            this.width = width;
            this.height = height;
            this.file = file;
        }
    }

    public static final List<Row> ROWS = new CopyOnWriteArrayList<Row>();
    public static volatile int queries;

    public static void install() {
        Robolectric.buildContentProvider(FakeMediaStore.class).create("media");
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private static Long idOf(Uri uri) {
        String last = uri.getLastPathSegment();
        if (last != null && last.matches("\\d+")) return Long.parseLong(last);
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] proj, String sel, String[] args, String sort) {
        queries++;
        boolean video = uri.getPath().contains("/video/");
        Long only = idOf(uri);
        List<Row> rows = new ArrayList<Row>();
        for (Row r : ROWS) if (r.video == video && (only == null || r.id == only)) rows.add(r);
        Collections.sort(rows, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                return Long.compare(b.dateAdded, a.dateAdded);
            }
        });
        MatrixCursor c = new MatrixCursor(proj);
        for (Row r : rows) {
            Object[] row = new Object[proj.length];
            for (int k = 0; k < proj.length; k++) {
                switch (proj[k]) {
                    case "_id": row[k] = r.id; break;
                    case "date_added": row[k] = r.dateAdded; break;
                    case "_display_name": row[k] = r.name; break;
                    case "width": row[k] = r.width; break;
                    case "height": row[k] = r.height; break;
                    case "orientation": row[k] = 0; break;
                    case "bucket_display_name": row[k] = r.bucket; break;
                    default: row[k] = null;
                }
            }
            c.addRow(row);
        }
        return c;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Long id = idOf(uri);
        for (Row r : ROWS) {
            if (id != null && r.id == id && r.file != null) return ParcelFileDescriptor.open(r.file, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        throw new FileNotFoundException(String.valueOf(uri));
    }

    @Override
    public String getType(Uri uri) {
        return uri.getPath().contains("/video/") ? "video/mp4" : "image/png";
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
