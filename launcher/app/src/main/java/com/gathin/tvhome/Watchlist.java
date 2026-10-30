package com.gathin.tvhome;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The watchlist lineup under the dock (theme keys {@code watchlist} and {@code watchlist_items}): characters cut out on
 * the computer, a page of up to seven at a time in two rows, each opening its title in a streaming app. Everything here
 * is plain data and the navigation between the characters; WatchlistView draws them. The TV does no image work: the
 * pictures arrive at their final size, already on transparent, with their glow and black-and-white scene made beforehand.
 */
final class Watchlist {
    /** The back row (higher on screen, partly behind the front row) and the front row. */
    static final int BACK = 0, FRONT = 1;
    /** A page holds up to this many; only the page on screen and the next one keep their pictures in memory. */
    static final int PER_PAGE = 7;
    /** A bound on what a theme can list, so a broken file can't run away with the TV's storage or time. */
    static final int MAX_ITEMS = 140;

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
        /** Which page it is on, from 0. */
        final int page;
        /** Centre of the figure across the screen, and where its bottom edge sits down it, both 0 to 1. */
        final float x, bottom;
        /** Height of the figure as a share of the screen's height. */
        final float height;
        /** What OK opens: the app, a link it understands, and optionally the exact activity, intent flags and extras. */
        final String packageName, uri, component;
        final int flags;
        final Map<String, String> extras;
        /** The app's name, shown when the theme has no logo for it. */
        final String appName;
        /** The small moment played when it is selected (watchlist_effects), or null for none. */
        final String effect;
        /** Where the head is in the figure picture: its centre across and down, and its height, all 0 to 1. */
        final float headX, headY, headSize;
        /** What names the title for watchlist_remove: the theme's key ("movie-138843"), or "title:" and its title. */
        final String key;

        Item(String title, File image, File glow, File scene, int color, int row, int page, float x, float bottom, float height,
             String packageName, String uri, String component, int flags, Map<String, String> extras, String appName) {
            this(title, image, glow, scene, color, row, page, x, bottom, height, packageName, uri, component, flags, extras,
                    appName, null, null, null);
        }

