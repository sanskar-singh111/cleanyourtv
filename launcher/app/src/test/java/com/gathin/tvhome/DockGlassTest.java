package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DockGlassTest {
    @Test public void aLeftRowStartsAtThePanelsEdge() {
        assertEquals(28, DockGlass.rowStart(1696, 1200, 28, false));
        assertEquals(0, DockGlass.rowStart(1696, 1200, 0, false));
    }

    @Test public void aCentredRowHasTheSameRoomEachSide() {
        assertEquals(248, DockGlass.rowStart(1696, 1200, 28, true));
        assertEquals(248, DockGlass.rowStart(1696, 1200, 0, true));
    }

    @Test public void aRowWiderThanTheScreenStillLeavesThePanelItsEdge() {
        assertEquals(28, DockGlass.rowStart(1000, 1200, 28, true));
    }

    @Test public void strongerGlassIsStrongerInEveryPart() {
        assertTrue(DockGlass.edgeAlpha(100, 0) > DockGlass.edgeAlpha(1, 0));
        assertTrue(DockGlass.highlightAlpha(100) > DockGlass.highlightAlpha(1));
        assertTrue(DockGlass.shadowAlpha(100) > DockGlass.shadowAlpha(1));
        assertTrue(DockGlass.edgeAlpha(100, 0) <= 255);
    }

    @Test public void theTintIsWhatTheTransparencyLeaves() {
        assertEquals(0, DockGlass.tintAlpha(100));
        assertEquals(31, DockGlass.tintAlpha(88));
        assertEquals(255, DockGlass.tintAlpha(0));
    }

    @Test public void clearerGlassLetsTheWallpaperThrough() {
        assertEquals(255, DockGlass.blurAlpha(50));
        assertEquals(166, DockGlass.blurAlpha(100));
        assertTrue(DockGlass.blurAlpha(88) < DockGlass.blurAlpha(60));
    }

    @Test
    public void noShineAddsNothing() {
        assertEquals(Math.round(255 * (0.18f + 0.30f * 60 / 100f)), DockGlass.edgeAlpha(60, 0));
        assertEquals(0, DockGlass.glossAlpha(0));
        assertEquals(0, DockIcon.glossAlpha(0));
        assertEquals(0, GlassDigits.brightAlpha(0));
        assertEquals(0, GlassDigits.darkAlpha(0));
        assertEquals(0, GlassDigits.glowAlpha(0));
    }

    @Test
    public void shineBrightensEverything() {
        assertTrue(DockGlass.edgeAlpha(60, 100) > DockGlass.edgeAlpha(60, 0));
        assertTrue(DockGlass.glossAlpha(100) > DockGlass.glossAlpha(1));
        assertTrue(DockIcon.glossAlpha(100) > DockIcon.glossAlpha(1));
        assertTrue(GlassDigits.brightAlpha(100) > GlassDigits.darkAlpha(100));
        assertTrue(Math.min(255, DockGlass.edgeAlpha(100, 100)) <= 255);
    }

    @Test
    public void clockOpacityGoesFromClearToNearlySolid() {
        assertEquals(Math.round(255 * 0.10f), GlassDigits.veilAlpha(30));
        assertEquals(Math.round(255 * 0.85f), GlassDigits.veilAlpha(100));
        assertTrue(GlassDigits.veilAlpha(55) > GlassDigits.veilAlpha(33));
    }

    @Test
    public void theSweepShowsEvenWithNoShine() {
        assertTrue(DockGlass.sweepAlpha(0) > 0);
        assertTrue(DockGlass.sweepAlpha(100) > DockGlass.sweepAlpha(0));
    }
}
