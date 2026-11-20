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
        if (DISMISSED.contains(activity) && !BatteryReader.charging(activity)) return;
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
            // 初始隐藏，由 tick 按真实充电与可见性决定；避免未充电时闪一帧。
            container.setVisibility(View.GONE);
            OVERLAYS.put(activity, container);

            Runnable tick = new Runnable() {
                @Override
                public void run() {
                    try {
                        FrameLayout current = OVERLAYS.get(activity);
                        if (current == null) return;
                        // 不可见时只读粘贴的电池广播，不读 sysfs、不做整包状态采样。
                        boolean charging = BatteryReader.charging(activity);
                        if (charging && !lastCharging) DISMISSED.remove(activity);
                        lastCharging = charging;
                        boolean show = false;
                        boolean visible = hostVisible(activity);
                        if (charging && !DISMISSED.contains(activity)) {
                            RearConfig cfg = Prefs.readConfig();
                            show = cfg.enabled && visible;
                            if (show) {
                                panel.update(BatteryReader.read(activity), cfg);
                                if (cfg.keepOn) WakeGuard.keep(activity);
                                else WakeGuard.release();
                            }
                        }
                        if (show) {
                            if (current.getVisibility() != View.VISIBLE) current.setVisibility(View.VISIBLE);
                        } else {
                            if (current.getVisibility() != View.GONE) current.setVisibility(View.GONE);
                            WakeGuard.release();
                        }
                        // 可见时 2 秒刷新；宿主不可见时放慢到 10 秒，不跑动画/采样/持锁。
                        if (OVERLAYS.containsKey(activity)) {
                            HANDLER.postDelayed(this, visible ? 2000L : 10000L);
                        }
                    } catch (Throwable ignored) {
                        // 本拍异常时先撤下卡片并释放锁，避免上一拍的动画/唤醒锁被保留。
                        try {
                            FrameLayout current = OVERLAYS.get(activity);
                            if (current != null && current.getVisibility() != View.GONE) {
                                current.setVisibility(View.GONE);
                            }
                        } catch (Throwable ignored2) {
                        }
                        WakeGuard.release();
                        if (OVERLAYS.containsKey(activity)) {
                            HANDLER.postDelayed(this, 10000L);
                        }
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
        WakeGuard.release();
        FrameLayout container = OVERLAYS.get(activity);
        if (container != null) container.setVisibility(View.GONE);
    }

    /** 宿主窗口真的可见时才叠加与采样；Activity 已结束或窗口不可见都视为不可见。 */
    private static boolean hostVisible(Activity activity) {
        try {
            if (activity == null || activity.isFinishing()) return false;
            View decor = activity.getWindow().getDecorView();
            return decor.getWindowVisibility() == View.VISIBLE && decor.isShown();
        } catch (Throwable t) {
            return false;
        }
    }

    public static synchronized void detach(Activity activity) {
        FrameLayout container = OVERLAYS.remove(activity);
        Runnable tick = TICKS.remove(activity);
        if (tick != null) HANDLER.removeCallbacks(tick);
        if (container != null && container.getParent() instanceof ViewGroup) {
            ((ViewGroup) container.getParent()).removeView(container);
        }
        WakeGuard.release();
    }
}
