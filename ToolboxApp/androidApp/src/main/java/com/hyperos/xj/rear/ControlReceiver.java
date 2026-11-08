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

        RearConfig cfg = new RearConfig(enabled, keepOn, accent, scale, fields);
        Prefs.write(context, cfg);
        if (cfg.enabled) {
            RearService.start(context);
        } else {
            RearService.stop(context);
        }
    }
}
