package com.fongmi.android.tv.player.mpv;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MpvAutoOutputPolicyTest {

    @Test
    public void acceptsUltraWideFourKClassVideoOnTvHardwareDecode() {
        assertTrue(MpvAutoOutputPolicy.evaluate(3840, 1632, true, true, false, false, false).eligible());
    }

    @Test
    public void rejectsOrdinaryFullHdVideo() {
        assertFalse(MpvAutoOutputPolicy.evaluate(1920, 1080, true, true, false, false, false).eligible());
    }

    @Test
    public void rejectsFeaturesThatNeedGpuComposition() {
        assertFalse(MpvAutoOutputPolicy.evaluate(3840, 2160, true, true, true, false, false).eligible());
        assertFalse(MpvAutoOutputPolicy.evaluate(3840, 2160, true, true, false, true, false).eligible());
        assertFalse(MpvAutoOutputPolicy.evaluate(3840, 2160, true, true, false, false, true).eligible());
    }
}
