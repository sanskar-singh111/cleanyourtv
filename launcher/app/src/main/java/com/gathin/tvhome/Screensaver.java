package com.gathin.tvhome;

import java.util.Locale;

/** What Home shows once idle_fade_minutes has faded it (config.xml / theme "screensaver"). */
final class Screensaver {
    private Screensaver() { }

    /** Everything fades and the wallpaper stays: what idle_fade_minutes always did. */
    static final String WALLPAPER = "wallpaper";
    static final String CLOCK = "clock";
    static final String CLOCK_WEATHER = "clock_weather";
    static final String SLIDESHOW = "slideshow";
    /** The theme folder the slideshow's pictures come from. */
    static final String FOLDER = "screensaver";

    /** One of the modes above; anything else is the wallpaper alone. */
    static String mode(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return CLOCK.equals(v) || CLOCK_WEATHER.equals(v) || SLIDESHOW.equals(v) ? v : WALLPAPER;
    }

    /** True when the faded Home shows something over the wallpaper (a layer is needed). */
    static boolean draws(String mode, int dimPercent, int slideCount) {
        return dimPercent > 0 || CLOCK.equals(mode) || CLOCK_WEATHER.equals(mode)
                || SLIDESHOW.equals(mode) && slideCount > 0;
    }

    /** The slide after {@code index}, wrapping; 0 for the first. */
    static int next(int index, int count) {
        return count <= 0 ? 0 : (index + 1 + count) % count;
    }

    /** glow_strength: how much the built-in glow's alpha is multiplied by (-1 keeps it; 50 is the built-in look). */
    static float glowFactor(int strength) {
        return strength < 0 ? 1f : strength / 50f;
    }

    /** The glow's gradient colours (white with an alpha) with their alpha scaled, capped at opaque. */
    static int[] scaleAlpha(int[] colors, float factor) {
        int[] out = new int[colors.length];
        for (int i = 0; i < colors.length; i++) {
            int a = Math.min(255, Math.round((colors[i] >>> 24) * Math.max(0f, factor)));
            out[i] = (a << 24) | (colors[i] & 0xFFFFFF);
        }
        return out;
    }
}
