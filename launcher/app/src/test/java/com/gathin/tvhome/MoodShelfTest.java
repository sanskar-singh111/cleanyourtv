package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** mood_posters: the row of a mood's posters, its moves, what it keeps decoded, and a poster leaving it. */
public class MoodShelfTest {
    @Test
    public void aLongRowGoesRoundAShortOneStopsAtItsEnds() {
        assertTrue(MoodShelf.goesRound(20));
        assertFalse(MoodShelf.goesRound(4));
        assertEquals(19, MoodShelf.step(0, -1, 20));
        assertEquals(0, MoodShelf.step(19, 1, 20));
        assertEquals(0, MoodShelf.step(0, -1, 4));
        assertEquals(3, MoodShelf.step(3, 1, 4));
        assertEquals(-1, MoodShelf.step(0, 1, 0));
    }

    @Test
    public void movingOnByItselfStartsAgainAtTheEndOfAShortRow() {
        assertEquals(1, MoodShelf.next(0, 3));
        assertEquals(0, MoodShelf.next(2, 3));
        assertEquals(0, MoodShelf.next(19, 20));
        assertEquals(-1, MoodShelf.next(0, 0));
    }

    @Test
    public void offsetsTakeTheShorterWayRound() {
        assertEquals(0f, MoodShelf.offset(5, 5f, 20), 0f);
        assertEquals(-1f, MoodShelf.offset(19, 0f, 20), 0f);
        assertEquals(1f, MoodShelf.offset(0, 19f, 20), 0f);
        assertEquals(0.5f, MoodShelf.offset(0, 19.5f, 20), 0f);
        // A short row doesn't go round: the last poster is far to the right of the first.
        assertEquals(3f, MoodShelf.offset(3, 0f, 4), 0f);
    }

    @Test
    public void onlyThePostersOnScreenAndOneMoreEachWayAreKept() {
        Set<Integer> want = MoodShelf.wanted(0, 20);
        assertEquals(new HashSet<>(Arrays.asList(0, 1, 2, 3, 19, 18, 17)), want);
        assertEquals(2 * (MoodShelf.SIDE + MoodShelf.AHEAD) + 1, want.size());
        assertEquals(new HashSet<>(Arrays.asList(0, 1, 2)), MoodShelf.wanted(0, 3));
        assertTrue(MoodShelf.wanted(-1, 5).isEmpty());
    }

    @Test
    public void theNextPosterTakesTheWatchedOnesPlace() {
        assertEquals(3, MoodShelf.afterRemoving(3, 3, 10));
        assertEquals("the last one: the one before it", 8, MoodShelf.afterRemoving(9, 9, 10));
        assertEquals("one before the centre: the centre moves down one", 4, MoodShelf.afterRemoving(2, 5, 10));
        assertEquals("one after: the centre stays", 5, MoodShelf.afterRemoving(7, 5, 10));
        assertEquals(-1, MoodShelf.afterRemoving(0, 0, 1));
    }
}
