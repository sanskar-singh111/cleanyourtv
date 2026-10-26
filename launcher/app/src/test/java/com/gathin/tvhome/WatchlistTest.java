package com.gathin.tvhome;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** watchlist_items: which character the remote's arrows go to, pages, and the draw order. */
public class WatchlistTest {
    private static Watchlist.Item item(String title, int page, int row, float x) {
        return new Watchlist.Item(title, new File(title + ".png"), null, null, 0xFFFFFFFF, row, page, x, 0.9f, 0.5f,
                "com.netflix.ninja", "https://www.netflix.com/title/1", null, 0, null, "Netflix");
    }

    /** Ten, as TV Studio lays them out: seven on the first page (rows alternating across), three on the second. */
    private static List<Watchlist.Item> lineup() {
        return Arrays.asList(
                item("a", 0, Watchlist.FRONT, 0.17f), item("b", 0, Watchlist.BACK, 0.28f), item("c", 0, Watchlist.FRONT, 0.39f),
                item("d", 0, Watchlist.BACK, 0.50f), item("e", 0, Watchlist.FRONT, 0.61f), item("f", 0, Watchlist.BACK, 0.72f),
                item("g", 0, Watchlist.FRONT, 0.83f),
                item("h", 1, Watchlist.FRONT, 0.25f), item("i", 1, Watchlist.BACK, 0.50f), item("j", 1, Watchlist.FRONT, 0.75f));
    }

    @Test
    public void rightGoesAcrossBothRowsInOrder() {
        List<Watchlist.Item> l = lineup();
        assertEquals(1, Watchlist.move(l, 0, Watchlist.Direction.RIGHT));
        assertEquals(2, Watchlist.move(l, 1, Watchlist.Direction.RIGHT));
        assertEquals(5, Watchlist.move(l, 6, Watchlist.Direction.LEFT));
    }

    @Test
    public void theEdgesTurnThePage() {
        List<Watchlist.Item> l = lineup();
        assertEquals("right on the last goes to the next page's first", 7, Watchlist.move(l, 6, Watchlist.Direction.RIGHT));
        assertEquals("left on the next page's first comes back to the last", 6, Watchlist.move(l, 7, Watchlist.Direction.LEFT));
        assertEquals("the very first stays", 0, Watchlist.move(l, 0, Watchlist.Direction.LEFT));
        assertEquals("the very last stays", 9, Watchlist.move(l, 9, Watchlist.Direction.RIGHT));
    }

    @Test
    public void upGoesHomeAndDownGoesBelow() {
        List<Watchlist.Item> l = lineup();
        assertEquals(Watchlist.EXIT, Watchlist.move(l, 0, Watchlist.Direction.UP));
        assertEquals(Watchlist.EXIT, Watchlist.move(l, 3, Watchlist.Direction.UP));
        assertEquals(Watchlist.BELOW, Watchlist.move(l, 3, Watchlist.Direction.DOWN));
        assertEquals("from the second page too", Watchlist.BELOW, Watchlist.move(l, 8, Watchlist.Direction.DOWN));
    }

    @Test
    public void downFromTheDockLandsOnTheNearestCharacterOfThePage() {
        List<Watchlist.Item> l = lineup();
        assertEquals(0, Watchlist.entry(l, 0, 0.1f));
        assertEquals(3, Watchlist.entry(l, 0, 0.52f));
        assertEquals(8, Watchlist.entry(l, 1, 0.52f));
        assertEquals("a page that is gone falls back to the first", 3, Watchlist.entry(l, 5, 0.52f));
    }

    @Test
    public void pages() {
        List<Watchlist.Item> l = lineup();
        assertEquals(2, Watchlist.pageCount(l));
        assertEquals(Arrays.asList(7, 8, 9), Watchlist.onPage(l, 1));
        assertTrue(Watchlist.keeps(1, 0, 0));
        assertFalse("two pages on stays unloaded", Watchlist.keeps(2, 0, 0));
        assertTrue("sliding back loads the previous page", Watchlist.keeps(0, 1, 0));
        assertFalse(Watchlist.keeps(0, 1, 1));
    }

    @Test
    public void theSelectedOneIsDrawnLastOverEveryoneElse() {
        List<Watchlist.Item> l = lineup();
        assertEquals(Arrays.asList(1, 3, 5, 0, 2, 4, 6), Watchlist.drawOrder(l, 0, -1));
        assertEquals(Arrays.asList(1, 5, 0, 2, 4, 6, 3), Watchlist.drawOrder(l, 0, 3));
        assertEquals(Arrays.asList(8, 7, 9), Watchlist.drawOrder(l, 1, -1));
    }

    @Test
    public void onlyItemsWithAPicture() {
        List<Watchlist.Item> many = new ArrayList<>(lineup());
        many.add(0, new Watchlist.Item("none", null, null, null, 0, Watchlist.FRONT, 0, 0.5f, 1f, 0.5f, "p", "u", null, 0, null, "A"));
        List<Watchlist.Item> shown = Watchlist.usable(many);
        assertEquals(10, shown.size());
        assertEquals("a", shown.get(0).title);
    }

    @Test
    public void outOfRangeNumbersAreKeptOnScreen() {
        Watchlist.Item i = new Watchlist.Item("x", new File("x.png"), null, null, 0, 5, -3, -1f, 9f, 2f, null, null, null, 0, null, null);
        assertEquals(Watchlist.FRONT, i.row);
        assertEquals(0, i.page);
        assertEquals(0f, i.x, 0f);
        assertEquals(1.6f, i.bottom, 0f);
        assertEquals(1f, i.height, 0f);
        assertEquals("", i.uri);
        assertTrue(i.extras.isEmpty());
    }

    @Test
    public void intentFlagsReadAsNumberOrHex() {
        assertEquals(0x10808000, Config.intentFlags(276856832));
        assertEquals(0x10808000, Config.intentFlags("0x10808000"));
        assertEquals(0x10808000, Config.intentFlags(" 276856832 "));
        assertEquals(0, Config.intentFlags("netflix"));
        assertEquals(0, Config.intentFlags(null));
    }
}
