package io.github.teoplaydor.semsearch.app;

import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.Locale;

/**
 * Indexes the gallery while the phone rests: the screen is off, it is charging or has enough charge,
 * no battery saver, not warm. The moment the screen turns on the run pauses (and after a few minutes
 * of use the model is unloaded so other apps keep their memory). When nothing is left to index the
 * service stops itself; new photos are then handled by the regular background job.
 */
public final class IdleIndexService extends Service implements Engine.Listener {
    static final String ACTION_STOP = "io.github.teoplaydor.semsearch.IDLE_STOP";
    static final int NOTIFICATION = 4301;
    /** Let a quick glance at the clock pass before loading the model. */
    static final long SETTLE_MS = 20_000;
    /** Free the model's memory when the phone has been in use this long. */
    static final long RELEASE_AFTER_MS = 3 * 60_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Engine engine;
    private PowerManager.WakeLock wake;
    private boolean ownRun, checking, finished;
    private String lastText = "";
    private int noWorkStreak;

    private final BroadcastReceiver events = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(a) || Intent.ACTION_USER_PRESENT.equals(a)) {
                pause(); // picked up: give the phone back at once
            }
            main.removeCallbacks(reconsider);
            main.postDelayed(reconsider, Intent.ACTION_SCREEN_OFF.equals(a) ? SETTLE_MS : 1000);
        }
    };

    private final Runnable reconsider = new Runnable() {
        @Override
        public void run() {
            reconsider();
        }
    };

    private final Runnable release = new Runnable() {
        @Override
        public void run() {
            if (engine != null && !engine.indexing) engine.releaseIfBackground();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTIFICATION, IdleIndex.notification(this, "ждёт, пока экран погаснет", 0, 0));
        engine = Engine.get(this);
        engine.addListener(this);
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        f.addAction(Intent.ACTION_BATTERY_LOW);
        f.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        registerReceiver(events, f);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "semsearch:idle-index");
        wake.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            IdleIndex.prefs(this).edit().putBoolean("idle_index", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!IdleIndex.enabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        main.removeCallbacks(reconsider);
        main.post(reconsider);
        return START_STICKY; // killed for memory: Android brings the service back
    }

    /** Starts, keeps or pauses the run according to the phone's state right now. */
    private void reconsider() {
        if (finished) return;
        if (!IdleIndex.enabled(this) || !AutoIndex.hasMediaAccess(this)) {
            stopSelf();
            return;
        }
        String wait = IdleIndex.waitReason(this);
        if (wait != null) {
            pause();
            show(wait, 0, 0);
            return;
        }
        main.removeCallbacks(release);
        if (engine.indexing || checking) {
            hold();
            return;
        }
        if (!engine.hasModelFiles()) {
            show("ждёт, пока скачается модель", 0, 0);
            return;
        }
        checking = true;
        hold();
        engine.countPending(new Engine.Callback<Integer>() {
            @Override
            public void done(Integer n, Exception e) {
                checking = false;
                if (n == null || n == 0) {
                    if (++noWorkStreak >= 2) finish(); // nothing new twice in a row: the job is done
                    else {
                        letGo();
                        main.postDelayed(reconsider, 60_000);
                    }
                    return;
                }
                noWorkStreak = 0;
                if (IdleIndex.waitReason(IdleIndexService.this) != null) {
                    reconsider();
                    return;
                }
                engine.ensureLoadedForIndexing();
                onEngineChanged();
            }
        });
    }

    @Override
    public void onEngineChanged() {
        if (finished) return;
        if (engine.indexing) {
            if (ownRun) {
                hold();
                show(String.format(Locale.ROOT, "%d из %d", engine.idxDone, engine.idxTotal), engine.idxDone, engine.idxTotal);
            }
            return;
        }
        if (ownRun) {
            // our run ended (done, or paused by the screen): look again
            ownRun = false;
            letGo();
            main.removeCallbacks(reconsider);
            main.postDelayed(reconsider, 2000);
            return;
        }
        if (checking || !wake.isHeld()) return;
        if (engine.ready()) {
            if (IdleIndex.waitReason(this) == null) {
                ownRun = true;
                engine.startIndexFromPrefs(true);
            } else {
                letGo();
            }
        } else if (engine.state == Engine.State.ERROR || engine.state == Engine.State.NO_MODEL) {
            letGo();
        }
    }

    private void pause() {
        if (ownRun && engine.indexing && engine.backgroundRun) engine.stopIndex();
        main.removeCallbacks(release);
        main.postDelayed(release, RELEASE_AFTER_MS);
        letGo();
    }

    private void hold() {
        wake.acquire(10 * 60_000L); // renewed with every progress step; never held past a crash
    }

    private void letGo() {
        if (wake.isHeld()) wake.release();
    }

    private void show(String text, int done, int total) {
        if (text.equals(lastText) && total == 0) return;
        lastText = text;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION, IdleIndex.notification(this, text, done, total));
    }

    private void finish() {
        finished = true;
        letGo();
        engine.releaseIfBackground();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(events);
        } catch (Exception ignored) {
            // not registered
        }
        if (engine != null) {
            engine.removeListener(this);
            if (ownRun && engine.backgroundRun) engine.stopIndex();
            engine.releaseIfBackground();
        }
        letGo();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
