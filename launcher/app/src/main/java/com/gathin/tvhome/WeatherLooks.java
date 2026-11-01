package com.gathin.tvhome;

import android.graphics.Color;

import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * weather_scene: what each kind of weather does to a scene wallpaper. A computer made every picture and wrote every number here;
 * the launcher only reads them, picks a look from the weather ({@link #look}) and moves the pictures (WeatherScene).
 */
final class WeatherLooks {
    static final String CLEAR = "clear", PARTLY = "partly", OVERCAST = "overcast", FOG = "fog", HAZE = "haze", DRIZZLE = "drizzle",
            RAIN = "rain", STORM = "storm", SNOW = "snow";
    /** Under this many metres the air is hazy, and from this PM2.5 (India's "poor") it's smog. */
    static final int HAZE_METRES = 5000, HAZE_PM25 = 90;

    static final class Look {
        final String id;
        /** Pictures, each null when the look has none: the sky, the glass in the digits and the dock's two. */
        File sky, clock, strip, rim, clouds;
        int cloudAlpha, cloudTint = Color.WHITE, cloudDrift;
        /** What the land is multiplied by, and its saturation in percent. */
        int land = Color.WHITE, landSaturation = 100;
        /** In percent. */
        int mist, haze;
        /** How many streaks, how strong, how fast and how big in percent, how many big soft ones close by; flakes; the flash in percent. */
        int rain, rainAlpha, rainSpeed = 100, rainSize = 100, rainClose, snow, flash;
        /** How much darker the wet trees go, in percent. */
        int wet;

        Look(String id) {
            this.id = id;
        }

        boolean moves() {
            return clouds != null || mist > 0 || rain > 0 || snow > 0 || flash > 0;
        }
    }

    final Map<String, Look> looks = new HashMap<>();
    /** The theme's choice of looks: what to show instead of the look the weather asks for. TV Studio works this out. */
    final Map<String, String> use = new HashMap<>();
    File mist, haze, edge, trees, streak;

    /** The look to show for the look the weather asks for. */
    String shown(String real) {
        String to = use.get(real);
        return to != null && (CLEAR.equals(to) || looks.containsKey(to)) ? to : real;
    }

    /** The look's id for a WMO weather code as Open-Meteo reports it, with visibility in metres and PM2.5, each -1 when not known. */
    static String look(int code, int metres, float pm25) {
        if (code == 45 || code == 48) return FOG;
        if (code >= 51 && code <= 57 || code == 61 || code == 80) return DRIZZLE;
        if (code >= 63 && code <= 67 || code == 81 || code == 82) return RAIN;
        if (code == 95 || code == 96 || code == 99) return STORM;
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return SNOW;
        // Rain, fog and snow say more about the sky than smog does; it only takes over from a sky that shows it.
        if (metres >= 0 && metres < HAZE_METRES || pm25 >= HAZE_PM25) return HAZE;
        if (code == 2) return PARTLY;
        if (code == 3) return OVERCAST;
        return CLEAR;
    }

    /** What weather_scene says, or null when it says nothing. */
    static WeatherLooks parse(JSONObject json, File dir) {
        JSONObject looks = json == null ? null : json.optJSONObject("looks");
        if (looks == null || looks.length() == 0) return null;
        WeatherLooks out = new WeatherLooks();
        JSONObject art = json.optJSONObject("art");
        if (art != null) {
            out.mist = Config.themeFile(dir, art.optString("mist", null));
            out.haze = Config.themeFile(dir, art.optString("haze", null));
            out.edge = Config.themeFile(dir, art.optString("edge", null));
            out.trees = Config.themeFile(dir, art.optString("trees", null));
            out.streak = Config.themeFile(dir, art.optString("streak", null));
        }
        for (Iterator<String> ids = looks.keys(); ids.hasNext(); ) {
            String id = ids.next();
            JSONObject o = looks.optJSONObject(id);
            if (o == null) continue;
            Look l = new Look(id);
            l.sky = Config.themeFile(dir, o.optString("sky", null));
            l.clock = Config.themeFile(dir, o.optString("clock", null));
            l.strip = Config.themeFile(dir, o.optString("strip", null));
            l.rim = Config.themeFile(dir, o.optString("rim", null));
            l.clouds = Config.themeFile(dir, o.optString("clouds", null));
            l.cloudAlpha = percent(o, "cloud_alpha", 0);
            l.cloudTint = color(o, "cloud_tint", Color.WHITE);
            l.cloudDrift = o.optInt("cloud_drift", 8);
            l.land = color(o, "land", Color.WHITE);
            l.landSaturation = percent(o, "land_saturation", 100);
            l.mist = percent(o, "mist", 0);
            l.haze = percent(o, "haze", 0);
            l.rain = Math.max(0, Math.min(WeatherSky.MAX_RAIN, o.optInt("rain", 0)));
            l.rainAlpha = percent(o, "rain_alpha", 0);
            l.rainSpeed = Math.max(50, Math.min(200, o.optInt("rain_speed", 100)));
            l.rainSize = Math.max(30, Math.min(200, o.optInt("rain_size", 100)));
            l.rainClose = Math.max(0, Math.min(WeatherSky.MAX_CLOSE, o.optInt("rain_close", 0)));
            l.wet = percent(o, "wet", 0);
            l.snow = Math.max(0, Math.min(WeatherSky.MAX_SNOW, o.optInt("snow", 0)));
            l.flash = percent(o, "flash", 0);
            out.looks.put(id, l);
        }
        JSONObject use = json.optJSONObject("use");
        if (use != null) {
            for (Iterator<String> ids = use.keys(); ids.hasNext(); ) {
                String id = ids.next();
                out.use.put(id, use.optString(id));
            }
        }
        return out.looks.isEmpty() ? null : out;
    }

    private static int percent(JSONObject o, String key, int fallback) {
        return Math.max(0, Math.min(100, o.optInt(key, fallback)));
    }

    private static int color(JSONObject o, String key, int fallback) {
        try {
            return o.has(key) ? Color.parseColor(o.getString(key)) : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}
