package io.github.plpp555.zerolauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.graphics.PixelFormat;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowLauncherApps;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.lang.reflect.Constructor;

/**
 * Functional tests on the real Android framework code (Robolectric) for several API levels.
 * Covers loading, sorting, paging, swiping, launching, package change events, the label cache,
 * settings and home-button behaviour.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {23, 26, 29, 30, 35}, shadows = LauncherTest.GlyphlessLabelShadow.class)
public class LauncherTest {
    /**
     * API 34+ LauncherActivityInfo.getLabel() measures glyphs to drop invisible labels.
     * Robolectric's default Paint has no glyphs, so every label would look invisible; this
     * restores the plain label lookup for tests only.
     */
    @Implements(value = LauncherActivityInfo.class, minSdk = 34)
    public static class GlyphlessLabelShadow {
        @RealObject
        private LauncherActivityInfo real;

        @Implementation
        protected CharSequence getLabel() {
            return real.getActivityInfo().loadLabel(
                    RuntimeEnvironment.getApplication().getPackageManager());
        }
    }

    private Context ctx;
    private ShadowLauncherApps shadowLa;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        shadowLa = shadowOf((LauncherApps) ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE));
        AppStore.resetForTest();
        Prefs.reset(ctx);
        new File(ctx.getCacheDir(), "apps.bin").delete();
    }

    @After
    public void tearDown() {
        AppStore.resetForTest();
    }

    // ------------------------------------------------------------------ helpers

    private void addApp(String pkg, String cls, String label, long installTime) {
        addApp(pkg, cls, label, installTime, true);
    }

    private void addApp(String pkg, String cls, String label, long installTime, boolean install) {
        ApplicationInfo app = new ApplicationInfo();
        app.packageName = pkg;
        app.sourceDir = "/data/app/" + pkg + "/base.apk";
        app.flags = ApplicationInfo.FLAG_INSTALLED;
        app.enabled = true;
        PackageInfo pi = new PackageInfo();
        pi.packageName = pkg;
        pi.applicationInfo = app;
        pi.firstInstallTime = installTime;
        if (install) shadowOf(ctx.getPackageManager()).installPackage(pi);

        ActivityInfo ai = new ActivityInfo();
        ai.packageName = pkg;
        ai.name = cls;
        ai.nonLocalizedLabel = label;
        ai.applicationInfo = app;
        ai.enabled = true;
        ai.exported = true;
        shadowLa.addActivity(Process.myUserHandle(), lai(ai, installTime));
    }

    /** LauncherActivityInfo has a hidden constructor whose signature differs between releases. */
    private LauncherActivityInfo lai(ActivityInfo ai, long installTime) {
        UserHandle user = Process.myUserHandle();
        try {
            for (Constructor<?> c : LauncherActivityInfo.class.getDeclaredConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                c.setAccessible(true);
                if (p.length == 4 && p[1] == ResolveInfo.class) {
                    ResolveInfo ri = new ResolveInfo();
                    ri.activityInfo = ai;
                    return (LauncherActivityInfo) c.newInstance(ctx, ri, user, installTime);
                }
                if (p.length == 3 && p[1] == ActivityInfo.class) {
                    return (LauncherActivityInfo) c.newInstance(ctx, ai, user);
                }
                if (p.length == 2 && p[1].getSimpleName().equals("LauncherActivityInfoInternal")) {
                    Class<?> statesCls = Class.forName("android.content.pm.IncrementalStatesInfo");
                    Object states = null;
                    for (Constructor<?> sc : statesCls.getDeclaredConstructors()) {
                        Class<?>[] sp = sc.getParameterTypes();
                        sc.setAccessible(true);
                        if (sp.length == 3) states = sc.newInstance(false, 1f, 0L);
                        else if (sp.length == 2) states = sc.newInstance(false, 1f);
                        if (states != null) break;
                    }
                    Object internal = p[1].getConstructor(ActivityInfo.class, statesCls,
                            UserHandle.class).newInstance(ai, states, user);
                    return (LauncherActivityInfo) c.newInstance(ctx, internal);
                }
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        throw new AssertionError("no usable LauncherActivityInfo constructor");
    }

    private void removeAllShadowActivities() {
        Object list = ReflectionHelpers.getStaticField(ShadowLauncherApps.class, "activityList");
        ReflectionHelpers.callInstanceMethod(list, "clear");
    }

    /** Waits for the background loader and delivers its result on the main looper. */
    private static void settle() {
        long end = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle();
            if (AppStore.get(RuntimeEnvironment.getApplication()).isIdle()) {
                shadowOf(Looper.getMainLooper()).idle();
                return;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
        fail("app list loader did not finish");
    }

    private static AppListView view(Activity a) {
        ViewGroup content = a.findViewById(android.R.id.content);
        return (AppListView) content.getChildAt(0);
    }

    private static App[] shown(AppListView v) {
        return ReflectionHelpers.getField(v, "list");
    }

    private static int intField(AppListView v, String name) {
        ReflectionHelpers.callInstanceMethod(v, "ensureMetrics");
        return (Integer) ReflectionHelpers.getField(v, name);
    }

    private ActivityController<HomeActivity> startHome() {
        ActivityController<HomeActivity> c = Robolectric.buildActivity(HomeActivity.class).setup();
        settle();
        return c;
    }

    private static MotionEvent touch(long down, long at, int action, float x, float y) {
        MotionEvent e = MotionEvent.obtain(down, at, action, x, y, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        return e;
    }

    private static void tap(AppListView v, float x, float y) {
        long t = SystemClock.uptimeMillis();
        v.dispatchTouchEvent(touch(t, t, MotionEvent.ACTION_DOWN, x, y));
        v.dispatchTouchEvent(touch(t, t + 50, MotionEvent.ACTION_UP, x, y));
    }

    private static void tapRow(AppListView v, int row) {
        tap(v, 100, intField(v, "top") + row * intField(v, "rowH") + intField(v, "rowH") / 2);
    }

    private static void swipe(AppListView v, float fromX, float toX) {
        float y = v.getHeight() / 3f;
        long t = SystemClock.uptimeMillis();
        v.dispatchTouchEvent(touch(t, t, MotionEvent.ACTION_DOWN, fromX, y));
        for (int i = 1; i <= 4; i++) {
            float x = fromX + (toX - fromX) * i / 5f;
            v.dispatchTouchEvent(touch(t, t + i * 10, MotionEvent.ACTION_MOVE, x, y));
        }
        v.dispatchTouchEvent(touch(t, t + 50, MotionEvent.ACTION_UP, toX, y));
    }

    private void addMany(int n) {
        for (int i = 0; i < n; i++) {
            String num = String.format("%02d", i);
            addApp("com.example.app" + num, "com.example.app" + num + ".Main", "App " + num, i);
        }
    }

    // ------------------------------------------------------------------ tests

    @Test
    public void listsAppsSortedByName_andLaunchesOnTap() {
        addApp("com.example.zebra", "com.example.zebra.Main", "Zebra", 1);
        addApp("com.example.alpha", "com.example.alpha.Main", "alpha", 2);
        addApp("com.example.mail", "com.example.mail.Inbox", "Mail", 3);
        HomeActivity home = startHome().get();
        AppListView v = view(home);

        App[] apps = shown(v);
        assertEquals(3, apps.length);
        assertEquals("alpha", apps[0].title);
        assertEquals("Mail", apps[1].title);
        assertEquals("Zebra", apps[2].title);

        tapRow(v, 1);
        Intent started = shadowOf(home).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(new ComponentName("com.example.mail", "com.example.mail.Inbox"),
                started.getComponent());
        assertTrue(started.hasCategory(Intent.CATEGORY_LAUNCHER));
        int flags = started.getFlags();
        assertTrue((flags & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        // Animations are off by default.
        assertTrue((flags & Intent.FLAG_ACTIVITY_NO_ANIMATION) != 0);
        assertNotNull(started.getSourceBounds());
    }

    @Test
    public void ownPackageIsNotListed() {
        addApp(ctx.getPackageName(), SettingsActivity.class.getName(), "Zero Launcher", 1, false);
        addApp("com.example.alpha", "com.example.alpha.Main", "Alpha", 2);
        App[] apps = shown(view(startHome().get()));
        assertEquals(1, apps.length);
        assertEquals("com.example.alpha", apps[0].pkg);
    }

    @Test
    public void swipeSwitchesPagesInstantly_andBackReturnsToFirstPage() {
        addMany(30);
        ActivityController<HomeActivity> c = startHome();
        AppListView v = view(c.get());
        int rows = intField(v, "rows");
        int pages = intField(v, "pages");
        assertTrue("rows=" + rows, rows >= 1);
        assertEquals((30 + rows - 1) / rows, pages);
        assertTrue(pages > 1);

        float w = v.getWidth();
        swipe(v, w * 0.8f, w * 0.2f);
        assertEquals(1, v.page());
        assertFalse("no animation by default", (Boolean) ReflectionHelpers.getField(v, "animating"));

        swipe(v, w * 0.2f, w * 0.8f);
        assertEquals(0, v.page());
        // Swiping right on the first page stays there.
        swipe(v, w * 0.2f, w * 0.8f);
        assertEquals(0, v.page());

        // A short, slow drag is not a page switch.
        long t = SystemClock.uptimeMillis();
        float y = v.getHeight() / 3f;
        v.dispatchTouchEvent(touch(t, t, MotionEvent.ACTION_DOWN, w * 0.5f, y));
        v.dispatchTouchEvent(touch(t, t + 400, MotionEvent.ACTION_MOVE, w * 0.45f, y));
        v.dispatchTouchEvent(touch(t, t + 800, MotionEvent.ACTION_UP, w * 0.45f, y));
        assertEquals(0, v.page());

        swipe(v, w * 0.8f, w * 0.2f);
        swipe(v, w * 0.8f, w * 0.2f);
        assertEquals(2, v.page());
        c.get().onBackPressed();
        assertEquals(0, v.page());
        assertFalse(c.get().isFinishing());

        // Tapping a row on page 2 launches the app of that page.
        swipe(v, w * 0.8f, w * 0.2f);
        tapRow(v, 0);
        Intent started = shadowOf(c.get()).getNextStartedActivity();
        assertEquals(shown(v)[rows].pkg, started.getComponent().getPackageName());
    }

    @Test
    public void pageNumberTapAndDpadAlsoSwitchPages() {
        addMany(30);
        AppListView v = view(startHome().get());
        int pages = intField(v, "pages");
        v.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, new KeyEvent(KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_DPAD_RIGHT));
        assertEquals(1, v.page());
        // Footer, right half: the page number. Tapping it advances and wraps around.
        int footer = intField(v, "rows");
        int y = intField(v, "top") + footer * intField(v, "rowH") + intField(v, "rowH") / 2;
        for (int i = 1; i < pages; i++) tap(v, v.getWidth() * 0.9f, y);
        assertEquals(0, v.page());
    }

    @Test
    public void homeButtonWhileHomeGoesToFirstPage_butReturningFromAppKeepsPage() {
        addMany(30);
        ActivityController<HomeActivity> c = startHome();
        AppListView v = view(c.get());
        float w = v.getWidth();
        swipe(v, w * 0.8f, w * 0.2f);
        assertEquals(1, v.page());

        // The user opens an app and comes back with the home button.
        c.pause().stop();
        c.restart().start().newIntent(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)).resume();
        assertEquals(1, v.page());

        // Home pressed again while on the home screen.
        c.newIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
        assertEquals(0, v.page());
    }

    @Test
    public void packageEventsUpdateOnlyThatPackage() {
        addApp("com.example.alpha", "com.example.alpha.Main", "Alpha", 1);
        AppListView v = view(startHome().get());
        assertEquals(1, shown(v).length);

        addApp("com.example.beta", "com.example.beta.Main", "Beta", 2);
        shadowLa.notifyPackageAdded("com.example.beta");
        settle();
        assertEquals(2, shown(v).length);
        assertEquals("Beta", shown(v)[1].title);

        removeAllShadowActivities();
        addApp("com.example.beta", "com.example.beta.Main", "Beta", 2);
        shadowLa.notifyPackageRemoved("com.example.alpha");
        settle();
        assertEquals(1, shown(v).length);
        assertEquals("com.example.beta", shown(v)[0].pkg);
    }

    @Test
    public void coldStartShowsCachedList_andReusesLabelsUntilApkChanges() {
        addApp("com.example.alpha", "com.example.alpha.Main", "Alpha", 1);
        addApp("com.example.beta", "com.example.beta.Main", "Beta", 2);
        startHome();
        assertTrue(new File(ctx.getCacheDir(), "apps.bin").length() > 0);

        // "Process death": a fresh store must show the list before any loading happens.
        AppStore.resetForTest();
        removeAllShadowActivities();
        addApp("com.example.alpha", "com.example.alpha.Main", "Renamed", 1);
        addApp("com.example.beta", "com.example.beta.Main", "Beta", 2);
        AppStore s = AppStore.get(ctx);
        assertTrue(s.isLoaded());
        App[] cached = s.ordered(Prefs.SORT_NAME);
        assertEquals(2, cached.length);
        assertEquals("Alpha", cached[0].title);

        // Verification pass: same APK path and mtime, so the label is not reloaded.
        settle();
        assertEquals("Alpha", s.ordered(Prefs.SORT_NAME)[0].title);

        // Explicit reload (settings) picks up the new label.
        s.requestFull(true);
        settle();
        App[] reloaded = s.ordered(Prefs.SORT_NAME);
        assertEquals("Beta", reloaded[0].title);
        assertEquals("Renamed", reloaded[1].title);
    }

    @Test
    public void corruptCacheIsIgnored() throws Exception {
        java.io.FileOutputStream out =
                new java.io.FileOutputStream(new File(ctx.getCacheDir(), "apps.bin"));
        out.write(new byte[] {0x5A, 0x4C, 0x43, 0x01, 0x00, 0x05, 'x'});
        out.close();
        addApp("com.example.alpha", "com.example.alpha.Main", "Alpha", 1);
        AppListView v = view(startHome().get());
        assertEquals(1, shown(v).length);
    }

    @Test
    public void duplicateLabelsAreDisambiguated() {
        addApp("com.vendor.camera", "com.vendor.camera.Main", "Camera", 1);
        addApp("com.other.camera", "com.other.camera.Main", "Camera", 2);
        addApp("com.multi", "com.multi.Photos", "Gallery", 3);
        addApp("com.multi", "com.multi.Videos", "Gallery", 4);
        App[] apps = shown(view(startHome().get()));
        assertEquals("Camera (com.other.camera)", apps[0].title);
        assertEquals("Camera (com.vendor.camera)", apps[1].title);
        assertEquals("Gallery (Photos)", apps[2].title);
        assertEquals("Gallery (Videos)", apps[3].title);
    }

    @Test
    public void sortOrderSetting() {
        addApp("com.example.a", "com.example.a.Main", "A", 300);
        addApp("com.example.b", "com.example.b.Main", "B", 100);
        addApp("com.example.c", "com.example.c.Main", "C", 200);
        ActivityController<HomeActivity> c = startHome();
        AppListView v = view(c.get());

        Prefs.sort = Prefs.SORT_NAME_DESC;
        Prefs.save(ctx);
        c.pause().stop().restart().start().resume();
        assertEquals("C", shown(v)[0].title);
        assertEquals("A", shown(v)[2].title);

        Prefs.sort = Prefs.SORT_INSTALL;
        Prefs.save(ctx);
        c.pause().stop().restart().start().resume();
        assertEquals("A", shown(v)[0].title);
        assertEquals("C", shown(v)[1].title);
        assertEquals("B", shown(v)[2].title);
    }

    @Test
    public void manualRowsPerPage() {
        addMany(30);
        Prefs.rows = 5;
        Prefs.save(ctx);
        AppListView v = view(startHome().get());
        assertEquals(5, intField(v, "rows"));
        assertEquals(6, intField(v, "pages"));
    }

    @Test
    public void windowIs16BitByDefault_andFollowsSetting() {
        addMany(3);
        ActivityController<HomeActivity> c = startHome();
        assertEquals(PixelFormat.RGB_565, c.get().getWindow().getAttributes().format);
        Prefs.color16 = false;
        Prefs.save(ctx);
        c.pause().stop().restart().start().resume();
        assertEquals(PixelFormat.OPAQUE, c.get().getWindow().getAttributes().format);
    }

    @Test
    public void launchFailureIsHandled() {
        addApp("com.example.alpha", "com.example.alpha.Main", "Alpha", 1);
        HomeActivity home = startHome().get();
        AppListView v = view(home);
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true);
        tapRow(v, 0);
        assertNull(shadowOf(home).getNextStartedActivity());
        assertEquals(home.getString(R.string.cannot_open), ShadowToast.getTextOfLatestToast());
        assertFalse(home.isFinishing());
    }

    @Test
    public void settingsCycleValues_andResetNeedsTwoTaps() {
        SettingsActivity s = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        TextView text = s.findViewById(2);  // TEXT + 1
        int before = Prefs.textSize;
        text.performClick();
        assertEquals((before + 1) % Prefs.TEXT_SP.length, Prefs.textSize);

        TextView rows = s.findViewById(4);  // ROWS + 1
        rows.performClick();
        assertEquals(Prefs.ROW_CHOICES[1], Prefs.rows);

        TextView bg = s.findViewById(6);  // BG + 1
        bg.performClick();
        assertTrue(Prefs.white);

        TextView reset = s.findViewById(9);  // RESET + 1
        reset.performClick();
        assertTrue(Prefs.white);
        assertEquals(s.getString(R.string.s_reset_confirm), reset.getText().toString());
        reset.performClick();
        assertFalse(Prefs.white);
        assertEquals(1, Prefs.textSize);
        assertEquals(0, Prefs.rows);
    }

    @Test
    public void footerSettingsTextOpensSettings() {
        addMany(3);
        HomeActivity home = startHome().get();
        AppListView v = view(home);
        int y = intField(v, "top") + intField(v, "rows") * intField(v, "rowH") + intField(v, "rowH") / 2;
        tap(v, v.getWidth() * 0.1f, y);
        Intent started = shadowOf(home).getNextStartedActivity();
        assertEquals(SettingsActivity.class.getName(), started.getComponent().getClassName());
    }

    @Test
    public void accessibilityNodesExposeAppsAndClickLaunches() {
        addApp("com.example.alpha", "com.example.alpha.Main", "Alpha", 1);
        addApp("com.example.beta", "com.example.beta.Main", "Beta", 2);
        HomeActivity home = startHome().get();
        AppListView v = view(home);
        AccessibilityNodeProvider p = v.getAccessibilityNodeProvider();
        AccessibilityNodeInfo host = p.createAccessibilityNodeInfo(AccessibilityNodeProvider.HOST_VIEW_ID);
        assertEquals(4, host.getChildCount()); // two apps, "Settings", page number
        AccessibilityNodeInfo second = p.createAccessibilityNodeInfo(1);
        assertEquals("Beta", second.getText().toString());
        assertTrue(second.isClickable());
        assertTrue(p.performAction(1, AccessibilityNodeInfo.ACTION_CLICK, null));
        Intent started = shadowOf(home).getNextStartedActivity();
        assertEquals("com.example.beta", started.getComponent().getPackageName());
    }

    @Test
    public void idleHomeScreenHasNoWorkerThreadAndNoScheduledWork() {
        addMany(30);
        AppListView v = view(startHome().get());
        swipe(v, v.getWidth() * 0.8f, v.getWidth() * 0.2f);
        shadowOf(Looper.getMainLooper()).idle();
        // The loader thread exits when its queue is empty.
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            assertFalse("loader thread still alive", "zl-loader".equals(t.getName()) && t.isAlive());
        }
        // Nothing is queued or scheduled on the UI thread: no timers, polling or animations.
        assertTrue(shadowOf(Looper.getMainLooper()).isIdle());
        assertEquals(java.time.Duration.ZERO,
                shadowOf(Looper.getMainLooper()).getNextScheduledTaskTime());
    }

    @Test
    public void emptyDeviceShowsNoCrash() {
        AppListView v = view(startHome().get());
        assertEquals(0, shown(v).length);
        assertTrue(AppStore.get(ctx).isLoaded());
        tapRow(v, 0);
        swipe(v, v.getWidth() * 0.8f, v.getWidth() * 0.2f);
        assertEquals(0, v.page());
    }
}
