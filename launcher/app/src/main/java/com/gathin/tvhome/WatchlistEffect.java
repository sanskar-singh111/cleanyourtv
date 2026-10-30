package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * watchlist_effects: the small moment a watchlist character gets when it is selected. Each plays once, for about a
 * second, drawn with plain shapes on the lineup's own canvas: no blur, no video, no new bitmaps. The view draws three
 * parts around the selected figure: {@link #drawBehind} (after the scene and the other figures), {@link #move} (the
 * figure's own nudge) and {@link #drawFront} (over everything but the title). Every play gets its own seed, so the
 * random parts come out a little different each time.
 */
final class WatchlistEffect {
    static final String SPIDER_SENSE = "spider_sense", SPEEDSTER = "speedster", BLOOD = "blood",
            BLUE_CRYSTALS = "blue_crystals", CIGAR_SMOKE = "cigar_smoke", THE_NUMBERS = "the_numbers",
            COFFEE_STEAM = "coffee_steam", LIGHT_FLICKER = "light_flicker", SCOPE_GLINT = "scope_glint",
            GLITCH = "glitch", POLICE_LIGHTS = "police_lights", BOUNCE = "bounce", SHAKE = "shake", SPOTLIGHT = "spotlight",
            FIRE_EMBERS = "fire_embers", SNOW = "snow", LIGHTNING = "lightning", MAGIC_SPARKS = "magic_sparks",
            STARS = "stars", GREEN_FOG = "green_fog", RAIN = "rain", GOLD_COINS = "gold_coins", GUN_SPARKS = "gun_sparks",
            PETALS = "petals", GHOST_SHIMMER = "ghost_shimmer";
    static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(SPIDER_SENSE, SPEEDSTER, BLOOD,
            BLUE_CRYSTALS, CIGAR_SMOKE, THE_NUMBERS, COFFEE_STEAM, LIGHT_FLICKER, SCOPE_GLINT, GLITCH, POLICE_LIGHTS,
            BOUNCE, SHAKE, SPOTLIGHT, FIRE_EMBERS, SNOW, LIGHTNING, MAGIC_SPARKS, STARS, GREEN_FOG, RAIN, GOLD_COINS,
            GUN_SPARKS, PETALS, GHOST_SHIMMER));

    /** The effect's id as the theme spells it, or null for none or one this launcher doesn't know. */
    static String known(String id) {
        if (id == null) return null;
        String s = id.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return ALL.contains(s) ? s : null;
    }

    static long durationMs(String id) {
        if (id == null) return 0;
        switch (id) {
            case SHAKE: return 650;
            case BOUNCE: return 800;
            case GLITCH: case LIGHTNING: case GUN_SPARKS: return 900;
            case BLOOD: case LIGHT_FLICKER: case MAGIC_SPARKS: return 1100;
            case THE_NUMBERS: case POLICE_LIGHTS: case FIRE_EMBERS: case RAIN: case GOLD_COINS: case GHOST_SHIMMER: return 1200;
            case SPOTLIGHT: case SNOW: case STARS: case GREEN_FOG: return 1300;
            case PETALS: return 1400;
            case CIGAR_SMOKE: return 1500;
            default: return 1000;
        }
    }

    // ---- the parts that are plain numbers (tested on the JVM) ----------------------------------------

    static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** 0 to 1 and back: fades in over the first `in` of t and out over the last `out`. */
    static float envelope(float t, float in, float out) {
        if (t <= 0f || t >= 1f) return 0f;
        return Math.min(1f, Math.min(t / in, (1f - t) / out));
    }

    static float easeOut(float x) {
        x = clamp01(x);
        return 1f - (1f - x) * (1f - x);
    }

    static float smooth(float x) {
        x = clamp01(x);
        return x * x * (3f - 2f * x);
    }

    /**
     * How the selected figure itself moves at `ms` into the effect: out[0] and out[1] are dx and dy as shares of the
     * figure's height, out[2] and out[3] its scale across and down (around its feet), out[4] a tilt in degrees.
     * Everything is back at rest when the effect ends.
     */
    static void motion(String id, float ms, long seed, float[] out) {
        out[0] = 0f; out[1] = 0f; out[2] = 1f; out[3] = 1f; out[4] = 0f;
        long duration = durationMs(id);
        if (id == null || ms <= 0f || ms >= duration) return;
        float t = ms / duration;
        switch (id) {
            case SPIDER_SENSE: {
                // One thud: a quick drop, then a damped settle.
                if (ms < 60f) out[1] = 0.022f * ms / 60f;
                else out[1] = 0.022f * (float) (Math.exp(-(ms - 60f) / 90f) * Math.cos((ms - 60f) / 55f));
                break;
            }
            case SPEEDSTER:
                out[0] = 0.010f * (float) Math.sin(2 * Math.PI * 14 * ms / 1000f) * envelope(t, 0.06f, 0.25f);
                break;
            case COFFEE_STEAM: {
                // A soft bounce: one small hop and a smaller one.
                float amp = ms < 450f ? 0.028f : 0.011f;
                out[1] = ms < 900f ? -amp * (float) Math.abs(Math.sin(Math.PI * ms / 450f)) : 0f;
                break;
            }
            case BOUNCE: {
                float local, amp;
                if (ms < 380f) { local = ms / 380f; amp = 0.045f; }
                else if (ms < 700f) { local = (ms - 380f) / 320f; amp = 0.018f; }
                else break;
                float hop = (float) Math.sin(Math.PI * local);
                out[1] = -amp * hop;
                // A little stretch in the air.
                out[3] = 1f + 0.6f * amp * hop;
                out[2] = 1f - 0.3f * amp * hop;
                break;
            }
            case SHAKE: {
                float fade = 1f - t;
                out[0] = 0.016f * (float) Math.sin(2 * Math.PI * 7 * t) * fade;
                out[1] = 0.006f * (float) Math.sin(2 * Math.PI * 11 * t) * fade;
                out[4] = 1.2f * (float) Math.sin(2 * Math.PI * 5 * t) * fade;
                break;
            }
            case GLITCH: {
                int b = glitchBurst(ms, seed);
                if (b >= 0) out[0] = ((seed >> b) & 1) == 0 ? 0.006f : -0.006f;
                break;
            }
            case GUN_SPARKS:
                // A small recoil away from each shot.
                for (float b : GUN_BURSTS) {
                    if (ms >= b && ms < b + 120f) out[0] = ((seed & 1) == 0 ? -1f : 1f) * 0.005f * (1f - (ms - b) / 120f);
                }
                break;
            default:
                break;
        }
    }

    /** gun_sparks: when the three shots go off, in ms. */
    static final float[] GUN_BURSTS = {60f, 320f, 580f};

    /** gun_sparks: how bright the shots' flash is at `ms`, 0 to 1 (each fades over 100 ms). */
    static float gunFlash(float ms) {
        float k = 0f;
        for (float b : GUN_BURSTS) if (ms >= b && ms < b + 100f) k = Math.max(k, 1f - (ms - b) / 100f);
        return k;
    }

    /** lightning: the two flashes, 0 to 1: a strike at 40 ms and a weaker one at 230 ms. */
    static float lightningFlash(float ms) {
        if (ms >= 40f && ms < 140f) return 1f - (ms - 40f) / 100f;
        if (ms >= 230f && ms < 330f) return 0.7f * (1f - (ms - 230f) / 100f);
        return 0f;
    }

    private static final float[] BURSTS = {20f, 300f, 560f};

    /** Which of the glitch's three short bursts `ms` falls in, or -1 between them. */
    static int glitchBurst(float ms, long seed) {
        for (int b = 0; b < BURSTS.length; b++) {
            float start = BURSTS[b] + hash01(seed + 2L * b) * 60f;
            float length = 90f + hash01(seed + 2L * b + 1) * 40f;
            if (ms >= start && ms < start + length) return b;
        }
        return -1;
    }

    /** A number from 0 to 1 that only depends on `a` (for per-frame flicker without making a Random). */
    static float hash01(long a) {
        a ^= a >>> 33;
        a *= 0xff51afd7ed558ccdL;
        a ^= a >>> 33;
        a *= 0xc4ceb9fe1a85ec53L;
        a ^= a >>> 33;
        return (a >>> 40) / (float) (1L << 24);
    }

    /**
     * light_flicker: how dark the scene behind is at `ms`, 0 lit to 1 dark (below 0, a brief brighter flash), in steps
     * like a failing bulb. Starts with a dark blink and ends lit.
     */
    static float flickerDark(float ms, long seed) {
        long duration = durationMs(LIGHT_FLICKER);
        if (ms <= 0f || ms >= duration - 120f) return 0f;
        float at = 0f;
        for (int step = 0; ; step++) {
            float length = 45f + hash01(seed * 13 + 2L * step) * 50f;
            float roll = hash01(seed * 13 + 2L * step + 1);
            float level = step == 0 ? 0.9f : (roll < 0.45f ? 0.9f : (roll < 0.7f ? 0.45f : (roll < 0.85f ? -0.12f : 0f)));
            if (ms < at + length) return level;
            at += length;
        }
    }

    // ---- drawing -----------------------------------------------------------------------------------------

    private final float density;
    private final Random random = new Random();
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ghost = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF box = new RectF();
    private final float[] moved = new float[5];
    private final PorterDuffColorFilter yellow = tint(0xFFFFD21F), red = tint(0xFFFF2A4A), cyan = tint(0xFF22E5FF),
            pale = tint(0xFFBFE8FF);
    /** Unit-sized gradients, placed with the canvas's matrix, so nothing is made per frame. */
    private final RadialGradient redGlow = glowShader(0xFFFF1E1E), blueGlow = glowShader(0xFF1E5BFF),
            warmGlow = glowShader(0xFFFFF1D6), greenGlow = glowShader(0xFF5FBF4F), emberGlow = glowShader(0xFFFF7A1E);
    private final LinearGradient beam = new LinearGradient(0, 0, 0, 1, 0x40FFF4D6, 0x0CFFF4D6, Shader.TileMode.CLAMP);

    private String id;
    private long seed;
    private float ms = -1f;
    /** The selected figure as drawn (before its nudge), and its head: centre and height, in px. */
    private final RectF figure = new RectF();
    private float headX, headY, headSize;
    /** The size smoke, steam, numbers and splatter are drawn at: the head, or more on a figure with a small head. */
    private float unit;

    WatchlistEffect(float density) {
        this.density = density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        text.setTextAlign(Paint.Align.CENTER);
    }

    private static PorterDuffColorFilter tint(int color) {
        return new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
    }

    private static RadialGradient glowShader(int color) {
        int rgb = color & 0x00FFFFFF;
        return new RadialGradient(0, 0, 1, new int[]{0xFF000000 | rgb, 0x66000000 | rgb, rgb},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
    }

    void start(String effect, long seed) {
        id = effect;
        this.seed = seed;
        ms = id == null ? -1f : 0f;
    }

    void stop() {
        ms = -1f;
    }

    /** Moves the effect to `ms` from its start; false once it has finished. */
    boolean at(float ms) {
        if (id == null) return false;
        this.ms = ms;
        return ms >= 0f && ms < durationMs(id);
    }

    boolean running() {
        return id != null && ms >= 0f && ms < durationMs(id);
    }

    long duration() {
        return durationMs(id);
    }

    /** Where the selected figure is this frame (px) and its head (shares of the picture, from the theme). */
    void place(RectF figureRect, float headShareX, float headShareY, float headShareSize) {
        figure.set(figureRect);
        headX = figure.left + headShareX * figure.width();
        headY = figure.top + headShareY * figure.height();
        headSize = Math.max(14 * density, headShareSize * figure.height());
        unit = Math.max(headSize, 0.06f * figure.height());
    }

    /** The figure's own nudge, applied to the canvas before it (and its glow) is drawn. */
    void move(Canvas canvas) {
        if (!running()) return;
        motion(id, ms, seed, moved);
        float fh = figure.height();
        if (moved[0] != 0f || moved[1] != 0f) canvas.translate(moved[0] * fh, moved[1] * fh);
        if (moved[2] != 1f || moved[3] != 1f) canvas.scale(moved[2], moved[3], figure.centerX(), figure.bottom);
        if (moved[4] != 0f) canvas.rotate(moved[4], figure.centerX(), figure.bottom);
    }

    /** Behind the selected figure: over the scene and the other figures. */
    void drawBehind(Canvas canvas, Bitmap fig, int w, int h) {
        if (!running()) return;
        float t = ms / durationMs(id);
        random.setSeed(seed);
        switch (id) {
            case SPEEDSTER: speedTrails(canvas, fig, t); break;
            case BLOOD: splatter(canvas); break;
            case THE_NUMBERS: numbers(canvas, t, w); break;
            case LIGHT_FLICKER: darken(canvas, flickerDark(ms, seed), w, h); break;
            case GLITCH: glitchSplit(canvas, fig); break;
            case POLICE_LIGHTS: policeLights(canvas, t, w, h); break;
            case SPOTLIGHT: spotlight(canvas, t, w, h); break;
            case FIRE_EMBERS: glow(canvas, emberGlow, figure.centerX(), emberBase(h), figure.width() * 0.75f,
                    figure.height() * 0.14f, 0.55f * envelope(t, 0.12f, 0.35f)); break;
            case LIGHTNING: darken(canvas, 0.3f * envelope(t, 0.05f, 0.3f) - 0.5f * lightningFlash(ms), w, h); break;
            case STARS: stars(canvas, t, w, h); break;
            case GREEN_FOG: fog(canvas, t, h, false); break;
            case RAIN: darken(canvas, 0.18f * envelope(t, 0.12f, 0.3f), w, h); break;
            case GUN_SPARKS: darken(canvas, -0.1f * gunFlash(ms), w, h); break;
            case GHOST_SHIMMER: ghostShimmer(canvas, fig); break;
            default: break;
        }
    }

    /** In front of everything but the title. */
    void drawFront(Canvas canvas, Bitmap fig, int w, int h) {
        if (!running()) return;
        float t = ms / durationMs(id);
        random.setSeed(seed);
        switch (id) {
            case SPIDER_SENSE: spiderSense(canvas); break;
            case BLOOD: drips(canvas, w, h); break;
            case BLUE_CRYSTALS: crystals(canvas, h); break;
            case CIGAR_SMOKE: cigarSmoke(canvas, t, w); break;
            case COFFEE_STEAM: steam(canvas, w); break;
            case SCOPE_GLINT: scopeGlint(canvas, w, h); break;
            case GLITCH: glitchSlices(canvas, fig); break;
            case FIRE_EMBERS: embers(canvas, h); break;
            case SNOW: snow(canvas, t, w); break;
            case LIGHTNING: lightning(canvas, w); break;
            case MAGIC_SPARKS: magicSparks(canvas); break;
            case GREEN_FOG: fog(canvas, t, h, true); break;
            case RAIN: rain(canvas, t, h); break;
            case GOLD_COINS: coins(canvas, h); break;
            case GUN_SPARKS: gunSparks(canvas); break;
            case PETALS: petals(canvas, t); break;
            default: break;
        }
    }

    private float rand(float min, float max) {
        return min + random.nextFloat() * (max - min);
    }

    private float footY(int h) {
        return Math.min(figure.bottom, h * 0.95f);
    }

    // Spider-sense: wavy lines bursting out around the head (the thud is in motion()).
    private void spiderSense(Canvas canvas) {
        int n = 8 + random.nextInt(4);
        float fade = 1f - smooth((ms - 520f) / 440f);
        for (int i = 0; i < n; i++) {
            double angle = Math.toRadians(-90 + ((n == 1 ? 0.5f : i / (float) (n - 1)) - 0.5f) * 250f + rand(-10f, 10f));
            float length = headSize * rand(0.55f, 1.0f), r0 = headSize * rand(0.62f, 0.78f);
            int waves = 2 + random.nextInt(2);
            float amp = headSize * rand(0.05f, 0.09f), phase = rand(0f, 6.28f), delay = rand(0f, 120f);
            float width = rand(2.2f, 3.2f) * density;
            float local = (ms - 40f - delay) / 520f;
            if (local <= 0f) continue;
            float grow = easeOut(local * 1.6f), push = headSize * 0.25f * clamp01(local);
            float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
            path.reset();
            for (int k = 0; k <= 16; k++) {
                float s = k / 16f;
                float along = r0 + push + s * length * grow;
                float across = amp * (float) Math.sin(s * waves * 2 * Math.PI + phase);
                float x = headX + cos * along - sin * across, y = headY + sin * along + cos * across;
                if (k == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            stroke.setStrokeWidth(width * 2.2f);
            stroke.setColor(0xFFFF3B3B);
            stroke.setAlpha(Math.round(70 * fade));
            canvas.drawPath(path, stroke);
            stroke.setStrokeWidth(width);
            stroke.setColor(0xFFFFFFFF);
            stroke.setAlpha(Math.round(235 * fade));
            canvas.drawPath(path, stroke);
        }
    }

    // Speedster: faint yellow copies of the figure trailing to one side, and a few speed lines.
    private void speedTrails(Canvas canvas, Bitmap fig, float t) {
        float e = envelope(t, 0.06f, 0.25f);
        float dir = random.nextBoolean() ? -1f : 1f;
        float step = Math.max(10 * density, 0.06f * figure.width());
        float shake = 0.010f * (float) Math.sin(2 * Math.PI * 14 * ms / 1000f) * e * figure.height();
        if (fig != null) {
            ghost.setColorFilter(yellow);
            float[] alphas = {0.30f, 0.18f, 0.09f};
            for (int k = 0; k < 3; k++) {
                float dx = dir * (k + 1) * step + shake * (k + 1) * 0.5f;
                box.set(figure.left + dx, figure.top, figure.right + dx, figure.bottom);
                ghost.setAlpha(Math.round(255 * alphas[k] * e));
                canvas.drawBitmap(fig, null, box, ghost);
            }
        }
        stroke.setColor(0xFFFFD21F);
        stroke.setStrokeWidth(2 * density);
        for (int i = 0; i < 6; i++) {
            float y = figure.top + figure.height() * rand(0.2f, 0.85f);
            float start = figure.centerX() + dir * figure.width() * rand(0.15f, 0.3f);
            float length = figure.width() * rand(0.25f, 0.5f);
            float flick = 0.25f + 0.25f * (float) Math.sin(ms / 40f + i * 1.7f);
            stroke.setAlpha(Math.round(255 * flick * e));
            canvas.drawLine(start, y, start + dir * length, y, stroke);
        }
    }

    // Blood: a splatter on the wall behind, beside the head.
    private void splatter(Canvas canvas) {
        float side = random.nextBoolean() ? 1f : -1f;
        float cx = headX + side * headSize * 0.9f, cy = headY + headSize * 0.6f;
        float pop = easeOut(ms / 140f);
        float alpha = 0.9f * (1f - smooth((ms - 700f) / 400f));
        fill.setShader(null);
        fill.setColor(0xFF8A0F0F);
        fill.setAlpha(Math.round(255 * alpha));
        for (int i = 0; i < 7; i++) {
            double a = rand(0f, 6.28f);
            float d = unit * rand(0f, 0.9f) * pop;
            canvas.drawCircle(cx + (float) Math.cos(a) * d, cy + (float) Math.sin(a) * d, unit * rand(0.18f, 0.38f) * pop, fill);
        }
        for (int i = 0; i < 16; i++) {
            double a = rand(0f, 6.28f);
            float d = unit * rand(0.9f, 2.6f) * pop;
            canvas.drawCircle(cx + (float) Math.cos(a) * d, cy + (float) Math.sin(a) * d,
                    Math.max(1.5f * density, unit * rand(0.03f, 0.09f)), fill);
        }
    }

    // Blood: a few drops running down from the top of the screen.
    private void drips(Canvas canvas, int w, int h) {
        int n = 3 + random.nextInt(3);
        float fade = 1f - smooth((ms - 850f) / 250f);
        for (int i = 0; i < n; i++) {
            float x = Math.max(20 * density, Math.min(w - 20 * density, figure.centerX() + rand(-0.7f, 0.7f) * figure.width()));
            float max = h * rand(0.16f, 0.42f), width = rand(2.5f, 4.5f) * density, delay = rand(0f, 200f);
            float local = clamp01((ms - delay) / 700f);
            if (local <= 0f) continue;
            float y = max * smooth(local);
            stroke.setColor(0xFF8E0B0B);
            stroke.setAlpha(Math.round(240 * fade));
            stroke.setStrokeWidth(width);
            canvas.drawLine(x, -width, x, y, stroke);
            fill.setColor(0xFF8E0B0B);
            fill.setAlpha(Math.round(240 * fade));
            canvas.drawCircle(x, y, width * 1.15f, fill);
        }
    }

    // Blue crystals: small shards sparkling around the figure, and a puff of green smoke at the feet.
    private void crystals(Canvas canvas, int h) {
        float feet = footY(h) - 0.02f * h;
        float grow = easeOut(ms / 900f), smoke = 0.6f * envelope(ms / 1000f, 0.12f, 0.45f);
        for (int i = 0; i < 8; i++) {
            float dx = rand(-0.5f, 0.5f) * figure.width() * grow, dy = -rand(0f, 0.12f) * figure.height() * grow;
            float r = figure.height() * rand(0.05f, 0.1f) * (0.6f + 0.8f * grow);
            glow(canvas, greenGlow, figure.centerX() + dx, feet + dy, r, r, smoke);
        }
        for (int i = 0; i < 7; i++) {
            float x = figure.left + rand(0f, 1f) * figure.width();
            float y = Math.min(h - 10 * density, figure.top + figure.height() * rand(0.15f, 0.85f));
            float size = rand(5f, 10f) * density, delay = rand(0f, 350f), turn = rand(-40f, 40f);
            float local = (ms - delay) / 450f;
            if (local <= 0f || local >= 1f) continue;
            float s = size * (float) Math.sin(Math.PI * local);
            int alpha = Math.round(255 * (float) Math.sin(Math.PI * local));
            canvas.save();
            canvas.translate(x, y);
            canvas.rotate(turn);
            path.reset();
            path.moveTo(0, -1.6f * s);
            path.lineTo(0.55f * s, -0.5f * s);
            path.lineTo(0.55f * s, 0.6f * s);
            path.lineTo(0, 1.4f * s);
            path.lineTo(-0.55f * s, 0.6f * s);
            path.lineTo(-0.55f * s, -0.5f * s);
            path.close();
            fill.setColor(0xFF5EC8FF);
            fill.setAlpha(alpha);
            canvas.drawPath(path, fill);
            stroke.setColor(0xFFD8F4FF);
            stroke.setAlpha(alpha);
            stroke.setStrokeWidth(1.2f * density);
            canvas.drawLine(0, -1.6f * s, 0, 0.2f * s, stroke);
            if (local > 0.3f && local < 0.7f) {
                float g = 2.2f * size * (float) Math.sin(Math.PI * (local - 0.3f) / 0.4f);
                stroke.setColor(0xFFFFFFFF);
                stroke.setAlpha(220);
                canvas.drawLine(-g, -1.6f * s, g, -1.6f * s, stroke);
                canvas.drawLine(0, -1.6f * s - g, 0, -1.6f * s + g, stroke);
            }
            canvas.restore();
        }
    }

    // Cigar smoke: a slow thin curl rising beside the face, from a small ember.
    private void cigarSmoke(Canvas canvas, float t, int w) {
        float side = (seed & 1) == 0 ? 1f : -1f;
        float ox = Math.max(30 * density, Math.min(w - 30 * density, headX + side * headSize * 1.1f));
        float oy = headY + headSize * 1.3f;
        float u = unit;
        float e = envelope(t, 0.12f, 0.35f);
        fill.setColor(0xFFFF7A2A);
        fill.setAlpha(Math.round(230 * e));
        canvas.drawCircle(ox, oy, 2.5f * density, fill);
        stroke.setColor(0xFFD8D8D8);
        stroke.setStrokeWidth(2f * density);
        float rise = easeOut(ms / 1100f);
        float[][] parts = {{0f, 0.4f, 1f}, {0.4f, 0.75f, 0.6f}, {0.75f, 1f, 0.3f}};
        for (int j = 0; j < 2; j++) {
            float height = u * (3.2f + j * 0.8f) * rise;
            for (float[] part : parts) {
                path.reset();
                for (int k = 0; k <= 8; k++) {
                    float s = part[0] + (part[1] - part[0]) * k / 8f;
                    float y = oy - s * height;
                    float x = ox + side * s * u * 0.6f
                            + (float) Math.sin(s * 3.2 * Math.PI + ms / 260f + j * 1.7f) * u * 0.22f * s;
                    if (k == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                stroke.setAlpha(Math.round(200 * e * part[2] * (j == 0 ? 1f : 0.6f)));
                canvas.drawPath(path, stroke);
            }
        }
    }

    // The numbers: 4 8 15 16 23 42, faint and flickering behind the figure.
    private void numbers(Canvas canvas, float t, int w) {
        String[] numbers = {"4", "8", "15", "16", "23", "42"};
        float e = envelope(t, 0.15f, 0.3f);
        text.setTextSize(Math.max(18 * density, Math.min(60 * density, unit * 0.9f)));
        text.setColor(0xFFBFE6C8);
        float span = Math.min(w - 80 * density, figure.width() * 1.9f);
        int step = (int) (ms / 70f);
        for (int i = 0; i < numbers.length; i++) {
            float x = figure.centerX() - span / 2f + span * i / (numbers.length - 1f);
            x = Math.max(40 * density, Math.min(w - 40 * density, x));
            float y = headY + headSize * (0.4f + (i % 2 == 0 ? -0.5f : 0.5f));
            float roll = hash01(seed * 31 + i * 977L + step);
            float flick = roll < 0.25f ? 0f : (roll < 0.55f ? 0.45f : 1f);
            text.setAlpha(Math.round(255 * 0.24f * e * flick));
            canvas.drawText(numbers[i], x, y, text);
        }
    }

    // Coffee steam: three small wisps rising beside the figure (the bounce is in motion()).
    private void steam(Canvas canvas, int w) {
        float side = (seed & 1) == 0 ? 1f : -1f;
        float u = unit;
        float ox = Math.max(30 * density, Math.min(w - 30 * density, headX + side * u * 1.4f));
        float oy = headY + u * 2.4f;
        stroke.setColor(0xFFFFFFFF);
        stroke.setStrokeWidth(2.4f * density);
        for (int j = 0; j < 3; j++) {
            float local = (ms - j * 120f) / 800f;
            if (local <= 0f || local >= 1f) continue;
            float rise = u * 1.6f * easeOut(local);
            path.reset();
            for (int k = 0; k <= 14; k++) {
                float s = k / 14f;
                float y = oy - rise * s - local * u * 0.4f;
                float x = ox + (j - 1) * u * 0.22f + (float) Math.sin(s * 2 * Math.PI * 1.2 + j + ms / 180f) * u * 0.12f;
                if (k == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            stroke.setAlpha(Math.round(170 * (float) Math.sin(Math.PI * local)));
            canvas.drawPath(path, stroke);
        }
    }

    /** Darker by `amount` (0 to 1); below 0, lighter, as a bulb flaring. */
    private void darken(Canvas canvas, float amount, int w, int h) {
        if (amount == 0f) return;
        fill.setShader(null);
        fill.setColor(amount > 0f ? 0xFF000000 : 0xFFFFFFFF);
        fill.setAlpha(Math.round(255 * clamp01(Math.abs(amount))));
        canvas.drawRect(0, 0, w, h, fill);
    }

    // Scope glint: a quick lens flash somewhere across the scene, then a thin crosshair closing on the head.
    private void scopeGlint(Canvas canvas, int w, int h) {
        boolean right = figure.centerX() < w / 2f;
        float gx = w * (right ? rand(0.65f, 0.92f) : rand(0.08f, 0.35f)), gy = h * rand(0.18f, 0.4f);
        float local = ms / 320f;
        if (local < 1f) {
            float k = (float) Math.sin(Math.PI * local);
            fill.setColor(0xFFE8F4FF);
            fill.setAlpha(Math.round(255 * k));
            canvas.drawCircle(gx, gy, (2f + 3f * k) * density, fill);
            stroke.setColor(0xFFE8F4FF);
            stroke.setStrokeWidth(1.5f * density);
            stroke.setAlpha(Math.round(230 * k));
            canvas.drawLine(gx - 46 * density * k, gy, gx + 46 * density * k, gy, stroke);
            canvas.drawLine(gx, gy - 20 * density * k, gx, gy + 20 * density * k, stroke);
            stroke.setAlpha(Math.round(120 * k));
            float d = 8 * density * k;
            canvas.drawLine(gx - d, gy - d, gx + d, gy + d, stroke);
            canvas.drawLine(gx - d, gy + d, gx + d, gy - d, stroke);
        }
        if (ms > 180f) {
            float lock = easeOut((ms - 180f) / 380f);
            float r = headSize * (2.6f - 1.4f * lock);
            float alpha = 0.9f * clamp01((ms - 180f) / 120f) * (1f - smooth((ms - 620f) / 380f));
            stroke.setColor(0xFFFF3B30);
            stroke.setStrokeWidth(1.4f * density);
            stroke.setAlpha(Math.round(255 * alpha));
            canvas.drawCircle(headX, headY, r, stroke);
            canvas.drawLine(headX - r * 1.35f, headY, headX - r * 0.35f, headY, stroke);
            canvas.drawLine(headX + r * 0.35f, headY, headX + r * 1.35f, headY, stroke);
            canvas.drawLine(headX, headY - r * 1.35f, headX, headY - r * 0.35f, stroke);
            canvas.drawLine(headX, headY + r * 0.35f, headX, headY + r * 1.35f, stroke);
            fill.setColor(0xFFFF3B30);
            fill.setAlpha(Math.round(255 * alpha));
            canvas.drawCircle(headX, headY, 1.6f * density, fill);
        }
    }

    // Glitch: red and cyan copies split apart during each burst...
    private void glitchSplit(Canvas canvas, Bitmap fig) {
        int b = glitchBurst(ms, seed);
        if (b < 0 || fig == null) return;
        random.setSeed(seed + b);
        float split = rand(6f, 12f) * density * (random.nextBoolean() ? 1f : -1f);
        ghost.setAlpha(140);
        ghost.setColorFilter(red);
        box.set(figure.left - split, figure.top, figure.right - split, figure.bottom);
        canvas.drawBitmap(fig, null, box, ghost);
        ghost.setColorFilter(cyan);
        box.set(figure.left + split, figure.top, figure.right + split, figure.bottom);
        canvas.drawBitmap(fig, null, box, ghost);
    }

    // ...and a few bands of the figure shifted sideways.
    private void glitchSlices(Canvas canvas, Bitmap fig) {
        int b = glitchBurst(ms, seed);
        if (b < 0 || fig == null) return;
        random.setSeed(seed * 7 + b);
        ghost.setColorFilter(null);
        ghost.setAlpha(255);
        for (int i = 0; i < 3; i++) {
            float top = figure.top + figure.height() * rand(0.05f, 0.8f), band = figure.height() * rand(0.03f, 0.08f);
            float dx = rand(14f, 34f) * density * (random.nextBoolean() ? 1f : -1f);
            canvas.save();
            canvas.clipRect(figure.left + Math.min(0, dx), top, figure.right + Math.max(0, dx), top + band);
            box.set(figure.left + dx, figure.top, figure.right + dx, figure.bottom);
            canvas.drawBitmap(fig, null, box, ghost);
            canvas.restore();
        }
        stroke.setColor(0xFFFFFFFF);
        stroke.setStrokeWidth(1f * density);
        stroke.setAlpha(70);
        for (int i = 0; i < 4; i++) {
            float y = figure.top + figure.height() * rand(0.05f, 0.9f);
            canvas.drawLine(figure.left, y, figure.right, y, stroke);
        }
    }

    // Police lights: red and blue light sweeping to and fro behind the figure.
    private void policeLights(Canvas canvas, float t, int w, int h) {
        float e = envelope(t, 0.12f, 0.25f);
        double phase = ms / 1000.0 * 2 * Math.PI * 2;
        float redOn = 0.5f + 0.5f * (float) Math.sin(phase), blueOn = 1f - redOn;
        float fw = figure.width(), cx = figure.centerX(), cy = figure.top + figure.height() * 0.4f;
        float sweep = (float) Math.sin(phase * 0.5) * fw * 0.25f;
        float radius = Math.max(fw, figure.height() * 0.6f) * 0.9f;
        fill.setShader(null);
        fill.setColor(0xFFFF1E1E);
        fill.setAlpha(Math.round(18 * redOn * e));
        canvas.drawRect(0, 0, w, h, fill);
        fill.setColor(0xFF1E5BFF);
        fill.setAlpha(Math.round(18 * blueOn * e));
        canvas.drawRect(0, 0, w, h, fill);
        glow(canvas, redGlow, cx - fw * 0.55f + sweep, cy, radius, radius, 0.62f * redOn * e);
        glow(canvas, blueGlow, cx + fw * 0.55f + sweep, cy, radius, radius, 0.62f * blueOn * e);
    }

    private void glow(Canvas canvas, Shader shader, float x, float y, float rx, float ry, float alpha) {
        if (alpha <= 0f) return;
        canvas.save();
        canvas.translate(x, y);
        canvas.scale(rx, ry);
        fill.setShader(shader);
        fill.setAlpha(Math.round(255 * clamp01(alpha)));
        canvas.drawCircle(0, 0, 1f, fill);
        fill.setShader(null);
        canvas.restore();
    }

    // Spotlight: the room dims and a soft beam from above lights the figure.
    private void spotlight(Canvas canvas, float t, int w, int h) {
        float e = envelope(t, 0.2f, 0.3f);
        darken(canvas, 0.45f * e, w, h);
        float feet = footY(h), cx = figure.centerX(), fw = figure.width();
        canvas.save();
        canvas.scale(1f, feet);
        path.reset();
        path.moveTo(cx - 0.12f * fw, 0);
        path.lineTo(cx + 0.12f * fw, 0);
        path.lineTo(cx + 0.75f * fw, 1f);
        path.lineTo(cx - 0.75f * fw, 1f);
        path.close();
        fill.setShader(beam);
        fill.setAlpha(Math.round(255 * e));
        canvas.drawPath(path, fill);
        fill.setShader(null);
        canvas.restore();
        glow(canvas, warmGlow, cx, feet, fw * 0.8f, figure.height() * 0.06f, 0.35f * e);
    }

    // Fire embers: sparks rising from a warm glow at the feet, swaying as they go.
    /** Where the embers start: low on the figure, but above the lineup's dark bottom edge. */
    private float emberBase(int h) {
        return Math.min(figure.top + figure.height() * 0.8f, h * 0.86f);
    }

    private void embers(Canvas canvas, int h) {
        float base = emberBase(h);
        fill.setShader(null);
        for (int i = 0; i < 22; i++) {
            float x0 = figure.centerX() + rand(-0.6f, 0.6f) * figure.width();
            float y0 = base - rand(0f, 0.4f) * figure.height();
            float rise = figure.height() * rand(0.4f, 0.8f), delay = rand(0f, 450f), life = rand(500f, 750f);
            float sway = rand(6f, 16f) * density, phase = rand(0f, 6.28f), size = rand(2.6f, 5f) * density;
            float local = (ms - delay) / life;
            if (local <= 0f || local >= 1f) continue;
            float x = x0 + (float) Math.sin(local * 5f + phase) * sway, y = y0 - rise * easeOut(local);
            fill.setColor(local < 0.5f ? 0xFFFFC14D : 0xFFFF6A1E);
            fill.setAlpha(Math.round(255 * (float) Math.sin(Math.PI * local)));
            canvas.drawCircle(x, y, size * (1f - 0.4f * local), fill);
        }
    }

    // Snow: soft flakes drifting down around the figure.
    private void snow(Canvas canvas, float t, int w) {
        float e = envelope(t, 0.15f, 0.3f);
        float span = Math.max(figure.width() * 2.2f, 240 * density);
        fill.setShader(null);
        fill.setColor(0xFFFFFFFF);
        for (int i = 0; i < 28; i++) {
            float x0 = figure.centerX() + rand(-0.5f, 0.5f) * span;
            float y0 = figure.top + figure.height() * rand(-0.4f, 0.5f);
            float fall = figure.height() * rand(0.3f, 0.6f), size = rand(1.8f, 4f) * density;
            float sway = rand(4f, 12f) * density, phase = rand(0f, 6.28f);
            float x = x0 + (float) Math.sin(t * 6f + phase) * sway, y = y0 + fall * t;
            if (x < 0f || x > w) continue;
            fill.setAlpha(Math.round(220 * e * (0.5f + 0.5f * size / (4f * density))));
            canvas.drawCircle(x, y, size, fill);
        }
    }

    // Lightning: a jagged bolt from the top of the screen to beside the head, with the flashes (behind).
    private void lightning(Canvas canvas, int w) {
        float side = random.nextBoolean() ? 1f : -1f;
        float x1 = headX + side * headSize * rand(0.9f, 1.4f), y1 = headY - headSize * 0.1f;
        float x0 = Math.max(0f, Math.min(w, x1 + side * figure.width() * rand(0.2f, 0.6f)));
        int n = 9;
        float bx = x0, by = 0f, seg = y1 / n;
        path.reset();
        path.moveTo(x0, 0f);
        for (int k = 1; k <= n; k++) {
            float jitter = k == n ? 0f : rand(-1f, 1f) * seg * 0.6f;
            float x = x0 + (x1 - x0) * k / n + jitter, y = seg * k;
            path.lineTo(x, y);
            if (k == 4) { bx = x; by = y; }
        }
        path.moveTo(bx, by);
        for (int k = 1; k <= 3; k++) path.lineTo(bx + side * seg * 0.7f * k + rand(-0.3f, 0.3f) * seg, by + seg * 0.8f * k);
        float flash = lightningFlash(ms);
        float a = ms < 40f ? 0f : (1f - smooth((ms - 330f) / 260f)) * (0.55f + 0.45f * Math.min(1f, flash * 1.4f));
        if (a <= 0f) return;
        stroke.setColor(0xFF9EC8FF);
        stroke.setStrokeWidth(8f * density);
        stroke.setAlpha(Math.round(90 * a));
        canvas.drawPath(path, stroke);
        stroke.setColor(0xFFFFFFFF);
        stroke.setStrokeWidth(2.4f * density);
        stroke.setAlpha(Math.round(255 * a));
        canvas.drawPath(path, stroke);
    }

    // Magic sparks: gold and violet sparkles circling the figure and rising.
    private void magicSparks(Canvas canvas) {
        float cx = figure.centerX(), cy = figure.top + figure.height() * 0.45f;
        float rx = figure.width() * 0.62f, ry = figure.height() * 0.42f;
        float dir = random.nextBoolean() ? 1f : -1f;
        for (int i = 0; i < 18; i++) {
            float a0 = rand(0f, 6.28f), speed = rand(2.4f, 4.2f), lift = rand(-0.15f, 0.15f), delay = rand(0f, 300f);
            float size = rand(5f, 11f) * density;
            boolean gold = random.nextBoolean();
            float local = (ms - delay) / 700f;
            if (local <= 0f || local >= 1f) continue;
            float a = a0 + dir * speed * local;
            float x = cx + (float) Math.cos(a) * rx * (0.7f + 0.3f * local);
            float y = cy + (float) Math.sin(a) * ry * 0.55f + lift * figure.height() - local * figure.height() * 0.12f;
            float k = (float) Math.sin(Math.PI * local);
            sparkle(canvas, x, y, size * k, gold ? 0xFFFFE07A : 0xFFC9A2FF, Math.round(255 * k));
        }
    }

    private void sparkle(Canvas canvas, float x, float y, float s, int color, int alpha) {
        stroke.setColor(color);
        stroke.setAlpha(alpha);
        stroke.setStrokeWidth(1.6f * density);
        canvas.drawLine(x - s, y, x + s, y, stroke);
        canvas.drawLine(x, y - s, x, y + s, stroke);
        fill.setShader(null);
        fill.setColor(0xFFFFFFFF);
        fill.setAlpha(alpha);
        canvas.drawCircle(x, y, Math.max(1f * density, s * 0.22f), fill);
    }

    // Stars: the room goes to night, small stars twinkle across it, and one shooting star crosses.
    private void stars(Canvas canvas, float t, int w, int h) {
        float e = envelope(t, 0.15f, 0.3f);
        darken(canvas, 0.35f * e, w, h);
        fill.setShader(null);
        fill.setColor(0xFFEAF2FF);
        for (int i = 0; i < 34; i++) {
            float x = rand(0.03f, 0.97f) * w, y = rand(0.04f, 0.7f) * h, size = rand(0.8f, 2.2f) * density;
            float phase = rand(0f, 6.28f);
            float twinkle = 0.55f + 0.45f * (float) Math.sin(ms / 90f + phase);
            fill.setAlpha(Math.round(230 * e * twinkle));
            canvas.drawCircle(x, y, size, fill);
        }
        float sx = rand(0.15f, 0.6f) * w, sy = rand(0.08f, 0.3f) * h;
        float local = (ms - 250f) / 450f;
        if (local <= 0f || local >= 1f) return;
        float length = 0.22f * w, x = sx + length * local, y = sy + length * 0.35f * local;
        float tail = 0.25f * length * (float) Math.sin(Math.PI * local);
        stroke.setColor(0xFFFFFFFF);
        stroke.setStrokeWidth(2f * density);
        stroke.setAlpha(Math.round(230 * (float) Math.sin(Math.PI * local)));
        canvas.drawLine(x - tail, y - tail * 0.35f, x, y, stroke);
    }

    // Green fog: low green puffs drifting around the legs, most behind the figure and a few in front.
    private void fog(Canvas canvas, float t, int h, boolean front) {
        if (front) random.setSeed(seed * 3 + 1);
        float e = envelope(t, 0.2f, 0.35f);
        float feet = footY(h), fw = figure.width(), fh = figure.height();
        for (int i = 0; i < (front ? 4 : 7); i++) {
            float x0 = figure.centerX() + rand(-0.9f, 0.9f) * fw, y = feet - rand(0f, front ? 0.12f : 0.3f) * fh;
            float rx = fw * rand(0.35f, 0.7f), ry = fh * rand(0.06f, 0.12f), drift = rand(-1f, 1f) * fw * 0.25f;
            glow(canvas, greenGlow, x0 + drift * t, y, rx, ry, (front ? 0.28f : 0.5f) * e);
        }
    }

    // Rain: thin slanted streaks falling past the figure (the room dims a little behind).
    private void rain(Canvas canvas, float t, int h) {
        float e = envelope(t, 0.12f, 0.3f);
        float span = Math.max(figure.width() * 2.4f, 300 * density);
        stroke.setColor(0xFFCFE3FF);
        stroke.setStrokeWidth(1.4f * density);
        stroke.setAlpha(Math.round(110 * e));
        for (int i = 0; i < 36; i++) {
            float x0 = figure.centerX() + rand(-0.5f, 0.5f) * span, offset = rand(0f, 1f);
            float length = rand(16f, 30f) * density, speed = rand(0.9f, 1.3f);
            float y = ((offset + ms / 700f * speed) % 1f) * h * 1.1f - 0.05f * h;
            float x = x0 - y / h * 30 * density;
            canvas.drawLine(x, y, x - 0.25f * length, y + length, stroke);
        }
    }

    // Gold coins: coins tumbling down onto the figure's feet, spinning as they fall.
    private void coins(Canvas canvas, int h) {
        float feet = footY(h);
        fill.setShader(null);
        for (int i = 0; i < 11; i++) {
            float x = figure.centerX() + rand(-0.7f, 0.7f) * figure.width();
            float y0 = figure.top - figure.height() * rand(0.1f, 0.35f), delay = rand(0f, 400f);
            float r = rand(6f, 10f) * density, spin = rand(8f, 14f), phase = rand(0f, 6.28f);
            float local = (ms - delay) / 650f;
            if (local <= 0f || local >= 1.15f) continue;
            float fall = Math.min(1f, local);
            float y = Math.min(feet - r, y0 + (feet - y0) * fall * fall);
            float half = r * Math.max(0.15f, Math.abs((float) Math.cos(phase + spin * local)));
            int alpha = Math.round(255 * (local < 1f ? 1f : 1f - (local - 1f) / 0.15f));
            box.set(x - half, y - r, x + half, y + r);
            fill.setColor(0xFFF5C542);
            fill.setAlpha(alpha);
            canvas.drawOval(box, fill);
            stroke.setColor(0xFFB8860B);
            stroke.setStrokeWidth(1.2f * density);
            stroke.setAlpha(alpha);
            canvas.drawOval(box, stroke);
            if (half > r * 0.6f) {
                fill.setColor(0xFFFFF6D0);
                fill.setAlpha(Math.round(alpha * 0.8f));
                canvas.drawCircle(x - half * 0.35f, y - r * 0.35f, r * 0.18f, fill);
            }
        }
    }

    // Gun sparks: three muzzle flashes beside the figure, each throwing a few sparks (recoil in motion()).
    private void gunSparks(Canvas canvas) {
        float side = (seed & 1) == 0 ? 1f : -1f;
        fill.setShader(null);
        for (int b = 0; b < GUN_BURSTS.length; b++) {
            float x = figure.centerX() + side * figure.width() * rand(0.38f, 0.55f);
            float y = figure.top + figure.height() * rand(0.35f, 0.6f);
            float length = rand(16f, 28f) * density, turn = rand(0f, 0.8f);
            float local = (ms - GUN_BURSTS[b]) / 140f, flying = (ms - GUN_BURSTS[b]) / 300f;
            if (local > 0f && local < 1f) {
                float k = 1f - local;
                stroke.setColor(0xFFFFE9A8);
                stroke.setStrokeWidth(2.4f * density);
                stroke.setAlpha(Math.round(255 * k));
                for (int r = 0; r < 8; r++) {
                    double a = turn + r * Math.PI / 4;
                    float l = length * k * (r % 2 == 0 ? 1f : 0.55f);
                    canvas.drawLine(x, y, x + (float) Math.cos(a) * l, y + (float) Math.sin(a) * l, stroke);
                }
                fill.setColor(0xFFFFFFFF);
                fill.setAlpha(Math.round(255 * k));
                canvas.drawCircle(x, y, (4f * k + 1.5f) * density, fill);
            }
            for (int i = 0; i < 6; i++) {
                float a = rand(0f, 6.28f), v = rand(30f, 70f) * density;
                if (flying <= 0f || flying >= 1f) continue;
                fill.setColor(0xFFFFB84D);
                fill.setAlpha(Math.round(255 * (1f - flying)));
                canvas.drawCircle(x + (float) Math.cos(a) * v * flying,
                        y + (float) Math.sin(a) * v * flying + 40 * density * flying * flying, 1.6f * density, fill);
            }
        }
    }

    // Petals: pink petals drifting down on a breeze, turning as they fall.
    private void petals(Canvas canvas, float t) {
        float e = envelope(t, 0.12f, 0.3f);
        float span = Math.max(figure.width() * 2.2f, 260 * density);
        float wind = random.nextBoolean() ? 1f : -1f;
        fill.setShader(null);
        for (int i = 0; i < 16; i++) {
            float x0 = figure.centerX() + rand(-0.5f, 0.5f) * span, y0 = figure.top - figure.height() * rand(0f, 0.35f);
            float fall = figure.height() * rand(0.4f, 0.75f), drift = wind * rand(20f, 60f) * density;
            float size = rand(4f, 7f) * density, spin = rand(-200f, 200f), phase = rand(0f, 6.28f);
            canvas.save();
            canvas.translate(x0 + drift * t + (float) Math.sin(t * 5f + phase) * 8 * density, y0 + fall * t);
            canvas.rotate(phase * 57f + spin * t);
            box.set(-size, -size * 0.55f, size, size * 0.55f);
            fill.setColor(i % 2 == 0 ? 0xFFFFA8C8 : 0xFFFFD1E0);
            fill.setAlpha(Math.round(230 * e));
            canvas.drawOval(box, fill);
            canvas.restore();
        }
    }

    // Ghost shimmer: two pale see-through copies of the figure slipping out to either side and up.
    private void ghostShimmer(Canvas canvas, Bitmap fig) {
        if (fig == null) return;
        float e = envelope(ms / durationMs(GHOST_SHIMMER), 0.15f, 0.35f);
        ghost.setColorFilter(pale);
        for (int k = 0; k < 2; k++) {
            float dx = (k == 0 ? -1f : 1f) * (6f + 10f * smooth(ms / 900f)) * density
                    + (float) Math.sin(ms / 110f + k * 2f) * 3 * density;
            float dy = -figure.height() * 0.025f * smooth(ms / 1000f) * (k + 1);
            float pulse = 0.5f + 0.5f * (float) Math.sin(ms / 70f + k * 1.3f);
            box.set(figure.left + dx, figure.top + dy, figure.right + dx, figure.bottom + dy);
            ghost.setAlpha(Math.round(255 * (0.2f + 0.14f * pulse) * e));
            canvas.drawBitmap(fig, null, box, ghost);
        }
    }
}
