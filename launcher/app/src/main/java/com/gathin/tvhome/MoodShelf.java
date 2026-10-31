package com.gathin.tvhome;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * mood_posters: the rules of the row of a mood's posters (MoodShelfView draws it). The row goes round when it has
 * enough posters to fill the screen, so it can keep moving on by itself; a short row stops at its ends. Plain data, so
 * it can be tested without a TV.
 */
final class MoodShelf {
    /** Posters drawn on each side of the centre one: the row fills the screen, the outer ones at its edges. */
    static final int SIDE = 4;
    /** Kept decoded beyond those: the next one each way, so a move never shows an empty frame. */
    static final int AHEAD = 1;
    /** From this many posters the row goes round (fewer would show the same poster twice on screen). */
    static final int ROUND_FROM = 2 * SIDE + 2;
    /** Moving on by itself: a rest, then a slow glide to the next poster. */
    static final long REST_MS = 2600, GLIDE_MS = 1100, MOVE_MS = 320;
    /** "Surprise me" on the row: how long it spins and how many posters it passes at least. */
    static final long SPIN_MS = 3000;
    static final int SPIN_STEPS = 14;
    /** While it spins, the posters decoded ahead of it (they come in from the left), and the last posters whose backdrops show. */
    static final int SPIN_AHEAD = 3, SPIN_BACKDROPS = 3;
    /**
     * Where a poster sits and how it looks by how far it is from the centre (0 the centre, 1 its neighbour, ...), as in
     * the reference picture: the posters touch, each drawn under the one nearer the centre. Across, in shares of the
     * screen's height from its middle; size against the centre poster; how much darker it is.
     */
    private static final float[] PLACE = {0f, 0.235f, 0.425f, 0.60f, 0.76f, 0.905f};
    private static final float[] SCALE = {1f, 0.90f, 0.78f, 0.70f, 0.64f, 0.60f};
    private static final float[] DIM = {0f, 0.06f, 0.12f, 0.26f, 0.42f, 0.60f};

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

    static float place(float distance) {
        return along(PLACE, distance);
    }

    static float scale(float distance) {
        return along(SCALE, distance);
    }

    static float dim(float distance) {
        return along(DIM, distance);
    }

    /** The table's value at a distance between its steps; past its end, its last value. */
    private static float along(float[] table, float distance) {
        float at = Math.max(0f, Math.min(table.length - 1f, distance));
        int i = Math.min(table.length - 2, (int) at);
        return table[i] + (table[i + 1] - table[i]) * (at - i);
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

    /** The posters the row passes turning left to right (each step one index down) to bring `target` to the centre. */
    static int spinSteps(int centre, int target, int count) {
        if (count <= 0) return 0;
        int steps = wrap(centre - target, count);
        while (steps < SPIN_STEPS) steps += count;
        return steps;
    }

    /**
     * The posters to hold decoded during a spin: those on screen and a few more ahead on the left, none behind. Even a
     * short row goes round while it spins.
     */
    static Set<Integer> wantedSpinning(int centre, int count) {
        Set<Integer> out = new LinkedHashSet<>();
        if (count <= 0 || centre < 0) return out;
        out.add(centre);
        for (int k = 1; k <= SIDE + SPIN_AHEAD; k++) {
            out.add(wrap(centre - k, count));
            if (k <= SIDE) out.add(wrap(centre + k, count));
        }
        return out;
    }

    /**
     * Whether a place in a spinning row, numbered on from `end` where `target` stops, is still there once the row stops.
     * A short row repeats its posters to go round; the repeats fade as it stops.
     */
    static boolean staysAfterSpin(int place, int end, int target, int count) {
        if (goesRound(count)) return true;
        int at = place - end + target;
        return at >= 0 && at < count;
    }

    /** After the poster at `gone` leaves a row of `count`: the poster now at the centre (the one after it). */
    static int afterRemoving(int gone, int centre, int count) {
        int left = count - 1;
        if (left <= 0) return -1;
        if (centre > gone) return centre - 1;
        return Math.min(centre, left - 1);
    }
}
