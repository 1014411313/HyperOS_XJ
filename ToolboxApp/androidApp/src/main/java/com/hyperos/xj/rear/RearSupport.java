package com.hyperos.xj.rear;

import android.content.Context;
import android.content.pm.PackageManager;

/**
 * 背屏宿主判定：本模块的 LSP hook 只作用于 com.xiaomi.subscreencenter，
 * 宿主未安装即无背屏，与 engine 的 rearscreen_supported 判据一致。
 */
public final class RearSupport {
    public static final String HOST_PACKAGE = "com.xiaomi.subscreencenter";

    private RearSupport() {
    }

    public static boolean isSupported(Context ctx) {
        if (ctx == null) return false;
        PackageManager pm = ctx.getPackageManager();
        if (pm == null) return false;
        try {
            pm.getPackageInfo(HOST_PACKAGE, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
