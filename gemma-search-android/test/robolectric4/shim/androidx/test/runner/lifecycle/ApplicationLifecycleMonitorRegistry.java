package androidx.test.runner.lifecycle;

public final class ApplicationLifecycleMonitorRegistry {
    private static volatile ApplicationLifecycleMonitor instance;

    public static void registerInstance(ApplicationLifecycleMonitor monitor) { instance = monitor; }

    public static ApplicationLifecycleMonitor getInstance() { return instance; }
}
