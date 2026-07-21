package com.fongmi.android.tv.player.exo;

/** Continuous, hysteretic controller for imperceptible EXO network protection. */
public final class ExoNetworkGuardController {

    public static final long SAMPLE_INTERVAL_MS = 5_000;
    static final long MIN_TREND_WINDOW_MS = 10_000;
    static final long ENTRY_CONFIRM_MS = 10_000;
    static final long RECOVERY_CONFIRM_MS = 25_000;
    static final long FULL_BUFFER_RECOVERY_CONFIRM_MS = 30_000;
    static final long ADJUSTMENT_COOLDOWN_MS = 10_000;
    static final long RISK_BUFFER_CEILING_MS = 45_000;
    static final long RECOVERY_BUFFER_FLOOR_MS = 30_000;
    static final long FULL_BUFFER_RECOVERY_MS = 50_000;
    static final long RISK_TIME_TO_EMPTY_MS = 240_000;
    static final long DECLINE_DEADBAND_MS_PER_SECOND = -15;
    static final float SUPPORTED_RECOVERY_SPEED = 0.998f;
    static final float SAFETY_MARGIN = 0.002f;
    static final float MAX_DOWN_STEP = 0.008f;
    static final float MAX_UP_STEP = 0.004f;
    static final float MIN_CHANGE = 0.002f;

    private static final long UNSET = Long.MIN_VALUE;
    private static final float EPSILON = 0.0005f;

    private State state = State.NORMAL;
    private long warningSinceMs = UNSET;
    private long recoverySinceMs = UNSET;
    private long lastAdjustmentMs = UNSET;
    private int lastRebufferCount;

    public void reset() {
        state = State.NORMAL;
        warningSinceMs = UNSET;
        recoverySinceMs = UNSET;
        lastAdjustmentMs = UNSET;
        lastRebufferCount = 0;
    }

    public void disrupt(float currentSpeed) {
        warningSinceMs = UNSET;
        recoverySinceMs = UNSET;
        state = currentSpeed < 1f - EPSILON ? State.PROTECT : State.NORMAL;
    }

    public State getState() {
        return state;
    }

    public Decision evaluate(Input input) {
        float currentSpeed = clamp(input.currentSpeed(), 0.25f, 1f);
        float minimumSpeed = clamp(input.minimumSpeed(), ExoNetworkProtectionPolicy.IMPERCEPTIBLE_MIN_SPEED, 1f);
        if (!input.eligible()) {
            boolean changed = currentSpeed < 1f - EPSILON;
            reset();
            return new Decision(state, 1f, changed, "ineligible", currentSpeed, Long.MAX_VALUE);
        }

        boolean rebuffered = input.rebufferCount() > lastRebufferCount;
        lastRebufferCount = Math.max(lastRebufferCount, input.rebufferCount());
        if (!input.ready() || !input.playing()) return hold(currentSpeed, input, "inactive");
        if (!input.loading()) return evaluateFullBufferRecovery(input, currentSpeed);
        if (!input.trendKnown() || input.trendWindowMs() < MIN_TREND_WINDOW_MS) {
            if (rebuffered) state = State.WARNING;
            else state = currentSpeed < 1f - EPSILON ? State.PROTECT : State.NORMAL;
            return hold(currentSpeed, input, "trend-warmup");
        }

        float supportedSpeed = supportedSpeed(currentSpeed, input);
        long timeToEmptyMs = timeToEmptyMs(input.bufferedMs(), input.slopeMsPerSecond());
        boolean decliningRisk = input.slopeMsPerSecond() <= DECLINE_DEADBAND_MS_PER_SECOND
                && (input.bufferedMs() <= RISK_BUFFER_CEILING_MS || timeToEmptyMs <= RISK_TIME_TO_EMPTY_MS);

        if (decliningRisk) {
            recoverySinceMs = UNSET;
            if (supportedSpeed < minimumSpeed - SAFETY_MARGIN) {
                warningSinceMs = UNSET;
                state = State.UNSUSTAINABLE;
                if (currentSpeed < 1f - EPSILON && canAdjust(input.nowMs())) return adjustToward(input, currentSpeed, 1f, MAX_UP_STEP, "outside-imperceptible-range");
                return decision(state, currentSpeed, false, "outside-imperceptible-range", supportedSpeed, timeToEmptyMs);
            }

            state = State.WARNING;
            if (warningSinceMs == UNSET) warningSinceMs = rebuffered ? input.nowMs() - ENTRY_CONFIRM_MS / 2 : input.nowMs();
            if (input.nowMs() - warningSinceMs < ENTRY_CONFIRM_MS || !canAdjust(input.nowMs())) {
                return decision(state, currentSpeed, false, "risk-confirming", supportedSpeed, timeToEmptyMs);
            }

            float desiredSpeed = clamp(supportedSpeed - SAFETY_MARGIN, minimumSpeed, 1f);
            if (desiredSpeed >= currentSpeed - MIN_CHANGE) {
                state = currentSpeed < 1f - EPSILON ? State.PROTECT : State.WARNING;
                return decision(state, currentSpeed, false, "within-deadband", supportedSpeed, timeToEmptyMs);
            }
            warningSinceMs = input.nowMs();
            return adjustToward(input, currentSpeed, desiredSpeed, MAX_DOWN_STEP, "sustained-risk");
        }

        warningSinceMs = UNSET;
        if (currentSpeed < 1f - EPSILON) {
            state = State.PROTECT;
            boolean recoveryReady = supportedSpeed >= SUPPORTED_RECOVERY_SPEED && input.bufferedMs() >= RECOVERY_BUFFER_FLOOR_MS;
            if (!recoveryReady) {
                recoverySinceMs = UNSET;
                return decision(state, currentSpeed, false, "protected-stable", supportedSpeed, timeToEmptyMs);
            }
            state = State.RECOVERY;
            if (recoverySinceMs == UNSET) recoverySinceMs = input.nowMs();
            if (input.nowMs() - recoverySinceMs < RECOVERY_CONFIRM_MS || !canAdjust(input.nowMs())) {
                return decision(state, currentSpeed, false, "recovery-confirming", supportedSpeed, timeToEmptyMs);
            }
            recoverySinceMs = input.nowMs();
            return adjustToward(input, currentSpeed, 1f, MAX_UP_STEP, "capacity-recovered");
        }

        recoverySinceMs = UNSET;
        state = State.NORMAL;
        return decision(state, currentSpeed, false, "healthy", supportedSpeed, timeToEmptyMs);
    }

