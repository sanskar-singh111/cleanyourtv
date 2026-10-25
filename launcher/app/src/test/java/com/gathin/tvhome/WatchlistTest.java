package com.gathin.tvhome;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** watchlist_items: which character the remote's arrows go to, and the draw order. */
public class WatchlistTest {
    private static Watchlist.Item item(String title, int row, float x) {
        return new Watchlist.Item(title, new File(title + ".png"), null, null, 0xFFFFFFFF, row, x, 0.9f, 0.5f,
                "com.netflix.ninja", "https://www.netflix.com/title/1", null, "Netflix");
    }

    /** Seven, as TV Studio lays them out: four in front, three behind in the gaps. */
    private static List<Watchlist.Item> lineup() {
        return Arrays.asList(
                item("f0", Watchlist.FRONT, 0.17f), item("f1", Watchlist.FRONT, 0.39f),
                item("f2", Watchlist.FRONT, 0.61f), item("f3", Watchlist.FRONT, 0.83f),
                item("b0", Watchlist.BACK, 0.28f), item("b1", Watchlist.BACK, 0.50f), item("b2", Watchlist.BACK, 0.72f));
    }

    @Test
    public void downFromTheDockLandsOnTheNearestTopRowCharacter() {
        List<Watchlist.Item> l = lineup();
        assertEquals(4, Watchlist.entry(l, 0.1f));
        assertEquals(5, Watchlist.entry(l, 0.52f));
        assertEquals(6, Watchlist.entry(l, 0.95f));
    }

    @Test
    public void leftAndRightStopAtTheRowsEnds() {
        List<Watchlist.Item> l = lineup();
        assertEquals(1, Watchlist.move(l, 0, Watchlist.Direction.RIGHT));
        assertEquals(0, Watchlist.move(l, 0, Watchlist.Direction.LEFT));
        assertEquals(3, Watchlist.move(l, 3, Watchlist.Direction.RIGHT));
        assertEquals(5, Watchlist.move(l, 4, Watchlist.Direction.RIGHT));
        assertEquals(4, Watchlist.move(l, 4, Watchlist.Direction.LEFT));
    }

    @Test
    public void upAndDownSwitchRowsAndUpFromTheTopGoesHome() {
        List<Watchlist.Item> l = lineup();
        assertEquals(4, Watchlist.move(l, 1, Watchlist.Direction.UP));    // 0.39 -> 0.28
        assertEquals(1, Watchlist.move(l, 4, Watchlist.Direction.DOWN));  // 0.28 -> 0.39 (0.17 is further)
        assertEquals(3, Watchlist.move(l, 3, Watchlist.Direction.DOWN));  // front row is the bottom
        assertEquals(Watchlist.EXIT, Watchlist.move(l, 5, Watchlist.Direction.UP));
    }

    @Test
    public void aSingleRowIsTheTopRow() {
        List<Watchlist.Item> l = Arrays.asList(item("a", Watchlist.FRONT, 0.4f), item("b", Watchlist.FRONT, 0.6f));
        assertEquals(0, Watchlist.entry(l, 0.3f));
        assertEquals(Watchlist.EXIT, Watchlist.move(l, 1, Watchlist.Direction.UP));
        assertEquals(1, Watchlist.move(l, 1, Watchlist.Direction.DOWN));
    }

    @Test
    public void theSelectedOneIsDrawnLastOverEveryoneElse() {
        List<Watchlist.Item> l = lineup();
        assertEquals(Arrays.asList(4, 5, 6, 0, 1, 2, 3), Watchlist.drawOrder(l, -1));
        assertEquals(Arrays.asList(4, 6, 0, 1, 2, 3, 5), Watchlist.drawOrder(l, 5));
    }

    @Test
    public void atMostSevenAndOnlyWithAPicture() {
        List<Watchlist.Item> many = new ArrayList<>(lineup());
        many.add(item("extra", Watchlist.FRONT, 0.5f));
        many.add(0, new Watchlist.Item("none", null, null, null, 0, Watchlist.FRONT, 0.5f, 1f, 0.5f, "p", "u", null, "A"));
        List<Watchlist.Item> shown = Watchlist.usable(many);
        assertEquals(7, shown.size());
        assertEquals("f0", shown.get(0).title);
    }

    @Test
    public void outOfRangeNumbersAreKeptOnScreen() {
        Watchlist.Item i = new Watchlist.Item("x", new File("x.png"), null, null, 0, 5, -1f, 9f, 2f, null, null, null, null);
        assertEquals(Watchlist.FRONT, i.row);
        assertEquals(0f, i.x, 0f);
        assertEquals(1.6f, i.bottom, 0f);
        assertEquals(1f, i.height, 0f);
        assertEquals("", i.uri);
    }
}
