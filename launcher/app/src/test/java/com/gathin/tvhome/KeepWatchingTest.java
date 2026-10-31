package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class KeepWatchingTest {
    private static KeepWatching.Item item(String pkg, String title, long engaged, String uri) {
        return new KeepWatching.Item(pkg, title, "", "", uri, engaged);
    }

    @Test
    public void theEpisodeLineUsesWhatTheAppGave() {
        assertEquals("S1 E4 · Pilot", KeepWatching.episode("1", "4", "Pilot"));
        assertEquals("S1 E4", KeepWatching.episode("1", "4", null));
        assertEquals("E4", KeepWatching.episode("", "4", ""));
        assertEquals("Pilot", KeepWatching.episode(null, null, "Pilot"));
        assertEquals("", KeepWatching.episode(null, null, null));
    }

    @Test
    public void newestFirstOnePerTitleOnlyWhatOpens() {
        List<KeepWatching.Item> rows = KeepWatching.usable(Arrays.asList(
                item("a", "Old", 1, "intent:x"),
                item("a", "New", 9, "intent:x"),
                item("b", "New", 5, "intent:x"),
                item("a", "NEW", 3, "intent:x"),
                item("a", "No link", 8, ""),
                item("", "No app", 8, "intent:x"),
                item("a", "", 8, "intent:x")));
        assertEquals(3, rows.size());
        assertEquals("New", rows.get(0).title);
        assertEquals("a", rows.get(0).packageName);
        assertEquals("b", rows.get(1).packageName);
        assertEquals("Old", rows.get(2).title);
    }

    @Test
    public void theRowHasAnEnd() {
        List<KeepWatching.Item> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) many.add(item("a", "T" + i, i, "intent:x"));
        assertEquals(KeepWatching.MAX_ITEMS, KeepWatching.usable(many).size());
        assertEquals("T39", KeepWatching.usable(many).get(0).title);
    }

    @Test
    public void weatherInWords() {
        assertEquals("Mostly clear", Weather.condition(1));
        assertEquals("Rain", Weather.condition(63));
        assertEquals("Thunderstorm", Weather.condition(95));
        assertEquals("", Weather.condition(-1));
        assertEquals("28° Mostly clear", BigClock.line(28, "Mostly clear"));
        assertEquals("28°", BigClock.line(28, ""));
    }

    @Test
    public void aBakedInShadowIsNotPartOfTheIcon() {
        int[] px = new int[16];
        java.util.Arrays.fill(px, 0x40000000);   // a soft shadow all round
        px[5] = px[6] = px[9] = px[10] = 0xFF112233;
        org.junit.Assert.assertArrayEquals(new int[]{1, 1, 3, 3}, DockIcon.solidBounds(px, 4));
    }

    @Test
    public void anIconCoversItsSquareCentred() {
        float[] wide = DockIcon.cover(320, 180, 90);
        assertEquals(90f, wide[3] - wide[1], 0.01f);
        assertEquals(160f, wide[2] - wide[0], 0.01f);
        assertEquals(-35f, wide[0], 0.01f);
    }
}
