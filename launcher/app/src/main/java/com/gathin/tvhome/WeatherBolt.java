package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.Random;

/**
 * weather_scene's lightning bolt: a thin jagged line with a few branches, in the distant sky behind the peaks. A new one is made
 * for each strike, at most every 15 seconds, and it shows for under half a second; nothing is drawn the rest of the time.
 */
final class WeatherBolt extends View {
    /** How many jags a bolt has, how far each swings, in pixels at 1080, and how many branches it may have. */
    private static final int JAGS = 22, MAX_BRANCHES = 4;
    private static final float SWING = 26, TOP = 70, TOP_MORE = 120, BOTTOM = 620, BOTTOM_MORE = 90, LEAN = 280;

    private final Path trunk = new Path(), branches = new Path();
    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG), thin = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG);

    WeatherBolt(Context context) {
        super(context);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setWillNotDraw(false);
        for (Paint p : new Paint[]{core, thin, glow}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeCap(Paint.Cap.ROUND);
        }
        core.setColor(0xFFF2F5FF);
        thin.setColor(0xD0DCE6FF);
        glow.setColor(0x4C8CB0FF);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    /** A new bolt somewhere across the sky, on a screen `width` wide at `scale` times 1080. */
    void strike(Random random, int width, float scale) {
        trunk.rewind();
        branches.rewind();
        core.setStrokeWidth(2f * scale);
        thin.setStrokeWidth(1.2f * scale);
        glow.setStrokeWidth(7f * scale);
        float x = width * (0.1f + 0.8f * random.nextFloat()), y = (TOP + TOP_MORE * random.nextFloat()) * scale;
        float end = (BOTTOM + BOTTOM_MORE * random.nextFloat()) * scale, step = (end - y) / JAGS;
        float drift = (random.nextFloat() - 0.5f) * LEAN * scale / JAGS;
        trunk.moveTo(x, y);
        int forks = 0;
        for (int i = 0; i < JAGS; i++) {
            x += drift + (random.nextFloat() - 0.5f) * SWING * scale;
            y += step * (0.7f + 0.6f * random.nextFloat());
            trunk.lineTo(x, y);
            if (i > 2 && forks < MAX_BRANCHES && random.nextFloat() < 0.2f) {
                forks++;
                branch(random, x, y, step, scale, random.nextBoolean() ? 1 : -1);
            }
        }
        invalidate();
    }

    private void branch(Random random, float x, float y, float step, float scale, int side) {
        branches.moveTo(x, y);
        int jags = 4 + random.nextInt(6);
        for (int i = 0; i < jags; i++) {
            x += side * (6 + random.nextFloat() * 18) * scale;
            y += step * (0.4f + 0.5f * random.nextFloat());
            branches.lineTo(x, y);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawPath(trunk, glow);
        canvas.drawPath(branches, thin);
        canvas.drawPath(trunk, core);
    }
}
