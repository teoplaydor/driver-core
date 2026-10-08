import static org.junit.Assert.*;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
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

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import io.github.teoplaydor.semsearch.app.Engine;
import io.github.teoplaydor.semsearch.app.IndexStore;
import io.github.teoplaydor.semsearch.app.MainActivity;
import io.github.teoplaydor.semsearch.core.FaceFinder;
import io.github.teoplaydor.semsearch.core.FaceModel;

/**
 * People and pets through the app: after indexing, faces are looked for in every photo (a stand-in for the face models
 * reads who is on a photo from its colour); the unnamed come grouped in «Альбомы»; the faces are right on the photo in
 * the viewer, and one tapped gets a name — every photo of that person is in their album, a photo of two people in
 * both, and the viewer lists all the albums a photo is in; a face nobody needs to name is hidden with the button
 * beside «Кто это?»; someone unnamed gets a name from the line above their photos; a lookalike taken for a person
 * goes once one of their faces is said not to be them; «Назад» from an album opened in the viewer comes back to the
 * viewer; a pet marked on one photo gathers its photos, and a photo taken out of it leaves with the ones like it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "ru-w411dp-h891dp-night-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PeopleAppTest {
    /** Who is on each photo (by its code): A, B, A and B together, C, D, E (A's lookalike), nobody. */
    static final String[] WHO = {"A", "A", "A", "A", "A", "A", "B", "B", "B", "B", "AB", "AB", "AB", "C", "C", "C", "D", "D", "D",
            "E", "E", "E", "", "", "", "", "", "", "", ""};

    static File png(File dir, int code) throws Exception {
        Bitmap b = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888);
        new Canvas(b).drawColor(Color.rgb(code * 8, 90, 160));
        File f = new File(dir, "p" + code + ".png");
        try (FileOutputStream o = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
        }
        return f;
    }

    static float[] unit(float[] v) {
        double n = 0;
        for (float x : v) n += x * x;
        for (int i = 0; i < v.length; i++) v[i] /= Math.sqrt(n);
        return v;
    }

    /** Faces as the photo's code says: one direction per person (E near A), a little noise per photo. */
    static final class Faces implements FaceFinder {
        final float[][] who = new float[5][];
        int calls;

        Faces() {
            Random r = new Random(9);
            for (int k = 0; k < who.length; k++) {
                who[k] = new float[128];
                for (int i = 0; i < 128; i++) who[k][i] = (float) r.nextGaussian();
                unit(who[k]);
            }
        }

        float[] person(char c, Random noise) {
            float[] base = new float[128];
            if (c == 'E') { // A's lookalike: some of A, some of their own
                for (int i = 0; i < 128; i++) base[i] = 0.6f * who[0][i] + 0.8f * who[4][i];
            } else {
                System.arraycopy(who[c - 'A'], 0, base, 0, 128);
            }
            for (int i = 0; i < 128; i++) base[i] += (float) (0.15 * noise.nextGaussian() / Math.sqrt(128));
            return unit(base);
        }

        @Override
        public synchronized List<FaceModel.Face> faces(int[] argb, int w, int h, int minSize) {
            calls++;
            int code = Math.round(((argb[0] >> 16) & 0xFF) / 8f);
            String people = code < WHO.length ? WHO[code] : "";
            List<FaceModel.Face> out = new ArrayList<FaceModel.Face>();
            for (int j = 0; j < people.length(); j++) {
                FaceModel.Face f = new FaceModel.Face();
                f.x = w * (0.1f + 0.45f * j);
                f.y = h * 0.2f;
                f.w = w * 0.35f;
                f.h = h * 0.45f;
                f.score = 0.95f;
                f.emb = person(people.charAt(j), new Random(code * 31 + j));
                out.add(f);
            }
            return out;
        }

        @Override
        public void close() {
        }
    }

    static View rowOf(View root, String title) {
        TextView t = Robo.textView(root, title);
        assertNotNull("«" + title + "» in " + Robo.allText(root), t);
        return (View) t.getParent().getParent();
    }

    static View topSheet(View root) {
        return Robo.byName(root, "Sheet");
    }

    /** Types a name into the open name sheet and confirms. */
    static void typeName(View root, String name) throws Exception {
        Robo.settle(400);
        View s = topSheet(root);
        EditText t = null;
        for (View v : Robo.views(s, new ArrayList<View>())) if (v instanceof EditText) t = (EditText) v;
        assertNotNull("a name field in " + Robo.allText(s), t);
        t.setText(name);
        Robo.textView(s, "Готово").performClick();
        Robo.settle(500);
    }

    @SuppressWarnings("unchecked")
    static List<IndexStore.Item> grid(MainActivity a) throws Exception {
        return (List<IndexStore.Item>) Robo.call(Robo.field(a, "gallery"), "items");
    }

    static String section(MainActivity a) throws Exception {
        return String.valueOf(((TextView) Robo.field(a, "section")).getText());
    }

    static List<Engine.Album> people(Engine e) throws Exception {
        final List<Engine.Album>[] r = new List[1];
        e.people((x, err) -> r[0] = x);
        Robo.waitFor("people", () -> r[0] != null);
        return r[0];
    }

    static Engine.Album album(List<Engine.Album> all, String name) {
        for (Engine.Album a : all) if (a.name.equals(name)) return a;
        return null;
    }

    static Set<String> names(List<IndexStore.Item> items) {
        Set<String> s = new HashSet<String>();
        for (IndexStore.Item it : items) s.add(it.title);
        return s;
    }

    static Set<String> photosOf(String who) {
        Set<String> s = new HashSet<String>();
        for (int i = 0; i < WHO.length; i++) if (WHO[i].contains(who)) s.add("IMG_" + i + ".png");
        return s;
    }

    /** Opens the viewer on the grid's photo with this title; its faces (and «Отметить») shown on it. */
    static View viewer(MainActivity a, View root, String title) throws Exception {
        int at = -1;
        List<IndexStore.Item> g = grid(a);
        for (int i = 0; i < g.size(); i++) if (title.equals(g.get(i).title)) at = i;
        assertTrue(title + " in the grid", at >= 0);
        Robo.call(a, "openViewer", at);
        Robo.settle(700);
        final View viewer = Robo.byName(root, "Viewer");
        Robo.waitFor("faces on the photo", () -> Robo.textView(faces(viewer), "Отметить") != null);
        Robo.settle(300);
        return viewer;
    }

    static View faces(View viewer) throws Exception {
        return (View) Robo.field(viewer, "facesRow");
    }

    /** The names on the photo's faces, left to right. */
    static List<String> faceNames(View viewer) throws Exception {
        ViewGroup row = (ViewGroup) faces(viewer);
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < row.getChildCount() - 1; i++) {
            for (View v : Robo.views(row.getChildAt(i), new ArrayList<View>())) if (v instanceof TextView) out.add(String.valueOf(((TextView) v).getText()));
        }
        return out;
    }

    static void tapFace(View viewer, String name) throws Exception {
        TextView t = Robo.textView(faces(viewer), name);
        assertNotNull(name + " on the photo: " + faceNames(viewer), t);
        ((View) t.getParent()).performClick();
        Robo.settle(500);
    }

    @Test
    public void peopleAndPets() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        File dir = new File(app.getCacheDir(), "media");
        assertTrue(dir.mkdirs());
        for (int i = 0; i < WHO.length; i++) {
            FakeMediaStore.ROWS.add(new FakeMediaStore.Row(100 + i, false, 1700000000L - i, "IMG_" + i + ".png", 400, 300, png(dir, i)));
        }
        FakeMediaStore.install();
        Shadows.shadowOf(app).grantPermissions("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO",
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED");
        Faces faces = new Faces();
        Engine.facesForTest = faces;
        try {
            final MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
            final Engine e = Engine.get(a);
            Robo.waitFor("store", () -> e.store() != null);
            e.prefs().edit().putInt("photo_model", 0).putInt("photo_detail", 0).apply();
            File model = new File(a.getFilesDir(), "model");
            assertTrue(model.mkdirs());
            java.nio.file.Files.write(new File(model, "manifest.json").toPath(), "{\"repo\":\"test\",\"files\":[]}".getBytes("UTF-8"));
            // what the photos show to the photo model: cats on a sofa and in a garden, dogs, the rest
            e.attachModelForTest(new Robo.FakeEmbedder("cat sofa", "dog park", "beach sea", "cat garden", "city night", "food plate"));
            Robo.waitFor("ready", e::ready);
            e.startIndex(1000, 0);
            Robo.waitFor("indexed", () -> !e.indexing && e.idxTotal > 0);
            Robo.waitFor("faces looked for", () -> !e.faceScanning && e.photosScanned() == WHO.length);
            a.onEngineChanged();
            Robo.settle(800);
            View root = a.getWindow().getDecorView();
            System.out.println("faces: " + e.facesFound() + " on " + e.photosScanned() + " photos, " + faces.calls + " looked at");
            assertEquals(WHO.length, faces.calls);
            int expectFaces = 0;
            for (String w : WHO) expectFaces += w.length();
            assertEquals(expectFaces, e.facesFound());

            // nobody named yet: the unnamed, grouped — A (9 photos), B (7), C, D (3 each); E goes with A (their lookalike)
            List<Engine.Album> all = people(e);
            StringBuilder sb = new StringBuilder();
            for (Engine.Album al : all) sb.append(' ').append(al.name).append('×').append(al.items.size());
            System.out.println("people:" + sb);
            assertTrue(sb.toString(), all.size() >= 4);
            for (Engine.Album al : all) assertEquals(Engine.Album.UNNAMED, al.kind);

            // the faces on a photo of A in the viewer: the one tapped gets the name «Маша»
            View v = viewer(a, root, "IMG_0.png");
            assertEquals(Arrays.asList("Кто это?"), faceNames(v));
            UiShots.shot(a, "07f-viewer-faces");
            tapFace(v, "Кто это?");
            rowOf(topSheet(root), "Новый человек…").performClick();
            typeName(root, "Маша");
            final View v0 = v;
            Robo.waitFor("named on the photo", () -> faceNames(v0).contains("Маша"));
            a.onBackPressed();
            Robo.settle(600);
            assertNull(Robo.byName(root, "Viewer"));

            all = people(e);
            Engine.Album masha = album(all, "Маша");
            assertNotNull(masha);
            System.out.println("Маша: " + new java.util.TreeSet<String>(names(masha.items)));
            Set<String> withLook = photosOf("A");
            withLook.addAll(photosOf("E"));
            assertTrue("all of A's photos (" + names(masha.items) + ")", names(masha.items).containsAll(photosOf("A")));
            assertTrue("no one else's but the lookalike's", withLook.containsAll(names(masha.items)));

            // the lookalike: one of their faces is not Маша — all of theirs go
            v = viewer(a, root, "IMG_19.png");
            System.out.println("the lookalike's photo: " + faceNames(v));
            if (faceNames(v).contains("Маша")) {
                tapFace(v, "Маша");
                rowOf(topSheet(root), "Это не Маша").performClick();
                final View v19 = v;
                Robo.waitFor("not Маша", () -> faceNames(v19).contains("Кто это?"));
            }
            a.onBackPressed();
            Robo.settle(600);
            masha = album(people(e), "Маша");
            System.out.println("Маша, after «это не Маша» on the lookalike: " + masha.items.size() + " photos");
            assertEquals(photosOf("A"), names(masha.items));

            // a face nobody needs to name: hidden with the button beside «Кто это?» — C, in three photos, is no longer offered
            int unnamedBefore = 0;
            for (Engine.Album al : people(e)) if (al.kind == Engine.Album.UNNAMED) unnamedBefore++;
            v = viewer(a, root, "IMG_13.png");
            View hide = null;
            for (View x : Robo.views(faces(v), new ArrayList<View>())) if ("Скрыть лицо".contentEquals(String.valueOf(x.getContentDescription()))) hide = x;
            assertNotNull("a hide button beside «Кто это?»", hide);
            hide.performClick();
            final View v13 = v;
            Robo.waitFor("the face hidden", () -> faceNames(v13).isEmpty());
            a.onBackPressed();
            Robo.settle(600);
            int unnamedAfter = 0;
            for (Engine.Album al : people(e)) if (al.kind == Engine.Album.UNNAMED) unnamedAfter++;
            System.out.println("unnamed: " + unnamedBefore + " → " + unnamedAfter + " after hiding one of C's faces");
            assertEquals(unnamedBefore - 1, unnamedAfter);
            assertEquals(1, e.facesHidden());

            // the albums sheet: Маша, the unnamed; B opened and named «Петя» from the line above the grid
            Robo.call(a, "showAlbums");
            Robo.waitFor("albums sheet", () -> Robo.textView(topSheet(root), "Маша") != null);
            Robo.settle(400);
            UiShots.shot(a, "07g-albums-people");
            System.out.println("albums sheet: " + Robo.allText(topSheet(root)).replace('\n', ' '));
            a.onBackPressed();
            Robo.settle(500);
            Engine.Album b = null;
            for (Engine.Album al : people(e)) if (al.kind == Engine.Album.UNNAMED && names(al.items).equals(photosOf("B"))) b = al;
            assertNotNull("B among the unnamed", b);
            Robo.call(a, "showAlbum", b);
            Robo.settle(700);
            System.out.println("someone unnamed: " + section(a));
            assertEquals("Кто это? · 7 · назвать ›", section(a));
            ((View) Robo.field(a, "section")).performClick();
            typeName(root, "Петя");
            Robo.waitFor("Петя shown", () -> section(a).startsWith("Петя"));
            assertEquals("Петя · 7", section(a));
            Engine.Album petya = album(people(e), "Петя");
            assertEquals(photosOf("B"), names(petya.items));
            // a photo of both: in both albums, and the viewer lists them
            assertTrue(names(album(people(e), "Маша").items).contains("IMG_10.png") && names(petya.items).contains("IMG_10.png"));
            v = viewer(a, root, "IMG_10.png");
            assertEquals(Arrays.asList("Маша", "Петя"), faceNames(v));
            final View viewer = v;
            ((View) Robo.textView((View) Robo.field(viewer, "actions"), "Что на фото").getParent()).performClick();
            Robo.waitFor("albums of the photo", () -> ((ViewGroup) Robo.field(viewer, "albumsRow")).getChildCount() >= 2);
            Robo.settle(300);
            List<String> chips = new ArrayList<String>();
            ViewGroup row = (ViewGroup) Robo.field(viewer, "albumsRow");
            for (int i = 0; i < row.getChildCount(); i++) chips.add(String.valueOf(((TextView) row.getChildAt(i)).getText()));
            System.out.println("albums of a photo of both: " + chips);
            UiShots.shot(a, "07h-viewer-albums");
            assertTrue(chips.toString(), chips.contains("Маша") && chips.contains("Петя"));
            // a chip opens its album; «Назад» — the viewer again, on the same photo, over Петя's photos
            row.getChildAt(chips.indexOf("Маша")).performClick();
            Robo.settle(800);
            assertNull(Robo.byName(root, "Viewer"));
            assertEquals("Маша · " + photosOf("A").size(), section(a));
            a.onBackPressed();
            Robo.settle(900);
            View again = Robo.byName(root, "Viewer");
            assertNotNull("«Назад» from the album: the viewer it was opened from", again);
            assertEquals("IMG_10.png", ((IndexStore.Item) Robo.call(again, "current")).title);
            assertEquals("Петя · 7", section(a));
            a.onBackPressed();
            Robo.settle(600);
            a.onBackPressed();
            Robo.settle(600);
            assertNull("then the gallery", Robo.field(a, "resultsLabel"));

            // a pet: one photo of a cat on a sofa marked «Барсик» — its cats come; a cat in the garden taken out goes with
            // the other garden cats
            String sofa = null, garden = null;
            Set<String> sofas = new HashSet<String>(), gardens = new HashSet<String>();
            for (IndexStore.Item it : grid(a)) {
                if (Arrays.equals(it.emb, Robo.bag("cat sofa"))) sofas.add(it.title);
                if (Arrays.equals(it.emb, Robo.bag("cat garden"))) gardens.add(it.title);
            }
            sofa = sofas.iterator().next();
            garden = gardens.iterator().next();
            v = viewer(a, root, sofa);
            ((View) Robo.textView(faces(v), "Отметить").getParent()).performClick();
            Robo.settle(500);
            rowOf(topSheet(root), "Отметить питомца или что-то ещё").performClick();
            Robo.settle(500);
            rowOf(topSheet(root), "Новое…").performClick();
            typeName(root, "Барсик");
            Robo.settle(600);
            a.onBackPressed();
            Robo.settle(600);
            Engine.Album cat = album(people(e), "Барсик");
            assertNotNull(cat);
            System.out.println("Барсик: " + cat.items.size() + " photos, " + cat.more.size() + " less sure");
            Set<String> cats = new HashSet<String>(sofas);
            cats.addAll(gardens);
            assertEquals(cats, names(cat.items));
            assertEquals(sofa, cat.items.get(0).title);
            v = viewer(a, root, garden);
            ((View) Robo.textView(faces(v), "Отметить").getParent()).performClick();
            Robo.waitFor("its albums", () -> Robo.textView(topSheet(root), "Убрать из «Барсик»") != null);
            rowOf(topSheet(root), "Убрать из «Барсик»").performClick();
            Robo.settle(600);
            a.onBackPressed();
            Robo.settle(600);
            cat = album(people(e), "Барсик");
            System.out.println("Барсик, after taking a garden cat out: " + names(cat.items));
            assertEquals(sofas, names(cat.items));

            // the settings card
            Robo.call(a, "openSettings");
            Robo.settle(600);
            Object settings = Robo.byName(root, "SettingsPanel");
            String note = String.valueOf(((TextView) Robo.field(settings, "facesNote")).getText());
            System.out.println("settings: " + ((TextView) Robo.field(settings, "facesValue")).getText() + " · " + note);
            assertEquals("Найдено лиц: " + expectFaces + " на " + WHO.length + " фото", note);
            assertEquals("1 · вернуть", String.valueOf(((TextView) Robo.field(settings, "hiddenFacesValue")).getText()));
            a.finish();
        } finally {
            Engine.facesForTest = null;
        }
    }
}
