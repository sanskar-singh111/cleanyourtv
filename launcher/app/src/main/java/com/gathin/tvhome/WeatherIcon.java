package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * The weather beside the clock as a thin white outline, drawn in code like the festive drawables: sun, moon, a cloud,
 * and the cloud with rain, snow or a bolt, or fog lines, or haze's wavy ones. Strokes scale with the bounds, so one size fits any text.
 */
final class WeatherIcon extends Drawable {
    static final int CLEAR_DAY = 0, CLEAR_NIGHT = 1, PARTLY_DAY = 2, PARTLY_NIGHT = 3, CLOUDY = 4, FOG = 5, RAIN = 6,
            SNOW = 7, STORM = 8, HAZE = 9;

    /** The icon for a WMO weather code as Open-Meteo reports it (0 clear ... 99 thunderstorm with hail). */
    static int forCode(int code, boolean day) {
        if (code == 0) return day ? CLEAR_DAY : CLEAR_NIGHT;
        if (code == 1 || code == 2) return day ? PARTLY_DAY : PARTLY_NIGHT;
        if (code == 45 || code == 48) return FOG;
        if (code >= 51 && code <= 67 || code >= 80 && code <= 82) return RAIN;
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return SNOW;
        if (code >= 95 && code <= 99) return STORM;
        return CLOUDY;
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private int kind = CLOUDY;

    WeatherIcon() {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(0xFFFFFFFF);
    }

    void setKind(int kind) {
        if (this.kind == kind) return;
        this.kind = kind;
        invalidateSelf();
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height());
        if (s <= 0) return;
        canvas.save();
        canvas.translate(b.left + (b.width() - s) / 2f, b.top + (b.height() - s) / 2f);
        paint.setStrokeWidth(Math.max(1.5f, s * 0.07f));
        switch (kind) {
            case CLEAR_DAY: sun(canvas, s * 0.5f, s * 0.5f, s * 0.2f, s); break;
            case CLEAR_NIGHT: moon(canvas, s * 0.5f, s * 0.5f, s * 0.3f); break;
            case PARTLY_DAY:
                sun(canvas, s * 0.36f, s * 0.36f, s * 0.14f, s * 0.7f);
                cloud(canvas, s, s * 0.14f);
                break;
            case PARTLY_NIGHT:
                moon(canvas, s * 0.36f, s * 0.34f, s * 0.2f);
                cloud(canvas, s, s * 0.14f);
                break;
            case FOG:
                for (int i = 0; i < 3; i++) {
                    float y = s * (0.32f + i * 0.18f), inset = i == 1 ? 0.1f : 0.18f;
                    canvas.drawLine(s * inset, y, s * (1 - inset), y, paint);
                }
                break;
            case HAZE:
                // Wavy where fog's lines are straight; no sun or moon, as the air hides both.
                for (int i = 0; i < 3; i++) {
                    float y = s * (0.32f + i * 0.18f), inset = i == 1 ? 0.1f : 0.18f, wide = s * (1 - 2 * inset), bend = s * 0.07f;
                    path.reset();
                    path.moveTo(s * inset, y);
                    path.cubicTo(s * inset + wide / 3, y - bend * 2, s * inset + wide * 2 / 3, y + bend * 2, s * (1 - inset), y);
                    canvas.drawPath(path, paint);
                }
                break;
            case RAIN:
                cloud(canvas, s, -s * 0.1f);
                for (int i = 0; i < 3; i++) {
                    float x = s * (0.34f + i * 0.16f);
                    canvas.drawLine(x, s * 0.7f, x - s * 0.06f, s * 0.86f, paint);
                }
                break;
            case SNOW:
                cloud(canvas, s, -s * 0.1f);
                paint.setStyle(Paint.Style.FILL);
                for (int i = 0; i < 3; i++) canvas.drawCircle(s * (0.34f + i * 0.16f), s * (i == 1 ? 0.84f : 0.76f), s * 0.04f, paint);
                paint.setStyle(Paint.Style.STROKE);
                break;
            case STORM:
                cloud(canvas, s, -s * 0.1f);
                path.reset();
                path.moveTo(s * 0.54f, s * 0.6f);
                path.lineTo(s * 0.44f, s * 0.76f);
                path.lineTo(s * 0.56f, s * 0.76f);
                path.lineTo(s * 0.46f, s * 0.92f);
                canvas.drawPath(path, paint);
                break;
            default:
                cloud(canvas, s, 0f);
                break;
        }
        canvas.restore();
    }

    private void sun(Canvas canvas, float cx, float cy, float r, float size) {
        canvas.drawCircle(cx, cy, r, paint);
        float in = r + size * 0.09f, out = r + size * 0.19f;
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
            canvas.drawLine(cx + dx * in, cy + dy * in, cx + dx * out, cy + dy * out, paint);
        }
    }

    /** A crescent: the outer arc of one circle closed by the inner arc of a second one shifted up and right. */
    private void moon(Canvas canvas, float cx, float cy, float r) {
        path.reset();
        oval.set(cx - r, cy - r, cx + r, cy + r);
        path.arcTo(oval, -40, 260, true);
        float d = r * 0.55f;
        oval.set(cx - r + d, cy - r - d * 0.5f, cx + r + d, cy + r - d * 0.5f);
        path.arcTo(oval, 160, -230);
        path.close();
        canvas.drawPath(path, paint);
    }

    /** A cloud along the lower half, shifted down by dy (negative raises it, to leave room for rain under it). */
    private void cloud(Canvas canvas, float s, float dy) {
        path.reset();
        float base = s * 0.78f + dy;
        path.moveTo(s * 0.3f, base);
        oval.set(s * 0.14f, base - s * 0.3f, s * 0.44f, base);
        path.arcTo(oval, 90, 180);
        oval.set(s * 0.3f, base - s * 0.46f, s * 0.66f, base - s * 0.1f);
        path.arcTo(oval, 190, 160);
        oval.set(s * 0.58f, base - s * 0.3f, s * 0.88f, base);
        path.arcTo(oval, 270, 180);
        path.close();
        canvas.drawPath(path, paint);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
