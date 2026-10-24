package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** screensaver, glow_strength and the slideshow's order. */
public class ScreensaverTest {
    @Test
    public void unknownModesAreTheWallpaperAlone() {
        assertEquals(Screensaver.CLOCK, Screensaver.mode(" Clock "));
        assertEquals(Screensaver.CLOCK_WEATHER, Screensaver.mode("clock_weather"));
        assertEquals(Screensaver.SLIDESHOW, Screensaver.mode("slideshow"));
        assertEquals(Screensaver.WALLPAPER, Screensaver.mode("video"));
        assertEquals(Screensaver.WALLPAPER, Screensaver.mode(""));
        assertEquals(Screensaver.WALLPAPER, Screensaver.mode(null));
    }

    @Test
    public void theDefaultDrawsNothingNew() {
        assertFalse(Screensaver.draws(Screensaver.WALLPAPER, 0, 0));
        assertTrue(Screensaver.draws(Screensaver.WALLPAPER, 30, 0));
        assertTrue(Screensaver.draws(Screensaver.CLOCK, 0, 0));
        assertFalse("a slideshow with no pictures is the wallpaper", Screensaver.draws(Screensaver.SLIDESHOW, 0, 0));
        assertTrue(Screensaver.draws(Screensaver.SLIDESHOW, 0, 3));
    }

    @Test
    public void slidesGoRoundInOrder() {
        assertEquals(0, Screensaver.next(-1, 3));
        assertEquals(1, Screensaver.next(0, 3));
        assertEquals(0, Screensaver.next(2, 3));
        assertEquals(0, Screensaver.next(5, 0));
    }

    @Test
    public void glowStrengthScalesTheAlphaOnly() {
        assertEquals(1f, Screensaver.glowFactor(-1), 0f);
        assertEquals(1f, Screensaver.glowFactor(50), 0f);
        assertEquals(0f, Screensaver.glowFactor(0), 0f);
        assertArrayEquals(new int[]{0xA4FFFFFF, 0x3EFFFFFF, 0x00FFFFFF},
                Screensaver.scaleAlpha(new int[]{0x52FFFFFF, 0x1FFFFFFF, 0x00FFFFFF}, 2f));
        assertArrayEquals(new int[]{0x00FFFFFF}, Screensaver.scaleAlpha(new int[]{0x52FFFFFF}, 0f));
        assertArrayEquals("capped at opaque", new int[]{0xFFFFFFFF}, Screensaver.scaleAlpha(new int[]{0xC0FFFFFF}, 2f));
    }
}
