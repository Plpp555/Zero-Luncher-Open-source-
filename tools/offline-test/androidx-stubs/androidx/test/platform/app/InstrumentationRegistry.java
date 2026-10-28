package androidx.test.platform.app;
public final class InstrumentationRegistry {
    private static android.app.Instrumentation sInstrumentation;
    public static android.app.Instrumentation getInstrumentation() { return sInstrumentation; }
    public static void registerInstance(android.app.Instrumentation i, android.os.Bundle args) { sInstrumentation = i; }
}
