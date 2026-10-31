package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * scene: the wallpaper as layers from the theme folder. Sky, clouds and the far mountains are painted into one picture
 * behind the clock, so a frame of the dock draws two layers and not four; the front layer goes over the clock. Decoded
 * off the main thread, and neither picture is redrawn while nothing moves.
 */
final class SceneLayers {
    static final String SKY = "sky", CLOUDS = "clouds", FAR = "far", FRONT = "front";
    private static final String TAG = "TvHome.Scene";

    private final FrameLayout root;
    private final ImageView back, front;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final int width, height;

    /** Puts the empty layers in `root` at `index`, with `between` (the clock, may be null) between them. */
    SceneLayers(Context context, FrameLayout root, int index, View between, Map<String, File> layers) {
        this.root = root;
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        back = layer(context);
        front = layer(context);
        root.addView(back, index, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        int next = index + 1;
        if (between != null) root.addView(between, next++);
        root.addView(front, next, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.BOTTOM));
        decoder.execute(() -> {
            Bitmap flat = flatten(layers);
            Bitmap top = onGpu(decode(layers.get(FRONT)));
            root.post(() -> show(flat, top));
        });
    }

    private static ImageView layer(Context context) {
        ImageView v = new ImageView(context);
        v.setScaleType(ImageView.ScaleType.FIT_XY);
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        v.setFocusable(false);
        v.setAlpha(0f);
        return v;
    }

    private void show(Bitmap flat, Bitmap top) {
        if (flat != null) {
            back.setImageBitmap(flat);
            // The picture covers the whole screen: the root's plain colour under it is overdraw.
            root.setBackground(null);
            back.animate().alpha(1f).setDuration(220).start();
        }
        if (top != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) front.getLayoutParams();
            lp.height = Math.round(top.getHeight() * (float) width / top.getWidth());
            front.setLayoutParams(lp);
            front.setImageBitmap(top);
            front.animate().alpha(1f).setDuration(220).start();
        }
    }

    /** Sky, clouds and far as one picture: the sky over the whole screen, clouds from the top, far on the bottom edge. */
    private Bitmap flatten(Map<String, File> layers) {
        Bitmap sky = decode(layers.get(SKY));
        Bitmap clouds = decode(layers.get(CLOUDS));
        Bitmap far = decode(layers.get(FAR));
        try {
            if (sky == null && clouds == null && far == null) return null;
            Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            if (sky != null) canvas.drawBitmap(sky, null, new RectF(0, 0, width, height), paint);
            if (clouds != null) canvas.drawBitmap(clouds, null, new RectF(0, 0, width, scaled(clouds)), paint);
            if (far != null) canvas.drawBitmap(far, null, new RectF(0, height - scaled(far), width, height), paint);
            return onGpu(out);
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "no room for the scene: " + e);
            return null;
        } finally {
            if (sky != null) sky.recycle();
            if (clouds != null) clouds.recycle();
            if (far != null) far.recycle();
        }
    }

    private float scaled(Bitmap layer) {
        return layer.getHeight() * (float) width / layer.getWidth();
    }

    private static Bitmap decode(File file) {
        if (file == null) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            if (!WallpaperSize.accepted(bounds.outWidth, bounds.outHeight)) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "skipping " + file.getName() + ": " + e);
            return null;
        }
    }

    /** One copy in GPU memory instead of a software bitmap plus its texture, as the wallpaper does. */
    static Bitmap onGpu(Bitmap bitmap) {
        if (bitmap == null) return null;
        Bitmap gpu = bitmap.copy(Bitmap.Config.HARDWARE, false);
        if (gpu == null) return bitmap;
        bitmap.recycle();
        return gpu;
    }

    void close() {
        decoder.shutdownNow();
    }
}
