package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The weather beside the clock (weather_city): the temperature and an outline icon from Open-Meteo, free and with no
 * key. The city is looked up once with Open-Meteo's geocoding and its coordinates kept; the weather is fetched only
 * while Home is in front ({@link #resume} to {@link #pause}), at most every 30 minutes, and the last reading is kept so
 * Home shows it at once. A failed request changes nothing on screen; a reading older than three hours is hidden. Only
 * the temperature and the icon are shown, never the place. With weather_scene it reads visibility too, and PM2.5 from Open-Meteo's
 * air quality API; a failure there leaves the rest of the reading as it is.
 */
final class Weather {
    interface Listener {
        /** A reading to show, or null to hide the weather. Called on the main thread. */
        void onWeather(Reading reading);
    }

    static final class Reading {
        final int celsius;
        final int icon;
        /** The WMO weather code, -1 when it isn't known. */
        final int code;
        /** Visibility in metres and PM2.5 in micrograms a cubic metre, -1 when not known. */
        final int metres;
        final float pm25;

        Reading(int celsius, int icon, int code) {
            this(celsius, icon, code, -1, -1);
        }

        Reading(int celsius, int icon, int code, int metres, float pm25) {
            this.celsius = celsius;
            this.icon = icon;
            this.code = code;
            this.metres = metres;
            this.pm25 = pm25;
        }
    }

    /** The line's word when the picture is the haze look: no weather code says it, it comes from visibility or PM2.5. */
    static final String HAZY = "Hazy";

    /** The condition in words for a WMO weather code as Open-Meteo reports it; empty when it isn't known. */
    static String condition(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mostly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code == 95) return "Thunderstorm";
        if (code == 96 || code == 99) return "Thunderstorm with hail";
        return "";
    }

    static final long REFRESH_MS = 30 * 60_000L;
    static final long STALE_MS = 3 * 60 * 60_000L;
    private static final String TAG = "TvHome.Weather";
    private static final String PREFS = "weather";

    private final String city;
    private final SharedPreferences prefs;
    private final Listener listener;
    private final boolean air;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private boolean active;
    private boolean fetching;
    private final Runnable tick = this::refresh;

    Weather(Context ctx, String city, Listener listener) {
        this(ctx, city, listener, false);
    }

    /** `air`: read visibility and PM2.5 too, for weather_scene. */
    Weather(Context ctx, String city, Listener listener, boolean air) {
        this.city = city;
        this.listener = listener;
        this.air = air;
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // Another city in the theme: the old place and reading belong to it.
        if (!city.equals(prefs.getString("city", ""))) prefs.edit().clear().putString("city", city).apply();
    }

    void resume() {
        active = true;
        show();
        refresh();
    }

    void pause() {
        active = false;
        main.removeCallbacks(tick);
    }

    void close() {
        pause();
        net.shutdownNow();
    }

    /** The kept reading, or nothing once it is too old to be the weather now. */
    private void show() {
        long at = prefs.getLong("at", 0);
        boolean fresh = at > 0 && System.currentTimeMillis() - at < STALE_MS;
        listener.onWeather(fresh ? new Reading(prefs.getInt("celsius", 0), prefs.getInt("icon", WeatherIcon.CLOUDY),
                prefs.getInt("code", -1), prefs.getInt("metres", -1), prefs.getFloat("pm25", -1)) : null);
    }

    /** Fetches when the kept reading is 30 minutes old, then checks again when the next one is due. */
    private void refresh() {
        main.removeCallbacks(tick);
        if (!active) return;
        long due = prefs.getLong("at", 0) + REFRESH_MS - System.currentTimeMillis();
        // A reading kept from before the condition was read is fetched again: the big clock's line needs it.
        if (due > 0 && prefs.contains("code") && (!air || prefs.contains("pm25"))) {
            main.postDelayed(tick, due);
            return;
        }
        // A failure waits ten minutes before the next try instead of hammering a network that is down.
        main.postDelayed(tick, 10 * 60_000L);
        if (fetching) return;
        fetching = true;
        net.execute(() -> {
            Reading reading = null;
            try {
                reading = fetch();
            } catch (Exception e) {
                Log.w(TAG, "no weather: " + e);
            }
            Reading result = reading;
            main.post(() -> {
                fetching = false;
                if (result == null) return;
                prefs.edit().putInt("celsius", result.celsius).putInt("icon", result.icon).putInt("code", result.code)
                        .putInt("metres", result.metres).putFloat("pm25", result.pm25).putLong("at", System.currentTimeMillis()).apply();
                if (active) {
                    show();
                    refresh();
                }
            });
        });
    }

    private Reading fetch() throws Exception {
        String lat = prefs.getString("lat", null), lon = prefs.getString("lon", null);
        if (lat == null || lon == null) {
            String name = city.split(",")[0].trim();
            double[] place = place(get("https://geocoding-api.open-meteo.com/v1/search?count=10&language=en&format=json&name="
                    + URLEncoder.encode(name, "UTF-8")), city);
            if (place == null) {
                Log.w(TAG, "no place found for weather_city");
                return null;
            }
            lat = String.format(Locale.US, "%.4f", place[0]);
            lon = String.format(Locale.US, "%.4f", place[1]);
            prefs.edit().putString("lat", lat).putString("lon", lon).apply();
        }
        Reading now = current(get("https://api.open-meteo.com/v1/forecast?current=temperature_2m,weather_code,is_day"
                + (air ? ",visibility" : "") + "&temperature_unit=celsius&latitude=" + lat + "&longitude=" + lon));
        if (!air || now == null) return now;
        float pm25 = -1;
        try {
            pm25 = pm25(get("https://air-quality-api.open-meteo.com/v1/air-quality?current=pm2_5&latitude=" + lat + "&longitude=" + lon));
        } catch (Exception e) {
            Log.w(TAG, "no air quality: " + e);
        }
        return new Reading(now.celsius, now.icon, now.code, now.metres, pm25);
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(10_000);
        try (InputStream in = c.getInputStream()) {
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0 && out.size() < 256 * 1024; ) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            c.disconnect();
        }
    }

    /**
     * {latitude, longitude} of the geocoding result that best matches "New Delhi, Delhi, India": every part after
     * the first must appear in the result's region or country, else the first result; null when there is none.
     */
    static double[] place(String json, String city) throws Exception {
        JSONArray results = new JSONObject(json).optJSONArray("results");
        if (results == null || results.length() == 0) return null;
        String[] parts = city.split(",");
        JSONObject best = results.optJSONObject(0);
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null) continue;
            String where = (r.optString("admin1", "") + " " + r.optString("admin2", "") + " " + r.optString("country", "")
                    + " " + r.optString("country_code", "")).toLowerCase(Locale.ROOT);
            boolean all = true;
            for (int p = 1; p < parts.length; p++) {
                if (!where.contains(parts[p].trim().toLowerCase(Locale.ROOT))) all = false;
            }
            if (all) {
                best = r;
                break;
            }
        }
        if (best == null || !best.has("latitude") || !best.has("longitude")) return null;
        return new double[]{best.getDouble("latitude"), best.getDouble("longitude")};
    }

    /** The reading in a forecast's "current" block, or null when it has no temperature. */
    static Reading current(String json) throws Exception {
        JSONObject now = new JSONObject(json).optJSONObject("current");
        if (now == null || !now.has("temperature_2m")) return null;
        int celsius = (int) Math.round(now.getDouble("temperature_2m"));
        int code = now.optInt("weather_code", 3);
        return new Reading(celsius, WeatherIcon.forCode(code, now.optInt("is_day", 1) == 1), code, (int) Math.round(now.optDouble("visibility", -1)), -1);
    }

    /** PM2.5 in an air quality answer's "current" block, -1 when it has none. */
    static float pm25(String json) throws Exception {
        JSONObject now = new JSONObject(json).optJSONObject("current");
        return now == null ? -1 : (float) now.optDouble("pm2_5", -1);
    }
}
