package androidx.test.platform.app;

import android.app.Instrumentation;
import android.os.Bundle;

public final class InstrumentationRegistry {
    private static volatile Instrumentation instrumentation;
    private static volatile Bundle arguments = new Bundle();

    public static void registerInstance(Instrumentation i, Bundle args) {
        instrumentation = i;
        arguments = args != null ? args : new Bundle();
    }

    public static Instrumentation getInstrumentation() { return instrumentation; }

    public static Bundle getArguments() { return arguments; }
}
