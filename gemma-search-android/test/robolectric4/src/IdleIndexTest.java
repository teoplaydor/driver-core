import static org.junit.Assert.*;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.BatteryManager;
import android.os.PowerManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPowerManager;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.TimeUnit;

import io.github.teoplaydor.semsearch.app.Engine;
import io.github.teoplaydor.semsearch.app.IdleIndexService;
import io.github.teoplaydor.semsearch.app.IndexStore;

/**
 * Indexing while the phone rests: runs only with the screen off and power to spare, pauses the moment
 * the screen turns on, resumes when it goes off again, keeps the CPU awake only while working, shows
 * its progress in the (silent) notification and stops itself when nothing is left.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class IdleIndexTest {
    static void photo(File dir, long id, long date) throws Exception {
        Bitmap b = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888);
        new Canvas(b).drawColor(Color.rgb((int) (id * 37 % 255), 90, 140));
        File f = new File(dir, id + ".png");
        try (FileOutputStream o = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
        }
        FakeMediaStore.ROWS.add(new FakeMediaStore.Row(id, false, date, "IMG_" + id + ".png", 40, 30, f));
    }

    static void battery(Application app, boolean plugged, int percent) {
        Intent b = new Intent(Intent.ACTION_BATTERY_CHANGED);
        b.putExtra(BatteryManager.EXTRA_PLUGGED, plugged ? BatteryManager.BATTERY_PLUGGED_AC : 0);
        b.putExtra(BatteryManager.EXTRA_STATUS, plugged ? BatteryManager.BATTERY_STATUS_CHARGING : BatteryManager.BATTERY_STATUS_DISCHARGING);
        b.putExtra(BatteryManager.EXTRA_LEVEL, percent);
        b.putExtra(BatteryManager.EXTRA_SCALE, 100);
        b.putExtra(BatteryManager.EXTRA_TEMPERATURE, 300);
        app.sendStickyBroadcast(b);
    }

    static void screen(Application app, boolean on) {
        Shadows.shadowOf((PowerManager) app.getSystemService(Context.POWER_SERVICE)).setIsInteractive(on);
        app.sendBroadcast(new Intent(on ? Intent.ACTION_SCREEN_ON : Intent.ACTION_SCREEN_OFF));
    }

    static String notificationText(Application app) {
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification n = Shadows.shadowOf(nm).getNotification(4301);
        return n == null ? null : String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TEXT));
    }

    static boolean awake() {
        PowerManager.WakeLock w = ShadowPowerManager.getLatestWakeLock();
        return w != null && w.isHeld();
    }

    @Test
    public void indexesOnlyWhileThePhoneRests() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        File dir = new File(app.getCacheDir(), "media");
        assertTrue(dir.mkdirs());
        for (int i = 1; i <= 3; i++) photo(dir, i, 1700000000L + i);
        FakeMediaStore.install();
        Shadows.shadowOf(app).grantPermissions("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO");
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("idle_index", true)
                .putInt("photo_model", 0).apply();

        final Engine e = Engine.get(app);
        Robo.waitFor("store", () -> e.store() != null);
        File model = new File(app.getFilesDir(), "model");
        assertTrue(model.mkdirs());
        try (FileOutputStream o = new FileOutputStream(new File(model, "manifest.json"))) {
            o.write("{}".getBytes("UTF-8"));
        }
        e.attachModelForTest(new Robo.FakeEmbedder("cat", "dog", "car", "sea", "tree"));
        Robo.waitFor("ready", e::ready);

        // Phone in use, on the charger: the service starts, but waits.
        battery(app, true, 80);
        screen(app, true);
        ServiceController<IdleIndexService> sc = Robolectric.buildService(IdleIndexService.class).create().startCommand(0, 1);
        IdleIndexService svc = sc.get();
        Robo.settle(1500);
        assertEquals(0, e.store().count(IndexStore.KIND_PHOTO));
        assertEquals("ждёт, пока экран погаснет", notificationText(app));
        assertFalse(awake());

        // Screen off: after a short settle the photos are indexed, the CPU kept awake meanwhile.
        screen(app, false);
        ShadowLooper.idleMainLooper(21, TimeUnit.SECONDS);
        Robo.waitFor("indexed", () -> e.store().count(IndexStore.KIND_PHOTO) == 3 && !e.indexing);
        System.out.println("notification while working: " + notificationText(app));

        // New photos arrive while the phone is in use: nothing happens until it rests again.
        photo(dir, 4, 1700000100L);
        photo(dir, 5, 1700000200L);
        screen(app, true);
        Robo.settle(3000);
        assertEquals(3, e.store().count(IndexStore.KIND_PHOTO));
        assertFalse("no wake lock while the phone is in use", awake());
        screen(app, false);
        ShadowLooper.idleMainLooper(21, TimeUnit.SECONDS);
        Robo.waitFor("resumed", () -> e.store().count(IndexStore.KIND_PHOTO) == 5 && !e.indexing);

        // Nothing left (checked twice, a minute apart): the service stops itself and lets the CPU sleep.
        for (int i = 0; i < 3 && !Shadows.shadowOf(svc).isStoppedBySelf(); i++) {
            ShadowLooper.idleMainLooper(65, TimeUnit.SECONDS);
            Robo.settle(500);
        }
        assertTrue("stops when the gallery is indexed", Shadows.shadowOf(svc).isStoppedBySelf());
        assertFalse(awake());

        // On battery below the limit it would wait for the charger; battery saver pauses it too.
        battery(app, false, 30);
        assertEquals("ждёт зарядку: заряд ниже 50%", waitReason(app));
        battery(app, false, 90);
        assertNull(waitReason(app));
        Shadows.shadowOf((PowerManager) app.getSystemService(Context.POWER_SERVICE)).setIsPowerSaveMode(true);
        assertEquals("пауза: включена экономия батареи", waitReason(app));
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("idle_battery", false).apply();
        assertEquals("ждёт зарядку", waitReason(app));
        sc.destroy();
    }

    static String waitReason(Context c) throws Exception {
        java.lang.reflect.Method m = Class.forName("io.github.teoplaydor.semsearch.app.IdleIndex")
                .getDeclaredMethod("waitReason", Context.class);
        m.setAccessible(true);
        return (String) m.invoke(null, c);
    }
}
