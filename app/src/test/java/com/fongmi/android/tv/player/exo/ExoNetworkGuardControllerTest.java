package com.fongmi.android.tv.player.exo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoNetworkGuardControllerTest {

    @Test
    public void sustainedSmallDeficitEntersWithContinuousBoundedTarget() {
        ExoNetworkGuardController controller = new ExoNetworkGuardController();
        assertFalse(evaluate(controller, 0, 30_000, true, -18, 10_000, 0, 1.00f).changed());

        ExoNetworkGuardController.Decision decision = evaluate(controller, 10_000, 29_000, true, -18, 20_000, 0, 1.00f);

        assertTrue(decision.changed());
        assertEquals(0.992f, decision.targetSpeed(), 0.0001f);
        assertEquals(ExoNetworkGuardController.State.PROTECT, decision.state());
    }

    @Test
    public void adjustmentCooldownPreventsRapidRateChurn() {
        ExoNetworkGuardController controller = new ExoNetworkGuardController();
        evaluate(controller, 0, 30_000, true, -18, 10_000, 0, 1.00f);
        assertTrue(evaluate(controller, 10_000, 29_000, true, -18, 20_000, 0, 1.00f).changed());

        ExoNetworkGuardController.Decision held = evaluate(controller, 15_000, 28_000, true, -18, 25_000, 0, 0.992f);
        ExoNetworkGuardController.Decision next = evaluate(controller, 20_000, 27_000, true, -18, 30_000, 0, 0.992f);

        assertFalse(held.changed());
        assertTrue(next.changed());
        assertEquals(0.984f, next.targetSpeed(), 0.0001f);
    }

    @Test
    public void deficitBeyondImperceptibleRangeDoesNotSecretlySlowPlayback() {
        ExoNetworkGuardController controller = new ExoNetworkGuardController();
        evaluate(controller, 0, 20_000, true, -80, 10_000, 0, 1.00f);

        ExoNetworkGuardController.Decision decision = evaluate(controller, 10_000, 19_000, true, -80, 20_000, 0, 1.00f);

        assertFalse(decision.changed());
        assertEquals(1.00f, decision.targetSpeed(), 0.0001f);
        assertEquals(ExoNetworkGuardController.State.UNSUSTAINABLE, decision.state());
    }

    @Test
    public void recoveredCapacityReturnsGraduallyToUnitSpeed() {
        ExoNetworkGuardController controller = new ExoNetworkGuardController();
        controller.disrupt(0.98f);
        assertFalse(evaluate(controller, 0, 35_000, true, 20, 15_000, 0, 0.98f).changed());

        ExoNetworkGuardController.Decision decision = evaluate(controller, 25_000, 36_000, true, 20, 30_000, 0, 0.98f);

        assertTrue(decision.changed());
        assertEquals(0.984f, decision.targetSpeed(), 0.0001f);
        assertEquals(ExoNetworkGuardController.State.RECOVERY, decision.state());
    }

    @Test
    public void fullBufferRecoversWithoutTreatingLoaderIdleAsNetworkFailure() {
        ExoNetworkGuardController controller = new ExoNetworkGuardController();
        controller.disrupt(0.98f);
        assertFalse(evaluate(controller, 0, 55_000, false, 0, 0, 0, 0.98f).changed());

        ExoNetworkGuardController.Decision decision = evaluate(controller, 30_000, 55_000, false, 0, 0, 0, 0.98f);

        assertTrue(decision.changed());
        assertEquals(0.984f, decision.targetSpeed(), 0.0001f);
    }

    @Test
    public void directNetworkEstimateCanOnlyMakeDecisionMoreConservative() {
        ExoNetworkGuardController controller = new ExoNetworkGuardController();
        evaluate(controller, 0, 30_000, true, -15, 10_000, 0, 1.00f, true, 0.975f);

        ExoNetworkGuardController.Decision decision = evaluate(controller, 10_000, 29_000, true, -15, 20_000, 0, 1.00f, true, 0.975f);

        assertTrue(decision.changed());
        assertEquals(0.992f, decision.targetSpeed(), 0.0001f);
        assertEquals(0.975f, decision.supportedSpeed(), 0.0001f);
    }

    @Test
    public void timeToEmptyParticipatesInRiskDetectionAtLargeBuffers() {
        ExoNetworkGuardController controller = new ExoNetworkGuardController();
        evaluate(controller, 0, 60_000, true, -300, 10_000, 0, 1.00f);

        ExoNetworkGuardController.Decision decision = evaluate(controller, 10_000, 57_000, true, -300, 20_000, 0, 1.00f);

        assertEquals(190_000, decision.timeToEmptyMs());
        assertEquals(ExoNetworkGuardController.State.UNSUSTAINABLE, decision.state());
    }

    @Test
    public void rebufferWithoutFreshTrendDoesNotBlindlyChangeSpeed() {
        ExoNetworkGuardController controller = new ExoNetworkGuardController();

        ExoNetworkGuardController.Decision decision = evaluate(controller, 10_000, 5_000, true, 0, 0, 1, 1.00f);

        assertFalse(decision.changed());
        assertEquals(1.00f, decision.targetSpeed(), 0.0001f);
        assertEquals(ExoNetworkGuardController.State.WARNING, decision.state());
    }

    @Test
    public void ineligibleSessionRestoresNormalSpeed() {
        ExoNetworkGuardController controller = new ExoNetworkGuardController();
        ExoNetworkGuardController.Input input = new ExoNetworkGuardController.Input(0, false, true, true, true, 10_000, true, -20, 15_000, 0, 0.98f, 0.97f);

        ExoNetworkGuardController.Decision decision = controller.evaluate(input);

        assertTrue(decision.changed());
        assertEquals(1.00f, decision.targetSpeed(), 0.0001f);
        assertEquals(ExoNetworkGuardController.State.NORMAL, decision.state());
    }

    private static ExoNetworkGuardController.Decision evaluate(ExoNetworkGuardController controller, long nowMs, long bufferedMs, boolean loading, long slope, long windowMs, int rebufferCount, float currentSpeed) {
        return evaluate(controller, nowMs, bufferedMs, loading, slope, windowMs, rebufferCount, currentSpeed, false, 1f);
    }

    private static ExoNetworkGuardController.Decision evaluate(ExoNetworkGuardController controller, long nowMs, long bufferedMs, boolean loading, long slope, long windowMs, int rebufferCount, float currentSpeed, boolean networkKnown, float networkSupported) {
        return controller.evaluate(new ExoNetworkGuardController.Input(nowMs, true, true, true, loading, bufferedMs, windowMs > 0, slope, windowMs, rebufferCount, currentSpeed, 0.97f, networkKnown, networkSupported));
    }
}
