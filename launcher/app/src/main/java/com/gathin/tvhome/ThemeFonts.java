package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * fonts: which font a theme uses where. Each place names a font bundled here or a .ttf or .otf in the theme's fonts/ folder,
 * read when Home starts, with the weight and width a variable font should use. A file that is missing, not allowed or broken
 * only means that place keeps the font it would have had: Home must never fail over a font. Nothing here runs without the key.
 */
final class ThemeFonts {
    static final String CLOCK = "clock", LINE = "line", LABELS = "labels", TAGLINES = "taglines";
    private static final String[] PLACES = {CLOCK, LINE, LABELS, TAGLINES};
    static final String INTER = "inter", ROBOTO_FLEX = "roboto_flex";
    /** A file past this isn't read at all: nothing a TV should map for a clock. TV Studio warns long before it. */
    static final long MAX_BYTES = 32L * 1024 * 1024;
    private static final String TAG = "TvHome.Fonts";

    /** What a place uses: `file`, or `bundled`, with the variable font's axes. */
    private static final class Choice {
        final File file;
        final String bundled;
        final Map<String, Float> axes;

        Choice(File file, String bundled, Map<String, Float> axes) {
            this.file = file;
            this.bundled = bundled;
            this.axes = axes;
        }
    }

    private final Map<String, Choice> choices = new HashMap<>();
    private final Map<String, Typeface> loaded = new HashMap<>();

    /** {"clock": {"file": "fonts/x.ttf", "axes": {"wght": 300, "wdth": 80}}, "line": {"bundled": "inter"}}. May be null. */
    ThemeFonts(JSONObject json, File dir) {
        if (json == null) return;
        for (String place : PLACES) {
            JSONObject o = json.optJSONObject(place);
            if (o == null) continue;
            String bundled = o.optString("bundled", "");
            if (INTER.equals(bundled) || ROBOTO_FLEX.equals(bundled)) {
                choices.put(place, new Choice(null, bundled, Collections.emptyMap()));
                continue;
            }
            File file = Config.fontFile(dir, o.optString("file", ""));
            if (file == null) {
                Log.w(TAG, "no font file for " + place);
                continue;
            }
            choices.put(place, new Choice(file, null, axes(o.optJSONObject("axes"))));
        }
    }

    private static Map<String, Float> axes(JSONObject o) {
        Map<String, Float> out = new TreeMap<>();
        if (o == null) return out;
        for (Iterator<String> keys = o.keys(); keys.hasNext(); ) {
            String tag = keys.next();
            double value = o.optDouble(tag, Double.NaN);
            if (tag.length() == 4 && !Double.isNaN(value)) out.put(tag, (float) value);
        }
        return out;
    }

    boolean isEmpty() {
        return choices.isEmpty();
    }

    /** True when this place has a font of its own that loads, so what is measured from the bundled font no longer holds. */
    boolean has(Context context, String place) {
        return get(context, place, null) != null;
    }

    /** The place's font, or `current` when it has none or it can't be read. */
    Typeface get(Context context, String place, Typeface current) {
        Choice choice = choices.get(place);
        if (choice == null) return current;
        if (!loaded.containsKey(place)) loaded.put(place, load(context, choice));
        Typeface font = loaded.get(place);
        return font != null ? font : current;
    }

    private static Typeface load(Context context, Choice choice) {
        try {
            if (choice.bundled != null) {
                return INTER.equals(choice.bundled) ? UiFont.get(context, R.string.font_inter_regular) : context.getResources().getFont(R.font.roboto_flex);
            }
            if (!usable(choice.file)) {
                Log.w(TAG, "not a font: " + choice.file.getName());
                return null;
            }
            Typeface.Builder builder = new Typeface.Builder(choice.file);
            if (!choice.axes.isEmpty()) builder.setFontVariationSettings(settings(choice.axes));
            return builder.build();
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "can't read " + choice.file + ": " + e);
            return null;
        }
    }

    /** "'wdth' 80, 'wght' 300", as Android wants them. */
    static String settings(Map<String, Float> axes) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Float> axis : axes.entrySet()) {
            parts.add(String.format(Locale.ROOT, "'%s' %s", axis.getKey(), number(axis.getValue())));
        }
        return String.join(", ", parts);
    }

    private static String number(float value) {
        return value == Math.rint(value) ? String.valueOf(Math.round(value)) : String.valueOf(value);
    }

    private static boolean usable(File file) throws IOException {
        long size = file.length();
        if (size > MAX_BYTES) return false;
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            byte[] head = new byte[(int) Math.min(size, 12 + 16 * 256)];
            in.readFully(head);
            return wellFormed(head, size);
        }
    }

    /**
     * The start of a .ttf or .otf: its kind, then a table directory whose every table lies inside the file. A truncated
     * file or anything else with the name is refused here, before Android's font code ever sees it.
     */
    static boolean wellFormed(byte[] head, long size) {
        if (head.length < 12 || size < 12) return false;
        int kind = word(head, 0);
        if (kind != 0x00010000 && kind != 0x4F54544F && kind != 0x74727565) return false;   // 1.0, OTTO, true
        int tables = (head[4] & 0xFF) << 8 | head[5] & 0xFF;
        if (tables == 0) return false;
        for (int i = 0; i < tables; i++) {
            int at = 12 + 16 * i;
            if (at + 16 > head.length) return size >= at && head.length == 12 + 16 * 256;
            long offset = word(head, at + 8) & 0xFFFFFFFFL, length = word(head, at + 12) & 0xFFFFFFFFL;
            if (offset + length > size) return false;
        }
        return true;
    }

    private static int word(byte[] b, int at) {
        return (b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | b[at + 3] & 0xFF;
    }
}
