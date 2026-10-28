package androidx.test.runner.lifecycle;
public interface ActivityLifecycleMonitor {
    void addLifecycleCallback(ActivityLifecycleCallback callback);
    void removeLifecycleCallback(ActivityLifecycleCallback callback);
    Stage getLifecycleStageOf(android.app.Activity activity);
    java.util.Collection<android.app.Activity> getActivitiesInStage(Stage stage);
}
