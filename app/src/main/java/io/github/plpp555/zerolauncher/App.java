package io.github.plpp555.zerolauncher;

import android.os.UserHandle;

/** One launchable activity. Immutable, so lists can be shared between threads without copying. */
final class App {
    final String pkg;
    final String cls;
    final UserHandle user;
    /** UserManager serial number of {@link #user}; stable across reboots, used in the cache file. */
    final long serial;
    /** Label as the system reports it (badged for work profiles). */
    final String label;
    /** Cache validation key (APK path + mtime); null when it could not be determined. */
    final String key;
    final long installTime;
    /** Text shown on screen: {@link #label}, plus a suffix when several apps share a label. */
    final String title;

    App(String pkg, String cls, UserHandle user, long serial, String label, String key,
            long installTime, String title) {
        this.pkg = pkg;
        this.cls = cls;
        this.user = user;
        this.serial = serial;
        this.label = label;
        this.key = key;
        this.installTime = installTime;
        this.title = title;
    }

    App withTitle(String t) {
        return t.equals(title) ? this : new App(pkg, cls, user, serial, label, key, installTime, t);
    }

    boolean same(App o) {
        return serial == o.serial && installTime == o.installTime && pkg.equals(o.pkg)
                && cls.equals(o.cls) && title.equals(o.title) && label.equals(o.label)
                && (key == null ? o.key == null : key.equals(o.key));
    }
}
