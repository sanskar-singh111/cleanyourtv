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
        assertTrue(DockGlass.edgeAlpha(100) > DockGlass.edgeAlpha(1));
        assertTrue(DockGlass.highlightAlpha(100) > DockGlass.highlightAlpha(1));
        assertTrue(DockGlass.shadowAlpha(100) > DockGlass.shadowAlpha(1));
        assertTrue(DockGlass.edgeAlpha(100) <= 255);
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
}
