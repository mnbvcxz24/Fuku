package com.fuku.floating;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Adds an in-window title bar and resize handle to activities whose manifest theme
 * is based on a floating Dialog theme. No SYSTEM_ALERT_WINDOW or external window
 * manager is used for the app window; minimize uses a separate overlay bubble.
 */
public final class FloatingWindow implements Application.ActivityLifecycleCallbacks {
    private static final String PREFS = "fuku_floating";
    private static final String BOUNDS_PREFIX = "bounds.";
    private static final Object LOCK = new Object();
    private static final Set<Activity> INSTALLED =
            Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>());
    private static final Map<Activity, View> DECORATIONS = new WeakHashMap<>();
    private static final Map<Activity, ViewTreeObserver.OnGlobalLayoutListener> LAYOUT_LISTENERS =
            new WeakHashMap<>();
    private static final Map<Activity, Integer> INSTALL_RETRIES = new WeakHashMap<>();
    private static final Set<Activity> LIVE_ACTIVITIES =
            Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>());
    private static final int MAX_INSTALL_RETRIES = 20;
    private static boolean callbacksRegistered;
    private static boolean startupStateRead;
    private static boolean previousRunDirty;

    private static final class DecorationState {
        final View bar;
        final FrameLayout contentHost;
        final ViewGroup.LayoutParams decorLayoutParams;
        int lastScreenWidth;
        int lastScreenHeight;

        DecorationState(View bar, FrameLayout contentHost,
                        ViewGroup.LayoutParams decorLayoutParams, DisplayMetrics metrics) {
            this.bar = bar;
            this.contentHost = contentHost;
            this.decorLayoutParams = decorLayoutParams;
            this.lastScreenWidth = metrics.widthPixels;
            this.lastScreenHeight = metrics.heightPixels;
        }
    }

    private FloatingWindow() {}

    public static void install(Application app) {
        if (app == null) return;
        synchronized (LOCK) {
            if (callbacksRegistered) return;
            callbacksRegistered = true;
        }
        app.registerActivityLifecycleCallbacks(new FloatingWindow());
    }

    /**
     * Restore the app's real theme before the Activity's own onCreate runs.
     * AppCompat validates its theme during onCreate, so onActivityCreated/Started
     * are too late. This callback exists from API 29; older devices are not
     * given the dialog theme during clone building.
     */
    @Override public void onActivityPreCreated(Activity activity, Bundle state) {
        // Apply non-modal behavior before Android attaches the Activity window.
        // Setting this only in onActivityStarted can be too late on some builds:
        // the initial window may already have been registered as touch-modal.
        try {
            Window window = activity.getWindow();
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            WindowManager.LayoutParams params = window.getAttributes();
            params.dimAmount = 0f;
            window.setAttributes(params);
        } catch (Throwable error) {
            android.util.Log.e("FukuFloating", "Could not configure non-modal window before creation", error);
        }

        try {
            android.content.pm.ActivityInfo info = activity.getPackageManager()
                    .getActivityInfo(activity.getComponentName(),
                            android.content.pm.PackageManager.GET_META_DATA);
            Bundle metadata = info.metaData;
            if (metadata == null) return;
            int originalTheme = metadata.getInt("com.fuku.floating.ORIGINAL_THEME", 0);
            if (originalTheme != 0) {
                activity.setTheme(originalTheme);
                android.util.Log.d("FukuFloating", "Restored original activity theme: "
                        + activity.getComponentName().flattenToShortString());
            }
        } catch (Throwable error) {
            android.util.Log.e("FukuFloating", "Could not restore original activity theme", error);
        }
    }

    @Override public void onActivityStarted(final Activity activity) {
        markRunDirty(activity);
        synchronized (LOCK) {
            LIVE_ACTIVITIES.add(activity);
        }
        final View decor = activity.getWindow().getDecorView();
        decor.post(new Runnable() {
            @Override public void run() {
                if (!activity.isFinishing() && !activity.isDestroyed()) setup(activity);
            }
        });
    }

    @Override public void onActivityStopped(Activity activity) { saveBounds(activity); }
    @Override public void onActivityPaused(Activity activity) { saveBounds(activity); }
    @Override public void onActivityCreated(Activity activity, Bundle state) {
        synchronized (LOCK) {
            LIVE_ACTIVITIES.add(activity);
        }
    }
    @Override public void onActivityResumed(Activity activity) { FloatingBubble.hide(activity.getTaskId()); }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}

    @Override public void onActivityDestroyed(Activity activity) {
        if (activity.isFinishing()) FloatingBubble.hide(activity.getTaskId());
        View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
        synchronized (LOCK) {
            ViewTreeObserver.OnGlobalLayoutListener listener = LAYOUT_LISTENERS.remove(activity);
            if (listener != null && decor != null) {
                ViewTreeObserver observer = decor.getViewTreeObserver();
                if (observer.isAlive()) observer.removeOnGlobalLayoutListener(listener);
            }
            INSTALLED.remove(activity);
            DECORATIONS.remove(activity);
            INSTALL_RETRIES.remove(activity);
            LIVE_ACTIVITIES.remove(activity);
            if (activity.isFinishing() && LIVE_ACTIVITIES.isEmpty()) {
                markRunClean(activity);
                startupStateRead = false;
            }
        }
    }

    private void setup(final Activity activity) {
        final View decorView = activity.getWindow().getDecorView();
        if (!(decorView instanceof ViewGroup)) {
            android.util.Log.w("FukuFloating", "Cannot install title bar: decor is not a ViewGroup");
            return;
        }
        final ViewGroup decor = (ViewGroup) decorView;

        synchronized (LOCK) {
            if (INSTALLED.contains(activity)) return;
            if (LAYOUT_LISTENERS.containsKey(activity)) return;
        }

        final ViewTreeObserver.OnGlobalLayoutListener listener =
                new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                if (activity.isFinishing() || activity.isDestroyed()) return;

                final View wrapper;
                synchronized (LOCK) {
                    wrapper = DECORATIONS.get(activity);
                }
                if (wrapper == null) {
                    try {
                        installDecoration(activity, decor);
                    } catch (Throwable error) {
                        android.util.Log.w("FukuFloating", "Title-bar installation attempt failed", error);
                        recordInstallRetry(activity, decor, "installation exception");
                    }
                    return;
                }

                Object tag = wrapper.getTag();
                if (!(tag instanceof DecorationState)) return;
                DecorationState state = (DecorationState) tag;

                if (wrapper.getParent() != decor) {
                    if (wrapper.getParent() instanceof ViewGroup) {
                        ((ViewGroup) wrapper.getParent()).removeView(wrapper);
                    }
                    if (wrapper.getParent() == null) {
                        decor.addView(wrapper, 0, state.decorLayoutParams);
                    }
                }

                if (state.bar.getParent() != wrapper) {
                    if (state.bar.getParent() instanceof ViewGroup) {
                        ((ViewGroup) state.bar.getParent()).removeView(state.bar);
                    }
                    ((LinearLayout) wrapper).addView(state.bar, 0, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, Math.round(
                                    38 * activity.getResources().getDisplayMetrics().density)));
                }
                if (state.bar.getVisibility() != View.VISIBLE) {
                    state.bar.setVisibility(View.VISIBLE);
                }

                DisplayMetrics current = activity.getResources().getDisplayMetrics();
                if (state.lastScreenWidth != current.widthPixels
                        || state.lastScreenHeight != current.heightPixels) {
                    state.lastScreenWidth = current.widthPixels;
                    state.lastScreenHeight = current.heightPixels;
                    float density = current.density;
                    WindowManager.LayoutParams p = activity.getWindow().getAttributes();
                    p.width = clampDimension(p.width, Math.round(260 * density), current.widthPixels);
                    p.height = clampDimension(p.height, Math.round(180 * density), current.heightPixels);
                    p.x = clamp(p.x, -p.width + Math.round(72 * density),
                            Math.max(0, current.widthPixels - Math.round(72 * density)));
                    int barHeight = Math.round(38 * density);
                    p.y = clamp(p.y, 0, Math.max(0, current.heightPixels - barHeight));
                    activity.getWindow().setAttributes(p);
                }
            }
        };

        synchronized (LOCK) {
            LAYOUT_LISTENERS.put(activity, listener);
        }
        ViewTreeObserver observer = decor.getViewTreeObserver();
        if (observer.isAlive()) observer.addOnGlobalLayoutListener(listener);
        try {
            installDecoration(activity, decor);
        } catch (Throwable error) {
            android.util.Log.w("FukuFloating", "Initial title-bar installation failed", error);
            recordInstallRetry(activity, decor, "initial installation exception");
        }
    }

    private void installDecoration(final Activity activity, final ViewGroup decor) {
        synchronized (LOCK) {
            if (INSTALLED.contains(activity) || activity.isFinishing() || activity.isDestroyed()) return;
        }
        if (decor.getChildCount() == 0) {
            android.util.Log.w("FukuFloating", "Cannot install title bar yet: decor has no child");
            recordInstallRetry(activity, decor, "decor has no child");
            return;
        }

        final View appRoot = decor.getChildAt(0);
        if (!(appRoot.getParent() instanceof ViewGroup)) {
            android.util.Log.w("FukuFloating", "Cannot wrap decor root: parent is not a ViewGroup");
            recordInstallRetry(activity, decor, "decor root parent is not a ViewGroup");
            return;
        }
        final ViewGroup rootParent = (ViewGroup) appRoot.getParent();
        if (rootParent != decor) {
            android.util.Log.w("FukuFloating", "Cannot wrap decor root: unexpected parent "
                    + rootParent.getClass().getName());
            recordInstallRetry(activity, decor, "unexpected decor root parent");
            return;
        }

        final DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        final float density = metrics.density;
        final boolean dirty;
        synchronized (LOCK) {
            dirty = previousRunDirty;
        }
        int[] bounds = loadValidatedBounds(activity, metrics, dirty);

        Window window = activity.getWindow();
        WindowManager.LayoutParams windowParams = window.getAttributes();
        windowParams.gravity = Gravity.TOP | Gravity.START;
        windowParams.x = bounds[0];
        windowParams.y = bounds[1];
        windowParams.width = clampDimension(bounds[2], Math.round(260 * density), metrics.widthPixels);
        windowParams.height = clampDimension(bounds[3], Math.round(180 * density), metrics.heightPixels);
        windowParams.dimAmount = 0f;
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        window.setAttributes(windowParams);

        final int barHeight = Math.round(38 * density);
        final LinearLayout wrapper = new LinearLayout(activity);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setTag(null);

        final FrameLayout bar = new FrameLayout(activity);
        bar.setBackgroundColor(0xFF202124);
        bar.setElevation(8 * density);

        final TextView title = new TextView(activity);
        String appTitle = activity.getApplicationInfo().loadLabel(activity.getPackageManager()).toString();
        try {
            android.content.pm.PackageInfo packageInfo = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0);
            if (packageInfo.versionName != null && !packageInfo.versionName.trim().isEmpty()) {
                appTitle += " · v" + packageInfo.versionName;
            }
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {}
        title.setText(appTitle);
        title.setTextColor(Color.WHITE);
        title.setTextSize(14);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View view) {
                DisplayMetrics current = activity.getResources().getDisplayMetrics();
                int[] defaults = defaultBounds(current);
                WindowManager.LayoutParams p = activity.getWindow().getAttributes();
                p.x = defaults[0];
                p.y = defaults[1];
                p.width = defaults[2];
                p.height = defaults[3];
                activity.getWindow().setAttributes(p);
                saveBounds(activity);
                android.util.Log.d("FukuFloating", "Title-bar long press reset window to centered bounds");
                return true;
            }
        });
        title.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean dragging;
            @Override public boolean onTouch(View view, MotionEvent event) {
                WindowManager.LayoutParams p = activity.getWindow().getAttributes();
                DisplayMetrics current = activity.getResources().getDisplayMetrics();
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startX = p.x;
                        startY = p.y;
                        dragging = false;
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downX;
                        float dy = event.getRawY() - downY;
                        if (!dragging && (Math.abs(dx) > Math.round(8 * density)
                                || Math.abs(dy) > Math.round(8 * density))) {
                            dragging = true;
                        }
                        if (dragging) {
                            p.x = clamp(startX + Math.round(dx), -p.width + Math.round(72 * density),
                                    Math.max(0, current.widthPixels - Math.round(72 * density)));
                            p.y = clamp(startY + Math.round(dy), 0,
                                    Math.max(0, current.heightPixels - Math.round(38 * density)));
                            activity.getWindow().setAttributes(p);
                        }
                        return false;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        dragging = false;
                        return false;
                    default:
                        return false;
                }
            }
        });
        bar.addView(title, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));

        final LinearLayout controls = new LinearLayout(activity);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams controlsParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END | Gravity.CENTER_VERTICAL);
        bar.addView(controls, controlsParams);

        final boolean[] maximized = {false};
        final int[][] previous = {null};

        TextView minimizeButton = button(activity, "–", density, new View.OnClickListener() {
            @Override public void onClick(View view) { FloatingBubble.minimize(activity); }
        });
        controls.addView(minimizeButton);

        TextView maximizeButton = button(activity, "□", density, new View.OnClickListener() {
            @Override public void onClick(View view) {
                WindowManager.LayoutParams p = activity.getWindow().getAttributes();
                DisplayMetrics current = activity.getResources().getDisplayMetrics();
                if (!maximized[0]) {
                    previous[0] = new int[] {p.x, p.y, p.width, p.height};
                    p.x = 0;
                    p.y = 0;
                    p.width = current.widthPixels;
                    p.height = current.heightPixels;
                } else if (previous[0] != null) {
                    p.x = previous[0][0];
                    p.y = previous[0][1];
                    p.width = previous[0][2];
                    p.height = previous[0][3];
                }
                maximized[0] = !maximized[0];
                activity.getWindow().setAttributes(p);
            }
        });
        controls.addView(maximizeButton);

        TextView closeButton = button(activity, "×", density, new View.OnClickListener() {
            @Override public void onClick(View view) { activity.finish(); }
        });
        controls.addView(closeButton);

        bar.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            @Override public boolean onTouch(View view, MotionEvent event) {
                WindowManager.LayoutParams p = activity.getWindow().getAttributes();
                DisplayMetrics current = activity.getResources().getDisplayMetrics();
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startX = p.x;
                        startY = p.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        p.x = startX + Math.round(event.getRawX() - downX);
                        p.y = startY + Math.round(event.getRawY() - downY);
                        p.x = clamp(p.x, -p.width + Math.round(72 * density),
                                Math.max(0, current.widthPixels - Math.round(72 * density)));
                        int barHeight = Math.round(38 * density);
                        p.y = clamp(p.y, 0, Math.max(0, current.heightPixels - barHeight));
                        activity.getWindow().setAttributes(p);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        view.performClick();
                        return true;
                    default:
                        return true;
                }
            }
        });

        final FrameLayout contentHost = new FrameLayout(activity);
        final int handleSize = Math.round(30 * density);
        View handle = new View(activity);
        handle.setBackgroundColor(0x66808080);
        handle.setContentDescription("Resize floating window");
        FrameLayout.LayoutParams handleParams = new FrameLayout.LayoutParams(
                handleSize, handleSize, Gravity.BOTTOM | Gravity.END);
        handle.setLayoutParams(handleParams);
        handle.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startWidth, startHeight;
            @Override public boolean onTouch(View view, MotionEvent event) {
                WindowManager.LayoutParams p = activity.getWindow().getAttributes();
                DisplayMetrics current = activity.getResources().getDisplayMetrics();
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startWidth = p.width;
                        startHeight = p.height;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        p.width = clamp(startWidth + Math.round(event.getRawX() - downX),
                                Math.round(260 * density), current.widthPixels);
                        p.height = clamp(startHeight + Math.round(event.getRawY() - downY),
                                Math.round(180 * density), current.heightPixels);
                        activity.getWindow().setAttributes(p);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        view.performClick();
                        return true;
                    default:
                        return true;
                }
            }
        });

        ViewGroup.LayoutParams originalDecorParams = appRoot.getLayoutParams();
        decor.removeView(appRoot);
        contentHost.addView(appRoot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        contentHost.addView(handle, handleParams);
        wrapper.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, barHeight));
        wrapper.addView(contentHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        DecorationState state = new DecorationState(bar, contentHost, originalDecorParams,
                activity.getResources().getDisplayMetrics());
        wrapper.setTag(state);
        decor.addView(wrapper, 0, originalDecorParams);

        synchronized (LOCK) {
            INSTALLED.add(activity);
            DECORATIONS.put(activity, wrapper);
            INSTALL_RETRIES.remove(activity);
        }
        android.util.Log.d("FukuFloating", "Installed floating title bar with bounds x=" + windowParams.x
                + ", y=" + windowParams.y + ", w=" + windowParams.width + ", h=" + windowParams.height);
    }

    private static TextView button(Context context, String label, float density,
                                   View.OnClickListener listener) {
        TextView text = new TextView(context);
        text.setText(label);
        text.setTextColor(Color.WHITE);
        text.setTextSize(18);
        text.setGravity(Gravity.CENTER);
        text.setLayoutParams(new LinearLayout.LayoutParams(Math.round(42 * density),
                ViewGroup.LayoutParams.MATCH_PARENT));
        text.setOnClickListener(listener);
        return text;
    }

    private static int clamp(int value, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, value));
    }

    private static int clampDimension(int value, int min, int max) {
        if (max <= 0) return Math.max(1, value);
        return Math.min(max, Math.max(Math.min(min, max), value));
    }

    private static int[] defaultBounds(DisplayMetrics metrics) {
        float density = metrics.density;
        int width = clampDimension(Math.round(metrics.widthPixels * 0.82f),
                Math.round(260 * density), metrics.widthPixels);
        int height = clampDimension(Math.round(metrics.heightPixels * 0.78f),
                Math.round(180 * density), metrics.heightPixels);
        return new int[] {
                Math.max(0, (metrics.widthPixels - width) / 2),
                Math.max(0, (metrics.heightPixels - height) / 2),
                width, height
        };
    }

    private static boolean screenSizeChangedALot(int oldWidth, int oldHeight,
                                                  int newWidth, int newHeight) {
        if (oldWidth <= 0 || oldHeight <= 0) return true;
        double widthChange = Math.abs(newWidth - oldWidth) / (double) oldWidth;
        double heightChange = Math.abs(newHeight - oldHeight) / (double) oldHeight;
        double oldRatio = oldWidth / (double) oldHeight;
        double newRatio = newWidth / (double) newHeight;
        double ratioChange = Math.abs(newRatio - oldRatio) / oldRatio;
        return widthChange > 0.20 || heightChange > 0.20 || ratioChange > 0.20;
    }

    private static void markRunDirty(Activity activity) {
        synchronized (LOCK) {
            if (startupStateRead) return;
            SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            previousRunDirty = !prefs.getBoolean("clean_exit", true);
            startupStateRead = true;
            boolean saved = prefs.edit().putBoolean("clean_exit", false).commit();
            android.util.Log.d("FukuFloating", "Previous run dirty flag=" + previousRunDirty
                    + ", marked current run dirty (saved=" + saved + ")");
        }
    }

    private static void markRunClean(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean saved = prefs.edit().putBoolean("clean_exit", true).commit();
        android.util.Log.d("FukuFloating", "Marked run clean on finishing activity (saved=" + saved + ")");
    }

    private static void recordInstallRetry(Activity activity, ViewGroup decor, String reason) {
        ViewTreeObserver.OnGlobalLayoutListener remove = null;
        synchronized (LOCK) {
            int attempts = INSTALL_RETRIES.containsKey(activity) ? INSTALL_RETRIES.get(activity) + 1 : 1;
            INSTALL_RETRIES.put(activity, attempts);
            android.util.Log.w("FukuFloating", "Title-bar setup retry " + attempts + "/"
                    + MAX_INSTALL_RETRIES + ": " + reason);
            if (attempts >= MAX_INSTALL_RETRIES) {
                remove = LAYOUT_LISTENERS.remove(activity);
                INSTALL_RETRIES.remove(activity);
                android.util.Log.w("FukuFloating", "Stopped title-bar retries after "
                        + MAX_INSTALL_RETRIES + " attempts");
            }
        }
        if (remove != null) {
            ViewTreeObserver observer = decor.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnGlobalLayoutListener(remove);
        }
    }

    private static int[] loadValidatedBounds(Activity activity, DisplayMetrics metrics,
                                             boolean dirty) {
        int[] saved = loadBounds(activity);
        android.util.Log.d("FukuFloating", "Loaded saved bounds="
                + (saved == null ? "none" : "x=" + saved[0] + ",y=" + saved[1]
                + ",w=" + saved[2] + ",h=" + saved[3] + ",screen=" + saved[4] + "x" + saved[5])
                + ", dirty flag=" + dirty);

        if (dirty || saved == null) {
            int[] defaults = defaultBounds(metrics);
            android.util.Log.d("FukuFloating", "Validated bounds=default centered (dirty or no saved bounds)");
            return defaults;
        }

        float density = metrics.density;
        int minWidth = Math.round(260 * density);
        int minHeight = Math.round(180 * density);
        int barHeight = Math.round(38 * density);
        if (saved[2] <= 0 || saved[3] <= 0
                || saved[0] < -metrics.widthPixels * 2 || saved[0] > metrics.widthPixels * 2
                || saved[1] < -metrics.heightPixels * 2 || saved[1] > metrics.heightPixels * 2
                || screenSizeChangedALot(saved[4], saved[5], metrics.widthPixels, metrics.heightPixels)) {
            int[] defaults = defaultBounds(metrics);
            android.util.Log.d("FukuFloating", "Validated bounds=default centered (saved bounds invalid or screen changed)");
            return defaults;
        }

        int width = clampDimension(saved[2], minWidth, metrics.widthPixels);
        int height = clampDimension(saved[3], minHeight, metrics.heightPixels);
        int x = clamp(saved[0], -width + Math.round(72 * density),
                Math.max(0, metrics.widthPixels - Math.round(72 * density)));
        int y = clamp(saved[1], 0, Math.max(0, metrics.heightPixels - barHeight));
        int[] validated = new int[] {x, y, width, height};
        android.util.Log.d("FukuFloating", "Validated bounds=x=" + x + ",y=" + y
                + ",w=" + width + ",h=" + height);
        return validated;
    }

    private static String boundsKey(Activity activity, String suffix) {
        return BOUNDS_PREFIX + activity.getClass().getName() + "." + suffix;
    }

    private static void saveBounds(Activity activity) {
        try {
            final View wrapper;
            synchronized (LOCK) {
                wrapper = DECORATIONS.get(activity);
            }
            if (wrapper == null || !(wrapper.getTag() instanceof DecorationState)) return;
            DecorationState state = (DecorationState) wrapper.getTag();
            View decor = activity.getWindow().getDecorView();
            if (wrapper.getParent() != decor || state.bar.getParent() != wrapper) return;

            DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            WindowManager.LayoutParams p = activity.getWindow().getAttributes();
            if (p.width <= 0 || p.height <= 0) return;
            int barHeight = Math.round(38 * metrics.density);
            int minVisible = Math.round(72 * metrics.density);
            if (p.x < -p.width + minVisible || p.x > metrics.widthPixels - minVisible
                    || p.y < 0 || p.y + barHeight > metrics.heightPixels) {
                android.util.Log.w("FukuFloating", "Skipped saving invalid/off-screen floating bounds");
                return;
            }

            SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            prefs.edit()
                    .putInt(boundsKey(activity, "x"), p.x)
                    .putInt(boundsKey(activity, "y"), p.y)
                    .putInt(boundsKey(activity, "w"), p.width)
                    .putInt(boundsKey(activity, "h"), p.height)
                    .putInt(boundsKey(activity, "screen_width"), metrics.widthPixels)
                    .putInt(boundsKey(activity, "screen_height"), metrics.heightPixels)
                    .apply();
        } catch (Throwable error) {
            android.util.Log.w("FukuFloating", "Could not save floating bounds", error);
        }
    }

    private static int[] loadBounds(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int width = prefs.getInt(boundsKey(activity, "w"), 0);
        int height = prefs.getInt(boundsKey(activity, "h"), 0);
        if (width <= 0 || height <= 0) return null;
        return new int[] {
                prefs.getInt(boundsKey(activity, "x"), 0),
                prefs.getInt(boundsKey(activity, "y"), 0),
                width, height,
                prefs.getInt(boundsKey(activity, "screen_width"), 0),
                prefs.getInt(boundsKey(activity, "screen_height"), 0)
        };
    }
}
