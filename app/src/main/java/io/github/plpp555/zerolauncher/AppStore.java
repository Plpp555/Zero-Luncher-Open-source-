package io.github.plpp555.zerolauncher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/**
 * Process-wide list of launchable apps.
 *
 * <ul>
 * <li>Cold start: the list is read from a small cache file before the first frame, then verified
 *     once on a short-lived background thread. Labels are reused from the cache while the APK path,
 *     its mtime and the locale are unchanged, so loading app resources is avoided.</li>
 * <li>Afterwards it only changes in response to {@link LauncherApps.Callback} events, and then only
 *     the affected package is queried. There is no polling and no periodic rescan.</li>
 * <li>The worker thread exits as soon as its queue is empty.</li>
 * </ul>
 */
final class AppStore implements Runnable {
    interface Listener {
        void onAppsChanged();
    }

    static final App[] EMPTY = new App[0];
    private static final int CACHE_MAGIC = 0x5A4C4301;

    private static AppStore sInstance;

    private final LauncherApps la;
    private final UserManager um;
    private final PackageManager pm;
    private final Handler main;
    private final UserHandle me;
    private final long mySerial;
    private final String myPkg;
    private final File cacheFile;

    // ---- UI thread only ----
    private App[] apps = EMPTY;
    private App[] desc;
    private App[] byInstall;
    private boolean loaded;
    private Listener listener;
    private String checkedLocale;

    // ---- guarded by "this" ----
    private boolean running;
    private boolean fullPending;
    private boolean forceLabels;
    private final ArrayList<String> pendingPkgs = new ArrayList<String>();
    private final ArrayList<UserHandle> pendingUsers = new ArrayList<UserHandle>();

    // ---- worker thread (handed between threads through Thread.start and the lock) ----
    private App[] work = EMPTY;
    private String workLocale = "";
    private String fileLocale = "";
    /** True once the UI has a list (from the cache file or a load). */
    private boolean published;
    private int failures;

    private final Runnable retryFull = new Runnable() {
        @Override
        public void run() {
            requestFull(false);
        }
    };

    static AppStore get(Context c) {
        if (sInstance == null) sInstance = new AppStore(c.getApplicationContext());
        return sInstance;
    }

    private AppStore(Context c) {
        la = (LauncherApps) c.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        um = (UserManager) c.getSystemService(Context.USER_SERVICE);
        pm = c.getPackageManager();
        main = new Handler(Looper.getMainLooper());
        me = Process.myUserHandle();
        mySerial = um.getSerialNumberForUser(me);
        myPkg = c.getPackageName();
        cacheFile = new File(c.getCacheDir(), "apps.bin");
        checkedLocale = localeKey();

        long t0 = SystemClock.uptimeMillis();
        readCache();
        if (PerfLog.on) PerfLog.log("cache_read", SystemClock.uptimeMillis() - t0, apps.length);

        la.registerCallback(callback);
        requestFull(false);
    }

    // ------------------------------------------------------------------ UI thread API

    boolean isLoaded() {
        return loaded;
    }

    void setListener(Listener l) {
        listener = l;
    }

    void clearListener(Listener l) {
        if (listener == l) listener = null;
    }

    /** Called when an activity is (re)created; a locale change means every label must be reloaded. */
    void checkLocale() {
        String loc = localeKey();
        if (!loc.equals(checkedLocale)) {
            checkedLocale = loc;
            requestFull(false);
        }
    }

    /** The list in display order. Derived orders are built once per list change. */
    App[] ordered(int sort) {
        if (sort == Prefs.SORT_NAME_DESC) {
            if (desc == null) {
                int n = apps.length;
                desc = new App[n];
                for (int i = 0; i < n; i++) desc[i] = apps[n - 1 - i];
            }
            return desc;
        }
        if (sort == Prefs.SORT_INSTALL) {
            if (byInstall == null) {
                byInstall = apps.clone();
                // Stable sort: equal install times keep name order.
                Arrays.sort(byInstall, new Comparator<App>() {
                    @Override
                    public int compare(App a, App b) {
                        return a.installTime < b.installTime ? 1
                                : (a.installTime == b.installTime ? 0 : -1);
                    }
                });
            }
            return byInstall;
        }
        return apps;
    }

