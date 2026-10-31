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
        assertTrue(DockGlass.tintAlpha(100) > DockGlass.tintAlpha(1));
        assertTrue(DockGlass.edgeAlpha(100) > DockGlass.edgeAlpha(1));
        assertTrue(DockGlass.highlightAlpha(100) > DockGlass.highlightAlpha(1));
        assertTrue(DockGlass.edgeAlpha(100) <= 255);
    }
}
