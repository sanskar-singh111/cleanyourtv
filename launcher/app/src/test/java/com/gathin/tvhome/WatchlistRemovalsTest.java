package com.gathin.tvhome;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** watchlist_remove: what stays hidden, who is selected after a removal, and the deck without removed titles. */
public class WatchlistRemovalsTest {
    private static Watchlist.Item item(String key, int page, float x) {
        return new Watchlist.Item(key, new File(key + ".png"), null, null, 0xFFFFFFFF, Watchlist.FRONT, page, x, 0.9f, 0.5f,
                "p", "u", null, 0, null, "A", null, null, key);
    }

    @Test
    public void aTitleIsNamedByItsKeyElseItsTitle() {
        assertEquals("movie-138843", WatchlistRemovals.keyOf(" movie-138843 ", "The Conjuring"));
        assertEquals("title:The Conjuring", WatchlistRemovals.keyOf("", "The Conjuring"));
        assertEquals("title:Lost", new Watchlist.Item("Lost", new File("l.png"), null, null, 0, 1, 0, 0.5f, 1f, 0.5f,
                "p", "u", null, 0, null, "").key);
    }

    @Test
    public void theFileHidesAlwaysAndRememberedOnesOnlyUntilTheNextPush() {
        Set<String> file = new HashSet<>(Collections.singletonList("tv-1"));
        Set<String> remembered = new HashSet<>(Collections.singletonList("tv-2"));
        assertEquals(new HashSet<>(Arrays.asList("tv-1", "tv-2")), WatchlistRemovals.hidden(file, "r1", remembered, "r1"));
        assertEquals("a push since: only the file's", file, WatchlistRemovals.hidden(file, "r1", remembered, "r2"));
        assertEquals("file cleared by TV Studio, no push yet: still hidden", remembered,
                WatchlistRemovals.hidden(Collections.emptySet(), "r1", remembered, "r1"));
        assertTrue(WatchlistRemovals.hidden(Collections.emptySet(), null, remembered, "absent").isEmpty());
    }

    @Test
    public void hiddenItemsAreLeftOutInOrder() {
        List<Watchlist.Item> l = Arrays.asList(item("a", 0, 0.1f), item("b", 0, 0.2f), item("c", 0, 0.3f));
        List<Watchlist.Item> kept = WatchlistRemovals.without(l, i -> i.key, new HashSet<>(Collections.singletonList("b")));
        assertEquals(2, kept.size());
        assertEquals("a", kept.get(0).key);
        assertEquals("c", kept.get(1).key);
    }

    @Test
    public void afterARemovalTheNextOneAcrossIsSelected() {
        List<Watchlist.Item> l = Arrays.asList(item("a", 0, 0.1f), item("b", 0, 0.5f), item("c", 0, 0.9f), item("d", 1, 0.5f));
        assertEquals("the next across", 2, Watchlist.afterRemoving(l, 1));
        assertEquals("the last on its page: the one before", 1, Watchlist.afterRemoving(l, 2));
        assertEquals("alone on its page: the page before's last", 2, Watchlist.afterRemoving(l, 3));
        List<Watchlist.Item> two = Arrays.asList(item("a", 0, 0.5f), item("b", 1, 0.5f));
        assertEquals("alone on page 1: the next page's first", 1, Watchlist.afterRemoving(two, 0));
        assertEquals(-1, Watchlist.afterRemoving(Collections.singletonList(item("a", 0, 0.5f)), 0));
    }

    @Test
    public void theDeckLosesRemovedTitlesAndEmptyMoods() {
        List<MoodDeck.Title> titles = Arrays.asList(
                new MoodDeck.Title("movie-1", "One", new File("1.jpg"), null, "p", "u", null, 0, null, "A"),
                new MoodDeck.Title("movie-2", "Two", new File("2.jpg"), null, "p", "u", null, 0, null, "A"));
        List<MoodDeck.Mood> moods = Arrays.asList(new MoodDeck.Mood("Horror", 0xFF0000, Collections.singletonList(0)),
                new MoodDeck.Mood("Anything", 0x00FF00, Arrays.asList(0, 1)));
        List<MoodDeck.Mood> left = WatchlistRemovals.without(moods, titles, new HashSet<>(Collections.singletonList("movie-1")));
        assertEquals(1, left.size());
        assertEquals("Anything", left.get(0).name);
        assertEquals(Collections.singletonList(1), left.get(0).titles);
    }
}