        Item(String title, File image, File glow, File scene, int color, int row, int page, float x, float bottom, float height,
             String packageName, String uri, String component, int flags, Map<String, String> extras, String appName,
             String effect, float[] head, String key) {
            this.title = title == null ? "" : title;
            this.image = image;
            this.glow = glow;
            this.scene = scene;
            this.color = color;
            this.row = row == BACK ? BACK : FRONT;
            this.page = Math.max(0, page);
            this.x = clamp(x, 0f, 1f);
            this.bottom = clamp(bottom, 0.2f, 1.6f);
            this.height = clamp(height, 0.1f, 1f);
            this.packageName = packageName == null ? "" : packageName;
            this.uri = uri == null ? "" : uri;
            this.component = component;
            this.flags = flags;
            this.extras = extras == null ? Collections.emptyMap() : Collections.unmodifiableMap(extras);
            this.appName = appName == null ? "" : appName;
            this.effect = WatchlistEffect.known(effect);
            // Without a head from the computer: a guess near the top, as most cut-outs stand.
            boolean hasHead = head != null && head.length >= 3;
            this.headX = hasHead ? clamp(head[0], 0f, 1f) : 0.5f;
            this.headY = hasHead ? clamp(head[1], 0f, 1f) : 0.12f;
            this.headSize = hasHead ? clamp(head[2], 0.02f, 0.6f) : 0.14f;
            this.key = WatchlistRemovals.keyOf(key, this.title);
        }
    }

    private Watchlist() {}

    static float clamp(float v, float min, float max) {
        return Float.isNaN(v) ? min : Math.max(min, Math.min(max, v));
    }

    /** Every item with a picture, in the theme's order, at most MAX_ITEMS. */
    static List<Item> usable(List<Item> items) {
        List<Item> out = new ArrayList<>();
        for (Item i : items) {
            if (i.image != null && out.size() < MAX_ITEMS) out.add(i);
        }
        return out;
    }

    /** One more than the highest page any item is on (0 for none). */
    static int pageCount(List<Item> items) {
        int n = 0;
        for (Item i : items) n = Math.max(n, i.page + 1);
        return n;
    }

    /** Indexes of a page's items, left to right across both rows. */
    static List<Integer> onPage(List<Item> items, int page) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) if (items.get(i).page == page) out.add(i);
        Collections.sort(out, (a, b) -> Float.compare(items.get(a).x, items.get(b).x));
        return out;
    }

    /**
     * Where Down from the dock lands: always the first character (leftmost) of the first page that has one, whichever
     * dock app was focused and whichever character was selected last time. -1 when there are none.
     */
    static int entry(List<Item> items) {
        for (int p = 0; p < pageCount(items); p++) {
            List<Integer> here = onPage(items, p);
            if (!here.isEmpty()) return here.get(0);
        }
        return -1;
    }

    /**
     * Result of a move: the new selection, EXIT for "back up to the dock", or BELOW for "down to what is under the lineup"
     * (the mood deck, when there is one). Unchanged means the key did nothing.
     */
    static final int EXIT = -2, BELOW = -3;

    enum Direction { LEFT, RIGHT, UP, DOWN }

    /**
     * Left and Right go to the next character across the screen, whichever row it is in; past the page's last one Right
     * goes on to the next page's first, and Left before the first back to the previous page's last. Up goes back to the
     * dock; Down goes below, from any page.
     */
    static int move(List<Item> items, int selected, Direction direction) {
        if (direction == Direction.UP) return EXIT;
        if (selected < 0 || selected >= items.size()) return items.isEmpty() ? EXIT : entry(items);
        if (direction == Direction.DOWN) return BELOW;
        int page = items.get(selected).page;
        List<Integer> here = onPage(items, page);
        int at = here.indexOf(selected);
        if (direction == Direction.RIGHT) {
            if (at < here.size() - 1) return here.get(at + 1);
            for (int p = page + 1; p < pageCount(items); p++) {
                List<Integer> next = onPage(items, p);
                if (!next.isEmpty()) return next.get(0);
            }
            return selected;
        }
        if (at > 0) return here.get(at - 1);
        for (int p = page - 1; p >= 0; p--) {
            List<Integer> previous = onPage(items, p);
            if (!previous.isEmpty()) return previous.get(previous.size() - 1);
        }
        return selected;
    }

    /**
     * watchlist_remove: who is selected after `gone` is taken off: the next one across its page, else the one before
     * it, else the first on a later page, else the last on an earlier one; -1 when it was the only one. An index into
     * the list as it is before the removal.
     */
    static int afterRemoving(List<Item> items, int gone) {
        int page = items.get(gone).page;
        List<Integer> here = onPage(items, page);
        int at = here.indexOf(gone);
        if (at + 1 < here.size()) return here.get(at + 1);
        if (at > 0) return here.get(at - 1);
        for (int p = page + 1; p < pageCount(items); p++) {
            List<Integer> next = onPage(items, p);
            if (!next.isEmpty()) return next.get(0);
        }
        for (int p = page - 1; p >= 0; p--) {
            List<Integer> previous = onPage(items, p);
            if (!previous.isEmpty()) return previous.get(previous.size() - 1);
        }
        return -1;
    }

    /** The order a page's figures are drawn in: back row, then front row, each left to right, the selected one last. */
    static List<Integer> drawOrder(List<Item> items, int page, int selected) {
        List<Integer> back = new ArrayList<>(), front = new ArrayList<>();
        for (int i : onPage(items, page)) (items.get(i).row == BACK ? back : front).add(i);
        back.addAll(front);
        if (back.remove(Integer.valueOf(selected))) back.add(selected);
        return back;
    }

    /** Pages whose pictures are kept: the one on screen and the next (the previous one only while sliding back to it). */
    static boolean keeps(int page, int current, int target) {
        return page == current || page == current + 1 || page == target;
    }
}
