package io.github.teoplaydor.semsearch.app;

import android.app.job.JobParameters;
import android.app.job.JobService;

/**
 * Indexes new photos and videos in the background. It first checks MediaStore against the index
 * without the model; only when there is something new does it load the model (or reuse the one
 * the open app already has), index, and unload it again if no screen is showing.
 */
public final class AutoIndexService extends JobService implements Engine.Listener {
    private Engine engine;
    private JobParameters params;
    private boolean checking, started;

    @Override
    public boolean onStartJob(JobParameters p) {
        if (!AutoIndex.enabled(this) || !AutoIndex.hasMediaAccess(this)) return false;
        engine = Engine.get(this);
        if (!engine.hasModelFiles()) return false;
        params = p;
        engine.addListener(this);
        if (engine.indexing) {
            started = true; // the app is already indexing: this run just waits for it
        } else {
            check();
        }
        return true;
    }

    private void check() {
        checking = true;
        engine.countPending(new Engine.Callback<Integer>() {
            @Override
            public void done(Integer n, Exception e) {
                checking = false;
                if (params == null) return;
                if (e != null || n == null || n == 0) {
                    finish();
                    return;
                }
                engine.ensureLoaded();
                onEngineChanged();
            }
        });
    }

    @Override
    public void onEngineChanged() {
        if (params == null || checking) return;
        if (!started) {
            if (engine.ready()) {
                started = true;
                engine.startIndexFromPrefs(true);
            } else if (engine.state == Engine.State.ERROR || engine.state == Engine.State.NO_MODEL) {
                finish();
            }
            return;
        }
        if (!engine.indexing) finish();
    }

    private void finish() {
        JobParameters p = params;
        params = null;
        engine.removeListener(this);
        engine.releaseIfBackground();
        AutoIndex.schedule(this); // a content-trigger job fires once: arm it again
        if (p != null) jobFinished(p, false);
    }

    @Override
    public boolean onStopJob(JobParameters p) {
        params = null;
        if (engine != null) {
            engine.removeListener(this);
            if (engine.backgroundRun) engine.stopIndex();
        }
        return true; // the system retries later
    }
}
