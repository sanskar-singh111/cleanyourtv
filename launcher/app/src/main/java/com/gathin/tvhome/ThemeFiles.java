package com.gathin.tvhome;

import java.util.List;
import java.util.regex.Pattern;

/** The files ThemeProvider may write or delete under Config.dir, by the URI's path segments. */
final class ThemeFiles {
    static final String WALLPAPERS = "wallpapers";
    /** The names tools/theme.sh accepts for a collection image, and the types WallpaperRotation reads. */
    private static final Pattern IMAGE = Pattern.compile("[A-Za-z0-9_.-]+\\.(jpg|jpeg|png|webp)",
            Pattern.CASE_INSENSITIVE);
    /**
     * A theme's own picture folder, such as logos/ (dock_logos) or wallpaper/ (a "wallpaper" path): lower-case
     * letters, digits, - and _, so never "..", a hidden name or a file name.
     */
    private static final Pattern FOLDER = Pattern.compile("[a-z0-9_-]+");

    private ThemeFiles() {}

    /** The path relative to the theme directory, e.g. "config.json" or "wallpapers/dusk.jpg", or null if refused. */
    static String path(List<String> segments) {
        if (segments == null) return null;
        if (segments.size() == 1) {
            String name = segments.get(0);
            return Config.FILE_NAME.equals(name) || Config.WALLPAPER_NAME.equals(name)
                    || Config.WALLPAPER_PNG_NAME.equals(name) || "theme_revision".equals(name)
                    || WatchlistRemovals.FILE_NAME.equals(name) ? name : null;
        }
        if (segments.size() == 2 && WALLPAPERS.equals(segments.get(0))
                && (collectionImage(segments.get(1)) || WallpaperCaptions.FILE_NAME.equals(segments.get(1)))) {
            return WALLPAPERS + "/" + segments.get(1);
        }
        if (segments.size() == 2 && folder(segments.get(0)) && collectionImage(segments.get(1))) {
            return segments.get(0) + "/" + segments.get(1);
        }
        return null;
    }

    /** True for a folder name alone ("wallpapers", "logos"): a delete of it clears the pictures in it. */
    static boolean isCollection(List<String> segments) {
        return segments != null && segments.size() == 1 && folder(segments.get(0));
    }

    static boolean folder(String name) {
        return name != null && FOLDER.matcher(name).matches();
    }

    static boolean collectionImage(String name) {
        return name != null && !name.startsWith(".") && IMAGE.matcher(name).matches();
    }
}
