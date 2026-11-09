package com.hyperos.xj.rear;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class Overlay {
    private static final Map<Activity, FrameLayout> OVERLAYS = new WeakHashMap<>();
    private static final Map<Activity, Runnable> TICKS = new WeakHashMap<>();
    // 上滑退出后按 Activity 记忆；重新插枪会清掉，让卡片回来。
    private static final Set<Activity> DISMISSED =
            Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>());
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> lastActivity = new WeakReference<>(null);
    private static boolean lastCharging = false;

    private Overlay() {
    }

    public static synchronized void attach(Activity activity) {
        lastActivity = new WeakReference<>(activity);
        if (OVERLAYS.containsKey(activity)) return;
        // 上滑退出过且当前没在充电就不叠加；插枪后由 onPowerConnected() 清标记再进来。
        if (DISMISSED.contains(activity) && !BatteryReader.read(activity).charging) return;
        try {
            View decor = activity.getWindow().getDecorView();
            if (!(decor instanceof ViewGroup)) return;
            ViewGroup root = (ViewGroup) decor;
            FrameLayout container = new FrameLayout(activity);
            container.setTag("xjrear_overlay");
            container.setClickable(false);
            container.setFocusable(false);
            PanelView panel = new PanelView(activity);
            panel.setOnSwipeUpListener(new Runnable() {
                @Override
                public void run() {
                    dismiss(activity);
                }
            });
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
                        ChargeState state = BatteryReader.read(activity);
                        boolean charging = state.charging;
                        // 重新插枪：清掉上滑退出的记忆，卡片重新出现
                        if (charging && !lastCharging) DISMISSED.remove(activity);
                        lastCharging = charging;
                        boolean show = cfg.enabled && charging;
                        container.setVisibility(show ? View.VISIBLE : View.GONE);
                        if (show) panel.update(state, cfg);
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

    /** 充电器插入：清掉上滑退出的记忆并立即重新叠加，卡片不用等重启就会回来。 */
    public static void onPowerConnected() {
        HANDLER.post(new Runnable() {
            @Override
            public void run() {
                synchronized (Overlay.class) {
                    DISMISSED.clear();
                    Activity activity = lastActivity.get();
                    if (activity != null) attach(activity);
                }
            }
        });
    }

    /** 上滑退出：只隐藏并记标记，保留 tick 以便插枪后自动恢复。 */
    public static synchronized void dismiss(Activity activity) {
        DISMISSED.add(activity);
        FrameLayout container = OVERLAYS.get(activity);
        if (container != null) container.setVisibility(View.GONE);
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
