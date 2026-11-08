package com.hyperos.xj.rear;

import android.app.Activity;
import java.util.Map;
import de.robv.android.xposed.XposedHelpers;

public final class Find {
    private Find() {
    }

    public static void attach() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = XposedHelpers.callStaticMethod(activityThread, "currentActivityThread");
            if (thread == null) return;
            Object activities = XposedHelpers.getObjectField(thread, "mActivities");
            if (!(activities instanceof Map)) return;
            for (Object record : ((Map<?, ?>) activities).values()) {
                Object activity = XposedHelpers.getObjectField(record, "activity");
                if (activity instanceof Activity) {
                    Activity candidate = (Activity) activity;
                    if ("com.xiaomi.subscreencenter.SubScreenLauncher"
                            .equals(candidate.getClass().getName())) {
                        Overlay.attach(candidate);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
