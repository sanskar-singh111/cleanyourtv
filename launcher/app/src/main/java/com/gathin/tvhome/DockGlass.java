package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
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
    static final float SIDE_DP = 14, EDGE_DP = 11, ROW_LIFT_DP = 6;
    private static final String TAG = "TvHome.DockGlass";

    private final ViewGroup scene;
    private final ImageView view;
    private final File strip;
    private final int intensity;
    private final float density;
    private final int screenW, screenH;
    private final ExecutorService baker = Executors.newSingleThreadExecutor();
    private final Rect placed = new Rect();

    /** Puts the empty panel under the dock in `frame`; `scene` is the full-screen view the strip lines up with. */
    DockGlass(Context context, ViewGroup frame, ViewGroup scene, File strip, int intensity) {
        this.scene = scene;
        this.strip = strip;
        this.intensity = intensity;
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

    /** The tint's alpha, from a whisper to a soft milky veil. */
    static int tintAlpha(int intensity) {
        return Math.round(255 * (0.04f + 0.10f * intensity / 100f));
    }

    /** The edge's alpha at the top of the panel; it fades to half of this at the bottom. */
    static int edgeAlpha(int intensity) {
        return Math.round(255 * (0.20f + 0.42f * intensity / 100f));
    }

    /** The highlight's alpha at the very top. */
    static int highlightAlpha(int intensity) {
        return Math.round(255 * (0.04f + 0.14f * intensity / 100f));
    }

    /** The panel around the icons at (x, y), `icons` wide and `tall` high, in the dock's frame. */
    void place(int x, int y, int icons, int tall, float iconRadius) {
        int side = Math.round(SIDE_DP * density), edge = Math.round(EDGE_DP * density);
        Rect wanted = new Rect(x - side, y - edge, x + icons + side, y + tall + edge);
        if (wanted.equals(placed)) return;
        placed.set(wanted);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
        lp.width = wanted.width();
        lp.height = wanted.height();
        lp.leftMargin = wanted.left;
        lp.topMargin = wanted.top;
        float radius = iconRadius + edge;
        view.post(() -> {
            view.setLayoutParams(lp);
            view.post(() -> {
                // Where the panel stands on the wallpaper, which is the strip's frame.
                Rect onScene = new Rect(0, 0, lp.width, lp.height);
                scene.offsetDescendantRectToMyCoords(view, onScene);
                baker.execute(() -> {
                    Bitmap baked = bake(lp.width, lp.height, onScene.left, onScene.top, radius);
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

    private Bitmap bake(int w, int h, int left, int top, float radius) {
        Bitmap in = null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
            in = BitmapFactory.decodeFile(strip.getPath(), options);
            if (in == null) return null;
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            RectF rect = new RectF(0, 0, w, h);
            float scale = (float) screenW / in.getWidth();
            BitmapShader shader = new BitmapShader(in, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix matrix = new Matrix();
            matrix.setScale(scale, scale);
            matrix.postTranslate(-left, screenH - in.getHeight() * scale - top);
            shader.setLocalMatrix(matrix);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            paint.setShader(shader);
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setShader(null);
            paint.setColor(tintAlpha(intensity) << 24 | 0xFFFFFF);
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setColor(0xFFFFFFFF);
            paint.setShader(new LinearGradient(0, 0, 0, h * 0.42f, highlightAlpha(intensity) << 24 | 0xFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, radius, radius, paint);
            int edge = edgeAlpha(intensity);
            float line = Math.max(1f, 0.75f * density), half = line / 2;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(line);
            paint.setShader(new LinearGradient(0, 0, 0, h, edge << 24 | 0xFFFFFF, (edge / 2) << 24 | 0xFFFFFF, Shader.TileMode.CLAMP));
            rect.inset(half, half);
            canvas.drawRoundRect(rect, radius - half, radius - half, paint);
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
