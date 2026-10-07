import static org.junit.Assert.*;

import android.app.Application;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.provider.MediaStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;

import io.github.teoplaydor.semsearch.app.AutoIndex;
import io.github.teoplaydor.semsearch.app.AutoIndexService;
import io.github.teoplaydor.semsearch.app.Engine;
import io.github.teoplaydor.semsearch.app.IndexStore;

/**
 * Background indexing: the jobs are armed (MediaStore content triggers + a persisted 6-hour safety
 * net, battery not low), a run with new photos indexes them and frees the model afterwards, a run
 * with nothing new never touches the model, and turning the switch off cancels everything.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AutoIndexTest {
    static JobParameters params(int jobId) throws Exception {
        Constructor<?> best = null;
        for (Constructor<?> k : JobParameters.class.getDeclaredConstructors()) {
            if (best == null || k.getParameterTypes().length > best.getParameterTypes().length) best = k;
        }
        Class<?>[] types = best.getParameterTypes();
        Object[] args = new Object[types.length];
        boolean idSet = false;
        for (int i = 0; i < types.length; i++) {
            if (types[i] == int.class) {
                args[i] = idSet ? 0 : jobId;
                idSet = true;
            } else if (types[i] == boolean.class) args[i] = false;
            else if (types[i] == long.class) args[i] = 0L;
            else args[i] = null;
        }
        best.setAccessible(true);
        return (JobParameters) best.newInstance(args);
    }

    static Map<Integer, JobInfo> jobs(Context c) {
        Map<Integer, JobInfo> m = new HashMap<Integer, JobInfo>();
        for (JobInfo j : ((JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE)).getAllPendingJobs()) m.put(j.getId(), j);
        return m;
    }

    static void photo(File dir, long id, long date) throws Exception {
        Bitmap b = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888);
        new Canvas(b).drawColor(Color.rgb((int) (id * 37 % 255), 120, 80));
        File f = new File(dir, id + ".png");
        try (FileOutputStream o = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
        }
        FakeMediaStore.ROWS.add(new FakeMediaStore.Row(id, false, date, "IMG_" + id + ".png", 40, 30, f));
    }

    @Test
    public void jobsAndBackgroundRuns() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        File dir = new File(app.getCacheDir(), "media");
        assertTrue(dir.mkdirs());
        photo(dir, 1, 1700000000L);
        photo(dir, 2, 1700000100L);
        FakeMediaStore.install();

        // Without access to photos there is nothing to run.
        AutoIndexService svc = Robolectric.buildService(AutoIndexService.class).create().get();
        assertFalse(svc.onStartJob(params(4201)));

        Shadows.shadowOf(app).grantPermissions("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO");
        AutoIndex.schedule(app);
        Map<Integer, JobInfo> js = jobs(app);
        assertEquals(js.keySet().toString(), 2, js.size());
        JobInfo content = js.get(4201), periodic = js.get(4202);
        assertNotNull(content.getTriggerContentUris());
        assertEquals(2, content.getTriggerContentUris().length);
        assertEquals(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, content.getTriggerContentUris()[0].getUri());
        assertEquals(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, content.getTriggerContentUris()[1].getUri());
        assertEquals(JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS, content.getTriggerContentUris()[0].getFlags());
        assertEquals(15_000L, content.getTriggerContentUpdateDelay());
        assertEquals(120_000L, content.getTriggerContentMaxDelay());
        assertTrue(content.isRequireBatteryNotLow());
        assertTrue(periodic.isPeriodic());
        assertEquals(6L * 3600 * 1000, periodic.getIntervalMillis());
        assertTrue(periodic.isPersisted());
        assertTrue(periodic.isRequireBatteryNotLow());

        // No model downloaded: the job ends at once.
        assertFalse(svc.onStartJob(params(4201)));

        // A run with two new photos: indexes them with the model, then lets the model go.
        final Engine e = Engine.get(app);
        Robo.waitFor("store", () -> e.store() != null);
        e.prefs().edit().putInt("photo_model", 0).apply();
        File model = new File(app.getFilesDir(), "model");
        assertTrue(model.mkdirs());
        try (FileOutputStream o = new FileOutputStream(new File(model, "manifest.json"))) {
            o.write("{}".getBytes("UTF-8"));
        }
        Robo.FakeEmbedder fake = new Robo.FakeEmbedder("cat", "dog");
        e.attachModelForTest(fake);
        Robo.waitFor("ready", e::ready);
        assertTrue(svc.onStartJob(params(4201)));
        Robo.waitFor("indexed", () -> e.store().count(IndexStore.KIND_PHOTO) == 2 && !e.indexing);
        Robo.waitFor("job finished", () -> Shadows.shadowOf(svc).getIsJobFinished());
        assertEquals(Engine.State.NO_MODEL, e.state); // nobody is looking at the app: memory freed
        assertEquals(2, jobs(app).size()); // the one-shot content trigger is armed again

        // Nothing new: finishes without loading the model (it would fail to load from this manifest).
        ServiceController<AutoIndexService> c2 = Robolectric.buildService(AutoIndexService.class).create();
        AutoIndexService svc2 = c2.get();
        int before = FakeMediaStore.queries;
        assertTrue(svc2.onStartJob(params(4202)));
        Robo.waitFor("job finished", () -> Shadows.shadowOf(svc2).getIsJobFinished());
        assertTrue(FakeMediaStore.queries > before);
        assertEquals(Engine.State.NO_MODEL, e.state);

        // A new screenshot appears: the next run picks it up (reusing a model the app already has).
        photo(dir, 3, 1700000200L);
        e.attachModelForTest(fake);
        Robo.waitFor("ready", e::ready);
        e.uiVisible = true;
        AutoIndexService svc3 = Robolectric.buildService(AutoIndexService.class).create().get();
        assertTrue(svc3.onStartJob(params(4201)));
        Robo.waitFor("job finished", () -> Shadows.shadowOf(svc3).getIsJobFinished());
        assertEquals(3, e.store().count(IndexStore.KIND_PHOTO));
        assertEquals(Engine.State.READY, e.state); // the app is open: the model stays
        e.uiVisible = false;

        // The switch off cancels both jobs; on arms them again.
        AutoIndex.setEnabled(app, false);
        assertEquals(0, jobs(app).size());
        AutoIndexService svc4 = Robolectric.buildService(AutoIndexService.class).create().get();
        assertFalse(svc4.onStartJob(params(4202)));
        AutoIndex.setEnabled(app, true);
        assertEquals(2, jobs(app).size());
    }
}
