package com.hyperos.xj.rear;

import java.util.Arrays;
import java.util.LinkedHashSet;

public final class RearConfig {
    public static final String DEFAULT_FIELDS = "temp,soc,volt,cur";
    private static final LinkedHashSet<String> FIELD_IDS = new LinkedHashSet<>(Arrays.asList(
            "temp", "soc", "volt", "cur", "power", "status", "design", "full",
            "health", "cycle", "fake", "proto", "rm", "soh"));

    public final boolean enabled;
    public final boolean keepOn;
    public final String accent;
    public final float fontScale;
    public final String fields;

    public RearConfig(boolean enabled, boolean keepOn, String accent, float fontScale, String fields) {
        this.enabled = enabled;
        this.keepOn = keepOn;
        this.accent = accent == null || accent.isEmpty() ? "#38BDF8" : accent;
        this.fontScale = Math.max(0.8f, Math.min(1.4f, fontScale));
        this.fields = normalizeFields(fields);
    }

    private static String normalizeFields(String raw) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                String id = part.trim();
                if (FIELD_IDS.contains(id)) out.add(id);
                if (out.size() == 4) break;
            }
        }
        return out.isEmpty() ? DEFAULT_FIELDS : String.join(",", out);
    }
}
