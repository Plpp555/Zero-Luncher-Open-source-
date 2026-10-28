package io.github.plpp555.zerolauncher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Text-only settings. Each line cycles through its values when tapped. */
public final class SettingsActivity extends Activity implements View.OnClickListener {
    private static final int HOME = 0;
    private static final int TEXT = 1;
    private static final int SORT = 2;
    private static final int ROWS = 3;
    private static final int ANIM = 4;
    private static final int BG = 5;
    private static final int COLOR = 6;
    private static final int RELOAD = 7;
    private static final int RESET = 8;
    private static final int COUNT = 9;

    private final TextView[] items = new TextView[COUNT];
    private TextView title;
    private TextView hint;
    private boolean resetArmed;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Prefs.load(this);
        float d = getResources().getDisplayMetrics().density;
        int padH = (int) (24 * d);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, (int) (8 * d), 0, (int) (16 * d));

        title = line(padH, (int) (64 * d), 24);
        title.setText(R.string.app_name);
        list.addView(title);
        for (int i = 0; i < COUNT; i++) {
            TextView tv = line(padH, (int) (56 * d), 18);
            tv.setId(i + 1);
            tv.setOnClickListener(this);
            tv.setFocusable(true);
            items[i] = tv;
            list.addView(tv);
        }
        hint = line(padH, 0, 14);
        hint.setPadding(padH, (int) (16 * d), padH, 0);
        hint.setText(R.string.s_hint);
        list.addView(hint);

        ScrollView sv = new ScrollView(this);
        sv.setFitsSystemWindows(true);
        sv.addView(list);
        setContentView(sv);
    }

    private TextView line(int padH, int minH, int sp) {
        TextView tv = new TextView(this);
        tv.setMinHeight(minH);
        tv.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        tv.setPadding(padH, 0, padH, 0);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return tv;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The home role may have changed while we were away.
        refresh();
    }

    private void refresh() {
        Ui.styleWindow(getWindow());
        int fg = Ui.fg();
        title.setTextColor(fg);
        hint.setTextColor(Ui.dim());
        Resources r = getResources();
        items[HOME].setText(isDefaultHome() ? R.string.s_home_is : R.string.s_home_set);
        items[TEXT].setText(item(R.string.s_text_size,
                r.getStringArray(R.array.v_text_size)[Prefs.textSize]));
        items[SORT].setText(item(R.string.s_sort, r.getStringArray(R.array.v_sort)[Prefs.sort]));
        items[ROWS].setText(item(R.string.s_rows,
                Prefs.rows == 0 ? getString(R.string.v_auto) : Integer.toString(Prefs.rows)));
        items[ANIM].setText(item(R.string.s_anim, r.getStringArray(R.array.v_off_on)[Prefs.anim ? 1 : 0]));
        items[BG].setText(item(R.string.s_bg, r.getStringArray(R.array.v_bg)[Prefs.white ? 1 : 0]));
        items[COLOR].setText(item(R.string.s_color,
                r.getStringArray(R.array.v_color)[Prefs.color16 ? 0 : 1]));
        items[RELOAD].setText(R.string.s_reload);
        items[RESET].setText(resetArmed ? R.string.s_reset_confirm : R.string.s_reset);
        for (TextView tv : items) tv.setTextColor(fg);
    }

    private String item(int label, String value) {
        return getString(label) + ": " + value;
    }

    @Override
    public void onClick(View v) {
        int id = v.getId() - 1;
        if (id != RESET) resetArmed = false;
        switch (id) {
            case HOME:
                requestHome();
                return;
            case TEXT:
                Prefs.textSize = (Prefs.textSize + 1) % Prefs.TEXT_SP.length;
                break;
            case SORT:
                Prefs.sort = (Prefs.sort + 1) % 3;
                break;
            case ROWS:
                Prefs.rows = nextRows(Prefs.rows);
                break;
            case ANIM:
                Prefs.anim = !Prefs.anim;
                break;
            case BG:
                Prefs.white = !Prefs.white;
                break;
            case COLOR:
                Prefs.color16 = !Prefs.color16;
                break;
            case RELOAD:
                AppStore.get(this).requestFull(true);
                refresh();
                return;
            case RESET:
                if (!resetArmed) {
                    resetArmed = true;
                } else {
                    resetArmed = false;
                    Prefs.reset(this);
                }
                refresh();
                return;
            default:
                return;
        }
        Prefs.save(this);
        refresh();
    }

    private static int nextRows(int cur) {
        int[] c = Prefs.ROW_CHOICES;
        for (int i = 0; i < c.length; i++) {
            if (c[i] == cur) return c[(i + 1) % c.length];
        }
        return c[0];
    }

    private boolean isDefaultHome() {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null
                && getPackageName().equals(ri.activityInfo.packageName);
    }

    private void requestHome() {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                if (Compat.requestHomeRole29(this, 1)) return;
            } catch (RuntimeException e) {
                // fall through to the system settings screen
            }
        }
        if (!open(new Intent(Settings.ACTION_HOME_SETTINGS))) open(new Intent(Settings.ACTION_SETTINGS));
    }

    private boolean open(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        } catch (SecurityException e) {
            return false;
        }
    }
}
