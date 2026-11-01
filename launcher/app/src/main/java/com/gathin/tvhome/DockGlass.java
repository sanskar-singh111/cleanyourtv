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
import android.graphics.Outline;
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
import android.view.ViewOutlineProvider;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * glass_intensity: the rounded panel behind the icon_dock. Nothing blurs on the TV: glass_strip is the wallpaper's bottom
 * as a computer blurred it, as wide as the screen and resting on its bottom edge. It is laid in the panel with a soft tint,
 * a thin bright edge and a highlight along the top, once, off the main thread, into one picture that a frame only has
 * to draw. glass_rim, made on a computer too, bends the wallpaper at the rounded edges; glass_shine adds a gloss over the top
 * half and brightens the edge. TV Studio's preview (Glass.swift, Look.swift) uses the same numbers.
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
    /** The gloss fades to this share of its strength at GLOSS_BODY of the panel's height, and is gone at GLOSS_END. */
    private static final float GLOSS_MIDDLE = 0.36f, GLOSS_BODY = 0.34f, GLOSS_END = 0.52f;
    /** glass_sweep: from one start to the next, how long the light takes to cross, and how wide and slanted it is. */
    private static final int SWEEP_EVERY_MS = 8000, SWEEP_MS = 1800;
    private static final float SWEEP_WIDE = 2.4f, SWEEP_SLANT = 0.3f;
    private static final String TAG = "TvHome.DockGlass";

    private final ViewGroup scene;
    private final ImageView view;
    private File strip, rim;
    /** weather_scene: another look's panel, fading in over this one, and where the picture stands. */
    private final ImageView next;
    private int bakedW, bakedH, bakedMargin, bakedLeft, bakedTop;
    private float bakedRadius;
    /** How far the look's change has come; the new picture when it is ready. */
    private float nextFade;
    private Bitmap nextBaked;
    private final int intensity, transparency, shine;
    private final float density;
    private final int screenW, screenH;
    private final ExecutorService baker = Executors.newSingleThreadExecutor();
    private final Rect placed = new Rect();
    /** glass_sweep: the light in a box clipped to the panel; null without the switch. */
    private final FrameLayout lightBox;
    private final ImageView light;
    private boolean sweeping;
    private final Runnable sweepNext = this::crossOnce;
    /** wake_animation: how much of the panel shows; the look's crossfade works under it. */
    private float wakeAlpha = 1f;

    /** Puts the empty panel under the dock in `frame`; `scene` is the full-screen view the strip lines up with. */
    DockGlass(Context context, ViewGroup frame, ViewGroup scene, File strip, File rim, int intensity, int transparency, int shine, boolean sweep) {
        this.scene = scene;
        this.strip = strip;
        this.rim = rim;
        this.shine = shine;
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
        next = new ImageView(context);
        next.setFocusable(false);
        next.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        next.setVisibility(View.GONE);
        frame.addView(next, 1, new FrameLayout.LayoutParams(0, 0));
        lightBox = sweep ? new FrameLayout(context) : null;
        light = sweep ? new ImageView(context) : null;
        if (sweep) {
            lightBox.setFocusable(false);
            lightBox.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            lightBox.setClipToOutline(true);
            light.setVisibility(View.GONE);
            lightBox.addView(light, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            frame.addView(lightBox, new FrameLayout.LayoutParams(0, 0));
        }
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
    static int edgeAlpha(int intensity, int shine) {
        return Math.round(255 * (0.18f + 0.30f * intensity / 100f + 0.32f * shine / 100f));
    }

    /** The gloss's alpha at the very top of the panel. */
    static int glossAlpha(int shine) {
        return Math.round(255 * 0.30f * shine / 100f);
    }

    /** The sweep's alpha at its middle: it has its own switch, so it still shows a little with no shine. */
    static int sweepAlpha(int shine) {
        return Math.round(255 * (0.10f + 0.24f * shine / 100f));
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
            if (lightBox != null) placeLight(wanted, margin, radius);
            view.post(() -> {
                // Where the panel stands on the wallpaper, which is the strip's frame.
                Rect onScene = new Rect(0, 0, lp.width, lp.height);
                scene.offsetDescendantRectToMyCoords(view, onScene);
                bakedW = lp.width;
                bakedH = lp.height;
                bakedMargin = margin;
                bakedLeft = onScene.left;
                bakedTop = onScene.top;
                bakedRadius = radius;
                File s = strip, r = rim;
                baker.execute(() -> {
                    Bitmap baked = bake(s, r, lp.width, lp.height, margin, onScene.left, onScene.top, radius);
                    view.post(() -> show(baked));
                });
            });
        });
    }

    private void placeLight(Rect picture, int margin, float radius) {
        FrameLayout.LayoutParams box = (FrameLayout.LayoutParams) lightBox.getLayoutParams();
        box.width = picture.width() - 2 * margin;
        box.height = picture.height() - 2 * margin;
        box.leftMargin = picture.left + margin;
        box.topMargin = picture.top + margin;
        lightBox.setLayoutParams(box);
        lightBox.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        int tall = box.height, wide = Math.round(tall * SWEEP_WIDE);
        baker.execute(() -> {
            Bitmap band = band(wide, tall);
            light.post(() -> light.setImageBitmap(band));
        });
    }

    /** The light: a soft slanted band, drawn once. */
    private Bitmap band(int wide, int tall) {
        Bitmap out = Bitmap.createBitmap(wide, tall, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        int middle = sweepAlpha(shine) << 24 | 0xFFFFFF;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        // The gradient's own direction slants the band, and it fades out before the picture's sides.
        paint.setShader(new LinearGradient(0, 0, wide, wide * SWEEP_SLANT, new int[]{0x00FFFFFF, middle, 0x00FFFFFF},
                new float[]{0.27f, 0.5f, 0.73f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, wide, tall, paint);
        return SceneLayers.onGpu(out);
    }

    /** glass_sweep on or off, for whether the dock is what's on screen. Nothing runs without the switch. */
    void sweep(boolean on) {
        if (light == null || on == sweeping) return;
        sweeping = on;
        view.removeCallbacks(sweepNext);
        light.animate().cancel();
        light.setVisibility(View.GONE);
        if (on) view.postDelayed(sweepNext, SWEEP_EVERY_MS - SWEEP_MS);
    }

    private void crossOnce() {
        if (!sweeping || light.getDrawable() == null) {
            if (sweeping) view.postDelayed(sweepNext, SWEEP_EVERY_MS);
            return;
        }
        float wide = light.getDrawable().getIntrinsicWidth();
        light.setTranslationX(-wide);
        light.setVisibility(View.VISIBLE);
        light.animate().translationX(lightBox.getWidth()).setDuration(SWEEP_MS).setInterpolator(new LinearInterpolator()).withEndAction(() -> {
            light.setVisibility(View.GONE);
            if (sweeping) view.postDelayed(sweepNext, SWEEP_EVERY_MS - SWEEP_MS);
        }).start();
    }

    private void show(Bitmap baked) {
        if (baked == null) return;
        view.setImageBitmap(baked);
        view.animate().alpha(wakeAlpha).setDuration(220).start();
    }

    /** weather_scene: the panel made from another look's pictures, baked off the main thread for glassFade to bring in. */
    void glass(File strip, File rim) {
        if (strip == null || strip.equals(this.strip) && rim != null && rim.equals(this.rim)) return;
        this.strip = strip;
        this.rim = rim;
        if (bakedW == 0) return;
        nextFade = 0;
        nextBaked = null;
        int w = bakedW, h = bakedH, margin = bakedMargin, left = bakedLeft, top = bakedTop;
        float radius = bakedRadius;
        baker.execute(() -> {
            Bitmap baked = bake(strip, rim, w, h, margin, left, top, radius);
            view.post(() -> {
                if (baked == null || strip != this.strip) return;
                nextBaked = baked;
                next.setLayoutParams(new FrameLayout.LayoutParams((FrameLayout.LayoutParams) view.getLayoutParams()));
                next.setImageBitmap(baked);
                next.setVisibility(View.VISIBLE);
                glassFade(nextFade);
            });
        });
    }

    /** How much of the new panel shows, 0 to 1; at 1 it takes the old one's place. */
    void glassFade(float amount) {
        nextFade = amount;
        if (nextBaked == null) return;
        next.setAlpha(amount * wakeAlpha);
        if (amount < 1f) return;
        view.setImageBitmap(nextBaked);
        view.setAlpha(wakeAlpha);
        nextBaked = null;
        next.setVisibility(View.GONE);
        next.setImageBitmap(null);
    }

    /** The picture, w x h with the panel `margin` inside it; (left, top) is the picture's place on the wallpaper. */
    private Bitmap bake(File strip, File rim, int w, int h, int margin, int left, int top, float radius) {
        Bitmap in = null, bent = null;
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
            bent = rim == null ? null : BitmapFactory.decodeFile(rim.getPath(), options);
            if (bent != null) canvas.drawBitmap(bent, null, rect, paint);
            paint.setColor(tintAlpha(transparency) << 24 | TINT);
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setColor(0xFFFFFFFF);
            paint.setShader(new LinearGradient(0, rect.top, 0, rect.top + rect.height() * 0.42f, highlightAlpha(intensity) << 24 | 0xFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, radius, radius, paint);
            if (shine > 0) {
                int gloss = glossAlpha(shine);
                paint.setShader(new LinearGradient(0, rect.top, 0, rect.top + rect.height() * GLOSS_END,
                        new int[]{gloss << 24 | 0xFFFFFF, Math.round(gloss * GLOSS_MIDDLE) << 24 | 0xFFFFFF, 0x00FFFFFF},
                        new float[]{0f, GLOSS_BODY / GLOSS_END, 1f}, Shader.TileMode.CLAMP));
                canvas.drawRoundRect(rect, radius, radius, paint);
            }
            // One real pixel, on the pixel grid: bright along the top, fainter along the bottom.
            int edge = Math.min(255, edgeAlpha(intensity, shine));
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
            if (bent != null) bent.recycle();
        }
    }

    /** wake_animation: the panel moves with the dock. A frame only changes properties of views already drawn. */
    void wake(float alpha, float y) {
        wakeAlpha = alpha;
        view.setAlpha(alpha);
        view.setTranslationY(y);
        next.setAlpha(nextFade * alpha);
        next.setTranslationY(y);
        if (lightBox != null) {
            lightBox.setAlpha(alpha);
            lightBox.setTranslationY(y);
        }
    }

    void close() {
        sweep(false);
        baker.shutdownNow();
    }
}