    boolean launch(Activity a, App app, Rect bounds) {
        try {
            ComponentName cn = new ComponentName(app.pkg, app.cls);
            if (me.equals(app.user)) {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.addCategory(Intent.CATEGORY_LAUNCHER);
                i.setComponent(cn);
                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                        | (Prefs.anim ? 0 : Intent.FLAG_ACTIVITY_NO_ANIMATION));
                i.setSourceBounds(bounds);
                a.startActivity(i);
            } else {
                la.startMainActivity(cn, app.user, bounds, noAnimation(a));
            }
            return true;
        } catch (RuntimeException e) {
            // ActivityNotFoundException, SecurityException, a locked profile, a removed app...
            Log.w(PerfLog.TAG, "launch failed: " + app.pkg + "/" + app.cls, e);
            Toast.makeText(a, R.string.cannot_open, Toast.LENGTH_SHORT).show();
            requestPackage(app.pkg, app.user);
            return false;
        }
    }

    void showDetails(Activity a, App app, Rect bounds) {
        try {
            la.startAppDetailsActivity(new ComponentName(app.pkg, app.cls), app.user, bounds,
                    noAnimation(a));
        } catch (RuntimeException e) {
            Toast.makeText(a, R.string.cannot_open, Toast.LENGTH_SHORT).show();
        }
    }

    private static Bundle noAnimation(Context c) {
        return Prefs.anim ? null : ActivityOptions.makeCustomAnimation(c, 0, 0).toBundle();
    }

    // ------------------------------------------------------------------ change events

    private final LauncherApps.Callback callback = new LauncherApps.Callback() {
        @Override
        public void onPackageRemoved(String pkg, UserHandle user) {
            requestPackage(pkg, user);
        }

        @Override
        public void onPackageAdded(String pkg, UserHandle user) {
            requestPackage(pkg, user);
        }

        @Override
        public void onPackageChanged(String pkg, UserHandle user) {
            requestPackage(pkg, user);
        }

        @Override
        public void onPackagesAvailable(String[] pkgs, UserHandle user, boolean replacing) {
            if (pkgs != null) for (String p : pkgs) requestPackage(p, user);
        }

        @Override
        public void onPackagesUnavailable(String[] pkgs, UserHandle user, boolean replacing) {
            // While a package is being replaced the following "available" event refreshes it.
            if (pkgs != null && !replacing) for (String p : pkgs) requestPackage(p, user);
        }
    };

    void requestFull(boolean relabel) {
        synchronized (this) {
            fullPending = true;
            forceLabels |= relabel;
            startLocked();
        }
    }

    void requestPackage(String pkg, UserHandle user) {
        if (pkg == null || user == null) return;
        synchronized (this) {
            pendingPkgs.add(pkg);
            pendingUsers.add(user);
            startLocked();
        }
    }

    private void startLocked() {
        if (running) return;
        running = true;
        new Thread(this, "zl-loader").start();
    }

    /** For tests and measurements: true when no load is queued or running. */
    synchronized boolean isIdle() {
        return !running;
    }

    // ------------------------------------------------------------------ worker thread

    @Override
    public void run() {
        // Nothing on screen yet: load at normal priority. Otherwise stay out of the UI's way.
        Process.setThreadPriority(work.length == 0
                ? Process.THREAD_PRIORITY_DEFAULT : Process.THREAD_PRIORITY_BACKGROUND);
        for (;;) {
            boolean full;
            boolean relabel;
            String[] pkgs = null;
            UserHandle[] users = null;
            synchronized (this) {
                full = fullPending;
                relabel = forceLabels;
                if (!full && pendingPkgs.isEmpty()) {
                    running = false;
                    return;
                }
                if (!full) {
                    pkgs = pendingPkgs.toArray(new String[pendingPkgs.size()]);
                    users = pendingUsers.toArray(new UserHandle[pendingUsers.size()]);
                }
                fullPending = false;
                forceLabels = false;
                pendingPkgs.clear();
                pendingUsers.clear();
            }
            long t0 = SystemClock.uptimeMillis();
            App[] next;
            try {
                next = full ? loadAll(relabel) : loadPackages(pkgs, users);
            } catch (RuntimeException e) {
                // System server restarting, etc. Keep showing the current list and retry a full
                // load a few times with growing delays.
                Log.w(PerfLog.TAG, "app list load failed", e);
                if (full && ++failures <= 3) main.postDelayed(retryFull, 2000L * failures);
                continue;
            }
            if (full) failures = 0;
            if (PerfLog.on) {
                PerfLog.log(full ? "load_full" : "load_pkg", SystemClock.uptimeMillis() - t0,
                        next.length);
            }
            boolean changed = !sameList(next, work);
            if (changed || !published) {
                // The first load is published even if empty, so "Loading..." is replaced.
                work = next;
                published = true;
                main.post(new Publish(next));
            }
            if (changed || !workLocale.equals(fileLocale)) writeCache(next);
        }
    }

    private final class Publish implements Runnable {
        private final App[] list;

        Publish(App[] list) {
            this.list = list;
        }

        @Override
        public void run() {
            apps = list;
            desc = null;
            byInstall = null;
            loaded = true;
            if (listener != null) listener.onAppsChanged();
        }
    }

    private App[] loadAll(boolean relabel) {
        String loc = localeKey();
        HashMap<String, App> old = null;
        if (!relabel && loc.equals(workLocale) && work.length > 0) {
            old = new HashMap<String, App>(work.length * 2);
            for (App a : work) old.put(id(a.serial, a.pkg, a.cls), a);
        }
        List<UserHandle> users = profiles();
        ArrayList<App> out = new ArrayList<App>(work.length + 8);
        HashMap<String, String> keys = new HashMap<String, String>();
        for (int u = 0, nu = users.size(); u < nu; u++) {
            UserHandle user = users.get(u);
            List<LauncherActivityInfo> list;
            try {
                list = la.getActivityList(null, user);
            } catch (SecurityException e) {
                continue; // profile not accessible
            }
            if (list == null) continue;
            boolean mine = me.equals(user);
            long serial = mine ? mySerial : um.getSerialNumberForUser(user);
            for (int i = 0, n = list.size(); i < n; i++) {
                LauncherActivityInfo info = list.get(i);
                ComponentName cn = info.getComponentName();
                String pkg = cn.getPackageName();
                if (mine && myPkg.equals(pkg)) continue;
                String cls = cn.getClassName();
                String key = keyOf(info, pkg, mine, keys);
                App o = old == null ? null : old.get(id(serial, pkg, cls));
                String label = o != null && key != null && key.equals(o.key)
                        ? o.label : labelOf(info, pkg, user, mine);
                out.add(new App(pkg, cls, user, serial, label, key, info.getFirstInstallTime(),
                        label));
            }
        }
        workLocale = loc;
        return finish(out);
    }

    private App[] loadPackages(String[] pkgs, UserHandle[] users) {
        int m = pkgs.length;
        long[] serials = new long[m];
        for (int j = 0; j < m; j++) {
            serials[j] = me.equals(users[j]) ? mySerial : um.getSerialNumberForUser(users[j]);
        }
        ArrayList<App> out = new ArrayList<App>(work.length + 4);
        outer:
        for (App a : work) {
            for (int j = 0; j < m; j++) {
                if (a.serial == serials[j] && a.pkg.equals(pkgs[j])) continue outer;
            }
            out.add(a);
        }
        HashMap<String, String> keys = new HashMap<String, String>();
        dup:
        for (int j = 0; j < m; j++) {
            for (int k = 0; k < j; k++) {
                if (serials[k] == serials[j] && pkgs[k].equals(pkgs[j])) continue dup;
            }
            UserHandle user = users[j];
            boolean mine = me.equals(user);
            if (mine && myPkg.equals(pkgs[j])) continue;
            List<LauncherActivityInfo> list;
            try {
                list = la.getActivityList(pkgs[j], user);
            } catch (SecurityException e) {
                list = null;
            }
            if (list == null) continue;
            for (int i = 0, n = list.size(); i < n; i++) {
                LauncherActivityInfo info = list.get(i);
                String cls = info.getComponentName().getClassName();
                String label = labelOf(info, pkgs[j], user, mine);
                out.add(new App(pkgs[j], cls, user, serials[j], label,
                        keyOf(info, pkgs[j], mine, keys), info.getFirstInstallTime(), label));
            }
        }
        return finish(out);
    }

    /** Sorts by label and gives apps that share a label a distinguishing suffix. */
    private static App[] finish(ArrayList<App> out) {
        App[] arr = out.toArray(new App[out.size()]);
        final Collator col = Collator.getInstance();
        Arrays.sort(arr, new Comparator<App>() {
            @Override
            public int compare(App a, App b) {
                int c = col.compare(a.label, b.label);
                if (c != 0) return c;
                c = a.pkg.compareTo(b.pkg);
                if (c != 0) return c;
                c = a.cls.compareTo(b.cls);
                if (c != 0) return c;
                return a.serial < b.serial ? -1 : (a.serial == b.serial ? 0 : 1);
            }
        });
        int n = arr.length;
        for (int i = 0; i < n; ) {
            int j = i + 1;
            while (j < n && arr[j].label.equals(arr[i].label)) j++;
            for (int k = i; k < j; k++) {
                arr[k] = arr[k].withTitle(j - i == 1 ? arr[k].label : disambiguated(arr, i, j, k));
            }
            i = j;
        }
        return arr;
    }

    private static String disambiguated(App[] arr, int from, int to, int k) {
        App a = arr[k];
        int samePkg = 0;
        int sameCls = 0;
        for (int x = from; x < to; x++) {
            if (arr[x].pkg.equals(a.pkg)) {
                samePkg++;
                if (arr[x].cls.equals(a.cls)) sameCls++;
            }
        }
        String hint;
        if (samePkg == 1) {
            hint = a.pkg;
        } else if (sameCls == 1) {
            hint = a.cls.substring(a.cls.lastIndexOf('.') + 1);
        } else {
            hint = "#" + a.serial;
        }
        return a.label + " (" + hint + ")";
    }

    private String labelOf(LauncherActivityInfo info, String pkg, UserHandle user, boolean mine) {
        CharSequence cs = null;
        try {
            cs = info.getLabel();
        } catch (RuntimeException e) {
            // An app with broken resources; fall back to its package name.
        }
        String s = clean(cs);
        if (s.length() == 0) s = pkg;
        if (!mine) {
            try {
                CharSequence b = pm.getUserBadgedLabel(s, user);
                if (b != null && b.length() > 0) s = clean(b);
            } catch (RuntimeException e) {
                // keep the plain label
            }
        }
        return s;
    }

    private static String clean(CharSequence cs) {
        if (cs == null) return "";
        String s = cs.toString();
        if (s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0 || s.indexOf('\t') >= 0) {
            s = s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        }
        return s.trim();
    }

    /** "APK path|mtime": changes whenever the app is updated, so the cached label must be reloaded. */
    private String keyOf(LauncherActivityInfo info, String pkg, boolean mine,
            HashMap<String, String> keys) {
        if (mine && keys.containsKey(pkg)) return keys.get(pkg);
        ApplicationInfo ai = null;
        if (Build.VERSION.SDK_INT >= 26) {
            ai = Compat.appInfo26(info);
        } else if (mine) {
            try {
                ai = pm.getApplicationInfo(pkg, 0);
            } catch (PackageManager.NameNotFoundException e) {
                ai = null;
            }
        }
        String k = null;
        if (ai != null && ai.sourceDir != null) {
            k = ai.sourceDir + '|' + new File(ai.sourceDir).lastModified();
        }
        if (mine) keys.put(pkg, k);
        return k;
    }

    private List<UserHandle> profiles() {
        List<UserHandle> l = null;
        try {
            l = um.getUserProfiles();
        } catch (SecurityException e) {
            l = null;
        }
        if (l == null || l.isEmpty()) l = Collections.singletonList(me);
        return l;
    }

    private static String id(long serial, String pkg, String cls) {
        return serial + "/" + pkg + "/" + cls;
    }

    private static boolean sameList(App[] a, App[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (!a[i].same(b[i])) return false;
        }
        return true;
    }

    static String localeKey() {
        return Build.VERSION.SDK_INT >= 24 ? Compat.localeKey24() : Compat.localeKeyLegacy();
    }

    // ------------------------------------------------------------------ cache file

    /** Runs on the UI thread before the first frame: one small sequential read. */
    private void readCache() {
        DataInputStream in = null;
        try {
            in = new DataInputStream(new BufferedInputStream(new FileInputStream(cacheFile), 8192));
            if (in.readInt() != CACHE_MAGIC) return;
            String loc = in.readUTF();
            int n = in.readInt();
            if (n < 0 || n > 100000) return;
            App[] arr = new App[n];
            int k = 0;
            long otherSerial = Long.MIN_VALUE;
            UserHandle other = null;
            for (int i = 0; i < n; i++) {
                long serial = in.readLong();
                String pkg = in.readUTF();
                String cls = in.readUTF();
                String label = in.readUTF();
                String title = in.readUTF();
                String key = in.readUTF();
                long installTime = in.readLong();
                UserHandle user;
                if (serial == mySerial) {
                    user = me;
                } else {
                    if (serial != otherSerial) {
                        otherSerial = serial;
                        other = um.getUserForSerialNumber(serial);
                    }
                    user = other;
                }
                if (user == null) continue; // that profile no longer exists
                arr[k++] = new App(pkg, cls, user, serial, label, key.length() == 0 ? null : key,
                        installTime, title);
            }
            if (k != n) arr = Arrays.copyOf(arr, k);
            work = arr;
            apps = arr;
            workLocale = loc;
            fileLocale = loc;
            loaded = true;
            published = true;
        } catch (IOException e) {
            // No cache yet, or a corrupt one: the full load below rebuilds it.
        } catch (RuntimeException e) {
            work = EMPTY;
            apps = EMPTY;
        } finally {
            close(in);
        }
    }

    private void writeCache(App[] arr) {
        File tmp = new File(cacheFile.getPath() + ".tmp");
        DataOutputStream out = null;
        try {
            out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 8192));
            out.writeInt(CACHE_MAGIC);
            out.writeUTF(workLocale);
            out.writeInt(arr.length);
            for (App a : arr) {
                out.writeLong(a.serial);
                out.writeUTF(a.pkg);
                out.writeUTF(a.cls);
                out.writeUTF(a.label);
                out.writeUTF(a.title);
                out.writeUTF(a.key == null ? "" : a.key);
                out.writeLong(a.installTime);
            }
            out.close();
            out = null;
            if (tmp.renameTo(cacheFile)) {
                fileLocale = workLocale;
            } else {
                tmp.delete();
            }
        } catch (IOException e) {
            tmp.delete();
        } finally {
            close(out);
        }
    }

    private static void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException e) {
            // ignore
        }
    }

    /** Tests only: forget the process-wide instance. */
    static void resetForTest() {
        if (sInstance != null) sInstance.la.unregisterCallback(sInstance.callback);
        sInstance = null;
    }
}
