package com.gathin.tvhome;

import java.util.List;

/** dock_taglines: the line shown above the dock for the focused app, the next of its lines on each return to Home. */
final class Taglines {
    private Taglines() {}

    /** The line for this visit to Home, or "" when the app has none. */
    static String pick(List<String> lines, int visit) {
        if (lines == null || lines.isEmpty()) return "";
        return lines.get(Math.floorMod(visit, lines.size()));
    }
}
