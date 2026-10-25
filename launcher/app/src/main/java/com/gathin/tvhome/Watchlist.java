package com.gathin.tvhome;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The watchlist lineup under the dock (theme keys {@code watchlist} and {@code watchlist_items}): characters cut out on
 * the computer, in two rows, each opening its title in a streaming app. Everything here is plain data and the
 * navigation between the characters; WatchlistView draws them. The TV does no image work: the pictures arrive at
 * their final size, already in black on transparent, with their glow and black-and-white scene made beforehand.
 */
final class Watchlist {
    /** The back row (higher on screen, partly behind the front row) and the front row. */
    static final int BACK = 0, FRONT = 1;
    /** The most the lineup shows; the TV's memory holds every figure while the section is open. */
    static final int MAX_ITEMS = 7;

    static final class Item {
        final String title;
        /** The figure (PNG with transparency, already the size it is drawn at on a 1080-line screen). */
        final File image;
        /** A quarter-size white halo of the figure, tinted with {@link #color}; may be null. */
        final File glow;
        /** The darkened black-and-white scene behind the lineup while this one is selected; may be null. */
        final File scene;
        final int color;
        final int row;
        /** Centre of the figure across the screen, and where its bottom edge sits down it, both 0 to 1. */
        final float x, bottom;
        /** Height of the figure as a share of the screen's height. */
        final float height;
        /** What OK opens: the app, a link it understands, and optionally the exact activity. */
        final String packageName, uri, component;
        /** The app's name, shown when the theme has no logo for it. */
        final String appName;

        Item(String title, File image, File glow, File scene, int color, int row, float x, float bottom, float height,
             String packageName, String uri, String component, String appName) {
            this.title = title == null ? "" : title;
            this.image = image;
            this.glow = glow;
            this.scene = scene;
            this.color = color;
            this.row = row == BACK ? BACK : FRONT;
            this.x = clamp(x, 0f, 1f);
            this.bottom = clamp(bottom, 0.2f, 1.3f);
            this.height = clamp(height, 0.1f, 0.9f);
            this.packageName = packageName == null ? "" : packageName;
            this.uri = uri == null ? "" : uri;
            this.component = component;
            this.appName = appName == null ? "" : appName;
        }
    }

    private Watchlist() {}

    static float clamp(float v, float min, float max) {
        return Float.isNaN(v) ? min : Math.max(min, Math.min(max, v));
    }

    /** At most MAX_ITEMS, the ones without a picture left out. */
    static List<Item> usable(List<Item> items) {
        List<Item> out = new ArrayList<>();
        for (Item i : items) {
            if (i.image != null && out.size() < MAX_ITEMS) out.add(i);
        }
        return out;
    }

    /** Indexes of one row's items, left to right. */
    static List<Integer> row(List<Item> items, int row) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) if (items.get(i).row == row) out.add(i);
        Collections.sort(out, (a, b) -> Float.compare(items.get(a).x, items.get(b).x));
        return out;
    }

    /** The item of `row` nearest to `x` across the screen, or -1 when the row is empty. */
    static int nearest(List<Item> items, int row, float x) {
        int best = -1;
        float distance = Float.MAX_VALUE;
        for (int i : row(items, row)) {
            float d = Math.abs(items.get(i).x - x);
            if (d < distance) { distance = d; best = i; }
        }
        return best;
    }

    /** Where Down from the dock lands: the top row's character nearest the focused dock app, else the front row's. */
    static int entry(List<Item> items, float x) {
        int i = nearest(items, BACK, x);
        return i >= 0 ? i : nearest(items, FRONT, x);
    }

    /** Result of a move: the new selection, or EXIT for "back up to the dock". Unchanged means the key did nothing. */
    static final int EXIT = -2;

    /**
     * Left and Right move along the row and stop at its ends; Up and Down switch rows to the nearest character;
     * Up from the top row goes back to the dock (also when the back row is empty and the front row is the top).
     */
    static int move(List<Item> items, int selected, Direction direction) {
        if (selected < 0 || selected >= items.size()) return items.isEmpty() ? EXIT : entry(items, 0.5f);
        Item current = items.get(selected);
        List<Integer> row = row(items, current.row);
        int at = row.indexOf(selected);
        switch (direction) {
            case LEFT:
                return at > 0 ? row.get(at - 1) : selected;
            case RIGHT:
                return at < row.size() - 1 ? row.get(at + 1) : selected;
            case UP: {
                if (current.row == BACK) return EXIT;
                int up = nearest(items, BACK, current.x);
                return up >= 0 ? up : EXIT;
            }
            case DOWN: {
                if (current.row == FRONT) return selected;
                int down = nearest(items, FRONT, current.x);
                return down >= 0 ? down : selected;
            }
            default:
                return selected;
        }
    }

    enum Direction { LEFT, RIGHT, UP, DOWN }

    /** The order figures are drawn in: back row first, each row left to right, the selected one last (on top). */
    static List<Integer> drawOrder(List<Item> items, int selected) {
        List<Integer> out = new ArrayList<>(row(items, BACK));
        out.addAll(row(items, FRONT));
        if (out.remove(Integer.valueOf(selected))) out.add(selected);
        return out;
    }
}
