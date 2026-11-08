package com.hyperos.xj.rear;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ControlReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.hyperos.xj.rear.CONTROL";

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

        // 引擎 send_control 总是附带 bg（空串=清除）；旧引擎不带 bg extra，此处重置为默认色。
        RearConfig cfg;
        if (intent.hasExtra("bg")) {
            String bg = intent.getStringExtra("bg");
            if (bg != null && bg.startsWith("image:")) {
                cfg = new RearConfig(enabled, keepOn, accent, scale, fields,
                        null, bg.substring("image:".length()));
            } else if (bg != null && bg.startsWith("color:")) {
                cfg = new RearConfig(enabled, keepOn, accent, scale, fields,
                        bg.substring("color:".length()), null);
            } else {
                cfg = new RearConfig(enabled, keepOn, accent, scale, fields);
            }
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
