package androidx.test.runner.lifecycle;
public final class ActivityLifecycleMonitorRegistry {
    private static ActivityLifecycleMonitor sInstance;
    public static ActivityLifecycleMonitor getInstance() { return sInstance; }
    public static void registerInstance(ActivityLifecycleMonitor m) { sInstance = m; }
}
