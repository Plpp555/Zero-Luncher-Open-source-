package io.github.plpp555.zerolauncher;

import android.util.Log;

/**
 * Timing logs for external measurement. Off unless enabled with
 * {@code adb shell setprop log.tag.ZeroLauncher D}; when off the cost is one boolean check.
 * Output goes to logcat only (never to a file).
 */
final class PerfLog {
    static final String TAG = "ZeroLauncher";
    static boolean on;

    private PerfLog() {
    }

    static void refresh() {
        on = Log.isLoggable(TAG, Log.DEBUG);
    }

    static void log(String what, long ms) {
        Log.d(TAG, what + "=" + ms + "ms");
    }

    static void log(String what, long ms, int count) {
        Log.d(TAG, what + "=" + ms + "ms n=" + count);
    }
}
