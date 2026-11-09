package com.hyperos.xj.rear;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class RearHook implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"com.xiaomi.subscreencenter".equals(lpparam.packageName)) return;
        try {
            Class<?> launcher = XposedHelpers.findClass(
                    "com.xiaomi.subscreencenter.SubScreenLauncher", lpparam.classLoader);

            XC_MethodHook attach = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof Activity) {
                        Overlay.attach((Activity) param.thisObject);
                    }
                }
            };
            XC_MethodHook detach = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof Activity) {
                        Overlay.detach((Activity) param.thisObject);
                    }
                }
            };

            XposedHelpers.findAndHookMethod(launcher, "onCreate", android.os.Bundle.class, attach);
            XposedHelpers.findAndHookMethod(launcher, "onNewIntent", android.content.Intent.class, attach);
            XposedHelpers.findAndHookMethod(launcher, "onResume", attach);
            XposedHelpers.findAndHookMethod(launcher, "onPause", detach);

            Class<?> appClass = XposedHelpers.findClass(
                    "com.xiaomi.subscreencenter.SubScreenCenterApp", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(appClass, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Context ctx = (Context) param.thisObject;
                        IntentFilter filter = new IntentFilter(Intent.ACTION_POWER_CONNECTED);
                        ctx.registerReceiver(new BroadcastReceiver() {
                            @Override
                            public void onReceive(Context c, Intent intent) {
                                if (Intent.ACTION_POWER_CONNECTED.equals(intent.getAction())) {
                                    Overlay.onPowerConnected();
                                }
                            }
                        }, filter);
                    } catch (Throwable ignored) {
                    }
                    new Handler(Looper.getMainLooper()).postDelayed(Find::attach, 800L);
                }
            });
            XposedBridge.log("XJRear: hooks installed");
        } catch (Throwable t) {
            XposedBridge.log("XJRear: hook failed " + t);
        }
    }
}
