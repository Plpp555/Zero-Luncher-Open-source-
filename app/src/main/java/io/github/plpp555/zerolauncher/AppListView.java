package io.github.plpp555.zerolauncher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.animation.DecelerateInterpolator;

/**
 * The whole home screen in one view: app names drawn as plain text, one page at a time, plus a
 * footer line with "Settings" and the page number.
 *
 * <p>Nothing is drawn unless something changed (touch feedback, page switch, list update). Only
 * the visible page is laid out; its truncated strings are computed once per page switch. There
 * are no child views, bitmaps or drawables.
 */
final class AppListView extends View implements AppStore.Listener,
        ValueAnimator.AnimatorUpdateListener {
    private static final int NONE = -1;
    private static final int FOOT_SETTINGS = -2;
    private static final int FOOT_PAGE = -3;

    private static final int V_SETTINGS = 1000;
    private static final int V_PAGE = 1001;
    private static final int V_NONE = Integer.MIN_VALUE;

    private final HomeActivity home;
    private final AppStore store;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final int touchSlop;
    private final float flingVelocity;
    private final String settingsText;
    private final String loadingText;
    private final String emptyText;
    private final AccessibilityManager am;

    private App[] list = AppStore.EMPTY;
    private int page;
    private int pages = 1;
    private int rows = 1;
    private int rowH = 1;
    private int top;
    private int left;
    private int right;
    private int textW;
    private int baseOff;
    private float ellipsisW;
    private int fg;
    private int dim;
    private boolean rtl;
    private boolean metricsOk;

    /** Truncated titles of the current page; rebuilt only when the page or the list changes. */
    private String[] lines = new String[0];
    private int linesPage = -1;
    private String pageText;

    private int target = NONE;
    private int pressed = NONE;
    private float downX;
    private float downY;
    private boolean moved;
    private boolean horizontal;
    private boolean longFired;
    private VelocityTracker vt;

    /** Keyboard / D-pad selection on the current page. */
    private int sel;

    private ValueAnimator animator;
    private boolean animating;
    private int animFrom;
    private int animVis;
    private float animPos;

    private long createdAt;
    private boolean reportedDrawn;
    private long switchT0;

    private A11y a11y;
    private int a11yFocus = V_NONE;
    private int hoverId = V_NONE;

    AppListView(HomeActivity home, AppStore store) {
        super(home);
        this.home = home;
        this.store = store;
        density = getResources().getDisplayMetrics().density;
        ViewConfiguration vc = ViewConfiguration.get(home);
        touchSlop = vc.getScaledTouchSlop();
        flingVelocity = 400 * density;
        settingsText = home.getString(R.string.settings);
        loadingText = home.getString(R.string.loading);
        emptyText = home.getString(R.string.no_apps);
        am = (AccessibilityManager) home.getSystemService(Context.ACCESSIBILITY_SERVICE);
        createdAt = SystemClock.uptimeMillis();
        setFocusable(true);
        // API 26+ would otherwise tint the whole view when it has keyboard focus.
        if (Build.VERSION.SDK_INT >= 26) Compat.noFocusHighlight26(this);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    // ------------------------------------------------------------------ state

    void applyPrefs() {
        fg = Ui.fg();
        dim = Ui.dim();
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                Prefs.TEXT_SP[Prefs.textSize], getResources().getDisplayMetrics()));
        ellipsisW = paint.measureText("…");
        list = store.ordered(Prefs.sort);
        metricsOk = false;
        invalidate();
    }

    @Override
    public void onAppsChanged() {
        // A pending tap would otherwise hit whatever app moved into that row.
        if (target != NONE) resetTouch();
        list = store.ordered(Prefs.sort);
        metricsOk = false;
        invalidate();
        if (am.isEnabled()) sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    }

    int page() {
        return page;
    }

    void goPage(int p) {
        ensureMetrics();
        if (p < 0) p = 0;
        if (p > pages - 1) p = pages - 1;
        if (p == page) return;
        int from = page;
        page = p;
        sel = 0;
        linesPage = -1;
        pageText = null;
        if (Prefs.anim && isShown()) startAnim(from, p > from ? 1 : -1);
        invalidate();
        if (am.isEnabled()) {
            sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
            announceForAccessibility(pageA11y());
        }
    }

    /** Restores a page without animation (state restore, before the first layout). */
    void setPageNow(int p) {
        page = Math.max(0, p);
        metricsOk = false;
    }

    void resetTouch() {
        removeCallbacks(longPress);
        target = NONE;
        setPressedTarget(NONE);
        recycleVelocityTracker();
    }

    private int rowsOnPage() {
        return Math.max(0, Math.min(rows, list.length - page * rows));
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        metricsOk = false;
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets in) {
        Compat.padForInsets(this, in, Build.VERSION.SDK_INT >= 28);
        metricsOk = false;
        return in.consumeSystemWindowInsets();
    }

    private void ensureMetrics() {
        if (metricsOk) return;
        metricsOk = true;
        int h = getHeight();
        int w = getWidth();
        Paint.FontMetricsInt fm = paint.getFontMetricsInt();
        int textH = fm.descent - fm.ascent;
        int avail = Math.max(1, h - getPaddingTop() - getPaddingBottom());
        int r;
        if (Prefs.rows > 0) {
            // Manual count, limited so the text itself still fits in a row.
            r = Math.min(Prefs.rows, Math.max(1, avail / Math.max(1, textH + textH / 4) - 1));
        } else {
            // Auto: rows at least 48dp high (comfortable touch target), footer included.
            int minRow = Math.max((int) (48 * density), textH + (int) (16 * density));
            r = avail / minRow - 1;
        }
        rows = Math.max(1, r);
        rowH = Math.max(1, avail / (rows + 1));
        top = getPaddingTop();
        baseOff = (rowH - textH) / 2 - fm.ascent;
        int hPad = (int) (24 * density);
        left = getPaddingLeft() + hPad;
        right = w - getPaddingRight() - hPad;
        textW = Math.max(0, right - left);
        rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        pages = Math.max(1, (list.length + rows - 1) / rows);
        if (page > pages - 1) page = pages - 1;
        if (sel >= rowsOnPage()) sel = 0;
        if (lines.length != rows) lines = new String[rows];
        linesPage = -1;
        pageText = null;
    }

    private String fit(String s) {
        if (paint.measureText(s) <= textW) return s;
        int k = paint.breakText(s, true, Math.max(0f, textW - ellipsisW), null);
        if (k > 0 && Character.isHighSurrogate(s.charAt(k - 1))) k--;
        return s.substring(0, k) + "…";
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        ensureMetrics();
        long t0 = PerfLog.on ? SystemClock.uptimeMillis() : 0;
        paint.setTextAlign(rtl ? Paint.Align.RIGHT : Paint.Align.LEFT);
        float x = rtl ? right : left;
        if (list.length == 0) {
            paint.setColor(dim);
            c.drawText(store.isLoaded() ? emptyText : loadingText, x, top + baseOff, paint);
        } else if (animating) {
            float w = getWidth();
            drawPage(c, animFrom, x - animVis * animPos * w);
            drawPage(c, page, x + animVis * (1f - animPos) * w);
        } else {
            drawPage(c, page, x);
        }
        drawFooter(c);

        if (list.length > 0 && !reportedDrawn) {
            reportedDrawn = true;
            if (PerfLog.on) PerfLog.log("first_list_draw", SystemClock.uptimeMillis() - createdAt);
            // Lets "Fully drawn" in logcat / am start -W measure time-to-list from outside.
            post(reportDrawn);
        }
        if (t0 != 0) {
            long now = SystemClock.uptimeMillis();
            PerfLog.log("draw", now - t0);
            if (switchT0 != 0 && !animating) {
                PerfLog.log("page_switch", now - switchT0);
                switchT0 = 0;
            }
        }
    }

    private void drawPage(Canvas c, int p, float x) {
        int start = p * rows;
        int end = Math.min(list.length, start + rows);
        boolean current = p == page;
        if (current && linesPage != page) {
            for (int i = start; i < end; i++) lines[i - start] = fit(list[i].title);
            linesPage = page;
        }
        boolean showSel = current && !animating && !isInTouchMode() && hasFocus();
        for (int i = start; i < end; i++) {
            int row = i - start;
            paint.setColor(current && row == pressed ? dim : fg);
            int y = top + row * rowH + baseOff;
            c.drawText(current ? lines[row] : fit(list[i].title), x, y, paint);
            if (showSel && row == sel) {
                float m = 12 * density;
                c.drawText("›", rtl ? x + m : x - m, y, paint);
            }
        }
    }

    private void drawFooter(Canvas c) {
        int y = top + rows * rowH + baseOff;
        paint.setColor(pressed == FOOT_SETTINGS ? fg : dim);
        paint.setTextAlign(rtl ? Paint.Align.RIGHT : Paint.Align.LEFT);
        c.drawText(settingsText, rtl ? right : left, y, paint);
        if (pageText == null) pageText = (page + 1) + " / " + pages;
        paint.setColor(pressed == FOOT_PAGE ? fg : dim);
        paint.setTextAlign(rtl ? Paint.Align.LEFT : Paint.Align.RIGHT);
        c.drawText(pageText, rtl ? left : right, y, paint);
    }

    private final Runnable reportDrawn = new Runnable() {
        @Override
        public void run() {
            try {
                home.reportFullyDrawn();
            } catch (RuntimeException e) {
                // Some old builds reject this without a permission; it is only a measurement hook.
            }
        }
    };

    // ------------------------------------------------------------------ page animation (opt-in)

    private void startAnim(int from, int dir) {
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(180);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(this);
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator a) {
                    animating = false;
                    invalidate();
                }
            });
        }
        animator.cancel();
        animFrom = from;
        // +1: the new page enters from the right (next page in left-to-right layouts).
        animVis = rtl ? -dir : dir;
        animPos = 0f;
        animating = true;
        animator.start();
    }

    private void stopAnim() {
        if (animating && animator != null) animator.end();
    }

    @Override
    public void onAnimationUpdate(ValueAnimator a) {
        animPos = a.getAnimatedFraction();
        invalidate();
    }

    // ------------------------------------------------------------------ touch

    private int hit(float x, float y) {
        ensureMetrics();
        if (y < top) return NONE;
        int r = (int) ((y - top) / rowH);
        if (r == rows) {
            boolean startHalf = rtl ? x > getWidth() / 2f : x < getWidth() / 2f;
            return startHalf ? FOOT_SETTINGS : FOOT_PAGE;
        }
        if (r > rows) return NONE;
        return r < rowsOnPage() ? r : NONE;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                stopAnim();
                downX = e.getX();
                downY = e.getY();
                moved = false;
                horizontal = false;
                longFired = false;
                if (vt == null) vt = VelocityTracker.obtain();
                vt.clear();
                vt.addMovement(e);
                target = hit(downX, downY);
                setPressedTarget(target);
                if (target >= 0) postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (vt != null) vt.addMovement(e);
                if (!moved) {
                    float dx = e.getX() - downX;
                    float dy = e.getY() - downY;
                    if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                        moved = true;
                        horizontal = Math.abs(dx) > Math.abs(dy);
                        removeCallbacks(longPress);
                        setPressedTarget(NONE);
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                if (vt != null) vt.addMovement(e);
                if (!longFired) {
                    if (!moved) {
                        setPressedTarget(NONE);
                        activate(target, e.getEventTime());
                    } else if (horizontal) {
                        swipe(e);
                    }
                }
                target = NONE;
                recycleVelocityTracker();
                return true;
            case MotionEvent.ACTION_CANCEL:
                resetTouch();
                return true;
            default:
                return true;
        }
    }

    private void swipe(MotionEvent e) {
        float dx = e.getX() - downX;
        float vx = 0f;
        if (vt != null) {
            vt.computeCurrentVelocity(1000);
            vx = vt.getXVelocity();
        }
        boolean far = Math.abs(dx) > getWidth() / 5f;
        boolean fling = Math.abs(vx) > flingVelocity && (vx < 0) == (dx < 0);
        if (!far && !fling) return;
        int dir = dx < 0 ? 1 : -1;
        if (rtl) dir = -dir;
        if (PerfLog.on) switchT0 = e.getEventTime();
        goPage(page + dir);
    }

    private void recycleVelocityTracker() {
        if (vt != null) {
            vt.recycle();
            vt = null;
        }
    }

    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            if (target < 0 || moved) return;
            int i = page * rows + target;
            if (i >= list.length) return;
            longFired = true;
            setPressedTarget(NONE);
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            store.showDetails(home, list[i], rowBounds(target));
        }
    };

    private void setPressedTarget(int t) {
        if (pressed == t) return;
        invalidateTarget(pressed);
        pressed = t;
        invalidateTarget(t);
    }

    /** Redraws only the affected line (software rendering honours the dirty rectangle). */
    @SuppressWarnings("deprecation")
    private void invalidateTarget(int t) {
        if (t == NONE) return;
        int r = t >= 0 ? t : rows;
        int y = top + r * rowH;
        invalidate(0, y, getWidth(), y + rowH);
    }

    private void activate(int t, long eventTime) {
        if (t == FOOT_SETTINGS) {
            playSoundEffect(SoundEffectConstants.CLICK);
            home.openSettings();
        } else if (t == FOOT_PAGE) {
            playSoundEffect(SoundEffectConstants.CLICK);
            goPage(page + 1 < pages ? page + 1 : 0);
        } else if (t >= 0) {
            int i = page * rows + t;
            if (i >= list.length) return;
            playSoundEffect(SoundEffectConstants.CLICK);
            boolean ok = store.launch(home, list[i], rowBounds(t));
            if (ok && PerfLog.on) {
                PerfLog.log("launch_request", SystemClock.uptimeMillis() - eventTime);
            }
        }
    }

    private Rect rowBounds(int t) {
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        int r = t >= 0 ? t : rows;
        int y = loc[1] + top + r * rowH;
        return new Rect(loc[0] + left, y, loc[0] + right, y + rowH);
    }

    // ------------------------------------------------------------------ keyboard / D-pad

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                moveSel(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                moveSel(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                goPage(page + (rtl ? -1 : 1));
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                goPage(page + (rtl ? 1 : -1));
                return true;
            case KeyEvent.KEYCODE_PAGE_DOWN:
                goPage(page + 1);
                return true;
            case KeyEvent.KEYCODE_PAGE_UP:
                goPage(page - 1);
                return true;
            case KeyEvent.KEYCODE_MOVE_HOME:
                goPage(0);
                return true;
            case KeyEvent.KEYCODE_MENU:
                home.openSettings();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                ensureMetrics();
                if (sel < rowsOnPage()) activate(sel, e.getEventTime());
                return true;
            default:
                return super.onKeyDown(code, e);
        }
    }

    private void moveSel(int d) {
        ensureMetrics();
        int s = sel + d;
        if (s < 0) {
            if (page > 0) {
                goPage(page - 1);
                sel = rowsOnPage() - 1;
            }
        } else if (s >= rowsOnPage()) {
            if (page < pages - 1) goPage(page + 1);
        } else {
            sel = s;
        }
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        resetTouch();
        if (animator != null) animator.cancel();
        removeCallbacks(reportDrawn);
    }

    // ------------------------------------------------------------------ accessibility
    // Only instantiated when an accessibility service queries this view.

    private String textFor(int id) {
        if (id == V_SETTINGS) return settingsText;
        if (id == V_PAGE) return pageA11y();
        if (id >= 0 && id < rowsOnPage()) return list[page * rows + id].title;
        return null;
    }

    private String pageA11y() {
        ensureMetrics();
        return getResources().getString(R.string.page_a11y, page + 1, pages);
    }

    private static int toTarget(int id) {
        return id == V_SETTINGS ? FOOT_SETTINGS : (id == V_PAGE ? FOOT_PAGE : id);
    }

    private int virtualAt(float x, float y) {
        int t = hit(x, y);
        if (t == FOOT_SETTINGS) return V_SETTINGS;
        if (t == FOOT_PAGE) return V_PAGE;
        return t >= 0 ? t : V_NONE;
    }

    @Override
    public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        if (a11y == null) a11y = new A11y();
        return a11y;
    }

    @Override
    public boolean dispatchHoverEvent(MotionEvent e) {
        if (!am.isEnabled() || !am.isTouchExplorationEnabled()) return super.dispatchHoverEvent(e);
        int id;
        switch (e.getAction()) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
                id = virtualAt(e.getX(), e.getY());
                break;
            default:
                id = V_NONE;
                break;
        }
        if (id != hoverId) {
            int old = hoverId;
            hoverId = id;
            if (id != V_NONE) sendVirtual(id, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER);
            if (old != V_NONE) sendVirtual(old, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);
        }
        return true;
    }

    private void sendVirtual(int id, int type) {
        ViewParent parent = getParent();
        String text = textFor(id);
        if (parent == null || text == null) return;
        AccessibilityEvent ev = AccessibilityEvent.obtain(type);
        ev.setPackageName(getContext().getPackageName());
        ev.setClassName("android.widget.Button");
        ev.getText().add(text);
        ev.setEnabled(true);
        ev.setSource(this, id);
        parent.requestSendAccessibilityEvent(this, ev);
    }

    private final class A11y extends AccessibilityNodeProvider {
        @Override
        @SuppressWarnings("deprecation")
        public AccessibilityNodeInfo createAccessibilityNodeInfo(int id) {
            ensureMetrics();
            View host = AppListView.this;
            if (id == HOST_VIEW_ID) {
                AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(host);
                onInitializeAccessibilityNodeInfo(info);
                for (int r = 0, n = rowsOnPage(); r < n; r++) info.addChild(host, r);
                info.addChild(host, V_SETTINGS);
                info.addChild(host, V_PAGE);
                info.setScrollable(pages > 1);
                if (page < pages - 1) info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                if (page > 0) info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
                return info;
            }
            String text = textFor(id);
            if (text == null) return null;
            AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
            info.setSource(host, id);
            info.setParent(host);
            info.setPackageName(getContext().getPackageName());
            info.setClassName("android.widget.Button");
            info.setText(text);
            info.setEnabled(true);
            info.setFocusable(true);
            info.setClickable(true);
            info.setVisibleToUser(true);
            info.addAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (id >= 0) {
                info.setLongClickable(true);
                info.addAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
            }
            int r = id >= 0 ? id : rows;
            int y = top + r * rowH;
            Rect b;
            if (id == V_SETTINGS) {
                b = rtl ? new Rect(getWidth() / 2, y, getWidth(), y + rowH)
                        : new Rect(0, y, getWidth() / 2, y + rowH);
            } else if (id == V_PAGE) {
                b = rtl ? new Rect(0, y, getWidth() / 2, y + rowH)
                        : new Rect(getWidth() / 2, y, getWidth(), y + rowH);
            } else {
                b = new Rect(0, y, getWidth(), y + rowH);
            }
            info.setBoundsInParent(b);
            int[] loc = new int[2];
            getLocationOnScreen(loc);
            b.offset(loc[0], loc[1]);
            info.setBoundsInScreen(b);
            if (a11yFocus == id) {
                info.setAccessibilityFocused(true);
                info.addAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS);
            } else {
                info.addAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
            }
            return info;
        }

        @Override
        public boolean performAction(int id, int action, Bundle args) {
            if (id == HOST_VIEW_ID) {
                if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD && page < pages - 1) {
                    goPage(page + 1);
                    return true;
                }
                if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD && page > 0) {
                    goPage(page - 1);
                    return true;
                }
                return performAccessibilityAction(action, args);
            }
            if (textFor(id) == null) return false;
            switch (action) {
                case AccessibilityNodeInfo.ACTION_CLICK:
                    activate(toTarget(id), SystemClock.uptimeMillis());
                    return true;
                case AccessibilityNodeInfo.ACTION_LONG_CLICK:
                    if (id < 0) return false;
                    store.showDetails(home, list[page * rows + id], rowBounds(id));
                    return true;
                case AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS:
                    if (a11yFocus != id) {
                        a11yFocus = id;
                        sendVirtual(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
                    }
                    return true;
                case AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:
                    if (a11yFocus == id) {
                        a11yFocus = V_NONE;
                        sendVirtual(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
                    }
                    return true;
                default:
                    return false;
            }
        }
    }
}
