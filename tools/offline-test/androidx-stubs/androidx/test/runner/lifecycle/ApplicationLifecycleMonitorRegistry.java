package androidx.test.runner.lifecycle;
public final class ApplicationLifecycleMonitorRegistry {
    private static ApplicationLifecycleMonitor sInstance;
    public static ApplicationLifecycleMonitor getInstance() { return sInstance; }
    public static void registerInstance(ApplicationLifecycleMonitor m) { sInstance = m; }
}
