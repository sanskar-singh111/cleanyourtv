package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.Random;

/**
 * weather_scene's rain and snow: plain thin rectangles and circles, redrawn each frame from the time alone, so a frame makes no
 * objects and keeps no state per drop. Rain is one straight fall, so the canvas turns once and each streak is a level rectangle.
 */
final class WeatherSky extends View {
    static final int MAX_RAIN = 400, MAX_CLOSE = 80, MAX_SNOW = 160;
    /** Rain falls this many degrees off the vertical. */
    private static final float SLANT = 7f;
    /** Every fourth streak is near: longer, faster and stronger. */
    private static final int NEAR_EVERY = 4;
    private static final float NEAR_LONG = 92, FAR_LONG = 48, NEAR_WIDE = 1.7f, FAR_WIDE = 1.1f, NEAR_SPEED = 2100, FAR_SPEED = 1250;
    /** The big soft streaks close to the camera, one small picture drawn many times; TV Studio's the same. */
    private static final float CLOSE_LONG = 250, CLOSE_WIDE = 7, CLOSE_ALPHA = 1.1f, CLOSE_SPEED = 3000;
    private static final int RAIN_COLOR = 0xD8E4F4, SNOW_COLOR = 0xF4F7FF;

    private final float[] rainX = new float[MAX_RAIN], rainY = new float[MAX_RAIN], rainSpeed = new float[MAX_RAIN];
    private final float[] snowX = new float[MAX_SNOW], snowY = new float[MAX_SNOW], snowSpeed = new float[MAX_SNOW], snowSize = new float[MAX_SNOW];
    private final Paint near = new Paint(Paint.ANTI_ALIAS_FLAG), far = new Paint(Paint.ANTI_ALIAS_FLAG), flake = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint soft = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF place = new RectF();
    private Bitmap streak;
    private float seconds, rainAmount, rainAlpha, rainFast = 1, rainSize = 1, closeAmount, snowAmount;

    WeatherSky(Context context) {
        super(context);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setWillNotDraw(false);
        Random random = new Random(7);
        for (int i = 0; i < MAX_RAIN; i++) {
            rainX[i] = random.nextFloat();
            rainY[i] = random.nextFloat();
            rainSpeed[i] = 0.8f + 0.4f * random.nextFloat();
        }
        for (int i = 0; i < MAX_SNOW; i++) {
            snowX[i] = random.nextFloat();
            snowY[i] = random.nextFloat();
            snowSpeed[i] = 40 + 85 * random.nextFloat();
            snowSize[i] = 1.6f + 2.6f * random.nextFloat() * random.nextFloat();
        }
        flake.setColor(SNOW_COLOR | 0xC8 << 24);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    /** The big soft streak's picture, once it's decoded. */
    void streak(Bitmap picture) {
        streak = picture;
    }

    boolean hasStreak() {
        return streak != null;
    }

    /** One frame's state: how many streaks, `alpha` their strength 0 to 1, `fast` and `size` shares; how many close ones and flakes. */
    void set(float seconds, float rain, float alpha, float fast, float size, float close, float snow) {
        this.seconds = seconds;
        rainAmount = rain;
        rainAlpha = alpha;
        rainFast = fast;
        rainSize = size;
        closeAmount = close;
        snowAmount = snow;
        invalidate();
    }

    boolean empty() {
        return rainAmount < 1 && snowAmount < 1;
    }

    /** Where something falling at `speed` is after `seconds`, starting at the share `start` of a fall `span` long. */
    static float fall(float start, float speed, float seconds, float span) {
        float y = (start * span + speed * seconds) % span;
        return y < 0 ? y + span : y;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (rainAmount >= 1) {
            near.setColor(RAIN_COLOR | Math.round(255 * rainAlpha) << 24);
            far.setColor(RAIN_COLOR | Math.round(255 * rainAlpha * 0.6f) << 24);
            int save = canvas.save();
            canvas.rotate(SLANT, w / 2f, h / 2f);
            float wide = w * 1.2f, left = -w * 0.1f, span = h * 1.25f, thin = Math.max(0.75f, rainSize);
            int n = Math.round(rainAmount);
            for (int i = 0; i < n; i++) {
                boolean close = i % NEAR_EVERY == 0;
                float tall = (close ? NEAR_LONG : FAR_LONG) * rainSize, speed = (close ? NEAR_SPEED : FAR_SPEED) * rainSpeed[i] * rainFast;
                float x = left + rainX[i] * wide, y = fall(rainY[i], speed, seconds, span + tall) - tall - h * 0.12f;
                canvas.drawRect(x, y, x + (close ? NEAR_WIDE : FAR_WIDE) * thin, y + tall, close ? near : far);
            }
            if (closeAmount >= 1 && streak != null) {
                soft.setAlpha(Math.min(255, Math.round(255 * rainAlpha * CLOSE_ALPHA)));
                int m = Math.round(closeAmount);
                for (int i = 0; i < m; i++) {
                    // The streaks' places read backwards, so these don't fall along the thin ones.
                    int at = MAX_RAIN - 1 - i;
                    float x = left + rainX[at] * wide, y = fall(rainY[at], CLOSE_SPEED * rainSpeed[at] * rainFast, seconds, span + CLOSE_LONG) - CLOSE_LONG - h * 0.12f;
                    place.set(x, y, x + CLOSE_WIDE, y + CLOSE_LONG);
                    canvas.drawBitmap(streak, null, place, soft);
                }
            }
            canvas.restoreToCount(save);
        }
        if (snowAmount >= 1) {
            float span = h + 20;
            int n = Math.round(snowAmount);
            for (int i = 0; i < n; i++) {
                float y = fall(snowY[i], snowSpeed[i], seconds, span) - 10;
                float x = snowX[i] * w + (float) Math.sin(seconds * 0.7f + i) * 24 * snowSize[i] / 3;
                canvas.drawCircle(x, y, snowSize[i], flake);
            }
        }
    }
}
