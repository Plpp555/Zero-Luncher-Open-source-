# Members that LauncherTest calls or reads by reflection. Only used for the test variant of the
# ProGuard output (build/manual/classes-pg-test.jar); the APK itself keeps none of these.
-keepclassmembers class io.github.plpp555.zerolauncher.AppListView {
    private ** list;
    private int rows;
    private int pages;
    private int top;
    private int rowH;
    private boolean animating;
    private void ensureMetrics();
    int page();
}
-keepclassmembers class io.github.plpp555.zerolauncher.AppStore {
    static ** get(android.content.Context);
    static void resetForTest();
    boolean isIdle();
    boolean isLoaded();
    ** ordered(int);
    void requestFull(boolean);
}
-keepclassmembers class io.github.plpp555.zerolauncher.App { *; }
-keepclassmembers class io.github.plpp555.zerolauncher.Prefs { *; }
-keep class io.github.plpp555.zerolauncher.R* { *; }
