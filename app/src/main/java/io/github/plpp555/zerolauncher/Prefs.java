package io.github.plpp555.zerolauncher;

import android.content.Context;
import android.content.SharedPreferences;

/** User settings, held in static fields so reading them costs nothing on the draw path. */
final class Prefs {
    static final int SORT_NAME = 0;
    static final int SORT_NAME_DESC = 1;
    static final int SORT_INSTALL = 2;

    /** Text sizes in sp for "small / medium / large / extra large". */
    static final int[] TEXT_SP = {14, 18, 22, 28};
    /** Manual rows-per-page choices; 0 means automatic. */
    static final int[] ROW_CHOICES = {0, 4, 5, 6, 7, 8, 10, 12, 15, 20, 25};

    static int textSize;
    static int sort;
    static int rows;
    static boolean anim;
    static boolean white;
    static boolean color16;

    /** Bumped on every change so activities can tell whether they must re-apply settings. */
    static int generation;

    private static boolean sLoaded;

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("zl", Context.MODE_PRIVATE);
    }

    static void load(Context c) {
        if (sLoaded) return;
        SharedPreferences p = sp(c);
        textSize = clamp(p.getInt("text", 1), 0, TEXT_SP.length - 1);
        sort = clamp(p.getInt("sort", SORT_NAME), SORT_NAME, SORT_INSTALL);
        rows = Math.max(0, p.getInt("rows", 0));
        anim = p.getBoolean("anim", false);
        white = p.getBoolean("white", false);
        color16 = p.getBoolean("c16", true);
        sLoaded = true;
        generation++;
    }

    static void save(Context c) {
        sp(c).edit()
                .putInt("text", textSize)
                .putInt("sort", sort)
                .putInt("rows", rows)
                .putBoolean("anim", anim)
                .putBoolean("white", white)
                .putBoolean("c16", color16)
                .apply();
        generation++;
    }

    static void reset(Context c) {
        sp(c).edit().clear().apply();
        sLoaded = false;
        load(c);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
