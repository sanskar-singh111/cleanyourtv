package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** dock_logos: the trim of a logo's empty margins and its size by visual weight. */
public class DockLogoTest {
    private static final float TILE_W = 308, TILE_H = 173;

    @Test
    public void inkBoundsCutTheTransparentMargin() {
        int w = 5, h = 4;
        int[] px = new int[w * h];
        px[1 * w + 2] = 0xFFFFFFFF;
        px[2 * w + 3] = 0x80FFFFFF;
        px[3 * w + 0] = 0x05FFFFFF;   // almost transparent: not ink
        assertArrayEquals(new int[]{2, 1, 4, 3}, DockLogo.inkBounds(px, w, h));
        assertNull(DockLogo.inkBounds(new int[w * h], w, h));
    }

    @Test
    public void theAspectRatioIsAlwaysTheLogosOwn() {
        for (float aspect : new float[]{0.3f, 0.57f, 1f, 1.42f, 3.3f, 8f}) {
            float[] box = DockLogo.box(0.5f, aspect, TILE_W, TILE_H, 100);
            assertEquals(aspect, box[0] / box[1], 0.001f);
        }
    }

    @Test
    public void aLogoNeverOutgrowsItsTile() {
        for (int size : new int[]{50, 100, 200}) {
            for (float aspect : new float[]{0.2f, 1f, 6f}) {
                float[] box = DockLogo.box(0.05f, aspect, TILE_W, TILE_H, size);
                assertTrue(box[0] <= TILE_W * DockLogo.MAX_W + 0.01f);
                assertTrue(box[1] <= TILE_H * DockLogo.MAX_H + 0.01f);
            }
        }
    }

    @Test
    public void aThinLogoIsDrawnBiggerThanASolidOne() {
        float[] solid = DockLogo.box(0.9f, 1f, TILE_W, TILE_H, 100);
        float[] thin = DockLogo.box(0.3f, 1f, TILE_W, TILE_H, 100);
        assertTrue(thin[0] > solid[0]);
        // ...but not so much bigger that it carries the same ink: thin shapes look heavier than their area.
        assertTrue(0.3f * thin[0] * thin[1] < 0.9f * solid[0] * solid[1]);
    }

    @Test
    public void sizePercentScalesTheLogo() {
        float[] base = DockLogo.box(0.6f, 1.2f, TILE_W, TILE_H, 100);
        float[] small = DockLogo.box(0.6f, 1.2f, TILE_W, TILE_H, 50);
        assertEquals(base[0] / 2, small[0], 0.5f);
    }
}
