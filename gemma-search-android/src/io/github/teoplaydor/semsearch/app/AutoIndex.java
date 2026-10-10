package io.github.teoplaydor.semsearch.app;

import android.Manifest;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Background indexing: a JobScheduler job that fires when MediaStore changes (a new photo,
 * screenshot or video) and a 6-hour safety net, both only while the battery is not low. Content
 * triggers are API 24+ and the app compiles against API 23, so they are wired up by reflection.
 */
public final class AutoIndex {
    static final int JOB_CONTENT = 4201, JOB_PERIODIC = 4202;

    private AutoIndex() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) {
        return prefs(c).getBoolean("auto_index", true);
    }

    public static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("auto_index", on).apply();
        if (on) schedule(c);
        else cancel(c);
    }

    /** Any read access to photos (full, or the user-selected subset on Android 14+). */
    static boolean hasMediaAccess(Context c) {
        if (Build.VERSION.SDK_INT >= 33) {
            return granted(c, "android.permission.READ_MEDIA_IMAGES")
                    || (Build.VERSION.SDK_INT >= 34 && granted(c, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"));
        }
        return granted(c, Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    /** Read access to sound files (recordings, voice messages, music). */
    static boolean hasAudioAccess(Context c) {
        return granted(c, Build.VERSION.SDK_INT >= 33 ? "android.permission.READ_MEDIA_AUDIO" : Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    /** Access to the whole gallery: only then can a photo missing from MediaStore be treated as deleted. */
    static boolean hasFullMediaAccess(Context c) {
        return Build.VERSION.SDK_INT >= 33 ? granted(c, "android.permission.READ_MEDIA_IMAGES")
                : granted(c, Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    private static boolean granted(Context c, String p) {
        return c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    /** (Re)arms both jobs; cheap and idempotent, called on app start and after every run. */
    public static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        if (!enabled(c)) {
            cancel(c);
            return;
        }
        ComponentName svc = new ComponentName(c, AutoIndexService.class);
        try {
            JobInfo.Builder b = new JobInfo.Builder(JOB_CONTENT, svc);
            Class<?> trigger = Class.forName("android.app.job.JobInfo$TriggerContentUri");
            Constructor<?> make = trigger.getConstructor(Uri.class, int.class);
            Method add = JobInfo.Builder.class.getMethod("addTriggerContentUri", trigger);
            int descendants = 1; // TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
            add.invoke(b, make.newInstance(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, descendants));
            add.invoke(b, make.newInstance(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, descendants));
            // new recordings and voice messages too (indexed when the audio encoder is there)
            if (hasAudioAccess(c)) add.invoke(b, make.newInstance(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, descendants));
            // let a burst of new photos settle, but never wait longer than two minutes
            JobInfo.Builder.class.getMethod("setTriggerContentUpdateDelay", long.class).invoke(b, 15_000L);
            JobInfo.Builder.class.getMethod("setTriggerContentMaxDelay", long.class).invoke(b, 120_000L);
            batteryNotLow(b);
            js.schedule(b.build());
        } catch (Exception e) {
            android.util.Log.w("SemSearch", "content-trigger job", e);
        }
        JobInfo.Builder p = new JobInfo.Builder(JOB_PERIODIC, svc)
                .setPeriodic(6L * 3600 * 1000)
                .setPersisted(true); // survives reboots and re-arms the content job from there
        batteryNotLow(p);
        js.schedule(p.build());
    }

    static void cancel(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        js.cancel(JOB_CONTENT);
        js.cancel(JOB_PERIODIC);
    }

    private static void batteryNotLow(JobInfo.Builder b) {
        try {
            JobInfo.Builder.class.getMethod("setRequiresBatteryNotLow", boolean.class).invoke(b, true); // API 26
        } catch (Exception ignored) {
            // older platform: run regardless
        }
    }
}
