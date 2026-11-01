package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.File;

/**
 * big_clock: one line (the date, then the weather) and under it the time, large and thin, in the screen's centre. It is
 * one view for SceneLayers to put between its layers, so the front mountain overlaps the digits.
 */
final class BigClock {
    /** How far above the digits the line starts, as a share of the screen's height. */
    private static final float LINE_ABOVE = 0.063f;
    /** The digits of res/font/clock_digits.ttf are this share of its text size. */
    private static final float DIGIT_SHARE = 0.765f;
    /** A very soft, very light shadow under the digits, for the bright sky. */
    private static final int LINE_COLOR = 0xF2FFFFFF, SHADOW = 0x59000000, DIGITS_SHADOW = 0x1C000000;
    /** The shadow's size in dp. */
    private static final float DIGITS_SHADOW_DP = 14;

    final FrameLayout view;
    /** The glass digits and how they were made; another look's digits are laid over them while the look changes. */
    private GlassDigits glassDigits, nextDigits;
    private File glassPicture;
    /** How far the look's change has come, and whether nextDigits has its picture. */
    private float glassFade;
    private boolean nextReady;
    private final Typeface digitsFont;
    private final int opacityPercent, shine;
    private final float textSize;
    private final LinearLayout weather;
    private final WeatherIcon icon = new WeatherIcon();
    private final TextView weatherText;
    private Weather.Reading reading;
    private boolean hazy;

    /** `ownDigits`: digitsFont is the theme's own, so the digits' share of the text size is measured from it. */
    BigClock(Context context, int sizePercent, int topPercent, int opacityPercent, Typeface regular, Typeface digitsFont, boolean ownDigits, File glass, int shine) {
        float density = context.getResources().getDisplayMetrics().density;
        int screenH = context.getResources().getDisplayMetrics().heightPixels;
        view = new FrameLayout(context);
        view.setFocusable(false);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        this.digitsFont = digitsFont;
        glassPicture = glass;
        this.opacityPercent = opacityPercent;
        this.shine = shine;
        float digitShare = ownDigits ? digitShare(digitsFont) : DIGIT_SHARE;
        textSize = screenH * sizePercent / 100f / digitShare;
        glassDigits = glass == null ? null : new GlassDigits(context, shine, DIGITS_SHADOW, DIGITS_SHADOW_DP);
        TextClock digits = glassDigits != null ? glassDigits : new TextClock(context);
        style(digits, density);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        // The digits' top, not the view's: above them the view keeps the font's whole ascent.
        float digitsTop = topPercent / 100f;
        dlp.topMargin = Math.round(screenH * digitsTop + digits.getPaint().getFontMetrics().ascent + textSize * digitShare);
        view.addView(digits, dlp);
        if (glassDigits != null) glassDigits.load(glass, opacityPercent);

        LinearLayout line = new LinearLayout(context);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextClock date = new TextClock(context);
        date.setFormat12Hour("EEEE d MMMM");
        date.setFormat24Hour("EEEE d MMMM");
        styleLine(date, regular, density);
        line.addView(date);
        weather = new LinearLayout(context);
        weather.setOrientation(LinearLayout.HORIZONTAL);
        weather.setGravity(Gravity.CENTER_VERTICAL);
        weather.setVisibility(View.GONE);
        ImageView mark = new ImageView(context);
        mark.setImageDrawable(icon);
        int size = Math.round(19 * density);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(size, size);
        mlp.setMarginStart(Math.round(12 * density));
        mlp.setMarginEnd(Math.round(6 * density));
        weather.addView(mark, mlp);
        weatherText = new TextView(context);
        styleLine(weatherText, regular, density);
        weather.addView(weatherText);
        line.addView(weather);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        llp.topMargin = Math.round(screenH * (digitsTop - LINE_ABOVE));
        view.addView(line, llp);
    }

    /** How tall this font's digits are as a share of its text size, so big_clock_size means the same in any font. */
    private static float digitShare(Typeface font) {
        Paint paint = new Paint();
        paint.setTypeface(font);
        paint.setTextSize(1000);
        Rect digit = new Rect();
        paint.getTextBounds("0", 0, 1, digit);
        return digit.height() >= 200 ? digit.height() / 1000f : DIGIT_SHARE;
    }

    private void style(TextClock digits, float density) {
        digits.setFormat12Hour("h:mm");
        digits.setFormat24Hour("HH:mm");
        digits.setTypeface(digitsFont);
        digits.setTextColor(Math.round(255 * opacityPercent / 100f) << 24 | 0xFFFFFF);
        digits.setIncludeFontPadding(false);
        digits.setShadowLayer(DIGITS_SHADOW_DP * density, 0, density, DIGITS_SHADOW);
        // Room for the shadow at the sides, and one texture: it is blurred when the minute changes, not on every frame.
        int room = Math.round(DIGITS_SHADOW_DP * density * 1.5f);
        digits.setPadding(room, 0, room, 0);
        digits.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        digits.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize);
    }

    /** weather_scene: the glass made from another sky, a second set of digits laid over the first for glassFade to bring in. */
    void glass(File picture) {
        if (glassDigits == null || picture == null || picture.equals(glassPicture)) return;
        glassPicture = picture;
        Context context = view.getContext();
        if (nextDigits != null) view.removeView(nextDigits);
        glassFade = 0;
        nextReady = false;
        GlassDigits next = new GlassDigits(context, shine, DIGITS_SHADOW, DIGITS_SHADOW_DP);
        nextDigits = next;
        style(next, context.getResources().getDisplayMetrics().density);
        view.addView(next, view.indexOfChild(glassDigits) + 1, new FrameLayout.LayoutParams((FrameLayout.LayoutParams) glassDigits.getLayoutParams()));
        next.load(picture, opacityPercent, () -> {
            if (nextDigits != next) return;
            nextReady = true;
            glassFade(glassFade);
        });
    }

    /** How much of the new digits shows, 0 to 1; at 1 they take the old ones' place. */
    void glassFade(float amount) {
        glassFade = amount;
        if (nextDigits == null || !nextReady) return;
        nextDigits.setAlpha(amount);
        if (amount < 1f) return;
        view.removeView(glassDigits);
        glassDigits = nextDigits;
        nextDigits = null;
    }

    private static void styleLine(TextView text, Typeface regular, float density) {
        text.setTypeface(regular);
        text.setTextColor(LINE_COLOR);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        text.setIncludeFontPadding(false);
        text.setShadowLayer(4 * density, 0, density, SHADOW);
    }

    /** The weather on the line, or (null) the date alone. */
    void show(Weather.Reading reading) {
        this.reading = reading;
        if (reading == null) {
            weather.setVisibility(View.GONE);
            return;
        }
        icon.setKind(hazy ? WeatherIcon.HAZE : reading.icon);
        weatherText.setText(line(reading.celsius, hazy ? Weather.HAZY : Weather.condition(reading.code)));
        weather.setVisibility(View.VISIBLE);
    }

    /** weather_scene: whether the picture is the haze look, which the line says instead of the sky's condition. */
    void hazy(boolean hazy) {
        if (this.hazy == hazy) return;
        this.hazy = hazy;
        show(reading);
    }

    /** "28° Mostly clear", or "28°" when the condition isn't known. */
    static String line(int celsius, String condition) {
        return celsius + "°" + (condition.isEmpty() ? "" : " " + condition);
    }
}
