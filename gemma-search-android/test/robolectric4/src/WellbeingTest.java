import static org.junit.Assert.*;

import android.app.Application;
import android.app.NotificationManager;
import android.app.usage.UsageStatsManager;
import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAppOpsManager;
import org.robolectric.shadows.ShadowUsageStatsManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.teoplaydor.semsearch.app.Engine;
import io.github.teoplaydor.semsearch.app.Wellbeing;

/**
 * «Разомнись»: from the phone's own usage statistics (how long the screen has been on) — no microphone, no camera. The
 * pure duration logic, and the whole check through Robolectric's usage-stats and app-ops shadows: a nudge when the
 * screen has been on too long, nothing within the cool-down, nothing at night, nothing without usage access.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WellbeingTest {
    static final int SCREEN_INTERACTIVE = 15, SCREEN_NON_INTERACTIVE = 16;

    static List<long[]> ev(long... pairs) {
        List<long[]> out = new ArrayList<long[]>();
        for (int i = 0; i < pairs.length; i += 2) out.add(new long[]{pairs[i], pairs[i + 1]});
        return out;
    }

    static int notes(Application app) {
        return Shadows.shadowOf((NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE)).getAllNotifications().size();
    }

    @Test
    public void wellbeing() {
        long now = 1_000_000_000_000L;
        // the pure part: how long the screen has been on, ending at now
        assertEquals(50 * 60_000L, Wellbeing.screenOnFor(ev(now - 50 * 60_000L, 1), now));
        assertEquals("off now → 0", 0, Wellbeing.screenOnFor(ev(now - 60 * 60_000L, 1, now - 5 * 60_000L, 0), now));
        // a brief off then on again: since the last off
        assertEquals(10 * 60_000L, Wellbeing.screenOnFor(ev(now - 60 * 60_000L, 1, now - 11 * 60_000L, 0, now - 10 * 60_000L, 1), now));
        assertEquals("no events → 0", 0, Wellbeing.screenOnFor(new ArrayList<long[]>(), now));

        Application app = RuntimeEnvironment.getApplication();
        final Engine e = Engine.get(app);
        Shadows.shadowOf(app).grantPermissions("android.permission.POST_NOTIFICATIONS");
        Wellbeing.set(app, true, 40, true);

        UsageStatsManager usm = (UsageStatsManager) app.getSystemService(Context.USAGE_STATS_SERVICE);
        ShadowUsageStatsManager su = Shadows.shadowOf(usm);
        long t = System.currentTimeMillis();
        su.addEvent(app.getPackageName(), t - 50 * 60_000L, SCREEN_INTERACTIVE); // screen on 50 min ago, still on

        // without usage access: nothing, even though the screen has been on long
        ShadowAppOpsManager ops = Shadows.shadowOf((android.app.AppOpsManager) app.getSystemService(Context.APP_OPS_SERVICE));
        ops.setMode("android:get_usage_stats", android.os.Process.myUid(), app.getPackageName(), android.app.AppOpsManager.MODE_IGNORED);
        assertFalse(Wellbeing.hasAccess(app));
        Wellbeing.check(app);
        assertEquals("no usage access → no nudge", 0, notes(app));

        // access granted: a nudge, with the minutes in it
        ops.setMode("android:get_usage_stats", android.os.Process.myUid(), app.getPackageName(), android.app.AppOpsManager.MODE_ALLOWED);
        assertTrue(Wellbeing.hasAccess(app));
        Wellbeing.check(app);
        assertEquals("nudged", 1, notes(app));
        android.app.Notification n = Shadows.shadowOf((NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE))
                .getAllNotifications().get(0);
        assertEquals("Разомнись", String.valueOf(n.extras.getCharSequence("android.title")));
        String text = String.valueOf(n.extras.getCharSequence("android.text"));
        assertTrue(text, text.contains("50 мин") && text.contains("размяться"));

        // within the cool-down: no second nudge
((NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE)).cancelAll();
        Wellbeing.check(app);
        assertEquals("cool-down: quiet", 0, notes(app));

        // the screen went off: the duration is 0, so no nudge after the cool-down either
        e.prefs().edit().putLong("wellbeing_nudged", 0).apply();
        su.addEvent(app.getPackageName(), t - 60_000L, SCREEN_NON_INTERACTIVE);
        Wellbeing.check(app);
        assertEquals("screen off → nothing", 0, notes(app));

        // quiet hours: at 1:00, nothing
        java.util.Calendar night = java.util.Calendar.getInstance();
        night.set(java.util.Calendar.HOUR_OF_DAY, 1);
        assertTrue(Wellbeing.isQuiet(app, night.getTimeInMillis()));
        java.util.Calendar noon = java.util.Calendar.getInstance();
        noon.set(java.util.Calendar.HOUR_OF_DAY, 12);
        assertFalse(Wellbeing.isQuiet(app, noon.getTimeInMillis()));

        // off: the alarm is cancelled and nothing fires
        Wellbeing.set(app, false, 40, true);
        assertFalse(Wellbeing.on(app));
    }
}
