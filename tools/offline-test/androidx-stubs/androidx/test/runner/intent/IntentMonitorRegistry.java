package androidx.test.runner.intent;
public final class IntentMonitorRegistry {
    private static IntentMonitor sInstance;
    public static IntentMonitor getInstance() { return sInstance; }
    public static void registerInstance(IntentMonitor m) { sInstance = m; }
}
