package com.hyperos.xj.rear;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.util.Map;
import java.util.WeakHashMap;

public final class Overlay {
    private static final Map<Activity, FrameLayout> OVERLAYS = new WeakHashMap<>();
    private static final Map<Activity, Runnable> TICKS = new WeakHashMap<>();
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    private Overlay() {
    }

    public static synchronized void attach(Activity activity) {
        if (OVERLAYS.containsKey(activity)) return;
        try {
            View decor = activity.getWindow().getDecorView();
            if (!(decor instanceof ViewGroup)) return;
            ViewGroup root = (ViewGroup) decor;
            FrameLayout container = new FrameLayout(activity);
            container.setTag("xjrear_overlay");
            container.setClickable(false);
            container.setFocusable(false);
            PanelView panel = new PanelView(activity);
            container.addView(panel, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(container, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            OVERLAYS.put(activity, container);

            Runnable tick = new Runnable() {
                @Override
                public void run() {
                    try {
                        RearConfig cfg = Prefs.readConfig();
                        container.setVisibility(cfg.enabled ? View.VISIBLE : View.GONE);
                        if (cfg.enabled) {
                            panel.update(BatteryReader.read(activity), cfg);
                        }
                    } catch (Throwable ignored) {
                    }
                    if (OVERLAYS.containsKey(activity)) {
                        HANDLER.postDelayed(this, 2000L);
                    }
                }
            };
            TICKS.put(activity, tick);
            HANDLER.post(tick);
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void detach(Activity activity) {
        FrameLayout container = OVERLAYS.remove(activity);
        Runnable tick = TICKS.remove(activity);
        if (tick != null) HANDLER.removeCallbacks(tick);
        if (container != null && container.getParent() instanceof ViewGroup) {
            ((ViewGroup) container.getParent()).removeView(container);
        }
    }
}
