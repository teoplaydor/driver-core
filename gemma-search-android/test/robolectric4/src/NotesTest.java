import static org.junit.Assert.*;

import android.app.AlarmManager;
import android.app.Application;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlarmManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import io.github.teoplaydor.semsearch.app.Engine;
import io.github.teoplaydor.semsearch.app.IndexStore;
import io.github.teoplaydor.semsearch.app.MainActivity;

/**
 * Notes: written in their editor (the rail's «+»), a list's items given boxes and carried on with Enter (an empty item
 * ends the list), changed and kept again (what they mean worked out anew), emptied — deleted; in the viewer the boxes
 * ticked with a tap; pinned — first among the notes, a pin on its card; a reminder — an alarm, then a notification
 * that opens the note; text shared «В заметки» and the app icon's shortcut start a note; a note made to a photo shows
 * with it; a note's exact word (a code) finds it first.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "ru-w411dp-h891dp-night-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NotesTest {
    static Object editor(MainActivity a) throws Exception {
        return Robo.field(a, "noteEditor");
    }

    static EditText text(MainActivity a) throws Exception {
        return (EditText) Robo.field(editor(a), "text");
    }

    /** Types into the editor where its cursor is, as a keyboard would (the editor's watcher sees each insert). */
    static void type(EditText t, String s) {
        for (String part : s.split("(?<=\n)|(?=\n)")) {
            int at = t.getSelectionStart();
            t.getText().insert(at, part);
        }
    }

    static IndexStore.Item noteWith(Engine e, String start) {
        for (IndexStore.Item n : e.store().notes()) if (n.body.startsWith(start)) return n;
        return null;
    }

    @Test
    public void notes() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        File dir = new File(app.getCacheDir(), "media");
        assertTrue(dir.mkdirs());
        File pic = ScanTest.photo(dir);
        for (int i = 0; i < 4; i++) FakeMediaStore.ROWS.add(new FakeMediaStore.Row(300 + i, false, 1700000000L - i, "IMG_" + i + ".png", 1200, 900, pic));
        FakeMediaStore.install();
        Shadows.shadowOf(app).grantPermissions("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO",
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED", "android.permission.POST_NOTIFICATIONS");
        final MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        final Engine e = Engine.get(a);
        Robo.waitFor("store", () -> e.store() != null);
        e.prefs().edit().putInt("photo_model", 0).putInt("photo_detail", 0).apply();
        File model = new File(a.getFilesDir(), "model");
        assertTrue(model.mkdirs());
        java.nio.file.Files.write(new File(model, "manifest.json").toPath(), "{\"repo\":\"test\",\"files\":[]}".getBytes("UTF-8"));
        e.attachModelForTest(new Robo.FakeEmbedder("paper document text", "dog", "beach", "cat"));
        Robo.waitFor("ready", e::ready);
        e.startIndex(1000, 0);
        Robo.waitFor("indexed", () -> !e.indexing && e.idxTotal > 0);
        a.onEngineChanged();
        Robo.settle(600);
        final View root = a.getWindow().getDecorView();

        // the rail's «+»: the editor; a heading, then a list — «Список» gives the line a box, Enter carries it on,
        // Enter on an empty item ends the list
        Robo.call(a, "noteEditor");
        Robo.settle(500);
        assertNotNull(Robo.byName(root, "NoteEditor"));
        EditText t = text(a);
        type(t, "Купить к ужину\n");
        Robo.call(editor(a), "toggleList");
        type(t, "молоко\nхлеб\n\n");
        type(t, "потом позвонить маме");
        System.out.println("typed: " + t.getText().toString().replace("\n", " | "));
        assertEquals("Купить к ужину\n☐ молоко\n☐ хлеб\nпотом позвонить маме", t.getText().toString());
        UiShots.shot(a, "16a-note-editor");
        a.onBackPressed();
        Robo.waitFor("kept", () -> noteWith(e, "Купить к ужину") != null);
        final IndexStore.Item list = noteWith(e, "Купить к ужину");
        Robo.waitFor("its meaning", () -> list.emb.length == 768);
        Robo.settle(500);
        assertNull("the editor closed", Robo.byName(root, "NoteEditor"));

        // in the viewer: the items with boxes; a tap ticks one
        Robo.call(a, "selectFilter", 3);
        Robo.settle(500);
        List<IndexStore.Item> grid = ScanTest.grid(a);
        Robo.call(a, "openViewer", grid.indexOf(list));
        Robo.settle(800);
        View viewer = Robo.byName(root, "Viewer");
        assertNotNull(viewer);
        View milk = null;
        for (View v : Robo.views(viewer, new ArrayList<View>())) if ("Сделать: молоко".contentEquals(String.valueOf(v.getContentDescription()))) milk = v;
        assertNotNull("an item to tick: " + Robo.allText(viewer), milk);
        float[] before = list.emb.clone();
        milk.performClick();
        Robo.settle(400);
        assertEquals("Купить к ужину\n☑ молоко\n☐ хлеб\nпотом позвонить маме", list.body);
        assertArrayEquals("ticking off changes nothing of what it means", before, list.emb, 0f);
        UiShots.shot(a, "16b-note-ticked");
        Robo.waitFor("ticked in the index", () -> {
            android.database.sqlite.SQLiteDatabase d = android.database.sqlite.SQLiteDatabase.openDatabase(
                    a.getDatabasePath("index.db").getPath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
            try (android.database.Cursor c = d.rawQuery("SELECT body FROM items WHERE id = ?", new String[]{String.valueOf(list.id)})) {
                return c.moveToFirst() && c.getString(0).contains("☑ молоко");
            } finally {
                d.close();
            }
        });

        // pinned: first among the notes, a pin on its card
        e.addNote("Код домофона у Саши: 47К1290", null);
        Robo.waitFor("second note", () -> noteWith(e, "Код домофона") != null);
        final IndexStore.Item code = noteWith(e, "Код домофона");
        assertEquals("the newest first", code, e.store().notes().get(0));
        Robo.call(a, "setPinned", list, true);
        Robo.settle(400);
        assertEquals("pinned: first", list, e.store().notes().get(0));
        a.onBackPressed();
        Robo.settle(600);
        Robo.call(a, "selectFilter", 3);
        Robo.settle(500);
        assertEquals("first in the notes tab too", list, ScanTest.grid(a).get(0));
        boolean pinShown = false;
        for (View v : Robo.views(Robo.field(a, "gallery") instanceof View ? (View) Robo.field(a, "gallery") : root, new ArrayList<View>())) {
            if (v.getClass().getSimpleName().equals("Tile")) {
                Object tileIndex = Robo.field(v, "index");
                Object badge = Robo.field(v, "badge");
                if (Integer.valueOf(0).equals(tileIndex) && ((View) badge).getVisibility() == View.VISIBLE) pinShown = true;
            }
        }
        assertTrue("a pin on the pinned card", pinShown);
        UiShots.shot(a, "16c-notes-pinned");

        // changed in the editor: kept, its meaning anew; emptied: deleted
        Robo.call(a, "openNoteEditor", code, null, null);
        Robo.settle(400);
        text(a).setText("Код домофона у Саши: 47К1290, подъезд 3");
        float[] old = code.emb.clone();
        a.onBackPressed();
        Robo.waitFor("changed", () -> code.body.endsWith("подъезд 3") && code.edited > 0 && !java.util.Arrays.equals(old, code.emb));

        // a reminder: an alarm at the time; when it goes off, a notification that opens the note
        long at = System.currentTimeMillis() + 3_600_000L;
        e.setReminder(code, at);
        Robo.settle(300);
        ShadowAlarmManager alarms = Shadows.shadowOf((AlarmManager) a.getSystemService(Context.ALARM_SERVICE));
        Robo.waitFor("the alarm", () -> alarms.peekNextScheduledAlarm() != null);
        assertEquals(at, alarms.peekNextScheduledAlarm().triggerAtTime);
        Intent fired = Shadows.shadowOf(alarms.peekNextScheduledAlarm().operation).getSavedIntent();
        Object receiver = Class.forName("io.github.teoplaydor.semsearch.app.Reminders").getDeclaredConstructor().newInstance();
        ((android.content.BroadcastReceiver) receiver).onReceive(a, fired);
        Robo.waitFor("reminded: none set any more", () -> code.remind == 0);
        NotificationManager nm = (NotificationManager) a.getSystemService(Context.NOTIFICATION_SERVICE);
        assertEquals(1, Shadows.shadowOf(nm).getAllNotifications().size());
        android.app.Notification n = Shadows.shadowOf(nm).getAllNotifications().get(0);
        assertEquals("Код домофона у Саши: 47К1290, подъезд 3", String.valueOf(n.extras.getCharSequence("android.title")));
        Intent tap = Shadows.shadowOf(n.contentIntent).getSavedIntent();
        Robo.call(a, "onNewIntent", tap);
        Robo.settle(800);
        View shownNote = Robo.byName(root, "Viewer");
        assertNotNull("the reminder opens the note", shownNote);
        assertTrue(Robo.allText(shownNote), Robo.allText(shownNote).contains("подъезд 3"));
        a.onBackPressed();
        Robo.settle(600);

        // its exact word finds it first (a code the meaning of a query will not)
        final Object[] found = new Object[1];
        e.search("47к1290", true, true, true, (r, err) -> found[0] = err != null ? err : r);
        Robo.waitFor("searched", () -> found[0] != null);
        Engine.SearchResult r = (Engine.SearchResult) found[0];
        assertEquals("the note with the code first", code, r.hits.get(0).item);

        // text shared «В заметки» while a draft is open: the draft kept, a new note with the text
        Robo.call(a, "noteEditor");
        Robo.settle(400);
        type(text(a), "черновик письма");
        Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Адрес: ул. Примерная, 1")
                .putExtra(Intent.EXTRA_SUBJECT, "Встреча в субботу").setComponent(new ComponentName(a, "io.github.teoplaydor.semsearch.app.ToNotes"));
        Robo.call(a, "onNewIntent", share);
        Robo.settle(500);
        Robo.waitFor("the draft kept", () -> noteWith(e, "черновик письма") != null);
        assertEquals("Встреча в субботу\nАдрес: ул. Примерная, 1", text(a).getText().toString());
        Robo.textView(root, "Готово").performClick();
        Robo.waitFor("shared kept", () -> noteWith(e, "Встреча в субботу") != null);
        Robo.settle(400);

        // the app icon's shortcut: a new note
        Robo.call(a, "onNewIntent", new Intent("io.github.teoplaydor.semsearch.NEW_NOTE"));
        Robo.settle(400);
        assertNotNull("the shortcut opens a new note", editor(a));
        assertEquals("", text(a).getText().toString());
        a.onBackPressed();
        Robo.settle(500);
        int notesNow = e.store().count(IndexStore.KIND_NOTE);
        assertEquals("left empty: not kept", 4, notesNow);

        // a note to a photo: shows with it — in the photo's viewer and the note's
        Robo.call(a, "selectFilter", 1);
        Robo.settle(500);
        final IndexStore.Item photo = ScanTest.grid(a).get(0);
        Robo.call(a, "openViewer", 0);
        Robo.settle(800);
        a.noteToPhoto(photo);
        Robo.settle(400);
        assertTrue(Robo.allText(root).contains("Заметка к фото"));
        text(a).setText("Это чек за ремонт — гарантия до мая");
        UiShots.shot(a, "16d-note-to-photo");
        a.onBackPressed();
        Robo.waitFor("to the photo", () -> e.store().notesOf(photo.mediaId).size() == 1);
        Robo.settle(500);
        assertTrue("the photo's viewer counts it: " + Robo.allText(root), Robo.allText(root).contains("Заметки · 1"));
        IndexStore.Item toPhoto = e.store().notesOf(photo.mediaId).get(0);
        assertEquals(photo, a.linkedPhoto(toPhoto));
        a.onBackPressed();
        Robo.settle(600);

        // emptied: deleted
        Robo.call(a, "openNoteEditor", toPhoto, null, null);
        Robo.settle(400);
        text(a).setText("");
        a.onBackPressed();
        Robo.waitFor("emptied, deleted", () -> e.store().notesOf(photo.mediaId).isEmpty());
        a.finish();
    }
}
