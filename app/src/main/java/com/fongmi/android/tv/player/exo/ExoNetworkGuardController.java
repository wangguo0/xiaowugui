package com.fongmi.android.tv.player.exo;

/** Buffer-trend controller for bounded, pitch-preserving EXO network protection. */
public final class ExoNetworkGuardController {

    public static final long SAMPLE_INTERVAL_MS = 5_000;
    static final long ADJUSTMENT_COOLDOWN_MS = 15_000;
    static final long WARNING_CONFIRM_MS = 10_000;
    static final long RECOVERY_CONFIRM_MS = 20_000;
    static final long FULL_BUFFER_RECOVERY_CONFIRM_MS = 30_000;
    static final long UNSUSTAINABLE_CONFIRM_MS = 15_000;
    static final long DECLINE_LIMIT_MS_PER_SECOND = -40;
    static final long SEVERE_DECLINE_MS_PER_SECOND = -150;
    static final long RECOVERY_LIMIT_MS_PER_SECOND = 40;
    static final long PROTECTION_BUFFER_CEILING_MS = 45_000;
    static final long RECOVERY_BUFFER_FLOOR_MS = 25_000;
    static final long FULL_BUFFER_RECOVERY_MS = 50_000;
    static final long UNSUSTAINABLE_BUFFER_MS = 12_000;
    static final float SPEED_STEP = 0.02f;

    private static final long UNSET = Long.MIN_VALUE;
    private static final float EPSILON = 0.001f;

    private State state = State.NORMAL;
    private long warningSinceMs = UNSET;
    private long recoverySinceMs = UNSET;
    private long floorDeclineSinceMs = UNSET;
    private long lastAdjustmentMs = UNSET;
    private int lastRebufferCount;

    public void reset() {
        state = State.NORMAL;
        warningSinceMs = UNSET;
        recoverySinceMs = UNSET;
        floorDeclineSinceMs = UNSET;
        lastAdjustmentMs = UNSET;
        lastRebufferCount = 0;
    }

    public void disrupt(float currentSpeed) {
        warningSinceMs = UNSET;
        recoverySinceMs = UNSET;
        floorDeclineSinceMs = UNSET;
        state = currentSpeed < 1f - EPSILON ? State.PROTECT : State.NORMAL;
    }

    public State getState() {
        return state;
    }

    public Decision evaluate(Input input) {
        float currentSpeed = clampSpeed(input.currentSpeed(), 0.25f, 1f);
        float minimumSpeed = clampSpeed(input.minimumSpeed(), 0.85f, 1f);
        if (!input.eligible()) {
            boolean changed = currentSpeed < 1f - EPSILON;
            reset();
            return new Decision(state, 1f, changed, "ineligible", supportedSpeed(currentSpeed, input));
        }

        boolean rebuffered = input.rebufferCount() > lastRebufferCount;
        lastRebufferCount = Math.max(lastRebufferCount, input.rebufferCount());
        if (!input.ready() || !input.playing()) return hold(currentSpeed, input, "inactive");

        if (rebuffered) {
            clearEvidence();
            if (currentSpeed > minimumSpeed + EPSILON && canAdjust(input.nowMs())) {
                return adjust(input.nowMs(), currentSpeed, Math.max(minimumSpeed, currentSpeed - SPEED_STEP), input, "rebuffer");
            }
            if (currentSpeed <= minimumSpeed + EPSILON) {
                state = State.UNSUSTAINABLE;
                return hold(currentSpeed, input, "rebuffer-at-floor");
            }
        }

        if (!input.loading()) return evaluateFullBufferRecovery(input, currentSpeed);
        if (!input.trendKnown() || input.trendWindowMs() < 5_000) return hold(currentSpeed, input, "trend-warmup");

        long slope = input.slopeMsPerSecond();
        boolean declining = slope <= DECLINE_LIMIT_MS_PER_SECOND && input.bufferedMs() <= PROTECTION_BUFFER_CEILING_MS;
        if (declining) return evaluateDecline(input, currentSpeed, minimumSpeed);
        floorDeclineSinceMs = UNSET;
        warningSinceMs = UNSET;

        boolean recovering = slope >= RECOVERY_LIMIT_MS_PER_SECOND && input.bufferedMs() >= RECOVERY_BUFFER_FLOOR_MS;
        if (recovering && currentSpeed < 1f - EPSILON) return evaluateRecovery(input, currentSpeed);

        recoverySinceMs = UNSET;
        state = currentSpeed < 1f - EPSILON ? State.PROTECT : State.NORMAL;
        return hold(currentSpeed, input, "stable");
    }

