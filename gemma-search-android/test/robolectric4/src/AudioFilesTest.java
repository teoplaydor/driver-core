import android.app.Application;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.github.teoplaydor.semsearch.app.Engine;
import io.github.teoplaydor.semsearch.app.IndexStore;
import io.github.teoplaydor.semsearch.app.MainActivity;
import io.github.teoplaydor.semsearch.core.Pcm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Sound and documents in the app. Recordings from MediaStore and a WAV in a chosen folder: decoded, the silence at the
 * start skipped, heard by the model (a stand-in that hears pitch: low — a dog barking, high — rain), found by what is
 * heard; documents from a folder given through Android's picker (a documents provider, its tree): text, Word, HTML read,
 * a hidden file and a picture left out, found by their meaning and by their words (a name too); the «Файлы» and «Аудио»
 * filters show them as cards; the viewer shows a document's text and a sound's player; a changed document is read again,
 * a deleted one leaves the index, and so does a folder taken away.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "ru-w411dp-h891dp-night-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AudioFilesTest {
    /** The model's stand-in: sound by its pitch (zero crossings: low a dog, middle speech, high rain), the rest as words. */
    static final class Hearing extends Robo.FakeEmbedder {
        final List<Integer> heard = new ArrayList<Integer>();

        Hearing() {
            super("cat", "dog", "beach", "cat");
        }

        @Override
        public boolean supportsAudio() {
            return true;
        }

        @Override
        public synchronized float[] embedAudio(float[] pcm) {
            heard.add(pcm.length);
            int zc = 0;
            for (int i = 1; i < pcm.length; i++) if ((pcm[i - 1] < 0) != (pcm[i] < 0)) zc++;
            double hz = zc / 2.0 / (pcm.length / (double) Pcm.RATE);
            return Robo.bag(hz < 600 ? "собака лает dog barking" : hz < 2000 ? "человек говорит речь speech" : "дождь шумит rain");
        }
    }

    static File tone(File dir, String name, double hz, double silence, double seconds, int rate) throws Exception {
        float[] x = new float[(int) ((silence + seconds) * rate)];
        for (int i = (int) (silence * rate); i < x.length; i++) x[i] = (float) (0.4 * Math.sin(2 * Math.PI * hz * i / rate));
        File f = new File(dir, name);
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(Pcm.wav16(x, rate));
        }
        return f;
    }

    static void write(File f, String text) throws Exception {
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    static void docx(File f, String... paragraphs) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(b)) {
            z.putNextEntry(new ZipEntry("[Content_Types].xml"));
            z.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            z.putNextEntry(new ZipEntry("word/document.xml"));
            StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document><w:body>");
            for (String p : paragraphs) x.append("<w:p><w:r><w:t>").append(p).append("</w:t></w:r></w:p>");
            x.append("</w:body></w:document>");
            z.write(x.toString().getBytes(StandardCharsets.UTF_8));
        }
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(b.toByteArray());
        }
    }

    static IndexStore.Item item(Engine e, int kind, String titleStart) {
        for (IndexStore.Item it : e.store().items(kind)) if (it.title != null && it.title.startsWith(titleStart)) return it;
        return null;
    }

    static Engine.SearchResult search(Engine e, String q, int kinds) throws Exception {
        final Object[] r = new Object[1];
        e.search(q, kinds, (res, err) -> r[0] = err != null ? err : res);
        Robo.waitFor("search " + q, () -> r[0] != null);
        if (r[0] instanceof Exception) throw (Exception) r[0];
        return (Engine.SearchResult) r[0];
    }

    static void index(Engine e) throws Exception {
        Robo.waitFor("idle", () -> !e.indexing);
        e.startIndex(1000, 0);
        Robo.waitFor("indexed", () -> !e.indexing);
    }

    @Test
    public void soundAndDocuments() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        File media = new File(app.getCacheDir(), "media");
        assertTrue(media.mkdirs());
        File pic = ScanTest.photo(media);
        for (int i = 0; i < 2; i++) FakeMediaStore.ROWS.add(new FakeMediaStore.Row(300 + i, false, 1700000000L - i, "IMG_" + i + ".png", 1200, 900, pic));
        // a dog in the yard (low, half a second of silence first), rain on the roof (high), at the rates phones record
        FakeMediaStore.SOUNDS.add(new FakeMediaStore.Sound(501, 1700000100L, "Двор.wav", "Во дворе", null, 2500, "Recordings",
                tone(media, "dog.wav", 440, 0.5, 2.0, 44100)));
        FakeMediaStore.SOUNDS.add(new FakeMediaStore.Sound(502, 1700000050L, "Крыша.wav", "Крыша", "Диктофон", 3000, "Recordings",
                tone(media, "rain.wav", 3000, 0, 3.0, 48000)));
        FakeMediaStore.install();
        // a folder of documents (and a recording, a hidden note, a picture)
        File docs = new File(app.getCacheDir(), "docs");
        File sub = new File(docs, "Работа");
        assertTrue(sub.mkdirs());
        docx(new File(sub, "Договор поставки.docx"), "Договор поставки № 17", "Поставщик передаёт кофемашины покупателю.");
        write(new File(docs, "Рецепт.txt"), "Блины: мука, молоко, яйца. Жарить на сковороде.");
        write(new File(docs, "Отпуск.html"), "<html><body><h1>План отпуска</h1><p>Море, пляж, горы.</p><script>x()</script></body></html>");
        write(new File(docs, ".скрытое.txt"), "не для поиска");
        java.nio.file.Files.copy(pic.toPath(), new File(docs, "фото.png").toPath());
        tone(sub, "Голосовое.wav", 1000, 0, 1.5, 16000);
        FakeDocs.install(docs);

        Shadows.shadowOf(app).grantPermissions("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO",
                "android.permission.READ_MEDIA_AUDIO", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED");
        final MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        final Engine e = Engine.get(a);
        Robo.waitFor("store", () -> e.store() != null);
        e.prefs().edit().putInt("photo_model", 0).putInt("photo_detail", 0).apply();
        File model = new File(a.getFilesDir(), "model");
        assertTrue(model.mkdirs());
        java.nio.file.Files.write(new File(model, "manifest.json").toPath(), "{\"repo\":\"test\",\"files\":[]}".getBytes("UTF-8"));
        final Hearing m = new Hearing();
        e.attachModelForTest(m);
        Robo.waitFor("ready", e::ready);

        // before: the filters say what is missing — a folder; the sound part (here it is loaded, a stand-in)
        final View root0 = a.getWindow().getDecorView();
        Robo.call(a, "selectFilter", MainActivity.F_FILES);
        Robo.settle(600);
        assertTrue(Robo.allText(root0), Robo.allText(root0).contains("Документы — из папок, которые вы дадите")
                && Robo.allText(root0).contains("Выбрать папку"));
        UiShots.shot(a, "17-0-files-empty");
        Robo.call(a, "selectFilter", MainActivity.F_ALL);
        Robo.settle(400);

        // the folder given (as the picker returns it): indexed at once, with the phone's sounds
        e.addFolder(FakeDocs.tree());
        Robo.waitFor("indexing started", () -> e.indexing);
        Robo.waitFor("indexed", () -> !e.indexing);
        IndexStore s = e.store();
        Robo.waitFor("the photos too", () -> s.count(IndexStore.KIND_PHOTO) == 2 && !e.indexing);
        assertEquals("two recordings of the phone and one in the folder", 3, s.count(IndexStore.KIND_AUDIO));
        assertEquals("text, Word, HTML — not the hidden file, not the picture", 3, s.count(IndexStore.KIND_FILE));
        IndexStore.Item dog = item(e, IndexStore.KIND_AUDIO, "Во дворе"), rain = item(e, IndexStore.KIND_AUDIO, "Крыша");
        IndexStore.Item voice = item(e, IndexStore.KIND_AUDIO, "Голосовое");
        IndexStore.Item contract = item(e, IndexStore.KIND_FILE, "Договор поставки");
        assertNotNull(dog);
        assertNotNull(rain);
        assertNotNull(voice);
        assertNotNull(contract);
        assertEquals("0:02 · Recordings", dog.body);
        assertEquals("0:03 · Диктофон", rain.body);
        assertTrue("the Word document's text: " + contract.body, contract.body.contains("Поставщик передаёт кофемашины"));
        IndexStore.Item trip = item(e, IndexStore.KIND_FILE, "Отпуск");
        assertTrue("HTML without its script: " + trip.body, trip.body.contains("План отпуска") && !trip.body.contains("x()"));
        // what the model heard: 16 kHz, the half-second of silence skipped
        boolean skipped = false;
        for (int n : m.heard) skipped |= Math.abs(n - 2 * Pcm.RATE) < Pcm.RATE / 20;
        assertTrue("the dog's two seconds without the silence: " + m.heard, skipped);

        // found by what is heard, by meaning, by words
        assertEquals("a dog barking: the yard's recording", dog, search(e, "собака", IndexStore.AUDIO).hits.get(0).item);
        List<IndexStore.Hit> all = search(e, "собака", IndexStore.ALL).hits;
        assertTrue("among everything: the photo of a dog and the recording first", (all.get(0).item == dog || all.get(1).item == dog)
                && (all.get(0).item.kind == IndexStore.KIND_PHOTO || all.get(1).item.kind == IndexStore.KIND_PHOTO));
        assertEquals("rain: the roof", rain, search(e, "дождь", IndexStore.AUDIO).hits.get(0).item);
        assertEquals("the contract by its words", contract, search(e, "договор поставки", IndexStore.ALL).hits.get(0).item);
        assertEquals("by the start of a word in the text", contract, search(e, "кофемаш", IndexStore.FILES).hits.get(0).item);
        assertEquals("a recording by its name", voice, search(e, "голосовое", IndexStore.ALL).hits.get(0).item);

        // the filters: documents and sounds as cards
        final View root = a.getWindow().getDecorView();
        Robo.call(a, "selectFilter", MainActivity.F_FILES);
        Robo.settle(600);
        List<IndexStore.Item> grid = ScanTest.grid(a);
        assertEquals(3, grid.size());
        for (IndexStore.Item it : grid) assertEquals(IndexStore.KIND_FILE, it.kind);
        assertTrue(Robo.allText(root), Robo.allText(root).contains("DOCX"));
        UiShots.shot(a, "17a-files");
        Robo.call(a, "selectFilter", MainActivity.F_AUDIO);
        Robo.settle(600);
        grid = ScanTest.grid(a);
        assertEquals(3, grid.size());
        assertTrue(Robo.allText(root), Robo.allText(root).contains("ЗВУК · 0:02 · Recordings"));
        UiShots.shot(a, "17b-audio");

        // the viewer: a sound's player, a document's text
        Robo.call(a, "openViewer", grid.indexOf(rain));
        Robo.settle(800);
        View viewer = Robo.byName(root, "Viewer");
        assertNotNull(viewer);
        boolean player = false;
        for (View v : Robo.views(viewer, new ArrayList<View>())) player |= "Слушать".contentEquals(String.valueOf(v.getContentDescription()));
        assertTrue("a play button", player);
        assertTrue(Robo.allText(viewer).contains("Открыть"));
        UiShots.shot(a, "17c-sound-viewer");
        a.onBackPressed();
        Robo.settle(600);
        Robo.call(a, "selectFilter", MainActivity.F_FILES);
        Robo.settle(600);
        grid = ScanTest.grid(a);
        Robo.call(a, "openViewer", grid.indexOf(contract));
        Robo.settle(800);
        viewer = Robo.byName(root, "Viewer");
        assertTrue(Robo.allText(viewer), Robo.allText(viewer).contains("Поставщик передаёт кофемашины покупателю."));
        UiShots.shot(a, "17d-document-viewer");
        a.onBackPressed();
        Robo.settle(600);

        // the settings: the folder, the sound part
        Robo.call(a, "openSettings");
        Robo.settle(800);
        View settings = Robo.byName(root, "SettingsPanel");
        assertNotNull(settings);
        TextView folderRow = Robo.textView(settings, "root"); // the picked folder's name as its provider gives it
        assertNotNull("the folder listed: " + Robo.allText(settings), folderRow);
        ScrollView sv = null;
        for (View v : Robo.views(settings, new ArrayList<View>())) if (v instanceof ScrollView) sv = (ScrollView) v;
        int[] at = new int[2], top = new int[2];
        folderRow.getLocationInWindow(at);
        sv.getLocationInWindow(top);
        sv.scrollTo(0, at[1] - top[1] - Math.round(160 * a.getResources().getDisplayMetrics().density));
        Robo.settle(300);
        UiShots.shot(a, "17e-settings-documents-sound");
        a.onBackPressed();
        Robo.settle(600);

        // a document changed: read again; one deleted: out of the index
        File recipe = new File(docs, "Рецепт.txt");
        write(recipe, "Сырники: творог, мука, яйца.");
        assertTrue(recipe.setLastModified(recipe.lastModified() + 60_000));
        assertTrue(new File(docs, "Отпуск.html").delete());
        index(e);
        assertEquals(2, s.count(IndexStore.KIND_FILE));
        assertTrue(item(e, IndexStore.KIND_FILE, "Рецепт").body.startsWith("Сырники"));

        // the folder taken away: its documents and its sound leave the index, the phone's sounds stay
        e.removeFolder(FakeDocs.tree());
        Robo.waitFor("folder gone", () -> s.count(IndexStore.KIND_FILE) == 0 && s.count(IndexStore.KIND_AUDIO) == 2);
        a.finish();
    }
}
