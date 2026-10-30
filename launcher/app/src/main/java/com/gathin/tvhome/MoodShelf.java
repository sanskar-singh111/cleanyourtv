package com.gathin.tvhome;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * mood_posters: the rules of the row of a mood's posters (MoodShelfView draws it). The row goes round when it has
 * enough posters to fill the screen, so it can keep moving on by itself; a short row stops at its ends. Plain data, so
 * it can be tested without a TV.
 */
final class MoodShelf {
    /** Posters drawn on each side of the centre one (the outer ones partly off screen). */
    static final int SIDE = 2;
    /** Kept decoded beyond those: the next one each way, so a move never shows an empty frame. */
    static final int AHEAD = 1;
    /** From this many posters the row goes round (fewer would show the same poster twice on screen). */
    static final int ROUND_FROM = 2 * SIDE + 2;
    /** Moving on by itself: a rest, then a slow glide to the next poster. */
    static final long REST_MS = 2600, GLIDE_MS = 1100, MOVE_MS = 320;

    private MoodShelf() {}

    static boolean goesRound(int count) {
        return count >= ROUND_FROM;
    }

    /** The poster one step to the right (+1) or left (-1): round, or held at the ends of a short row. */
    static int step(int centre, int direction, int count) {
        if (count <= 0) return -1;
        if (goesRound(count)) return wrap(centre + direction, count);
        return Math.max(0, Math.min(count - 1, centre + direction));
    }

    /** Moving on by itself: the next poster; a short row starts again from the first. */
    static int next(int centre, int count) {
        if (count <= 0) return -1;
        return goesRound(count) || centre + 1 < count ? wrap(centre + 1, count) : 0;
    }

    static int wrap(int i, int count) {
        return count <= 0 ? -1 : ((i % count) + count) % count;
    }

    /**
     * Where poster `index` sits relative to the (fractional) centre position: 0 in the middle, 1 the next on the
     * right, -1 on the left. A round row takes the shorter way, so the last poster sits left of the first.
     */
    static float offset(int index, float position, int count) {
        float d = index - position;
        if (goesRound(count)) {
            while (d > count / 2f) d -= count;
            while (d < -count / 2f) d += count;
        }
        return d;
    }

    /** The posters to hold decoded around the centre: those on screen and one more each way. */
    static Set<Integer> wanted(int centre, int count) {
        Set<Integer> out = new LinkedHashSet<>();
        if (count <= 0 || centre < 0) return out;
        out.add(centre);
        for (int k = 1; k <= SIDE + AHEAD; k++) {
            for (int i : new int[]{centre - k, centre + k}) {
                if (goesRound(count)) out.add(wrap(i, count));
                else if (i >= 0 && i < count) out.add(i);
            }
        }
        return out;
    }

    /** After the poster at `gone` leaves a row of `count`: the poster now at the centre (the one after it). */
    static int afterRemoving(int gone, int centre, int count) {
        int left = count - 1;
        if (left <= 0) return -1;
        if (centre > gone) return centre - 1;
        return Math.min(centre, left - 1);
    }
}
