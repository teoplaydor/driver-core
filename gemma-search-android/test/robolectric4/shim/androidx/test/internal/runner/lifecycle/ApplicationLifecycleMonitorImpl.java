package androidx.test.internal.runner.lifecycle;

import android.app.Application;

import androidx.test.runner.lifecycle.ApplicationLifecycleMonitor;
import androidx.test.runner.lifecycle.ApplicationStage;

public final class ApplicationLifecycleMonitorImpl implements ApplicationLifecycleMonitor {
    public void signalLifecycleChange(Application app, ApplicationStage stage) {}
}
