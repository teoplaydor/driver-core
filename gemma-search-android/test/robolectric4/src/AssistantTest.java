import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.speech.SpeechRecognizer;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAudioRecord;
import org.robolectric.shadows.ShadowNotificationManager;
import org.robolectric.shadows.ShadowSpeechRecognizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

import io.github.teoplaydor.semsearch.app.Engine;
import io.github.teoplaydor.semsearch.app.IndexStore;
import io.github.teoplaydor.semsearch.core.Spoken;

/**
 * The assistant around the notes: a question answered from them (in the quick note and by a reply in the shade),
 * «что купить?» from the list, «за день до дня рождения Оли» with the date found in a note, a place asked for and kept
 * («Я сейчас здесь»), the reminder there when the phone comes, «Готово» ticking a note, the morning digest's lines,
 * the calendar's event, a ticket found in a photo's text offered and kept as a reminder, the voice kept with a note.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "ru-w411dp-h891dp-night-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AssistantTest {
    static final String QUICK = "io.github.teoplaydor.semsearch.app.QuickNoteActivity";

    static IndexStore.Item noteWith(Engine e, String start) {
        for (IndexStore.Item n : e.store().notes()) if (n.body.startsWith(start)) return n;
        return null;
    }

    static IndexStore.Item add(Engine e, String text, long remind) throws Exception {
        final IndexStore.Item[] it = new IndexStore.Item[1];
        e.addNote(text, null, false, remind, (n, err) -> it[0] = n);
        Robo.waitFor("note «" + text + "»", () -> it[0] != null);
        return it[0];
    }

    static Bundle said(String s) {
        Bundle b = new Bundle();
        b.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, new ArrayList<String>(Collections.singletonList(s)));
        return b;
    }

    static Activity open() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        @SuppressWarnings("unchecked")
        Class<? extends Activity> k = (Class<? extends Activity>) Class.forName(QUICK);
        Activity a = Robolectric.buildActivity(k, new Intent("io.github.teoplaydor.semsearch.QUICK_NOTE").setClassName(app, QUICK)).setup().get();
        Robo.settle(300);
        return a;
    }

    static Notification find(String tag) {
        NotificationManager nm = (NotificationManager) RuntimeEnvironment.getApplication().getSystemService(Context.NOTIFICATION_SERVICE);
        ShadowNotificationManager s = Shadows.shadowOf(nm);
        for (Notification n : s.getAllNotifications()) {
            if (tag == null || String.valueOf(n.extras.getCharSequence("android.title")).contains(tag)) return n;
        }
        return null;
    }

    static String text(Notification n) {
        return String.valueOf(n.extras.getCharSequence("android.text"));
    }

    static void fire(Notification.Action a) throws Exception {
        Intent i = Shadows.shadowOf(a.actionIntent).getSavedIntent();
        BroadcastReceiver r = (BroadcastReceiver) Class.forName(i.getComponent().getClassName()).getDeclaredConstructor().newInstance();
        r.onReceive(RuntimeEnvironment.getApplication(), i);
    }

    @Test
    public void assistant() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).grantPermissions("android.permission.RECORD_AUDIO", "android.permission.POST_NOTIFICATIONS",
                "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.ACCESS_BACKGROUND_LOCATION");
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true);
        final Engine e = Engine.get(app);
        Robo.waitFor("store", () -> e.store() != null);
        long now = System.currentTimeMillis();
        IndexStore.Item code = add(e, "Код домофона у Саши: 47К1290, подъезд 3", 0);
        add(e, "Купить:\n☐ молоко\n☑ хлеб\n☐ сыр", 0);
        add(e, "День рождения Оли — 15 марта", 0);
        IndexStore.Item mom = add(e, "Позвонить маме", now + 2 * 3_600_000L);

        // a question: the answer from the notes, where it is from; «Открыть» shows the note
        Activity q = open();
        View root = q.getWindow().getDecorView();
        Shadows.shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).triggerOnResults(said("какой код от домофона у Саши"));
        final Activity q1 = q;
        Robo.waitFor("answered", () -> Robo.field(q1, "answer") != null);
        Robo.settle(300);
        String shown = Robo.allText(root);
        assertTrue(shown, shown.contains("Ответ из заметок") && shown.contains("Код домофона у Саши: 47К1290, подъезд 3")
                && shown.contains("Из заметки — «Открыть» покажет её"));
        assertNull("no note made of a question", noteWith(e, "Какой код"));
        UiShots.shot(q, "18a-quick-answer");
        Shadows.shadowOf(app).clearNextStartedActivities();
        Robo.textView(root, "Открыть").performClick();
        Intent opened = Shadows.shadowOf(app).getNextStartedActivity();
        assertNotNull(opened);
        assertEquals("io.github.teoplaydor.semsearch.OPEN_NOTE", opened.getAction());
        assertEquals(code.id, opened.getLongExtra("note_id", -1));

        // «что нужно купить?»: what is left to tick
        q = open();
        Robo.call(q, "heard", "что нужно купить");
        final Activity q2 = q;
        Robo.waitFor("the list", () -> Robo.field(q2, "answer") != null);
        Robo.settle(200);
        assertTrue(Robo.allText(q.getWindow().getDecorView()), Robo.allText(q.getWindow().getDecorView()).contains("молоко и сыр"));
        Robo.call(q, "close");
        Robo.settle(300);

        // «за день до дня рождения Оли»: the date from the note, a day before at 9:00
        q = open();
        Robo.call(q, "heard", "напомни за день до дня рождения Оли купить подарок");
        final Activity q3 = q;
        Robo.waitFor("kept", () -> Robo.field(q3, "saved") != null);
        IndexStore.Item gift = (IndexStore.Item) Robo.field(q, "saved");
        long bday = Spoken.dateIn("15 марта", System.currentTimeMillis(), TimeZone.getDefault());
        assertEquals("До дня рождения Оли купить подарок", gift.body);
        assertEquals(bday - 86_400_000L, gift.remind);
        Robo.call(q, "close");
        Robo.settle(300);

        // a place not known yet: asked; «Я сейчас здесь» keeps where the phone is, the note waits there
        LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
        Shadows.shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true);
        Location here = new Location(LocationManager.GPS_PROVIDER);
        here.setLatitude(55.7512);
        here.setLongitude(37.6184);
        here.setAccuracy(12);
        here.setTime(System.currentTimeMillis());
        Shadows.shadowOf(lm).simulateLocation(here);
        q = open();
        root = q.getWindow().getDecorView();
        Robo.call(q, "heard", "когда буду в магазине купить хлеб");
        final Activity q4 = q;
        Robo.waitFor("kept", () -> Robo.field(q4, "saved") != null);
        Robo.settle(300);
        assertTrue(Robo.allText(root), Robo.allText(root).contains("Где это — «Магазин»?"));
        UiShots.shot(q, "18b-quick-place");
        Robo.textView(root, "Я сейчас здесь").performClick();
        Class<?> places = Class.forName("io.github.teoplaydor.semsearch.app.Places");
        Robo.waitFor("the place kept", () -> Robo.callStatic(places, "find", app, "магазин") != null);
        final IndexStore.Item bread = (IndexStore.Item) Robo.field(q, "saved");
        Robo.waitFor("at the place", () -> "Магазин".equals(bread.place) && !bread.leaving);
        Robo.settle(200);
        assertTrue(Robo.allText(root), Robo.allText(root).contains("Напомнит у места: Магазин"));
        Robo.call(q, "close");
        Robo.settle(300);
        // the phone comes there: the reminder, and the note no longer waits
        Intent came = new Intent("io.github.teoplaydor.semsearch.PLACE").putExtra("note_id", bread.id)
                .putExtra(LocationManager.KEY_PROXIMITY_ENTERING, true);
        ((BroadcastReceiver) Class.forName("io.github.teoplaydor.semsearch.app.PlaceAlert").getDeclaredConstructor().newInstance())
                .onReceive(app, came);
        Robo.waitFor("reminded there", () -> bread.place == null && find("Купить хлеб") != null);

        // «Готово» on a reminder: the note done (its line ticked)
        Robo.callStatic(Class.forName("io.github.teoplaydor.semsearch.app.Reminders"), "notify", app, mom.id, mom.body, 0L, 0);
        Notification n = find("Позвонить маме");
        assertEquals("Готово", String.valueOf(n.actions[0].title));
        fire(n.actions[0]);
        Robo.waitFor("ticked", () -> mom.body.startsWith("☑ "));
        assertEquals(0, mom.remind);

        // the shade (a watch): a reply becomes a note with its reminder; a question gets its answer
        Robo.callStatic(Class.forName("io.github.teoplaydor.semsearch.app.QuickNotes"), "setShade", app, true);
        Notification shade = find("Быстрая заметка");
        assertNotNull("the quiet note in the shade", shade);
        Notification.Action reply = shade.actions[0];
        assertEquals(1, reply.getRemoteInputs().length);
        Intent typed = Shadows.shadowOf(reply.actionIntent).getSavedIntent();
        Bundle words = new Bundle();
        words.putCharSequence("quick_text", "завтра в 10 оплатить интернет");
        RemoteInput.addResultsToIntent(reply.getRemoteInputs(), typed, words);
        ((BroadcastReceiver) Class.forName("io.github.teoplaydor.semsearch.app.QuickReply").getDeclaredConstructor().newInstance()).onReceive(app, typed);
        Robo.waitFor("kept from the shade", () -> noteWith(e, "Оплатить интернет") != null && text(find("Быстрая заметка")).startsWith("Сохранено"));
        java.util.Calendar ten = java.util.Calendar.getInstance();
        ten.add(java.util.Calendar.DAY_OF_YEAR, 1);
        ten.set(java.util.Calendar.HOUR_OF_DAY, 10);
        ten.set(java.util.Calendar.MINUTE, 0);
        ten.set(java.util.Calendar.SECOND, 0);
        ten.set(java.util.Calendar.MILLISECOND, 0);
        assertEquals(ten.getTimeInMillis(), noteWith(e, "Оплатить интернет").remind);
        Intent asked = Shadows.shadowOf(reply.actionIntent).getSavedIntent();
        words.putCharSequence("quick_text", "какой код домофона у Саши?");
        RemoteInput.addResultsToIntent(reply.getRemoteInputs(), asked, words);
        ((BroadcastReceiver) Class.forName("io.github.teoplaydor.semsearch.app.QuickReply").getDeclaredConstructor().newInstance()).onReceive(app, asked);
        Robo.waitFor("answered in the shade", () -> text(find("Быстрая заметка")).contains("47К1290"));

        // the morning digest: today's reminders, what is left in the lists, what waits at places
        IndexStore.Item water = add(e, "Полить цветы", 0);
        e.setPlace(water, "Дом", false);
        Robo.settle(300);
        add(e, "Созвон с командой", System.currentTimeMillis() + 3_600_000L);
        Class<?> digest = Class.forName("io.github.teoplaydor.semsearch.app.Digest");
        Robo.callStatic(digest, "set", app, true, 8 * 60);
        ((BroadcastReceiver) digest.getDeclaredConstructor().newInstance()).onReceive(app, new Intent("io.github.teoplaydor.semsearch.DIGEST"));
        Robo.waitFor("the digest", () -> find("Сегодня") != null);
        Notification d = find("Сегодня");
        CharSequence[] lines = d.extras.getCharSequenceArray("android.textLines");
        String all = lines == null ? "" : String.join(" | ", java.util.Arrays.asList(lines).toArray(new String[0]));
        assertTrue(all, all.contains("Созвон с командой") && all.contains("Купить: молоко, сыр") && all.contains("Дом · Полить цветы"));
        System.out.println("digest: " + all);

        // the calendar: the event filled in, repeating as the reminder does
        Intent cal = (Intent) Robo.callStatic(Class.forName("io.github.teoplaydor.semsearch.app.Calendars"), "event",
                "Планёрка", 1_800_000_000_000L, Spoken.WEEKLY);
        assertEquals(Intent.ACTION_INSERT, cal.getAction());
        assertEquals("Планёрка", cal.getStringExtra(CalendarContract.Events.TITLE));
        assertEquals(1_800_000_000_000L, cal.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0));
        assertEquals("FREQ=WEEKLY", cal.getStringExtra(CalendarContract.Events.RRULE));

        // a ticket in a new screenshot's text: offered as a reminder; «Напомнить» — a note to the photo, two hours before
        java.util.Calendar dep = java.util.Calendar.getInstance();
        dep.add(java.util.Calendar.DAY_OF_YEAR, 5);
        dep.set(java.util.Calendar.HOUR_OF_DAY, 14);
        dep.set(java.util.Calendar.MINUTE, 35);
        dep.set(java.util.Calendar.SECOND, 0);
        dep.set(java.util.Calendar.MILLISECOND, 0);
        String day = String.format(java.util.Locale.ROOT, "%02d.%02d.%d", dep.get(java.util.Calendar.DAY_OF_MONTH),
                dep.get(java.util.Calendar.MONTH) + 1, dep.get(java.util.Calendar.YEAR));
        IndexStore.Item shot = e.store().add(IndexStore.KIND_PHOTO, 777, "content://media/external/images/media/777", "Screenshot_1.png",
                null, System.currentTimeMillis() - 3_600_000L, new float[]{1, 0, 0});
        e.store().setOcr(shot, "ЭЛЕКТРОННЫЙ БИЛЕТ\nПоезд 024А МОСКВА — КАЗАНЬ\nОтправление " + day + " 14:35\nВагон 05 Место 017");
        Robo.callStatic(Class.forName("io.github.teoplaydor.semsearch.app.PhotoAlerts"), "check", app, shot);
        Notification ticket = find("Поезд 024А Москва — Казань");
        assertNotNull("offered", ticket);
        assertTrue(text(ticket), text(ticket).endsWith("напомнить за 2 часа?"));
        fire(ticket.actions[0]);
        Robo.waitFor("a note to the photo", () -> e.store().notesOf(777).size() == 1);
        IndexStore.Item trip = e.store().notesOf(777).get(0);
        assertEquals(dep.getTimeInMillis() - 2 * 3_600_000L, trip.remind);
        assertTrue(trip.body, trip.body.startsWith("Поезд 024А Москва — Казань"));
        // offered once
        int before = Shadows.shadowOf((NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE)).getAllNotifications().size();
        Robo.callStatic(Class.forName("io.github.teoplaydor.semsearch.app.PhotoAlerts"), "check", app, shot);
        assertEquals(before, Shadows.shadowOf((NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE)).getAllNotifications().size());

        // the voice kept with a quick note (Android 13+): the recognizer gets the app's sound, the note its recording
        e.setKeepVoice(true);
        ShadowAudioRecord.setSource(new ShadowAudioRecord.AudioRecordSource() {
            int t;

            @Override
            public int readInByteArray(byte[] b, int off, int n, boolean blocking) {
                for (int i = 0; i + 1 < n; i += 2, t++) {
                    short v = (short) (8000 * Math.sin(t * 2 * Math.PI * 220 / 16000.0));
                    b[off + i] = (byte) v;
                    b[off + i + 1] = (byte) (v >> 8);
                }
                try {
                    Thread.sleep(n / 32); // as fast as a microphone (32 bytes a millisecond)
                } catch (InterruptedException ignored) {
                    // on
                }
                return n;
            }
        });
        q = open();
        SpeechRecognizer sr = ShadowSpeechRecognizer.getLatestSpeechRecognizer();
        Intent listened = Shadows.shadowOf(sr).getLastRecognizerIntent();
        assertNotNull("the app's sound to the recognizer", listened.getParcelableExtra("android.speech.extra.AUDIO_SOURCE"));
        assertEquals(16000, listened.getIntExtra("android.speech.extra.AUDIO_SOURCE_SAMPLING_RATE", 0));
        Thread.sleep(1200);
        Shadows.shadowOf(sr).triggerOnResults(said("запиши идея для подарка папе — набор инструментов"));
        final Activity q5 = q;
        Robo.waitFor("kept", () -> Robo.field(q5, "saved") != null);
        final IndexStore.Item idea = (IndexStore.Item) Robo.field(q, "saved");
        Robo.waitFor("its voice", () -> idea.audio != null && new java.io.File(idea.audio).length() > 16000);
        System.out.println("voice: " + new java.io.File(idea.audio).length() + " bytes");
        ShadowAudioRecord.clearSource();
        Robo.call(q, "close");
        Robo.settle(300);

        // GigaAM on the phone (the stand-in from test/gigaam): the same recording heard again by it, its words the note's
        // (the recognizer's only shown meanwhile); recorded for it though the voice is not kept, and not kept
        System.setProperty("onnxruntime.native.path", new java.io.File(System.getProperty("npu.fixture")).getAbsolutePath());
        java.lang.reflect.Field giga = Engine.class.getDeclaredField("speechForTest");
        giga.setAccessible(true);
        giga.set(null, new java.io.File("build/test/gigaam/int8"));
        e.setKeepVoice(false);
        try {
            ShadowAudioRecord.setSource(new ShadowAudioRecord.AudioRecordSource() {
                int t;

                @Override
                public int readInByteArray(byte[] b, int off, int n, boolean blocking) {
                    for (int i = 0; i + 1 < n; i += 2, t++) {
                        // «syllables»: a gliding pitch with harmonics, 0.3 s on, 0.2 s off
                        double v = 0;
                        if (t % 8000 < 4800) {
                            double ph = 2 * Math.PI * 140 * t / 16000.0 * (1 + 0.1 * Math.sin(2 * Math.PI * 2 * t / 16000.0));
                            for (int k = 1; k <= 12; k++) v += Math.sin(k * ph) / k;
                            v *= Math.sin(Math.PI * (t % 8000) / 4800.0);
                        }
                        short s = (short) (6000 * v);
                        b[off + i] = (byte) s;
                        b[off + i + 1] = (byte) (s >> 8);
                    }
                    try {
                        Thread.sleep(n / 32);
                    } catch (InterruptedException ignored) {
                        // on
                    }
                    return n;
                }
            });
            assertTrue(e.speechReady());
            q = open();
            sr = ShadowSpeechRecognizer.getLatestSpeechRecognizer();
            assertNotNull("recorded for GigaAM", Shadows.shadowOf(sr).getLastRecognizerIntent().getParcelableExtra("android.speech.extra.AUDIO_SOURCE"));
            Thread.sleep(2500);
            Shadows.shadowOf(sr).triggerOnResults(said("слова распознавателя телефона"));
            final Activity q6 = q;
            Robo.waitFor("kept", () -> Robo.field(q6, "saved") != null);
            final IndexStore.Item heard = (IndexStore.Item) Robo.field(q, "saved");
            System.out.println("GigaAM (stand-in) in a quick note: «" + heard.body + "»");
            assertFalse(heard.body, heard.body.toLowerCase().contains("распознавателя"));
            assertNull("the voice not kept (off)", heard.audio);
            ShadowAudioRecord.clearSource();
            Robo.call(q, "close");
            Robo.settle(300);
        } finally {
            giga.set(null, null);
        }
    }
}
