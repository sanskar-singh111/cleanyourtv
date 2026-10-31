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
    public void aSpinPassesEnoughPostersAndStopsOnItsTarget() {
        for (int count : new int[]{1, 2, 3, 9, 10, 20}) {
            for (int centre = 0; centre < count; centre++) {
                for (int target = 0; target < count; target++) {
                    int steps = MoodShelf.spinSteps(centre, target, count);
                    assertTrue(steps >= MoodShelf.SPIN_STEPS);
                    assertTrue(steps < MoodShelf.SPIN_STEPS + count);
                    assertEquals(target, MoodShelf.wrap(centre - steps, count));
                }
            }
        }
        assertEquals(0, MoodShelf.spinSteps(0, 0, 0));
    }

    @Test
    public void aSpinKeepsOnlyThePostersAroundTheCentreDecodedAheadOnTheLeft() {
        Set<Integer> around = MoodShelf.wantedSpinning(5, 20);
        assertEquals(2 * MoodShelf.SIDE + 1 + MoodShelf.SPIN_AHEAD, around.size());
        assertTrue(around.contains(5 - MoodShelf.SIDE - MoodShelf.SPIN_AHEAD + 20));
        assertTrue(around.contains(5 + MoodShelf.SIDE));
        assertFalse(around.contains(5 + MoodShelf.SIDE + 1));
        // A short row goes round during the spin, so all of it is near.
        assertEquals(new HashSet<>(Arrays.asList(0, 1, 2)), MoodShelf.wantedSpinning(1, 3));
        assertTrue(MoodShelf.wantedSpinning(0, 0).isEmpty());
    }

    @Test
    public void aShortRowsRepeatsGoWhenTheSpinStops() {
        // A row of 3 stopping with poster 1 at place -20: only places -21, -20 and -19 stay.
        assertFalse(MoodShelf.staysAfterSpin(-22, -20, 1, 3));
        assertTrue(MoodShelf.staysAfterSpin(-21, -20, 1, 3));
        assertTrue(MoodShelf.staysAfterSpin(-19, -20, 1, 3));
        assertFalse(MoodShelf.staysAfterSpin(-18, -20, 1, 3));
        assertTrue(MoodShelf.staysAfterSpin(-18, -20, 1, 20));
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
        assertEquals(new HashSet<>(Arrays.asList(0, 1, 2, 3, 4, 5, 19, 18, 17, 16, 15)), want);
        assertEquals(2 * (MoodShelf.SIDE + MoodShelf.AHEAD) + 1, want.size());
        assertEquals(new HashSet<>(Arrays.asList(0, 1, 2)), MoodShelf.wanted(0, 3));
        assertTrue(MoodShelf.wanted(-1, 5).isEmpty());
    }

    @Test
    public void postersGetSmallerAndDarkerAwayFromTheCentreAndTouch() {
        assertEquals(1f, MoodShelf.scale(0f), 0f);
        assertEquals(0f, MoodShelf.dim(0f), 0f);
        assertEquals(0f, MoodShelf.place(0f), 0f);
        float halfWidth = 0.14f;   // the centre poster's, in shares of the screen's height
        for (float d = 0f; d < MoodShelf.SIDE; d += 1f) {
            assertTrue(MoodShelf.scale(d + 1f) < MoodShelf.scale(d));
            assertTrue(MoodShelf.dim(d + 1f) > MoodShelf.dim(d));
            float gap = MoodShelf.place(d + 1f) - MoodShelf.place(d);
            assertTrue("no gap between posters, turned and all", gap < halfWidth * (MoodShelf.scale(d) + MoodShelf.scale(d + 1f)));
        }
        assertEquals("halfway between two", (MoodShelf.scale(1f) + MoodShelf.scale(2f)) / 2f, MoodShelf.scale(1.5f), 0.0001f);
        assertEquals("past the end, the last value", MoodShelf.scale(5f), MoodShelf.scale(9f), 0f);
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