    private Decision evaluateFullBufferRecovery(Input input, float currentSpeed) {
        warningSinceMs = UNSET;
        if (currentSpeed >= 1f - EPSILON || input.bufferedMs() < FULL_BUFFER_RECOVERY_MS) {
            recoverySinceMs = UNSET;
            state = currentSpeed < 1f - EPSILON ? State.PROTECT : State.NORMAL;
            return hold(currentSpeed, input, "loader-idle");
        }
        state = State.RECOVERY;
        if (recoverySinceMs == UNSET) recoverySinceMs = input.nowMs();
        if (input.nowMs() - recoverySinceMs < FULL_BUFFER_RECOVERY_CONFIRM_MS || !canAdjust(input.nowMs())) return hold(currentSpeed, input, "full-buffer-confirming");
        recoverySinceMs = input.nowMs();
        return adjustToward(input, currentSpeed, 1f, MAX_UP_STEP, "full-buffer");
    }

    private Decision adjustToward(Input input, float currentSpeed, float desiredSpeed, float maximumStep, String reason) {
        float delta = clamp(desiredSpeed - currentSpeed, -maximumStep, maximumStep);
        float target = roundThousandth(currentSpeed + delta);
        if (Math.abs(target - currentSpeed) < MIN_CHANGE) return hold(currentSpeed, input, reason + "-deadband");
        lastAdjustmentMs = input.nowMs();
        if (target >= 1f - EPSILON) state = State.NORMAL;
        else if (target > currentSpeed) state = State.RECOVERY;
        else state = State.PROTECT;
        return decision(state, target, true, reason, supportedSpeed(currentSpeed, input), timeToEmptyMs(input.bufferedMs(), input.slopeMsPerSecond()));
    }

    private Decision hold(float currentSpeed, Input input, String reason) {
        return decision(state, currentSpeed, false, reason, supportedSpeed(currentSpeed, input), timeToEmptyMs(input.bufferedMs(), input.slopeMsPerSecond()));
    }

    private static Decision decision(State state, float targetSpeed, boolean changed, String reason, float supportedSpeed, long timeToEmptyMs) {
        return new Decision(state, targetSpeed, changed, reason, supportedSpeed, timeToEmptyMs);
    }

    private boolean canAdjust(long nowMs) {
        return lastAdjustmentMs == UNSET || nowMs - lastAdjustmentMs >= ADJUSTMENT_COOLDOWN_MS;
    }

    private static float supportedSpeed(float currentSpeed, Input input) {
        if (!input.trendKnown()) return currentSpeed;
        float bufferSupported = currentSpeed + input.slopeMsPerSecond() / 1_000f;
        float supported = input.networkEstimateKnown() ? Math.min(bufferSupported, input.networkSupportedSpeed()) : bufferSupported;
        return clamp(supported, 0f, 2f);
    }

    private static long timeToEmptyMs(long bufferedMs, long slopeMsPerSecond) {
        if (bufferedMs <= 0) return 0;
        if (slopeMsPerSecond >= 0) return Long.MAX_VALUE;
        if (bufferedMs > Long.MAX_VALUE / 1_000L) return Long.MAX_VALUE;
        return bufferedMs * 1_000L / -slopeMsPerSecond;
    }

    private static float roundThousandth(float value) {
        return Math.round(value * 1_000f) / 1_000f;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.min(Math.max(value, minimum), maximum);
    }

    public enum State {
        NORMAL("观察"),
        WARNING("评估"),
        PROTECT("保护"),
        RECOVERY("恢复"),
        UNSUSTAINABLE("超出无感范围");

        private final String text;

        State(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    public record Input(long nowMs, boolean eligible, boolean ready, boolean playing, boolean loading, long bufferedMs, boolean trendKnown, long slopeMsPerSecond, long trendWindowMs, int rebufferCount, float currentSpeed, float minimumSpeed, boolean networkEstimateKnown, float networkSupportedSpeed) {

        public Input(long nowMs, boolean eligible, boolean ready, boolean playing, boolean loading, long bufferedMs, boolean trendKnown, long slopeMsPerSecond, long trendWindowMs, int rebufferCount, float currentSpeed, float minimumSpeed) {
            this(nowMs, eligible, ready, playing, loading, bufferedMs, trendKnown, slopeMsPerSecond, trendWindowMs, rebufferCount, currentSpeed, minimumSpeed, false, 1f);
        }
    }

    public record Decision(State state, float targetSpeed, boolean changed, String reason, float supportedSpeed, long timeToEmptyMs) {
    }
}
