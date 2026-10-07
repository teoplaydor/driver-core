package androidx.test.runner.intent;

public final class IntentMonitorRegistry {
    private static volatile IntentMonitor instance;

    public static void registerInstance(IntentMonitor monitor) { instance = monitor; }

    public static IntentMonitor getInstance() { return instance; }
}
