package com.gathin.tvhome;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** mood_deck: which card comes up, what peeks out, the reel and the countdown. */
public class MoodDeckTest {
    private static MoodDeck.Mood mood(String name, Integer... titles) {
        return new MoodDeck.Mood(name, 0xB3261E, Arrays.asList(titles));
    }

    @Test
    public void emptyMoodsAreHidden() {
        List<MoodDeck.Mood> deck = MoodDeck.usable(Arrays.asList(mood("Horror"), mood("Crime", 0, 1), mood("Anything at all", 0, 1, 2)));
        assertEquals(2, deck.size());
        assertEquals("Crime", deck.get(0).name);
        assertEquals("the colour is opaque", 0xFFB3261E, deck.get(0).color);
    }

    @Test
    public void theDeckGoesRound() {
        assertEquals(1, MoodDeck.behind(0, 1, 3));
        assertEquals(2, MoodDeck.behind(0, 2, 3));
        assertEquals("after the last mood comes the first", 0, MoodDeck.behind(2, 1, 3));
        assertEquals(2, MoodDeck.peeking(7));
        assertEquals(1, MoodDeck.peeking(2));
        assertEquals("a deck of one shows nothing behind it", 0, MoodDeck.peeking(1));
    }

    @Test
    public void theFanIsTheFirstThree() {
        assertEquals(Arrays.asList(4, 5, 6), MoodDeck.fan(mood("Drama", 4, 5, 6, 7)));
        assertEquals(Collections.singletonList(4), MoodDeck.fan(mood("Comedy", 4)));
    }

    @Test
    public void thePickIsOneOfTheMoodsAndNotTheSameTwiceRunning() {
        MoodDeck.Mood m = mood("Crime", 3, 5, 8);
        Random r = new Random(1);
        int last = -1;
        for (int i = 0; i < 50; i++) {
            int p = MoodDeck.pick(r, m, last);
            assertTrue(m.titles.contains(p));
            assertNotEquals(last, p);
            last = p;
        }
        assertEquals("a mood of one always lands on it", 9, MoodDeck.pick(r, mood("Comedy", 9), 9));
    }

    @Test
    public void theReelEndsOnThePickAndHoldsFewPosters() {
        List<Integer> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) many.add(i);
        MoodDeck.Mood m = new MoodDeck.Mood("Anything at all", 0, many);
        List<Integer> reel = MoodDeck.reel(new Random(2), m, 17);
        assertEquals(17, (int) reel.get(reel.size() - 1));
        assertTrue("long enough to spin", reel.size() >= MoodDeck.REEL_LENGTH - 1);
        assertTrue("only a few posters are decoded", new HashSet<>(reel).size() <= MoodDeck.REEL_POSTERS);
        for (int i = 1; i < reel.size(); i++) assertNotEquals("neighbours differ", reel.get(i - 1), reel.get(i));
        List<Integer> one = MoodDeck.reel(new Random(3), mood("Comedy", 4), 4);
        assertEquals(4, (int) one.get(one.size() - 1));
        assertEquals(Collections.singleton(4), new HashSet<>(one));
    }

    @Test
    public void theCountdownSaysThreeTwoOne() {
        assertEquals(3, MoodDeck.secondsLeft(0));
        assertEquals(3, MoodDeck.secondsLeft(999));
        assertEquals(2, MoodDeck.secondsLeft(1000));
        assertEquals(1, MoodDeck.secondsLeft(2500));
        assertEquals(1, MoodDeck.secondsLeft(5000));
    }
}
