package com.gathin.tvhome;

import org.junit.Test;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** watchlist_effects: the effect names, their lengths, and that every figure comes back to rest. */
public class WatchlistEffectTest {
    @Test
    public void knownNamesOnly() {
        assertEquals(WatchlistEffect.SPIDER_SENSE, WatchlistEffect.known(" Spider-Sense "));
        assertEquals(WatchlistEffect.POLICE_LIGHTS, WatchlistEffect.known("police lights"));
        assertNull(WatchlistEffect.known("none"));
        assertNull(WatchlistEffect.known("fireworks"));
        assertNull(WatchlistEffect.known(null));
    }

    @Test
    public void everyEffectLastsAboutASecond() {
        assertEquals(14, WatchlistEffect.ALL.size());
        for (String id : WatchlistEffect.ALL) {
            long ms = WatchlistEffect.durationMs(id);
            assertTrue(id + " " + ms, ms >= 600 && ms <= 1500);
        }
        assertEquals(0, WatchlistEffect.durationMs(null));
    }

    @Test
    public void figuresMoveOnlyWhileTheEffectRuns() {
        float[] out = new float[5];
        for (String id : WatchlistEffect.ALL) {
            long end = WatchlistEffect.durationMs(id);
            for (float ms : new float[]{0f, end, end + 500f}) {
                WatchlistEffect.motion(id, ms, 42L, out);
                assertEquals(id, 0f, out[0], 0f);
                assertEquals(id, 0f, out[1], 0f);
                assertEquals(id, 1f, out[2], 0f);
                assertEquals(id, 1f, out[3], 0f);
                assertEquals(id, 0f, out[4], 0f);
            }
            // Never more than a nudge: a few percent of the figure's height.
            for (float ms = 1f; ms < end; ms += 7f) {
                WatchlistEffect.motion(id, ms, 42L, out);
                assertTrue(id, Math.abs(out[0]) <= 0.03f && Math.abs(out[1]) <= 0.05f);
                assertTrue(id, Math.abs(out[2] - 1f) <= 0.05f && Math.abs(out[3] - 1f) <= 0.05f && Math.abs(out[4]) <= 2f);
            }
        }
    }

    @Test
    public void spiderSenseThudsDownThenSettles() {
        float[] out = new float[5];
        WatchlistEffect.motion(WatchlistEffect.SPIDER_SENSE, 60f, 1L, out);
        assertTrue("down at the thud", out[1] > 0.02f);
        WatchlistEffect.motion(WatchlistEffect.SPIDER_SENSE, 700f, 1L, out);
        assertTrue("settled", Math.abs(out[1]) < 0.001f);
    }

    @Test
    public void flickerStartsDarkAndEndsLit() {
        for (long seed = 0; seed < 20; seed++) {
            assertTrue(WatchlistEffect.flickerDark(10f, seed) > 0.5f);
            assertEquals(0f, WatchlistEffect.flickerDark(1050f, seed), 0f);
            Set<Float> levels = new HashSet<>();
            for (float ms = 0f; ms < 1100f; ms += 10f) levels.add(WatchlistEffect.flickerDark(ms, seed));
            assertTrue("it flickers", levels.size() >= 2);
        }
    }

    @Test
    public void glitchComesInShortBursts() {
        int inBursts = 0;
        for (float ms = 0f; ms < 900f; ms += 5f) if (WatchlistEffect.glitchBurst(ms, 7L) >= 0) inBursts++;
        assertTrue(inBursts > 30 && inBursts < 120);
        assertEquals(-1, WatchlistEffect.glitchBurst(5f, 7L));
    }

    @Test
    public void theHeadIsGuessedWhenMissing() {
        Watchlist.Item none = new Watchlist.Item("a", new File("a.png"), null, null, 0, 1, 0, 0.5f, 1f, 0.5f,
                "p", "u", null, 0, null, "", "Blood", null, null);
        assertEquals(WatchlistEffect.BLOOD, none.effect);
        assertEquals(0.5f, none.headX, 0f);
        assertEquals(0.12f, none.headY, 0f);
        Watchlist.Item given = new Watchlist.Item("b", new File("b.png"), null, null, 0, 1, 0, 0.5f, 1f, 0.5f,
                "p", "u", null, 0, null, "", "nope", new float[]{0.3f, 0.2f, 0.1f}, null);
        assertNull(given.effect);
        assertEquals(0.3f, given.headX, 0f);
        assertEquals(0.2f, given.headY, 0f);
        assertEquals(0.1f, given.headSize, 0f);
    }
}
