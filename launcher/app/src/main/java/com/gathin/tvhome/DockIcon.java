package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;

import java.io.File;

/**
 * icon_dock: an app's picture for the dock as one square bitmap, the sharpest it has. A theme's own PNG comes first (the
 * dock_icons key), then the app's icon at the highest density it ships, full bleed for an adaptive icon. An icon too small
 * for the tile gives way to the app's banner, laid on its own edge colour. What fills the square is rounded by the tile.
 */
final class DockIcon {
    private static final String TAG = "TvHome.DockIcon";
    /** An icon under this share of the tile's size would look soft: the banner goes first. */
    static final float SOFT_BELOW = 0.75f;
    /** Alpha from which a pixel counts as the icon itself. */
    private static final int SOLID = 200;
    /** Android gives the largest icon it has up to this. */
    private static final int DENSITY = DisplayMetrics.DENSITY_XXXHIGH;

    private DockIcon() {}

    static Bitmap make(Context context, ResolveInfo ri, File own, int size) {
        try {
            Bitmap mine = own == null ? null : ownPicture(own, size);
            if (mine != null) return mine;
            PackageManager pm = context.getPackageManager();
            ActivityInfo a = ri.activityInfo;
            Resources res = pm.getResourcesForApplication(a.applicationInfo);
            Drawable icon = drawable(res, a.getIconResource());
            if (icon == null) icon = ri.loadIcon(pm);
            if (icon instanceof AdaptiveIconDrawable) return adaptive((AdaptiveIconDrawable) icon, size);
            int w = realWidth(res, a.getIconResource(), icon);
            if (w > 0 && w < size * SOFT_BELOW) {
                Bitmap banner = banner(res, a, size);
                if (banner != null) return banner;
            }
            return plain(icon, size);
        } catch (PackageManager.NameNotFoundException | RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "no icon for " + ri.activityInfo.packageName + ": " + e);
            return null;
        }
    }

    /** The icon's own pixels across: asked for at DENSITY, a small icon is scaled up and only looks big. */
    private static int realWidth(Resources res, int id, Drawable icon) {
        int w = Math.max(icon.getIntrinsicWidth(), icon.getIntrinsicHeight());
        try {
            TypedValue value = new TypedValue();
            res.getValueForDensity(id, DENSITY, value, true);
            if (value.density > 0 && value.density < 0xFFFF) return w * value.density / DENSITY;
        } catch (Resources.NotFoundException e) {
            // the size it reports is all there is
        }
        return w;
    }

    private static Drawable drawable(Resources res, int id) {
        if (id == 0) return null;
        try {
            return res.getDrawableForDensity(id, DENSITY, null);
        } catch (Resources.NotFoundException e) {
            return null;
        }
    }

    private static Bitmap ownPicture(File file, int size) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (Math.min(o.outWidth, o.outHeight) / (o.inSampleSize * 2) >= size) o.inSampleSize *= 2;
        Bitmap picture = BitmapFactory.decodeFile(file.getPath(), o);
        if (picture == null) return null;
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        float[] box = cover(picture.getWidth(), picture.getHeight(), size);
        new Canvas(out).drawBitmap(picture, null, new RectF(box[0], box[1], box[2], box[3]), new Paint(Paint.FILTER_BITMAP_FLAG));
        picture.recycle();
        return out;
    }

    /** {left, top, right, bottom} of a w x h picture covering a square of `size`, centred. */
    static float[] cover(int w, int h, int size) {
        float s = Math.max(size / (float) w, size / (float) h), dw = w * s, dh = h * s;
        return new float[]{(size - dw) / 2, (size - dh) / 2, (size + dw) / 2, (size + dh) / 2};
    }

    /** Both layers without the system's mask: 108 of an adaptive icon's size, of which the middle 72 are meant to show. */
    private static Bitmap adaptive(AdaptiveIconDrawable icon, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        int bleed = size / 4;
        for (Drawable layer : new Drawable[]{icon.getBackground(), icon.getForeground()}) {
            if (layer == null) continue;
            layer.setBounds(-bleed, -bleed, size + bleed, size + bleed);
            layer.draw(canvas);
        }
        return out;
    }

    /** An old-style icon: trimmed of its clear margin, on the colour at its own edge so the square is filled. */
    private static Bitmap plain(Drawable icon, int size) {
        Bitmap full = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(full);
        icon.setBounds(0, 0, size, size);
        icon.draw(canvas);
        int[] px = new int[size * size];
        full.getPixels(px, 0, size, 0, 0, size, size);
        int[] ink = solidBounds(px, size);
        if (ink == null) return full;
        if ((px[0] >>> 24) == 255 && (px[size - 1] >>> 24) == 255 && (px[px.length - 1] >>> 24) == 255) return full;
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(edge(px, size, ink));
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(full, new Rect(ink[0], ink[1], ink[2], ink[3]), new RectF(0, 0, size, size), paint);
        full.recycle();
        return out;
    }

    /** {left, top, right, bottom} of the icon's solid part: a soft shadow baked into it stays outside. */
    static int[] solidBounds(int[] argb, int size) {
        int left = size, top = size, right = -1, bottom = -1;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if ((argb[y * size + x] >>> 24) < SOLID) continue;
                left = Math.min(left, x);
                right = Math.max(right, x);
                top = Math.min(top, y);
                bottom = Math.max(bottom, y);
            }
        }
        return right < 0 ? null : new int[]{left, top, right + 1, bottom + 1};
    }

    private static int edge(int[] px, int size, int[] ink) {
        int mid = px[(ink[1] + ink[3]) / 2 * size + ink[0] + 1];
        return (mid >>> 24) < 128 ? Color.WHITE : mid | 0xFF000000;
    }

    /** The banner across the square, on the colour at its corner. */
    private static Bitmap banner(Resources res, ActivityInfo a, int size) {
        int id = a.banner != 0 ? a.banner : a.applicationInfo.banner;
        Drawable banner = drawable(res, id);
        if (banner == null) return null;
        int w = banner.getIntrinsicWidth(), h = banner.getIntrinsicHeight();
        if (w <= 0 || h <= 0) return null;
        Bitmap art = Bitmap.createBitmap(size, Math.max(1, size * h / w), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(art);
        banner.setBounds(0, 0, art.getWidth(), art.getHeight());
        banner.draw(c);
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        int corner = art.getPixel(1, 1);
        canvas.drawColor((corner >>> 24) < 128 ? 0xFF1C1C22 : corner | 0xFF000000);
        canvas.drawBitmap(art, 0, (size - art.getHeight()) / 2f, null);
        art.recycle();
        return out;
    }

    /** A soft white rounded square for behind a focused tile; drawn once, it is all blur so it scales freely. */
    static Bitmap glow(int tile, int pad, float radius) {
        int size = tile + 2 * pad;
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xB3FFFFFF);
        p.setMaskFilter(new BlurMaskFilter(pad * 0.62f, BlurMaskFilter.Blur.NORMAL));
        new Canvas(out).drawRoundRect(new RectF(pad, pad, size - pad, size - pad), radius, radius, p);
        return out;
    }
}
