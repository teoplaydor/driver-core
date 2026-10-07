package androidx.test.internal.runner.lifecycle;

import android.app.Activity;

import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitor;
import androidx.test.runner.lifecycle.Stage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ActivityLifecycleMonitorImpl implements ActivityLifecycleMonitor {
    private final Map<Activity, Stage> stages = new WeakHashMap<>();
    private final List<ActivityLifecycleCallback> callbacks = new CopyOnWriteArrayList<>();

    public synchronized void signalLifecycleChange(Stage stage, Activity activity) {
        stages.put(activity, stage);
        for (ActivityLifecycleCallback c : callbacks) c.onActivityLifecycleChanged(activity, stage);
    }

    @Override
    public void addLifecycleCallback(ActivityLifecycleCallback callback) { callbacks.add(callback); }

    @Override
    public void removeLifecycleCallback(ActivityLifecycleCallback callback) { callbacks.remove(callback); }

    @Override
    public synchronized Stage getLifecycleStageOf(Activity activity) { return stages.get(activity); }

    @Override
    public synchronized Collection<Activity> getActivitiesInStage(Stage stage) {
        List<Activity> out = new ArrayList<>();
        for (Map.Entry<Activity, Stage> e : stages.entrySet()) if (e.getValue() == stage) out.add(e.getKey());
        return out;
    }
}
