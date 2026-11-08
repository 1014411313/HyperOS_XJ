package com.hyperos.xj.rear;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.Map;

public final class Prefs {
    public static final String CONFIG_PATH =
            "/storage/emulated/0/Android/data/com.hyperos.xj/files/rear.conf";
    public static final String TMP_CONFIG_PATH = "/data/local/tmp/xj_rear.conf";
    private static final String NAME = "xjrear";

    private Prefs() {
    }

    public static RearConfig read(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return new RearConfig(
                p.getBoolean("enabled", true),
                p.getBoolean("keep_on", true),
                p.getString("accent", "#38BDF8"),
                p.getFloat("font_scale", 1f),
                p.getString("fields", RearConfig.DEFAULT_FIELDS)
        );
    }

    public static void write(Context ctx, RearConfig cfg) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", cfg.enabled)
                .putBoolean("keep_on", cfg.keepOn)
                .putString("accent", cfg.accent)
                .putFloat("font_scale", cfg.fontScale)
                .putString("fields", cfg.fields)
                .apply();
        sync(ctx, cfg);
    }

    public static void sync(Context ctx, RearConfig cfg) {
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) return;
            if (!dir.exists()) dir.mkdirs();
            File target = new File(dir, "rear.conf");
            File tmp = new File(dir, "rear.conf.tmp");
            writeConf(tmp, cfg);
            if (!tmp.renameTo(target)) {
                target.delete();
                tmp.renameTo(target);
            }
            try {
                writeConf(new File(TMP_CONFIG_PATH), cfg);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    private static void writeConf(File file, RearConfig cfg) throws java.io.IOException {
        FileWriter writer = new FileWriter(file, false);
        writer.write("enabled=" + (cfg.enabled ? 1 : 0) + "\n");
        writer.write("keep_on=" + (cfg.keepOn ? 1 : 0) + "\n");
        writer.write("accent=" + cfg.accent + "\n");
        writer.write("font_scale=" + cfg.fontScale + "\n");
        writer.write("fields=" + cfg.fields + "\n");
        if (!cfg.bgImagePath.isEmpty()) {
            writer.write("bg=image:" + cfg.bgImagePath + "\n");
        } else if (!"#000000".equals(cfg.bgColor)) {
            writer.write("bg=color:" + cfg.bgColor + "\n");
        }
        writer.close();
    }

    public static RearConfig readConfig() {
        try {
            File file = new File(CONFIG_PATH);
            if (!file.exists()) file = new File(TMP_CONFIG_PATH);
            if (!file.exists()) return new RearConfig(true, true, "#38BDF8", 1f, RearConfig.DEFAULT_FIELDS);
            Map<String, String> map = new HashMap<>();
            BufferedReader reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                int idx = line.indexOf('=');
                if (idx > 0) {
                    map.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
                }
            }
            reader.close();
            String bgColor = null;
            String bgImage = null;
            String bg = map.get("bg");
            if (bg != null) {
                String v = bg.trim();
                if (v.startsWith("color:")) {
                    bgColor = v.substring("color:".length()).trim();
                } else if (v.startsWith("image:")) {
                    bgImage = v.substring("image:".length()).trim();
                }
            }
            return new RearConfig(
                    !"0".equals(map.get("enabled")),
                    !"0".equals(map.get("keep_on")),
                    map.containsKey("accent") ? map.get("accent") : "#38BDF8",
                    parseFloat(map.get("font_scale"), 1f),
                    map.get("fields"),
                    bgColor,
                    bgImage
            );
        } catch (Throwable t) {
            return new RearConfig(true, true, "#38BDF8", 1f, RearConfig.DEFAULT_FIELDS);
        }
    }

    private static float parseFloat(String value, float fallback) {
        try {
            return Float.parseFloat(value);
        } catch (Throwable t) {
            return fallback;
        }
    }
}
