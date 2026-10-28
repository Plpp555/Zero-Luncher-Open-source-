package io.github.plpp555.zerolauncher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Bundle;

/** The home screen. All UI is a single {@link AppListView}. */
public final class HomeActivity extends Activity {
    private AppStore store;
    private AppListView view;
    private int appliedGeneration = -1;
    /** True from onResume until onStop, i.e. while the home screen is (or just was) on screen. */
    private boolean onScreen;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        PerfLog.refresh();
        Prefs.load(this);
        store = AppStore.get(this);
        store.checkLocale();
        view = new AppListView(this, store);
        if (state != null) view.setPageNow(state.getInt("page"));
        setContentView(view);
        applyPrefs();
        store.setListener(view);
        view.requestFocus();
    }

    private void applyPrefs() {
        appliedGeneration = Prefs.generation;
        // 16-bit surfaces halve the memory of the window buffers; plain text on a flat color
        // looks the same.
        getWindow().setFormat(Prefs.color16 ? PixelFormat.RGB_565 : PixelFormat.OPAQUE);
        Ui.styleWindow(getWindow());
        view.applyPrefs();
    }

    @Override
    protected void onStart() {
        super.onStart();
        PerfLog.refresh();
        if (appliedGeneration != Prefs.generation) applyPrefs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        onScreen = true;
    }

    @Override
    protected void onStop() {
        super.onStop();
        onScreen = false;
        view.resetTouch();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: back to the first page. Coming back from an app keeps
        // the page the user was on.
        if (onScreen) view.goPage(0);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Never finish the home screen; back only returns to the first page.
        if (view.page() > 0) view.goPage(0);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("page", view.page());
    }

    @Override
    protected void onDestroy() {
        store.clearListener(view);
        super.onDestroy();
    }

    void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }
}
