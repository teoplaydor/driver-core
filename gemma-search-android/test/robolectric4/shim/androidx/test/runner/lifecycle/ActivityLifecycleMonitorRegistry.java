package androidx.test.runner.lifecycle;

public final class ActivityLifecycleMonitorRegistry {
    private static volatile ActivityLifecycleMonitor instance;

    public static void registerInstance(ActivityLifecycleMonitor monitor) { instance = monitor; }

    public static ActivityLifecycleMonitor getInstance() { return instance; }
}
