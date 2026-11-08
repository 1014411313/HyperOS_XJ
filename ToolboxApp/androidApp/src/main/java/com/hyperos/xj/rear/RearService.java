package com.hyperos.xj.rear;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

public class RearService extends Service {
    private static final String CHANNEL = "xjrear";
    private static final int NOTIFY_ID = 0x584A;
    private PowerManager.WakeLock wakeLock;

    public static void start(Context ctx) {
        try {
            Intent intent = new Intent(ctx, RearService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent);
            } else {
                ctx.startService(intent);
            }
        } catch (Throwable ignored) {
            // 后台 FGS 限制时由 LSP overlay 继续工作，服务只负责常驻/通知。
        }
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, RearService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "背屏电池卡片", NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }

        Notification notification;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("背屏电池卡片")
                    .setContentText("插电时在背屏显示电池状态")
                    .setOngoing(true)
                    .build();
        } else {
            notification = new Notification.Builder(this)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("背屏电池卡片")
                    .setContentText("插电时在背屏显示电池状态")
                    .setOngoing(true)
                    .build();
        }

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFY_ID, notification);
            }
        } catch (Throwable t) {
            stopSelf();
        }
        Prefs.sync(this, Prefs.read(this));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        RearConfig cfg = Prefs.read(this);
        Prefs.sync(this, cfg);
        if (cfg.keepOn) {
            acquireWakeLock();
        } else {
            releaseWakeLock();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void acquireWakeLock() {
        if (wakeLock != null) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xjrear:panel");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null) {
            try {
                wakeLock.release();
            } catch (Throwable ignored) {
            }
            wakeLock = null;
        }
    }
}
