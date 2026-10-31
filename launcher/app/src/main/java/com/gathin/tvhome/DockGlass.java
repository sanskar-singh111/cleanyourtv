package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * glass_intensity: the rounded panel behind the icon_dock. Nothing blurs on the TV: glass_strip is the wallpaper's bottom
 * as a computer blurred it, as wide as the screen and resting on its bottom edge. It is laid in the panel with a soft tint,
 * a thin bright edge and a highlight along the top, once, off the main thread, into one picture that a frame only has
 * to draw. TV Studio's preview (Glass.swift) uses the same numbers.
 */
final class DockGlass {
    /** How far the panel reaches past the icons, in dp, and how much the Keep watching row rises to keep clear of it. */
    static final float SIDE_DP = 18, EDGE_DP = 15, ROW_LIFT_DP = 8;
    /** The soft shadow under the panel: its blur and how far down it sits, in dp. The picture is this much bigger all round. */
    private static final float SHADOW_DP = 16, SHADOW_DROP_DP = 6, MARGIN_DP = 30;
    /** The panel's corners, as a share of the room above the icons on top of the icons' own. */
    private static final float CORNER = 1.4f;
    /** The edge's strength part-way down and at the bottom, as shares of its top. */
    private static final float EDGE_MIDDLE = 0.34f, EDGE_BOTTOM = 0.24f;
    private static final String TAG = "TvHome.DockGlass";

    private final ViewGroup scene;
    private final ImageView view;
    private final File strip;
    private final int intensity, transparency;
    private final float density;
    private final int screenW, screenH;
    private final ExecutorService baker = Executors.newSingleThreadExecutor();
    private final Rect placed = new Rect();

    /** Puts the empty panel under the dock in `frame`; `scene` is the full-screen view the strip lines up with. */
    DockGlass(Context context, ViewGroup frame, ViewGroup scene, File strip, int intensity, int transparency) {
        this.scene = scene;
        this.strip = strip;
        this.intensity = intensity;
        this.transparency = transparency;
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        density = metrics.density;
        screenW = metrics.widthPixels;
        screenH = metrics.heightPixels;
        view = new ImageView(context);
        view.setFocusable(false);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.setAlpha(0f);
        frame.addView(view, 0, new FrameLayout.LayoutParams(0, 0));
    }

    /** Where the row's first icon starts in a space `width` wide, for icons `icons` wide and a panel `side` past them. */
    static int rowStart(int width, int icons, int side, boolean centered) {
        return centered ? Math.max(side, (width - icons) / 2) : side;
    }

    /** A cool grey: on a dark foreground white would read as milk, not as glass. */
    private static final int TINT = 0xB4BCCC;

    /** The blur's alpha: the clearer the glass, the more of the sharp wallpaper shows through it (a third at 100). */
    static int blurAlpha(int transparency) {
        return Math.round(255 * (1f - 0.35f * Math.max(0, transparency - 50) / 50f));
    }

    /** The tint's alpha: dock_transparency is how clear the glass is, so the tint is the rest. */
    static int tintAlpha(int transparency) {
        return Math.round(255 * (100 - transparency) / 100f);
    }

    /** The edge's alpha along the top of the panel; it falls to EDGE_BOTTOM of this at the bottom. */
    static int edgeAlpha(int intensity) {
        return Math.round(255 * (0.18f + 0.30f * intensity / 100f));
    }

    /** The highlight's alpha at the very top. */
    static int highlightAlpha(int intensity) {
        return Math.round(255 * (0.01f + 0.05f * intensity / 100f));
    }

    /** The shadow's alpha. */
    static int shadowAlpha(int intensity) {
        return Math.round(255 * (0.12f + 0.28f * intensity / 100f));
    }

    /** The panel around the icons at (x, y), `icons` wide and `tall` high, in the dock's frame. */
    void place(int x, int y, int icons, int tall, float iconRadius) {
        int side = Math.round(SIDE_DP * density), edge = Math.round(EDGE_DP * density);
        int margin = Math.round(MARGIN_DP * density);
        Rect wanted = new Rect(x - side - margin, y - edge - margin, x + icons + side + margin, y + tall + edge + margin);
        if (wanted.equals(placed)) return;
        placed.set(wanted);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
        lp.width = wanted.width();
        lp.height = wanted.height();
        lp.leftMargin = wanted.left;
        lp.topMargin = wanted.top;
        float radius = iconRadius + edge * CORNER;
        view.post(() -> {
            view.setLayoutParams(lp);
            view.post(() -> {
                // Where the panel stands on the wallpaper, which is the strip's frame.
                Rect onScene = new Rect(0, 0, lp.width, lp.height);
                scene.offsetDescendantRectToMyCoords(view, onScene);
                baker.execute(() -> {
                    Bitmap baked = bake(lp.width, lp.height, margin, onScene.left, onScene.top, radius);
                    view.post(() -> show(baked));
                });
            });
        });
    }

    private void show(Bitmap baked) {
        if (baked == null) return;
        view.setImageBitmap(baked);
        view.animate().alpha(1f).setDuration(220).start();
    }

    /** The picture, w x h with the panel `margin` inside it; (left, top) is the picture's place on the wallpaper. */
    private Bitmap bake(int w, int h, int margin, int left, int top, float radius) {
        Bitmap in = null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
            in = BitmapFactory.decodeFile(strip.getPath(), options);
            if (in == null) return null;
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            RectF rect = new RectF(margin, margin, w - margin, h - margin);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            paint.setColor(shadowAlpha(intensity) << 24);
            paint.setMaskFilter(new BlurMaskFilter(SHADOW_DP * density, BlurMaskFilter.Blur.NORMAL));
            RectF under = new RectF(rect);
            under.offset(0, SHADOW_DROP_DP * density);
            canvas.drawRoundRect(under, radius, radius, paint);
            paint.setMaskFilter(null);
            // The shadow stays outside: the panel is see-through, and it would darken what shows through.
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setXfermode(null);
            float scale = (float) screenW / in.getWidth();
            BitmapShader shader = new BitmapShader(in, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix matrix = new Matrix();
            matrix.setScale(scale, scale);
            matrix.postTranslate(-left, screenH - in.getHeight() * scale - top);
            shader.setLocalMatrix(matrix);
            paint.setColor(blurAlpha(transparency) << 24 | 0xFFFFFF);
            paint.setShader(shader);
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setShader(null);
            paint.setColor(tintAlpha(transparency) << 24 | TINT);
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setColor(0xFFFFFFFF);
            paint.setShader(new LinearGradient(0, rect.top, 0, rect.top + rect.height() * 0.42f, highlightAlpha(intensity) << 24 | 0xFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, radius, radius, paint);
            // One real pixel, on the pixel grid: bright along the top, fainter along the bottom.
            int edge = edgeAlpha(intensity);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1f);
            paint.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                    new int[]{edge << 24 | 0xFFFFFF, Math.round(edge * EDGE_MIDDLE) << 24 | 0xFFFFFF, Math.round(edge * EDGE_BOTTOM) << 24 | 0xFFFFFF},
                    new float[]{0f, 0.3f, 1f}, Shader.TileMode.CLAMP));
            rect.inset(0.5f, 0.5f);
            canvas.drawRoundRect(rect, radius - 0.5f, radius - 0.5f, paint);
            return SceneLayers.onGpu(out);
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "no glass panel: " + e);
            return null;
        } finally {
            if (in != null) in.recycle();
        }
    }

    void close() {
        baker.shutdownNow();
    }
}
