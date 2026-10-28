package io.github.plpp555.zerolauncher;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.os.LocaleList;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowInsets;

import java.util.Locale;

/**
 * Calls to APIs newer than minSdk 21, kept out of the frequently used classes so those classes
 * verify cleanly on old runtimes. Every caller checks {@code Build.VERSION.SDK_INT} first.
 */
final class Compat {
    private Compat() {
    }

    /** API 24+: the full locale list, because labels can fall back to a secondary locale. */
    @TargetApi(24)
    static String localeKey24() {
        return LocaleList.getDefault().toLanguageTags();
    }

    static String localeKeyLegacy() {
        return Locale.getDefault().toString();
    }

    @TargetApi(26)
    static ApplicationInfo appInfo26(LauncherActivityInfo info) {
        return info.getApplicationInfo();
    }

    @TargetApi(26)
    static void noFocusHighlight26(View v) {
        v.setDefaultFocusHighlightEnabled(false);
    }

    /** Pads {@code v} so nothing is drawn under the system bars or (API 28+) a display cutout. */
    @TargetApi(28)
    @SuppressWarnings("deprecation")
    static void padForInsets(View v, WindowInsets in, boolean cutout) {
        int l = in.getSystemWindowInsetLeft();
        int t = in.getSystemWindowInsetTop();
        int r = in.getSystemWindowInsetRight();
        int b = in.getSystemWindowInsetBottom();
        if (cutout) {
            DisplayCutout c = in.getDisplayCutout();
            if (c != null) {
                l = Math.max(l, c.getSafeInsetLeft());
                t = Math.max(t, c.getSafeInsetTop());
                r = Math.max(r, c.getSafeInsetRight());
                b = Math.max(b, c.getSafeInsetBottom());
            }
        }
        v.setPadding(l, t, r, b);
    }

    /** API 29+: asks the role holder UI to make us the home app. Returns false if not possible. */
    @TargetApi(29)
    static boolean requestHomeRole29(Activity a, int requestCode) {
        RoleManager rm = (RoleManager) a.getSystemService(Context.ROLE_SERVICE);
        if (rm == null || !rm.isRoleAvailable(RoleManager.ROLE_HOME)
                || rm.isRoleHeld(RoleManager.ROLE_HOME)) {
            return false;
        }
        Intent i = rm.createRequestRoleIntent(RoleManager.ROLE_HOME);
        a.startActivityForResult(i, requestCode);
        return true;
    }
}
