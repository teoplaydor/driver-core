import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Application;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.View;
import android.widget.EditText;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.shadows.ShadowAppWidgetManager;
import org.robolectric.shadows.ShadowSpeechRecognizer;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;

import io.github.teoplaydor.semsearch.app.Engine;
import io.github.teoplaydor.semsearch.app.IndexStore;

/**
 * The quick note: opened (as the assistant would open it), it listens at once on the phone's own recognizer; the words
 * show as they come; what was said is kept as a note with its reminder (an alarm set) — «Отменить» takes it back;
 * «напомни…» without a time asks when; «найди…» opens the search; typed instead of said, a weekly reminder; the
 * widget's buttons and the tile open it; the «Голосовая заметка» icon turned on and off.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "ru-w411dp-h891dp-night-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class QuickNoteTest {
    static final String QUICK = "io.github.teoplaydor.semsearch.app.QuickNoteActivity";

    @SuppressWarnings("unchecked")
    static Activity open(Intent i) throws Exception {
        Class<? extends Activity> k = (Class<? extends Activity>) Class.forName(QUICK);
        ActivityController<? extends Activity> c = Robolectric.buildActivity(k, i).setup();
        Robo.settle(400);
        return c.get();
    }

    static Bundle said(String s) {
        Bundle b = new Bundle();
        b.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, new ArrayList<String>(Collections.singletonList(s)));
        return b;
    }

    static IndexStore.Item noteWith(Engine e, String start) {
        for (IndexStore.Item n : e.store().notes()) if (n.body.startsWith(start)) return n;
        return null;
    }

    static long tomorrowAt(int hour) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, 1);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    @Test
    public void quickNote() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).grantPermissions("android.permission.RECORD_AUDIO", "android.permission.POST_NOTIFICATIONS");
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true);
        final Engine e = Engine.get(app);
        Robo.waitFor("store", () -> e.store() != null);

        // opened: listening at once, on the phone itself, words as they come
        Intent assist = new Intent(Intent.ACTION_ASSIST).setClassName(app, QUICK);
        Activity q = open(assist);
        View root = q.getWindow().getDecorView();
        SpeechRecognizer sr = ShadowSpeechRecognizer.getLatestSpeechRecognizer();
        assertNotNull("listening", sr);
        ShadowSpeechRecognizer ss = Shadows.shadowOf(sr);
        Intent asked = ss.getLastRecognizerIntent();
        assertTrue("words as they come", asked.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false));
        assertTrue("without the network where it can", asked.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false));
        ss.triggerOnReadyForSpeech(new Bundle());
        ss.triggerOnRmsChanged(6f);
        ss.triggerOnPartialResults(said("напомни завтра в 9"));
        Robo.settle(100);
        assertTrue(Robo.allText(root), Robo.allText(root).contains("напомни завтра в 9"));
        UiShots.shot(q, "17a-quick-listening");

        // said: the note kept with its reminder, the alarm set
        ss.triggerOnResults(said("Напомни завтра в 9 позвонить маме"));
        final Activity q1 = q;
        Robo.waitFor("kept", () -> Robo.field(q1, "saved") != null);
        IndexStore.Item mom = noteWith(e, "Позвонить маме");
        assertEquals(tomorrowAt(9), mom.remind);
        Robo.settle(300);
        String shown = Robo.allText(root);
        assertTrue(shown, shown.contains("Сохранено в заметки") && shown.contains("Напомнит завтра в"));
        ShadowAlarmManager alarms = Shadows.shadowOf((AlarmManager) app.getSystemService(Context.ALARM_SERVICE));
        assertNotNull(alarms.peekNextScheduledAlarm());
        assertEquals(tomorrowAt(9), alarms.peekNextScheduledAlarm().triggerAtTime);
        UiShots.shot(q, "17b-quick-saved");
        // «Отменить»: the note gone, the window too
        Robo.textView(root, "Отменить").performClick();
        Robo.waitFor("taken back", () -> noteWith(e, "Позвонить маме") == null);
        Robo.settle(400);
        assertTrue("closed", q.isFinishing());

        // «напомни» without a time: asked when; tomorrow 9:00 chosen
        q = open(new Intent("io.github.teoplaydor.semsearch.QUICK_NOTE").setClassName(app, QUICK));
        root = q.getWindow().getDecorView();
        Shadows.shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).triggerOnResults(said("напомни оплатить интернет"));
        final Activity q2 = q;
        Robo.waitFor("kept", () -> Robo.field(q2, "saved") != null);
        Robo.settle(300);
        assertTrue(Robo.allText(root), Robo.allText(root).contains("Когда напомнить?"));
        UiShots.shot(q, "17c-quick-when");
        Robo.textView(root, "Завтра в 9:00").performClick();
        IndexStore.Item net = noteWith(e, "Оплатить интернет");
        Robo.waitFor("reminder set", () -> net.remind == tomorrowAt(9));
        Robo.settle(200);
        assertTrue(Robo.allText(root), Robo.allText(root).contains("Напомнит завтра в"));
        Robo.call(q, "close");
        Robo.settle(400);

        // the phone's own recognition has no Russian yet: the usual recognizer then
        q = open(new Intent("io.github.teoplaydor.semsearch.QUICK_NOTE").setClassName(app, QUICK));
        SpeechRecognizer first = ShadowSpeechRecognizer.getLatestSpeechRecognizer();
        Shadows.shadowOf(first).triggerOnError(13 /* ERROR_LANGUAGE_UNAVAILABLE */);
        Robo.settle(200);
        assertTrue("the first one let go", Shadows.shadowOf(first).isDestroyed());
        // «найди…»: the search, not a note
        Shadows.shadowOf(app).clearNextStartedActivities();
        Robo.call(q, "heard", "найди фото с котом");
        Intent search = Shadows.shadowOf(app).getNextStartedActivity();
        assertNotNull("the search opened", search);
        assertEquals("io.github.teoplaydor.semsearch.SEARCH", search.getAction());
        assertEquals("фото с котом", search.getStringExtra("query"));
        assertNull("no note", noteWith(e, "Фото с котом"));

        // typed: «каждый понедельник в 10 планёрка» — a weekly reminder
        q = open(new Intent("io.github.teoplaydor.semsearch.QUICK_NOTE").setClassName(app, QUICK).putExtra("type", true));
        root = q.getWindow().getDecorView();
        EditText typed = (EditText) Robo.field(q, "typed");
        assertEquals(View.VISIBLE, typed.getVisibility());
        typed.setText("каждый понедельник в 10 планёрка");
        Robo.textView(root, "Сохранить").performClick();
        final Activity q3 = q;
        Robo.waitFor("kept", () -> Robo.field(q3, "saved") != null);
        IndexStore.Item weekly = noteWith(e, "Планёрка");
        assertEquals(7 /* Spoken.WEEKLY */, weekly.repeat);
        Calendar mon = Calendar.getInstance();
        mon.setTimeInMillis(weekly.remind);
        assertEquals(Calendar.MONDAY, mon.get(Calendar.DAY_OF_WEEK));
        assertEquals(10, mon.get(Calendar.HOUR_OF_DAY));
        Robo.settle(300);
        assertTrue(Robo.allText(root), Robo.allText(root).contains("каждую неделю"));
        UiShots.shot(q, "17d-quick-typed");
        Robo.call(q, "close");
        Robo.settle(400);

        // the widget: «Сказать заметку» opens it listening, the other button with the keyboard
        ShadowAppWidgetManager wm = Shadows.shadowOf(AppWidgetManager.getInstance(app));
        Class<?> widget = Class.forName("io.github.teoplaydor.semsearch.app.QuickWidget");
        int layout = app.getResources().getIdentifier("widget_quick", "layout", app.getPackageName());
        @SuppressWarnings("unchecked")
        int id = wm.createWidget((Class<? extends android.appwidget.AppWidgetProvider>) widget, layout);
        View w = wm.getViewFor(id);
        Shadows.shadowOf(app).clearNextStartedActivities();
        w.findViewById(app.getResources().getIdentifier("widget_voice", "id", app.getPackageName())).performClick();
        Intent fromWidget = Shadows.shadowOf(app).getNextStartedActivity();
        assertNotNull("the widget opens it", fromWidget);
        assertEquals(QUICK, fromWidget.getComponent().getClassName());
        assertFalse(fromWidget.getBooleanExtra("type", false));
        w.findViewById(app.getResources().getIdentifier("widget_type", "id", app.getPackageName())).performClick();
        assertTrue("with the keyboard", Shadows.shadowOf(app).getNextStartedActivity().getBooleanExtra("type", false));

        // the tile
        Class<?> tile = Class.forName("io.github.teoplaydor.semsearch.app.QuickTile");
        @SuppressWarnings("unchecked")
        android.app.Service t = Robolectric.buildService((Class<? extends android.app.Service>) tile).create().get();
        Shadows.shadowOf(app).clearNextStartedActivities();
        Robo.call(t, "onClick");
        Intent fromTile = Shadows.shadowOf(app).getNextStartedActivity();
        assertNotNull("the tile opens it", fromTile);
        assertEquals(QUICK, fromTile.getComponent().getClassName());

        // the «Голосовая заметка» icon: off at first, on and off from the settings
        PackageManager pm = app.getPackageManager();
        ComponentName alias = new ComponentName(app.getPackageName(), "io.github.teoplaydor.semsearch.app.VoiceNote");
        Class<?> qn = Class.forName("io.github.teoplaydor.semsearch.app.QuickNotes");
        assertEquals(false, Robo.callStatic(qn, "iconShown", app));
        Robo.callStatic(qn, "showIcon", app, true);
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, pm.getComponentEnabledSetting(alias));
        assertEquals(true, Robo.callStatic(qn, "iconShown", app));
        Robo.callStatic(qn, "showIcon", app, false);
        assertEquals(false, Robo.callStatic(qn, "iconShown", app));
    }
}
