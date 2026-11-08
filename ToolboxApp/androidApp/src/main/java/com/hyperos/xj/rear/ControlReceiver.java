package com.hyperos.xj.rear;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.io.File;

public class ControlReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.hyperos.xj.rear.CONTROL";
    // 内置默认动图独立成文件：旧版与用户自选图共用路径会互相覆盖，恢复默认后回不到四叶草（bug-070）
    private static final String DEFAULT_BG_PATH = "/data/local/tmp/xj_rear_bg_default.gif";
    private static final File DEFAULT_BG_FILE = new File(DEFAULT_BG_PATH);

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        RearConfig old = Prefs.read(context);
        boolean enabled = intent.hasExtra("enabled")
                ? intent.getBooleanExtra("enabled", old.enabled) : old.enabled;
        boolean keepOn = intent.hasExtra("keep_on")
                ? intent.getBooleanExtra("keep_on", old.keepOn) : old.keepOn;
        String accent = intent.getStringExtra("accent");
        if (accent == null || accent.isEmpty()) accent = old.accent;
        float scale = intent.hasExtra("font_scale")
                ? intent.getFloatExtra("font_scale", old.fontScale) : old.fontScale;
        String fields = intent.getStringExtra("fields");
        if (fields == null || fields.isEmpty()) fields = old.fields;

        // 空 bg / 无 bg extra = 恢复默认：回落内置默认动图，读不到该文件时才退回黑色（bug-070）
        String bg = intent.getStringExtra("bg");
        RearConfig cfg;
        if (bg != null && bg.startsWith("image:")) {
            cfg = new RearConfig(enabled, keepOn, accent, scale, fields,
                    null, bg.substring("image:".length()));
        } else if (bg != null && bg.startsWith("color:")) {
            cfg = new RearConfig(enabled, keepOn, accent, scale, fields,
                    bg.substring("color:".length()), null);
        } else if (DEFAULT_BG_FILE.isFile()) {
            cfg = new RearConfig(enabled, keepOn, accent, scale, fields,
                    null, DEFAULT_BG_PATH);
        } else {
            cfg = new RearConfig(enabled, keepOn, accent, scale, fields);
        }
        Prefs.write(context, cfg);
        if (cfg.enabled) {
            RearService.start(context);
        } else {
            RearService.stop(context);
        }
    }
}
