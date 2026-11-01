package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class ThemeFontsTest {
    /** A font's start: its kind, a table count and one directory entry per {offset, length}. */
    private static byte[] head(int kind, int[]... tables) {
        ByteBuffer b = ByteBuffer.allocate(12 + 16 * tables.length);
        b.putInt(kind).putShort((short) tables.length).putShort((short) 0).putInt(0);
        for (int[] t : tables) b.putInt(0x68656164).putInt(0).putInt(t[0]).putInt(t[1]);
        return b.array();
    }

    @Test
    public void aFontsKindsAreAccepted() {
        for (int kind : new int[]{0x00010000, 0x4F54544F, 0x74727565}) {
            assertTrue(ThemeFonts.wellFormed(head(kind, new int[]{44, 100}), 200));
        }
    }

    @Test
    public void anythingElseIsNot() {
        assertFalse(ThemeFonts.wellFormed(new byte[0], 0));
        assertFalse(ThemeFonts.wellFormed(new byte[8], 8));
        assertFalse("a web font", ThemeFonts.wellFormed(head(0x774F4646, new int[]{44, 100}), 200));
        assertFalse("a collection", ThemeFonts.wellFormed(head(0x74746366, new int[]{44, 100}), 200));
        assertFalse("HTML saved as a font", ThemeFonts.wellFormed("<html><head><title>".getBytes(), 19));
        assertFalse("no tables", ThemeFonts.wellFormed(head(0x00010000), 200));
    }

    @Test
    public void aTruncatedFileIsRefused() {
        assertFalse("a table runs past the end", ThemeFonts.wellFormed(head(0x00010000, new int[]{44, 100}), 143));
        assertFalse("the table list is cut short", ThemeFonts.wellFormed(Arrays.copyOf(head(0x00010000, new int[]{60, 1}, new int[]{61, 1}), 30), 30));
        assertTrue(ThemeFonts.wellFormed(head(0x00010000, new int[]{44, 100}), 144));
    }

    @Test
    public void aHugeOffsetDoesNotWrapAround() {
        assertFalse(ThemeFonts.wellFormed(head(0x00010000, new int[]{-1, 100}), 200));
    }

    @Test
    public void variationSettingsAreAndroidsForm() {
        Map<String, Float> axes = new TreeMap<>();
        axes.put("wght", 300f);
        axes.put("wdth", 87.5f);
        assertEquals("'wdth' 87.5, 'wght' 300", ThemeFonts.settings(axes));
    }

    @Test
    public void fontsAreAllowedOnlyInTheirFolder() {
        assertEquals("fonts/clock.ttf", ThemeFiles.path(List.of("fonts", "clock.ttf")));
        assertEquals("fonts/My-Font_2.OTF", ThemeFiles.path(List.of("fonts", "My-Font_2.OTF")));
        assertEquals(null, ThemeFiles.path(List.of("logos", "clock.ttf")));
        assertEquals(null, ThemeFiles.path(List.of("clock.ttf")));
        assertEquals(null, ThemeFiles.path(List.of("fonts", "..ttf")));
        assertEquals(null, ThemeFiles.path(List.of("fonts", "clock.woff2")));
        assertEquals(null, ThemeFiles.path(List.of("fonts", "a b.ttf")));
    }
}
