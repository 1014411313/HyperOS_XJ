package com.hyperos.xj.rear;

public final class RearConfig {
    public final boolean enabled;
    public final boolean keepOn;
    public final String accent;
    public final float fontScale;

    public RearConfig(boolean enabled, boolean keepOn, String accent, float fontScale) {
        this.enabled = enabled;
        this.keepOn = keepOn;
        this.accent = accent == null || accent.isEmpty() ? "#38BDF8" : accent;
        this.fontScale = Math.max(0.8f, Math.min(1.4f, fontScale));
    }
}
