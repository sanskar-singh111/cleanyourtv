package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * keep_watching: what Android TV's Watch Next lists, read as docs/watch-next.md says: the whole table, with no
 * selection, filtered here. An app that publishes nothing simply isn't in the row.
 */
final class KeepWatching {
    private static final String TAG = "TvHome.KeepWatching";
    /** Cards kept: enough for a few screens of the row, each with a small picture in memory. */
    static final int MAX_ITEMS = 12;

    private static final String[] COLUMNS = {
            TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME, TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE, TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI, TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS};

    static final class Item {
        final String packageName, title, episode, picture, intentUri;
        final long engaged;
        /** The app's name, filled in by {@link #read}. */
        String app = "";

        Item(String packageName, String title, String episode, String picture, String intentUri, long engaged) {
            this.packageName = packageName;
            this.title = title == null ? "" : title;
            this.episode = episode == null ? "" : episode;
            this.picture = picture == null ? "" : picture;
            this.intentUri = intentUri == null ? "" : intentUri;
            this.engaged = engaged;
        }
    }

    private KeepWatching() {}

    /** The row, newest first; empty when the list is empty or can't be read (READ_TV_LISTINGS not granted, no provider). */
    static List<Item> read(Context context, Map<String, String> labels) {
        List<Item> found = new ArrayList<>();
        try (Cursor c = context.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, COLUMNS, null, null, null)) {
            if (c == null) return found;
            while (c.moveToNext()) {
                String poster = c.getString(5);
                found.add(new Item(c.getString(0), c.getString(1), episode(c.getString(3), c.getString(4), c.getString(2)),
                        poster == null || poster.isEmpty() ? c.getString(6) : poster, c.getString(7), c.getLong(8)));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "can't read Watch Next: " + e);
            return new ArrayList<>();
        }
        List<Item> rows = usable(found);
        PackageManager pm = context.getPackageManager();
        for (Item item : rows) item.app = appName(pm, item.packageName, labels);
        return rows;
    }

    private static String appName(PackageManager pm, String pkg, Map<String, String> labels) {
        if (labels.containsKey(pkg)) return labels.get(pkg);
        try {
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    /** "S1 E4 · Pilot", "S1 E4", "E4", or the episode's title alone; empty for a film. */
    static String episode(String season, String number, String title) {
        StringBuilder out = new StringBuilder();
        if (season != null && !season.isEmpty()) out.append('S').append(season);
        if (number != null && !number.isEmpty()) out.append(out.length() > 0 ? " E" : "E").append(number);
        if (title != null && !title.isEmpty()) out.append(out.length() > 0 ? " · " : "").append(title);
        return out.toString();
    }

    /** Newest first, one card per title of an app, only what can be opened, at most MAX_ITEMS. */
    static List<Item> usable(List<Item> rows) {
        List<Item> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparingLong((Item i) -> i.engaged).reversed());
        List<Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Item item : sorted) {
            if (item.packageName == null || item.packageName.isEmpty() || item.title.isEmpty() || item.intentUri.isEmpty()) continue;
            if (!seen.add(item.packageName + '\n' + item.title.toLowerCase(Locale.ROOT))) continue;
            out.add(item);
            if (out.size() == MAX_ITEMS) break;
        }
        return out;
    }

    /**
     * The intent the row's app published, kept to that app: whatever a row says, it can only open its own package,
     * and it gets no URI permissions from Home. Null when the link can't be read.
     */
    static Intent intent(Item item) {
        try {
            String uri = item.intentUri;
            Intent intent = uri.startsWith("intent:") || uri.contains("#Intent;")
                    ? Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) : new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            intent.setSelector(null);
            intent.removeFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            if (intent.getComponent() != null && !item.packageName.equals(intent.getComponent().getPackageName())) {
                intent.setComponent(null);
            }
            intent.setPackage(item.packageName);
            return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }
}
