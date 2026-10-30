package com.gathin.tvhome;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The mood deck under the watchlist (theme keys {@code mood_deck}, {@code mood_deck_moods} and {@code mood_deck_titles}):
 * a card per mood, filled from the watchlist on the computer. Left throws the top card away and the next mood comes
 * up; Right or OK spins a reel of the mood's posters that lands on one title at random, which then opens in its app
 * after a short countdown. Everything here is plain data and the rules; MoodDeckView draws it.
 */
final class MoodDeck {
    /** Bounds on what a theme can list, so a broken file can't run away with the TV's memory or time. */
    static final int MAX_TITLES = 200, MAX_MOODS = 12;
    /** Different posters on one reel at most: only they are decoded for the spin. */
    static final int REEL_POSTERS = 8;
    /** Posters the reel passes before it lands (at least; whole turns of its posters). */
    static final int REEL_LENGTH = 28;
    /** The countdown after the reel lands, before the title opens. */
    static final long COUNTDOWN_MS = 3000;

    static final class Title {
        final String key, title;
        /** The poster (2:3) and the darkened black-and-white backdrop shown behind the deck; the backdrop may be null. */
        final File poster, backdrop;
        /** What opening it starts: the app, a link it understands, and optionally the exact activity, intent flags and extras. */
        final String packageName, uri, component;
        final int flags;
        final Map<String, String> extras;
        final String appName;
        /** The year it came out, "" when the theme doesn't say (shown under the poster row). */
        final String year;

        Title(String key, String title, File poster, File backdrop, String packageName, String uri, String component,
              int flags, Map<String, String> extras, String appName) {
            this(key, title, poster, backdrop, packageName, uri, component, flags, extras, appName, "");
        }

        Title(String key, String title, File poster, File backdrop, String packageName, String uri, String component,
              int flags, Map<String, String> extras, String appName, String year) {
            this.key = key == null ? "" : key;
            this.title = title == null ? "" : title;
            this.poster = poster;
            this.backdrop = backdrop;
            this.packageName = packageName == null ? "" : packageName;
            this.uri = uri == null ? "" : uri;
            this.component = component;
            this.flags = flags;
            this.extras = extras == null ? Collections.emptyMap() : Collections.unmodifiableMap(extras);
            this.appName = appName == null ? "" : appName;
            this.year = year == null ? "" : year;
        }
    }

    static final class Mood {
        final String name;
        final int color;
        /** Indexes into the deck's titles, in the theme's order. */
        final List<Integer> titles;

        Mood(String name, int color, List<Integer> titles) {
            this.name = name == null ? "" : name;
            this.color = color | 0xFF000000;
            this.titles = Collections.unmodifiableList(new ArrayList<>(titles));
        }
    }

    private MoodDeck() {}

    /** Moods that have at least one title, in the theme's order, at most MAX_MOODS. */
    static List<Mood> usable(List<Mood> moods) {
        List<Mood> out = new ArrayList<>();
        for (Mood m : moods) if (!m.titles.isEmpty() && out.size() < MAX_MOODS) out.add(m);
        return out;
    }

    /** The card `steps` behind the top one; the deck goes round, so after the last mood comes the first again. */
    static int behind(int top, int steps, int count) {
        return count <= 0 ? -1 : ((top + steps) % count + count) % count;
    }

    /** How many cards peek out behind the top one: the next two moods, fewer when the deck is smaller. */
    static int peeking(int count) {
        return Math.max(0, Math.min(2, count - 1));
    }

    /** The posters fanned out on a card: its first three titles. */
    static List<Integer> fan(Mood m) {
        return m.titles.subList(0, Math.min(3, m.titles.size()));
    }

    /** A title of the mood at random, not the one it landed on last time when there is another. */
    static int pick(Random random, Mood m, int last) {
        if (m.titles.size() == 1) return m.titles.get(0);
        int t;
        do t = m.titles.get(random.nextInt(m.titles.size())); while (t == last);
        return t;
    }

    /**
     * The reel: up to REEL_POSTERS of the mood's titles (always the picked one) in a shuffled order, repeated until it
     * is at least REEL_LENGTH long, ending on the picked one. Neighbours are never the same poster when the mood has two
     * or more.
     */
    static List<Integer> reel(Random random, Mood m, int picked) {
        List<Integer> others = new ArrayList<>(m.titles);
        others.remove(Integer.valueOf(picked));
        Collections.shuffle(others, random);
        List<Integer> round = new ArrayList<>(others.subList(0, Math.min(REEL_POSTERS - 1, others.size())));
        round.add(picked);   // each turn of the reel ends on it
        List<Integer> out = new ArrayList<>();
        while (out.size() < REEL_LENGTH - 1 || out.isEmpty()) out.addAll(round);
        return out;
    }

    /** The number shown while counting down: 3, 2, 1. */
    static int secondsLeft(long elapsedMs) {
        long left = COUNTDOWN_MS - Math.max(0, elapsedMs);
        return (int) Math.max(1, Math.min(COUNTDOWN_MS / 1000, (left + 999) / 1000));
    }
}
