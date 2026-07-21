package com.fongmi.android.tv.player.exo;

/** Compatibility and speed boundaries for EXO network-protection playback. */
public final class ExoNetworkProtectionPolicy {

    public static final int MODE_OFF = 0;
    public static final int MODE_STANDARD = 1;
    public static final int MODE_ENHANCED = 2;
    public static final int MODE_AGGRESSIVE = 3;

    private ExoNetworkProtectionPolicy() {
    }

    public static Decision resolve(int mode) {
        int normalized = Math.min(Math.max(mode, MODE_OFF), MODE_AGGRESSIVE);
        float minimumSpeed = switch (normalized) {
            case MODE_STANDARD -> 0.95f;
            case MODE_ENHANCED -> 0.90f;
            case MODE_AGGRESSIVE -> 0.85f;
            default -> 1.0f;
        };
        boolean enabled = normalized != MODE_OFF;
        return new Decision(normalized, enabled, minimumSpeed, enabled, enabled, enabled);
    }

    public record Decision(int mode, boolean enabled, float minimumSpeed, boolean disableTunneling, boolean forcePcm, boolean suppressOutputMode) {
    }
}
