package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WakeMotionTest {
    @Test
    public void springStartsAtZeroAndEndsAtRest() {
        assertEquals(0f, WakeMotion.spring(0f), 0f);
        assertEquals(0f, WakeMotion.spring(-1f), 0f);
        assertEquals(1f, WakeMotion.spring(WakeMotion.END_S), 0.002f);
    }

    @Test
    public void springOvershootsALittle() {
        float peak = 0;
        for (float t = 0; t < 1f; t += 0.002f) peak = Math.max(peak, WakeMotion.spring(t));
        assertTrue("peak " + peak, peak > 1.02f && peak < 1.06f);
    }

    @Test
    public void springSettlesInAboutHalfASecond() {
        for (float t = 0.5f; t < 1f; t += 0.01f) assertEquals(1f, WakeMotion.spring(t), 0.012f);
        assertTrue(Math.abs(1f - WakeMotion.spring(0.2f)) > 0.05f);
    }

    @Test
    public void fadeIsSoftAndStaysInRange() {
        assertEquals(0f, WakeMotion.ease(-1f), 0f);
        assertEquals(1f, WakeMotion.ease(2f), 0f);
        assertEquals(0.5f, WakeMotion.ease(0.5f), 0f);
        assertTrue(WakeMotion.ease(0.1f) < 0.1f);
    }
}
