package com.fongmi.android.tv.player.exo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoNetworkGuardControllerTest {

    @Test
    public void sustainedDeclineStepsDownByTwoPercent() {
        ExoNetworkGuardController controller = new ExoNetworkGuardController();

        ExoNetworkGuardController.Decision decision = evaluate(controller, 15_000, 30_000, true, -100, 15_000, 0, 1.00f, 0.90f);

        assertTrue(decision.changed());
        assertEquals(0.98f, decision.targetSpeed(), 0.0001f);
        assertEquals(ExoNetworkGuardController.State.PROTECT, decision.state());
    }

    @Test
    public void adjustmentCooldownPreventsRapidOscillation() {
        ExoNetworkGuardController controller = new ExoNetworkGuardController();
        assertTrue(evaluate(controller, 15_000, 30_000, true, -100, 15_000, 0, 1.00f, 0.90f).changed());

        ExoNetworkGuardController.Decision held = evaluate(controller, 20_000, 28_000, true, -100, 20_000, 0, 0.98f, 0.90f);
        ExoNetworkGuardController.Decision next = evaluate(controller, 30_000, 26_000, true, -100, 30_000, 0, 0.98f, 0.90f);

        assertFalse(held.changed());
        assertTrue(next.changed());
        assertEquals(0.96f, next.targetSpeed(), 0.0001f);
    }

    @Test
    public void protectionNeverDropsBelowConfiguredFloor() {
        ExoNetworkGuardController controller = new ExoNetworkGuardController();

        ExoNetworkGuardController.Decision decision = evaluate(controller, 15_000, 10_000, true, -300, 15_000, 0, 0.91f, 0.90f);

        assertTrue(decision.changed());
        assertEquals(0.90f, decision.targetSpeed(), 0.0001f);
    }

    @Test
    public void healthyGrowthRecoversGradually() {
        ExoNetworkGuardController controller = new ExoNetworkGuardController();
        controller.disrupt(0.94f);
        evaluate(controller, 0, 30_000, true, 100, 15_000, 0, 0.94f, 0.90f);

        ExoNetworkGuardController.Decision decision = evaluate(controller, 20_000, 34_000, true, 100, 30_000, 0, 0.94f, 0.90f);

        assertTrue(decision.changed());
        assertEquals(0.96f, decision.targetSpeed(), 0.0001f);
        assertEquals(ExoNetworkGuardController.State.RECOVERY, decision.state());
    }

    @Test
    public void fullBufferCanRecoverWithoutUsingAFalseDeclineSample() {
        ExoNetworkGuardController controller = new ExoNetworkGuardController();
        controller.disrupt(0.95f);
        evaluate(controller, 0, 55_000, false, 0, 0, 0, 0.95f, 0.90f);

        ExoNetworkGuardController.Decision decision = evaluate(controller, 30_000, 55_000, false, 0, 0, 0, 0.95f, 0.90f);

        assertTrue(decision.changed());
        assertEquals(0.97f, decision.targetSpeed(), 0.0001f);
    }

    @Test
    public void stoppedLoaderAtLowBufferDoesNotTreatPlaybackDrainAsNetworkDecline() {
        ExoNetworkGuardController controller = new ExoNetworkGuardController();

        ExoNetworkGuardController.Decision decision = evaluate(controller, 30_000, 20_000, false, -1_000, 30_000, 0, 1.00f, 0.90f);

        assertFalse(decision.changed());
        assertEquals(1.00f, decision.targetSpeed(), 0.0001f);
        assertEquals(ExoNetworkGuardController.State.NORMAL, decision.state());
    }

    @Test
    public void floorDeclineBecomesUnsustainableInsteadOfDroppingForever() {
        ExoNetworkGuardController controller = new ExoNetworkGuardController();
        evaluate(controller, 0, 11_000, true, -100, 15_000, 0, 0.90f, 0.90f);

        ExoNetworkGuardController.Decision decision = evaluate(controller, 15_000, 8_000, true, -100, 30_000, 0, 0.90f, 0.90f);

        assertFalse(decision.changed());
        assertEquals(0.90f, decision.targetSpeed(), 0.0001f);
        assertEquals(ExoNetworkGuardController.State.UNSUSTAINABLE, decision.state());
    }

    @Test
    public void rebufferAtFloorIsImmediatelyReportedUnsustainable() {
        ExoNetworkGuardController controller = new ExoNetworkGuardController();

        ExoNetworkGuardController.Decision decision = evaluate(controller, 10_000, 5_000, false, 0, 0, 1, 0.95f, 0.95f);

        assertEquals(ExoNetworkGuardController.State.UNSUSTAINABLE, decision.state());
        assertFalse(decision.changed());
    }

    @Test
    public void ineligibleSessionRestoresNormalSpeed() {
        ExoNetworkGuardController controller = new ExoNetworkGuardController();
        ExoNetworkGuardController.Input input = new ExoNetworkGuardController.Input(0, false, true, true, true, 10_000, true, -100, 15_000, 0, 0.90f, 0.90f);

        ExoNetworkGuardController.Decision decision = controller.evaluate(input);

        assertTrue(decision.changed());
        assertEquals(1.00f, decision.targetSpeed(), 0.0001f);
        assertEquals(ExoNetworkGuardController.State.NORMAL, decision.state());
    }

    private static ExoNetworkGuardController.Decision evaluate(ExoNetworkGuardController controller, long nowMs, long bufferedMs, boolean loading, long slope, long windowMs, int rebufferCount, float currentSpeed, float minimumSpeed) {
        return controller.evaluate(new ExoNetworkGuardController.Input(nowMs, true, true, true, loading, bufferedMs, windowMs > 0, slope, windowMs, rebufferCount, currentSpeed, minimumSpeed));
    }
}
