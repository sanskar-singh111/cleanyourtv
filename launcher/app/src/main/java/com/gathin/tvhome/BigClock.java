package com.gathin.tvhome;

import android.content.Context;
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

/**
 * big_clock: one line (the date, then the weather) and under it the time, large and thin, in the screen's centre. It is
 * one view for SceneLayers to put between its layers, so the front mountain overlaps the digits.
 */
final class BigClock {
    /** Where the line and the digits start, as shares of the screen's height. */
    private static final float LINE_TOP = 0.095f, DIGITS_TOP = 0.158f;
    /** Outfit's digits are about this share of its text size. */
    private static final float DIGIT_SHARE = 0.70f;
    /** Slimmer than Outfit draws them: tall digits leave the peak room. */
    private static final float DIGITS_WIDTH = 0.88f;
    /** The digits let a little of the sky through, as glass would. */
    private static final int DIGITS_COLOR = 0xE6FFFFFF, LINE_COLOR = 0xF2FFFFFF, SHADOW = 0x59000000;

    final FrameLayout view;
    private final LinearLayout weather;
    private final WeatherIcon icon = new WeatherIcon();
    private final TextView weatherText;

    BigClock(Context context, int sizePercent, Typeface regular, Typeface thin) {
        float density = context.getResources().getDisplayMetrics().density;
        int screenH = context.getResources().getDisplayMetrics().heightPixels;
        view = new FrameLayout(context);
        view.setFocusable(false);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextClock digits = new TextClock(context);
        digits.setFormat12Hour("h:mm");
        digits.setFormat24Hour("HH:mm");
        digits.setTypeface(thin);
        digits.setTextColor(DIGITS_COLOR);
        digits.setIncludeFontPadding(false);
        digits.setLetterSpacing(-0.02f);
        float textSize = screenH * sizePercent / 100f / DIGIT_SHARE;
        digits.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize);
        digits.setTextScaleX(DIGITS_WIDTH);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        // The digits' top, not the view's: above them the view keeps the font's whole ascent.
        dlp.topMargin = Math.round(screenH * DIGITS_TOP + digits.getPaint().getFontMetrics().ascent + textSize * DIGIT_SHARE);
        view.addView(digits, dlp);

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
        llp.topMargin = Math.round(screenH * LINE_TOP);
        view.addView(line, llp);
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
        if (reading == null) {
            weather.setVisibility(View.GONE);
            return;
        }
        icon.setKind(reading.icon);
        weatherText.setText(line(reading.celsius, Weather.condition(reading.code)));
        weather.setVisibility(View.VISIBLE);
    }

    /** "28° Mostly clear", or "28°" when the condition isn't known. */
    static String line(int celsius, String condition) {
        return celsius + "°" + (condition.isEmpty() ? "" : " " + condition);
    }
}