    private Decision evaluateDecline(Input input, float currentSpeed, float minimumSpeed) {
        recoverySinceMs = UNSET;
        if (currentSpeed <= minimumSpeed + EPSILON) {
            state = State.PROTECT;
            if (floorDeclineSinceMs == UNSET) floorDeclineSinceMs = input.nowMs();
            if (input.bufferedMs() <= UNSUSTAINABLE_BUFFER_MS && input.nowMs() - floorDeclineSinceMs >= UNSUSTAINABLE_CONFIRM_MS) {
                state = State.UNSUSTAINABLE;
                return hold(currentSpeed, input, "floor-declining");
            }
            return hold(currentSpeed, input, "at-floor");
        }

        floorDeclineSinceMs = UNSET;
        state = State.WARNING;
        if (warningSinceMs == UNSET) warningSinceMs = input.nowMs();
        boolean severe = input.slopeMsPerSecond() <= SEVERE_DECLINE_MS_PER_SECOND && input.bufferedMs() <= 20_000;
        boolean confirmed = severe || input.trendWindowMs() >= 15_000 || input.nowMs() - warningSinceMs >= WARNING_CONFIRM_MS;
        if (!confirmed || !canAdjust(input.nowMs())) return hold(currentSpeed, input, "decline-confirming");

        float target = Math.max(minimumSpeed, currentSpeed - SPEED_STEP);
        warningSinceMs = input.nowMs();
        return adjust(input.nowMs(), currentSpeed, target, input, severe ? "severe-decline" : "sustained-decline");
    }

    private Decision evaluateRecovery(Input input, float currentSpeed) {
        warningSinceMs = UNSET;
        floorDeclineSinceMs = UNSET;
        state = State.RECOVERY;
        if (recoverySinceMs == UNSET) recoverySinceMs = input.nowMs();
        if (input.nowMs() - recoverySinceMs < RECOVERY_CONFIRM_MS || !canAdjust(input.nowMs())) return hold(currentSpeed, input, "recovery-confirming");
        recoverySinceMs = input.nowMs();
        return adjust(input.nowMs(), currentSpeed, Math.min(1f, currentSpeed + SPEED_STEP), input, "buffer-recovering");
    }

    private Decision evaluateFullBufferRecovery(Input input, float currentSpeed) {
        warningSinceMs = UNSET;
        floorDeclineSinceMs = UNSET;
        if (currentSpeed >= 1f - EPSILON || input.bufferedMs() < FULL_BUFFER_RECOVERY_MS) {
            recoverySinceMs = UNSET;
            state = currentSpeed < 1f - EPSILON ? State.PROTECT : State.NORMAL;
            return hold(currentSpeed, input, "not-loading");
        }
        state = State.RECOVERY;
        if (recoverySinceMs == UNSET) recoverySinceMs = input.nowMs();
        if (input.nowMs() - recoverySinceMs < FULL_BUFFER_RECOVERY_CONFIRM_MS || !canAdjust(input.nowMs())) return hold(currentSpeed, input, "full-buffer-confirming");
        recoverySinceMs = input.nowMs();
        return adjust(input.nowMs(), currentSpeed, Math.min(1f, currentSpeed + SPEED_STEP), input, "full-buffer");
    }

    private Decision adjust(long nowMs, float currentSpeed, float targetSpeed, Input input, String reason) {
        float target = roundHundredth(targetSpeed);
        lastAdjustmentMs = nowMs;
        state = target >= 1f - EPSILON ? State.NORMAL : reason.contains("recover") || reason.equals("full-buffer") ? State.RECOVERY : State.PROTECT;
        return new Decision(state, target, Math.abs(target - currentSpeed) >= EPSILON, reason, supportedSpeed(currentSpeed, input));
    }

    private Decision hold(float currentSpeed, Input input, String reason) {
        return new Decision(state, currentSpeed, false, reason, supportedSpeed(currentSpeed, input));
    }

    private boolean canAdjust(long nowMs) {
        return lastAdjustmentMs == UNSET || nowMs - lastAdjustmentMs >= ADJUSTMENT_COOLDOWN_MS;
    }

    private void clearEvidence() {
        warningSinceMs = UNSET;
        recoverySinceMs = UNSET;
        floorDeclineSinceMs = UNSET;
    }

    private static float supportedSpeed(float currentSpeed, Input input) {
        if (!input.trendKnown()) return currentSpeed;
        return clampSpeed(currentSpeed + input.slopeMsPerSecond() / 1_000f, 0f, 2f);
    }

    private static float roundHundredth(float speed) {
        return Math.round(speed * 100f) / 100f;
    }

    private static float clampSpeed(float speed, float minimum, float maximum) {
        return Math.min(Math.max(speed, minimum), maximum);
    }

    public enum State {
        NORMAL("正常"),
        WARNING("评估"),
        PROTECT("保护"),
        RECOVERY("恢复"),
        UNSUSTAINABLE("带宽不足");

        private final String text;

        State(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    public record Input(long nowMs, boolean eligible, boolean ready, boolean playing, boolean loading, long bufferedMs, boolean trendKnown, long slopeMsPerSecond, long trendWindowMs, int rebufferCount, float currentSpeed, float minimumSpeed) {
    }

    public record Decision(State state, float targetSpeed, boolean changed, String reason, float supportedSpeed) {
    }
}
