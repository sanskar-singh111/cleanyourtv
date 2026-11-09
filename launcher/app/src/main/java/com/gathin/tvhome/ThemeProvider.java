package com.gathin.tvhome;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Lets tools/theme.sh write the theme files into {@link Config#dir} on firmware where the adb shell is
 * locked out of Android/data (TCL G07, Android 11). Only the files {@link ThemeFiles} allows, and only for
 * callers holding WRITE_SECURE_SETTINGS, which the adb shell has and ordinary apps cannot get.
 *
 *   content write --uri content://com.gathin.tvhome.theme/config.json < /data/local/tmp/config.json
 *   content write --uri content://com.gathin.tvhome.theme/wallpapers/dusk.jpg < /data/local/tmp/dusk.jpg
 *   content delete --uri content://com.gathin.tvhome.theme/config.json
 *   content write --uri content://com.gathin.tvhome.theme/logos/netflix.png < /data/local/tmp/netflix.png
 *   content delete --uri content://com.gathin.tvhome.theme/wallpapers      (every image in the collection)
 *   content delete --uri content://com.gathin.tvhome.theme/logos           (every image in a theme folder)
 *   content write --uri content://com.gathin.tvhome.theme/fonts/clock.ttf < /data/local/tmp/clock.ttf
 *
 * A write never truncates the real file: it goes to a temporary file in the same folder, which is synced and renamed
 * into place only when the writer closes it cleanly ({@link ThemeWrites}). A write cut short leaves the old file whole.
 */
public class ThemeProvider extends ContentProvider {

    /** Waits for writes still being finished, so a read right after `content write` sees the new file. */
    private static final long WAIT_MS = 15000;

    private final ThemeWrites writes = new ThemeWrites();
    private Handler finisher;

    private synchronized Handler finisher() {
        if (finisher == null) {
            HandlerThread t = new HandlerThread("theme-writes");
            t.start();
            finisher = new Handler(t.getLooper());
        }
        return finisher;
    }

    private File file(Uri uri) throws FileNotFoundException {
        String path = ThemeFiles.path(uri.getPathSegments());
        File dir = Config.dir(getContext());
        if (dir == null || path == null) throw new FileNotFoundException(String.valueOf(uri));
        File f = new File(dir, path);
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new FileNotFoundException(String.valueOf(uri));
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = file(uri);
        writes.awaitDone(f, WAIT_MS);
        if (mode.contains("w")) {
            File tmp;
            try {
                tmp = ThemeWrites.temp(f);
            } catch (IOException e) {
                throw new FileNotFoundException(String.valueOf(uri));
            }
            writes.started(f);
            try {
                // The listener runs once the writer has closed its end: cleanly (no error), or not at all (its process
                // died, or it closed with an error), and only a clean close replaces the real file.
                return ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_WRITE_ONLY
                        | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE, finisher(), e -> {
                            ThemeWrites.finish(tmp, f, e);
                            writes.done(f);
                        });
            } catch (IOException | RuntimeException e) {
                tmp.delete();
                writes.done(f);
                throw new FileNotFoundException(String.valueOf(uri));
            }
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        File dir = Config.dir(getContext());
        if (dir != null && ThemeFiles.isCollection(uri.getPathSegments())) {
            String folder = uri.getPathSegments().get(0);
            File[] images = new File(dir, folder).listFiles((d, name) -> ThemeFiles.collectionImage(name)
                    || ThemeFiles.FONTS.equals(folder) && ThemeFiles.font(name));
            int n = 0;
            if (images != null) for (File f : images) if (f.delete()) n++;
            return n;
        }
        try {
            File f = file(uri);
            writes.awaitDone(f, WAIT_MS);
            return f.delete() ? 1 : 0;
        } catch (FileNotFoundException e) {
            return 0;
        }
    }

    /** Clears temporary files from writes that never finished, before any new write can start. */
    @Override public boolean onCreate() {
        ThemeWrites.clearLeftovers(Config.dir(getContext()));
        return true;
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
