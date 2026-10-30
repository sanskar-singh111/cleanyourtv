package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.function.Function;

/**
 * watchlist_remove: titles taken off the watchlist on the TV, by holding OK on a character. Each one goes into
 * {@link #FILE_NAME} in the theme folder, which a tool on the computer (TV Studio) reads through ThemeProvider, removes
 * from the person's list, and clears. The TV never holds a login for that list. Until the next theme push leaves the
 * title out of watchlist_items, Home also keeps it in its own preferences under the theme revision it was removed at,
 * so a restart after the file was cleared doesn't bring the title back; a push (new revision) forgets them.
 */
final class WatchlistRemovals {
    static final String FILE_NAME = "watchlist_removed.json";
    private static final String TAG = "WatchlistRemovals";
    private static final String PREFS = "watchlist_removed", REVISION = "revision", KEYS = "keys";
    /** The hold, in seconds, a theme can ask for (watchlist_remove_hold). */
    static final int HOLD_MIN_S = 1, HOLD_MAX_S = 30;
    /** A bound on the file, so a broken one can't run away with Home's time. */
    private static final int MAX_ENTRIES = 200;

    private WatchlistRemovals() {}

    /** What names a title: its key from the theme ("movie-138843"), or for a theme without keys its title. */
    static String keyOf(String key, String title) {
        if (key != null && !key.trim().isEmpty()) return key.trim();
        return "title:" + (title == null ? "" : title.trim());
    }

    /**
     * The titles hidden now: every one in the file, and those Home remembers from the same theme revision (TV Studio
     * may have cleared the file before the push that drops them).
     */
    static Set<String> hidden(Collection<String> inFile, String rememberedRevision, Collection<String> remembered,
                              String revision) {
        Set<String> out = new HashSet<>(inFile);
        if (rememberedRevision != null && rememberedRevision.equals(revision)) out.addAll(remembered);
        return out;
    }

    /** The list without the hidden titles, in the same order. */
    static <T> List<T> without(List<T> items, Function<T, String> key, Set<String> hidden) {
        List<T> out = new ArrayList<>();
        for (T t : items) if (!hidden.contains(key.apply(t))) out.add(t);
        return out;
    }

    /** The mood deck's moods with the hidden titles (by position in `titles`) taken out; a mood left empty goes. */
    static List<MoodDeck.Mood> without(List<MoodDeck.Mood> moods, List<MoodDeck.Title> titles, Set<String> hidden) {
        List<MoodDeck.Mood> out = new ArrayList<>();
        for (MoodDeck.Mood m : moods) {
            List<Integer> kept = new ArrayList<>();
            for (int i : m.titles) {
                MoodDeck.Title t = titles.get(i);
                if (!hidden.contains(keyOf(t.key, t.title))) kept.add(i);
            }
            if (!kept.isEmpty()) out.add(new MoodDeck.Mood(m.name, m.color, kept));
        }
        return out;
    }

    // ---- on the TV ------------------------------------------------------------------------------------------------

    /** Everything hidden on this TV now (file and remembered), for the theme folder `dir`. */
    static Set<String> load(Context ctx, File dir) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String revision = Config.themeRevision(dir);
        Set<String> remembered = p.getStringSet(KEYS, new HashSet<>());
        String rememberedRevision = p.getString(REVISION, null);
        if (rememberedRevision != null && !rememberedRevision.equals(revision)) {
            // A theme push since: the titles it still lists are back (TV Studio left them in on purpose).
            p.edit().remove(REVISION).remove(KEYS).apply();
        }
        return hidden(keysIn(read(dir)), rememberedRevision, remembered, revision);
    }

    /** Records one removal: remembered at once, and added to the file for TV Studio on a background thread. */
    static void record(Context ctx, File dir, String key, String title, String packageName) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String revision = Config.themeRevision(dir);
        Set<String> keys = new LinkedHashSet<>();
        if (revision.equals(p.getString(REVISION, null))) keys.addAll(p.getStringSet(KEYS, new HashSet<>()));
        keys.add(key);
        p.edit().putString(REVISION, revision).putStringSet(KEYS, keys).apply();
        if (dir == null) return;
        new Thread(() -> append(dir, key, title, packageName), "watchlist-remove").start();
    }

    private static synchronized void append(File dir, String key, String title, String packageName) {
        try {
            JSONArray list = read(dir);
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o != null && key.equals(o.optString("key"))) return;
            }
            if (list.length() >= MAX_ENTRIES) return;
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
            iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            list.put(new JSONObject().put("key", key).put("title", title).put("package", packageName)
                    .put("removed_at", iso.format(new Date())));
            byte[] bytes = new JSONObject().put("removed", list).toString(1).getBytes(StandardCharsets.UTF_8);
            File tmp = new File(dir, FILE_NAME + ".tmp"), file = new File(dir, FILE_NAME);
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
            }
            if (!tmp.renameTo(file)) Log.w(TAG, "couldn't write " + file);
        } catch (Exception e) {
            Log.w(TAG, "couldn't record a removal", e);
        }
    }

    /** The file's "removed" list; empty when there is no file or it doesn't parse. */
    private static JSONArray read(File dir) {
        File file = dir == null ? null : new File(dir, FILE_NAME);
        if (file == null || !file.isFile() || file.length() > 256 * 1024) return new JSONArray();
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int n = 0;
            while (n < bytes.length) {
                int r = in.read(bytes, n, bytes.length - n);
                if (r < 0) break;
                n += r;
            }
            JSONArray a = new JSONObject(new String(bytes, 0, n, StandardCharsets.UTF_8)).optJSONArray("removed");
            return a == null ? new JSONArray() : a;
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static Set<String> keysIn(JSONArray list) {
        Set<String> out = new HashSet<>();
        for (int i = 0; i < list.length() && i < MAX_ENTRIES; i++) {
            JSONObject o = list.optJSONObject(i);
            if (o != null && !o.optString("key").isEmpty()) out.add(o.optString("key"));
        }
        return out;
    }
}
