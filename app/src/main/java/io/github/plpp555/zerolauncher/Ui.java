package io.github.plpp555.zerolauncher;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;

/** Window styling shared by both activities: one flat color, system bars kept visible. */
final class Ui {
    private Ui() {
    }

    static int fg() {
        return Prefs.white ? Color.BLACK : Color.WHITE;
    }

    static int bg() {
        return Prefs.white ? Color.WHITE : Color.BLACK;
    }

    /** Secondary text (footer, hints, pressed row); at least 4.5:1 contrast on either background. */
    static int dim() {
        return Prefs.white ? 0xFF666666 : 0xFF8C8C8C;
    }

    @SuppressWarnings("deprecation")
    static void styleWindow(Window w) {
        boolean white = Prefs.white;
        int bg = bg();
        // The window background is the only full-screen fill; views draw no background.
        w.setBackgroundDrawable(new ColorDrawable(bg));
        // Dark icons on light bars need API 23 (status) / 26 (navigation); keep those bars black before that.
        w.setStatusBarColor(white && Build.VERSION.SDK_INT < 23 ? Color.BLACK : bg);
        w.setNavigationBarColor(white && Build.VERSION.SDK_INT < 26 ? Color.BLACK : bg);
        if (Build.VERSION.SDK_INT >= 29) {
            // No translucent scrim behind the bars.
            w.setStatusBarContrastEnforced(false);
            w.setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(white ? mask : 0, mask);
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            View decor = w.getDecorView();
            int f = decor.getSystemUiVisibility() & ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            if (white) {
                f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= 26) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            decor.setSystemUiVisibility(f);
        }
    }
}
