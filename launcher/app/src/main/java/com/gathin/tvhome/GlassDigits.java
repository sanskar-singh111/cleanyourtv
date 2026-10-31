package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.text.TextPaint;
import android.widget.TextClock;

import java.io.File;

/**
 * big_clock_glass: the clock's digits as thick glass. A computer made the picture (the sky behind the digits shifted, blurred
 * and brightened), so all this does is clip it to the digit shapes, then lay a thin bright edge along the top left of each
 * stroke and a soft darker one along the bottom. It sits in a hardware layer, so this runs when the minute changes and a frame
 * only draws one texture. Glass only reads as glass when what is seen through it is mostly the bent copy, so big_clock_opacity
 * doesn't thin the picture: it is how milky the digits are, from clear to nearly white. glass_shine is the edges. TV Studio's
 * Look.swift has the same numbers.
 */
final class GlassDigits extends TextClock {
    /** How wide the edges are, in dp, and how soft the dark one's inner side is. */
    private static final float BRIGHT_DP = 1.25f, DARK_DP = 2.25f, DARK_SOFT_DP = 2f;
    /** The wider soft light inside the bright edge: how far it reaches and how soft it is, in dp. */
    private static final float GLOW_DP = 4f, GLOW_SOFT_DP = 4f;
    /** How much of the bent picture covers the sharp sky: a little of the sharp one still shows, as through real glass. */
    private static final float FILL = 0.85f;

    private final int shine;
    private final float density;
    private final int screenW;
    private final int shadowColor;
    private final float shadowDp;
    private int opacity;
    private Bitmap glass;
    private TextPaint fill, bright, dark, shadow, cut, softCut, wideCut;
    private final Matrix matrix = new Matrix();

    GlassDigits(Context context, int shine, int shadowColor, float shadowDp) {
        super(context);
        this.shine = shine;
        this.shadowColor = shadowColor;
        this.shadowDp = shadowDp;
        density = context.getResources().getDisplayMetrics().density;
        screenW = context.getResources().getDisplayMetrics().widthPixels;
    }

    /** How much white lies over the glass at this big_clock_opacity: a tenth at 30, nearly solid at 100. */
    static int veilAlpha(int opacityPercent) {
        return Math.round(255 * (0.10f + 0.75f * (float) Math.pow((opacityPercent - 30) / 70f, 1.5)));
    }

    /** The soft light's alpha. */
    static int glowAlpha(int shine) {
        return Math.round(255 * 0.34f * shine / 100f);
    }

    /** The bright edge's alpha: glass_shine is how much of it there is. */
    static int brightAlpha(int shine) {
        return Math.round(255 * 0.80f * shine / 100f);
    }

    /** The dark edge's alpha. */
    static int darkAlpha(int shine) {
        return Math.round(255 * 0.36f * shine / 100f);
    }

    /** Decodes the picture off the main thread; the digits appear once it is there, or plain if it can't be read. */
    void load(File picture, int opacityPercent) {
        opacity = opacityPercent;
        setAlpha(0f);
        new Thread(() -> {
            Bitmap decoded = SceneLayers.onGpu(SceneLayers.decode(picture));
            post(() -> {
                glass = decoded;
                invalidate();
                animate().alpha(1f).setDuration(220).start();
            });
        }, "clock-glass").start();
    }

    private void makePaints() {
        fill = new TextPaint(getPaint());
        fill.clearShadowLayer();
        fill.setShader(new BitmapShader(glass, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        bright = plain(Color.WHITE);
        dark = plain(Color.BLACK);
        shadow = plain(shadowColor);
        shadow.setMaskFilter(new BlurMaskFilter(shadowDp * density, BlurMaskFilter.Blur.NORMAL));
        cut = plain(Color.BLACK);
        cut.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        softCut = new TextPaint(cut);
        softCut.setMaskFilter(new BlurMaskFilter(DARK_SOFT_DP * density, BlurMaskFilter.Blur.NORMAL));
        wideCut = new TextPaint(cut);
        wideCut.setMaskFilter(new BlurMaskFilter(GLOW_SOFT_DP * density, BlurMaskFilter.Blur.NORMAL));
    }

    private TextPaint plain(int color) {
        TextPaint p = new TextPaint(getPaint());
        p.clearShadowLayer();
        p.setShader(null);
        p.setColor(color);
        return p;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (glass == null) {
            super.onDraw(canvas);
            return;
        }
        if (fill == null) makePaints();
        String time = getText().toString();
        float x = getCompoundPaddingLeft() + getLayout().getLineLeft(0), y = getBaseline();
        int w = getWidth(), h = getHeight();

        // The shadow stays outside the digits: they are see-through, and it would darken what shows through them.
        int layer = canvas.saveLayer(0, 0, w, h, null);
        canvas.drawText(time, x, y + density, shadow);
        canvas.drawText(time, x, y, cut);
        canvas.restoreToCount(layer);

        // The picture covers the screen; this view's parent does too.
        float scale = (float) screenW / glass.getWidth();
        matrix.setScale(scale, scale);
        matrix.postTranslate(-getLeft(), -getTop());
        fill.getShader().setLocalMatrix(matrix);
        fill.setAlpha(Math.round(255 * FILL));
        canvas.drawText(time, x, y, fill);
        bright.setAlpha(veilAlpha(opacity));
        canvas.drawText(time, x, y, bright);

        if (shine == 0) return;
        // Each edge is the digits less the digits moved a little: what is left is a thin line on the far side of each stroke.
        float glow = GLOW_DP * density;
        layer = canvas.saveLayer(0, 0, w, h, null);
        bright.setAlpha(glowAlpha(shine));
        canvas.drawText(time, x, y, bright);
        canvas.drawText(time, x + glow, y + glow, wideCut);
        canvas.restoreToCount(layer);

        float d = BRIGHT_DP * density;
        layer = canvas.saveLayer(0, 0, w, h, null);
        bright.setAlpha(brightAlpha(shine));
        canvas.drawText(time, x, y, bright);
        canvas.drawText(time, x + d, y + d, cut);
        canvas.restoreToCount(layer);

        layer = canvas.saveLayer(0, 0, w, h, null);
        dark.setAlpha(darkAlpha(shine));
        canvas.drawText(time, x, y, dark);
        canvas.drawText(time, x, y - DARK_DP * density, softCut);
        canvas.restoreToCount(layer);
    }
}
