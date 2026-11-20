package com.hyperos.xj.rear;

import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * 面板可见期间的限时唤醒锁：只在卡片实际显示时持有，不可见立即释放，
 * 到期自动失效，避免宿主进程长期持锁。持锁失败只降级，不影响面板。
 */
final class WakeGuard {
    private static final long HOLD_MS = 10 * 60 * 1000L;
    private static final long RENEW_MARGIN_MS = 60 * 1000L;

    private static PowerManager.WakeLock lock;
    private static long expiresAt;

    private WakeGuard() {
    }

    static synchronized void keep(Context ctx) {
        long now = SystemClock.elapsedRealtime();
        if (lock != null && expiresAt - now > RENEW_MARGIN_MS) return;
        releaseLock();
        try {
            PowerManager pm = (PowerManager) ctx.getApplicationContext()
                    .getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xjrear:panel");
            wl.setReferenceCounted(false);
            wl.acquire(HOLD_MS);
            lock = wl;
            expiresAt = now + HOLD_MS;
        } catch (Throwable ignored) {
        }
    }

    static synchronized void release() {
        releaseLock();
        expiresAt = 0L;
    }

    private static void releaseLock() {
        if (lock != null) {
            try {
                lock.release();
            } catch (Throwable ignored) {
            }
            lock = null;
        }
    }
}
