package com.fongmi.android.tv.player.exo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoNetworkProtectionPolicyTest {

    @Test
    public void disabledModePreservesHardwareOutputPaths() {
        ExoNetworkProtectionPolicy.Decision decision = ExoNetworkProtectionPolicy.resolve(ExoNetworkProtectionPolicy.MODE_OFF);

        assertFalse(decision.enabled());
        assertFalse(decision.disableTunneling());
        assertFalse(decision.forcePcm());
        assertFalse(decision.suppressOutputMode());
        assertEquals(1.0f, decision.minimumSpeed(), 0.0001f);
    }

    @Test
    public void mapsProtectionModesToBoundedSpeeds() {
        assertEquals(0.95f, ExoNetworkProtectionPolicy.resolve(ExoNetworkProtectionPolicy.MODE_STANDARD).minimumSpeed(), 0.0001f);
        assertEquals(0.90f, ExoNetworkProtectionPolicy.resolve(ExoNetworkProtectionPolicy.MODE_ENHANCED).minimumSpeed(), 0.0001f);
        assertEquals(0.85f, ExoNetworkProtectionPolicy.resolve(ExoNetworkProtectionPolicy.MODE_AGGRESSIVE).minimumSpeed(), 0.0001f);
    }

    @Test
    public void enabledModesRequirePcmAndNonTunneledStableOutput() {
        ExoNetworkProtectionPolicy.Decision decision = ExoNetworkProtectionPolicy.resolve(ExoNetworkProtectionPolicy.MODE_STANDARD);

        assertTrue(decision.enabled());
        assertTrue(decision.disableTunneling());
        assertTrue(decision.forcePcm());
        assertTrue(decision.suppressOutputMode());
    }

    @Test
    public void clampsUnknownModes() {
        assertEquals(ExoNetworkProtectionPolicy.MODE_OFF, ExoNetworkProtectionPolicy.resolve(-1).mode());
        assertEquals(ExoNetworkProtectionPolicy.MODE_AGGRESSIVE, ExoNetworkProtectionPolicy.resolve(99).mode());
    }
}
