package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * A dock logo from the theme folder (dock_logos): a picture with a transparent background, shown floating instead of
 * the app's tile. Its transparent margins are cut off and its visual weight measured once, so a thin logo and a solid
 * one come out looking equally big ({@link #box}). A file that is missing, too big or not a picture gives null, and
 * the app keeps its ordinary tile.
 */
final class DockLogo {
    private static final String TAG = "TvHome.DockLogo";
    /** Decoded at most this many pixels on the long side: a dock logo is drawn about 300 px wide at the most. */
    private static final int MAX_SIDE = 640;
    /** The ink area a logo gets, as a share of its tile's area, at dock_logo_size 100. */
    static final float INK_SHARE = 0.15f;
    /**
     * How much a logo's emptiness is made up for. 1 would give every logo the same ink area, which makes thin, airy
     * logos (a star, a smile) look heavier than solid ones; 0.75 looked even across the five logos it was tuned on.
     */
    static final float WEIGHT_EXPONENT = 0.75f;
    /** The largest share of the tile a logo may fill across and down, so the focus grow stays inside its cell. */
    static final float MAX_W = 0.92f, MAX_H = 0.78f;

    final Bitmap bitmap;
    /** Share of the trimmed picture's area that is ink: the sum of its alpha over its pixel count, 0 to 1. */
    final float coverage;

    private DockLogo(Bitmap bitmap, float coverage) {
        this.bitmap = bitmap;
        this.coverage = coverage;
    }

    /** Loaded logos by path and file time, so a rebuild of the tiles does not decode them again. */
    private static final Map<String, DockLogo> CACHE = new HashMap<>();

    static DockLogo load(File file) {
        if (file == null) return null;
        String key = file.getPath() + "@" + file.lastModified() + ":" + file.length();
        synchronized (CACHE) {
            if (CACHE.containsKey(key)) return CACHE.get(key);
        }
        DockLogo logo = decode(file);
        synchronized (CACHE) {
            CACHE.put(key, logo);
        }
        return logo;
    }

    private static DockLogo decode(File file) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0 || (long) o.outWidth * o.outHeight > 16_000_000L) return null;
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap full = BitmapFactory.decodeFile(file.getPath(), o);
            if (full == null) return null;
            int w = full.getWidth(), h = full.getHeight();
            int[] px = new int[w * h];
            full.getPixels(px, 0, w, 0, 0, w, h);
            int[] bounds = inkBounds(px, w, h);
            if (bounds == null) {
                full.recycle();
                return null;   // fully transparent: nothing to show
            }
            int bw = bounds[2] - bounds[0], bh = bounds[3] - bounds[1];
            long alpha = 0;
            for (int y = bounds[1]; y < bounds[3]; y++) {
                for (int x = bounds[0]; x < bounds[2]; x++) alpha += px[y * w + x] >>> 24;
            }
            Bitmap trimmed = bw == w && bh == h ? full : Bitmap.createBitmap(full, bounds[0], bounds[1], bw, bh);
            if (trimmed != full) full.recycle();
            return new DockLogo(trimmed, alpha / (255f * bw * bh));
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "skipping logo " + file.getName() + ": " + e);
            return null;
        }
    }

    /** {left, top, right, bottom} (right and bottom exclusive) of the pixels that are not almost transparent, or null. */
    static int[] inkBounds(int[] argb, int w, int h) {
        int left = w, top = h, right = -1, bottom = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((argb[y * w + x] >>> 24) <= 8) continue;
                if (x < left) left = x;
                if (x > right) right = x;
                if (y < top) top = y;
                if (y > bottom) bottom = y;
            }
        }
        return right < 0 ? null : new int[]{left, top, right + 1, bottom + 1};
    }

    /**
     * The size to draw a logo at in a tile of tileW x tileH, as {width, height}: the ink area is about INK_SHARE of
     * the tile, made up for the logo's emptiness by WEIGHT_EXPONENT, then shrunk (never stretched) to fit MAX_W and
     * MAX_H. The aspect ratio always stays the logo's own.
     */
    static float[] box(float coverage, float aspect, float tileW, float tileH, int sizePercent) {
        float c = Math.max(0.02f, Math.min(1f, coverage));
        float scale = sizePercent / 100f;
        float area = tileW * tileH * INK_SHARE * scale * scale / (float) Math.pow(c, WEIGHT_EXPONENT);
        float w = (float) Math.sqrt(area * aspect), h = w / aspect;
        float fit = Math.min(1f, Math.min(tileW * MAX_W / w, tileH * MAX_H / h));
        return new float[]{w * fit, h * fit};
    }
}
