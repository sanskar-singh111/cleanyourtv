package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * weather_scene: the real weather on a scene wallpaper. Everything sits between the scene's layers, so the front layer, which is
 * the land the sky mask cut out, keeps the sky's weather behind the peaks: sky picture and clouds under the clock, mist, haze and
 * rain over the land. Nothing is made here: pictures are decoded once, off the main thread, onto the GPU, and a frame only moves
 * them. Nothing runs while Home isn't what's on screen, nor on a look that doesn't move. Looks change in a crossfade.
 */
final class WeatherScene implements Choreographer.FrameCallback {
    /** How long a look takes to change, and the first one to show. */
    private static final int FADE_MS = 4000, FIRST_MS = 300;
    /** Where the mist's two bands stand and how tall they are, and how tall the clouds are, in pixels at 1080; TV Studio's the same. */
    private static final float CLOUD_TALL = 760, MIST_TOP = 610, MIST_TALL = 300, MIST_APART = 120, MIST_WIDE = 1.6f, HAZE_TOP = 470, TREES_TOP = 690;
    /** The nearer cloud layer: its size, its speed and how solid it is, against the far one. */
    private static final float NEAR_SIZE = 1.3f, NEAR_SPEED = 1.7f, NEAR_ALPHA = 0.55f;
    private static final float MIST_SPEED = 5;
    /** During the idle fade things move this much slower, and only every second frame is drawn. */
    private static final float IDLE_SPEED = 0.45f;
    /** Lightning: the fewest and the most seconds between flashes, and the flash's colour. A bolt goes with a flash, at most this often. */
    private static final float FLASH_MIN_S = 6, FLASH_MAX_S = 18, BOLT_MIN_S = 15, BOLT_MAX_S = 20;
    /** A bolt only shows over the first part of its flash. */
    private static final int BOLT_MS = 420;
    private static final int FLASH_COLOR = 0xFFC8D6FF;
    private static final String TAG = "TvHome.Weather", PREFS = "weather_test";

    /** The pictures in the digits and the dock's glass, which change with the sky: this look's made ready, then brought in. */
    interface Glass {
        void show(WeatherLooks.Look look);

        void fade(float amount);
    }

    private final FrameLayout root;
    private final SceneLayers scene;
    private final WeatherLooks looks;
    private final Glass glass;
    private final SharedPreferences prefs;
    private final int width, height;
    private final float scale;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Choreographer choreographer = Choreographer.getInstance();
    private final Random random = new Random();
    private final WeatherSky sky;
    private final Runnable testOver = this::endTest;

    private Stage stage, leaving;
    private String real = WeatherLooks.CLEAR, test, wanted, loading;
    private boolean onScreen, idle, running, skipFrame, fading;
    private long lastFrame, fadeStart;
    private int fadeMs;
    private float seconds;
    private long flashAt, flashStart, boltAt;
    /** The land's colour and saturation before the fade, and where the fade ends. */
    private float[] toneFrom = {1, 1, 1, 1}, toneTo = {1, 1, 1, 1}, toneNow = {1, 1, 1, 1};

    /** One look's views. */
    private final class Stage {
        final WeatherLooks.Look look;
        ImageView skyView, hazeView, treesView, wetView, edgeView;
        View flashView;
        WeatherBolt bolt;
        CloudLayer far, near, mistBack, mistFront;
        final List<View> views = new ArrayList<>();

        Stage(WeatherLooks.Look look) {
            this.look = look;
        }

        void remove() {
            for (View v : views) root.removeView(v);
            views.clear();
        }

        /** How much of this stage shows, 0 to 1, over what is under it. */
        void show(float amount, boolean coverSky) {
            if (skyView != null && coverSky) skyView.setAlpha(amount);
            if (far != null) far.setAlpha(amount * look.cloudAlpha / 100f);
            if (near != null) near.setAlpha(amount * NEAR_ALPHA * look.cloudAlpha / 100f);
            float mist = amount * look.mist / 100f;
            if (mistBack != null) mistBack.setAlpha(mist);
            if (mistFront != null) mistFront.setAlpha(mist);
            if (treesView != null) treesView.setAlpha(amount);
            if (wetView != null) wetView.setAlpha(amount * look.wet / 100f);
            if (hazeView != null) hazeView.setAlpha(amount * look.haze / 100f);
        }
    }

    WeatherScene(Context context, FrameLayout root, SceneLayers scene, WeatherLooks looks, Glass glass) {
        this.root = root;
        this.scene = scene;
        this.looks = looks;
        this.glass = glass;
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        scale = height / 1080f;
        sky = new WeatherSky(context);
        sky.setVisibility(View.GONE);
        root.addView(sky, root.indexOfChild(scene.front()) + 1, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        long left = prefs.getLong("until", 0) - System.currentTimeMillis();
        if (left > 0) {
            test = prefs.getString("look", null);
            root.postDelayed(testOver, left);
        }
    }

    /** The look being tested, if a test is still running, else null: what Home should open with besides the weather. */
    static String testLook(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return p.getLong("until", 0) > System.currentTimeMillis() ? p.getString("look", null) : null;
    }

    /** The reading kept from last time, or nothing: the look to open with. */
    void reading(Weather.Reading reading) {
        real = reading == null ? WeatherLooks.CLEAR : WeatherLooks.look(reading.code, reading.metres, reading.pm25);
        choose();
    }

    /** Test weather: this look for `seconds`, then the real weather. An unknown look ends the test. */
    void test(String look, int seconds) {
        root.removeCallbacks(testOver);
        if (seconds <= 0 || !(WeatherLooks.CLEAR.equals(look) || looks.looks.containsKey(look))) {
            endTest();
            return;
        }
        test = look;
        prefs.edit().putString("look", look).putLong("until", System.currentTimeMillis() + seconds * 1000L).apply();
        root.postDelayed(testOver, seconds * 1000L);
        choose();
    }

    private void endTest() {
        test = null;
        prefs.edit().clear().apply();
        choose();
    }

    private void choose() {
        String look = test != null ? test : real;
        if (look.equals(wanted)) return;
        wanted = look;
        Log.i(TAG, "look " + look);
        change();
    }

    /** Whether Home is what's on screen, whether it has faded, whether rain may show (not over More apps). */
    void motion(boolean homeOnScreen, boolean idle, boolean particles) {
        this.idle = idle;
        sky.setVisibility(particles && !sky.empty() ? View.VISIBLE : View.GONE);
        if (homeOnScreen == onScreen) return;
        onScreen = homeOnScreen;
        if (onScreen) {
            change();
            run();
        } else {
            running = false;
            choreographer.removeFrameCallback(this);
            endFlash();
            if (fading) finishFade();
        }
    }

    private void change() {
        if (!onScreen || wanted == null || loading != null || fading) return;
        if (stage != null ? wanted.equals(stage.look.id) : WeatherLooks.CLEAR.equals(wanted)) return;
        WeatherLooks.Look look = looks.looks.get(wanted);
        if (look == null) look = new WeatherLooks.Look(WeatherLooks.CLEAR);
        WeatherLooks.Look next = look;
        int ms = stage == null && seconds == 0 ? FIRST_MS : FADE_MS;
        seconds = Math.max(seconds, 0.001f);
        loading = wanted;
        decoder.execute(() -> {
            Stage made = decode(next);
            root.post(() -> {
                loading = null;
                if (made != null) begin(made, ms);
                change();
            });
        });
    }

    /** The bitmaps on the GPU, and the views still empty. */
    private Stage decode(WeatherLooks.Look look) {
        Stage s = new Stage(look);
        Context context = root.getContext();
        s.skyView = picture(context, SceneLayers.onGpu(SceneLayers.decode(look.sky)));
        Bitmap clouds = SceneLayers.onGpu(SceneLayers.decode(look.clouds));
        if (clouds != null) {
            float wide = clouds.getWidth() * CLOUD_TALL * scale / clouds.getHeight();
            s.far = new CloudLayer(context, clouds, Math.round(wide), Math.round(CLOUD_TALL * scale), look.cloudTint, false);
            s.near = new CloudLayer(context, clouds, Math.round(wide * NEAR_SIZE), Math.round(CLOUD_TALL * scale * NEAR_SIZE), look.cloudTint, true);
        }
        if (look.mist > 0) {
            Bitmap mist = SceneLayers.onGpu(SceneLayers.decode(looks.mist));
            if (mist != null) {
                int wide = Math.round(width * MIST_WIDE), tall = Math.round(MIST_TALL * scale);
                s.mistBack = new CloudLayer(context, mist, wide, tall, Color.WHITE, false);
                s.mistFront = new CloudLayer(context, mist, wide, tall, Color.WHITE, false);
            }
            s.treesView = picture(context, SceneLayers.onGpu(SceneLayers.decode(looks.trees)));
        }
        if (look.wet > 0) {
            s.wetView = picture(context, SceneLayers.onGpu(SceneLayers.decode(looks.trees)));
            if (s.wetView != null) s.wetView.setColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN);
        }
        if (look.rainClose > 0 && !sky.hasStreak()) {
            Bitmap streak = SceneLayers.onGpu(SceneLayers.decode(looks.streak));
            root.post(() -> sky.streak(streak));
        }
        if (look.haze > 0) s.hazeView = picture(context, SceneLayers.onGpu(SceneLayers.decode(looks.haze)));
        if (look.flash > 0) {
            s.edgeView = picture(context, SceneLayers.onGpu(SceneLayers.decode(looks.edge)));
            s.flashView = new View(context);
            s.flashView.setBackgroundColor(FLASH_COLOR);
            s.flashView.setVisibility(View.INVISIBLE);
            s.flashView.setFocusable(false);
            s.flashView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            s.bolt = new WeatherBolt(context);
            s.bolt.setVisibility(View.INVISIBLE);
        }
        return s;
    }

    private static ImageView picture(Context context, Bitmap bitmap) {
        if (bitmap == null) return null;
        ImageView v = new ImageView(context);
        v.setScaleType(ImageView.ScaleType.FIT_XY);
        v.setImageBitmap(bitmap);
        v.setFocusable(false);
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        v.setAlpha(0f);
        return v;
    }

    private void add(Stage s, View view, int index, FrameLayout.LayoutParams lp) {
        if (view == null) return;
        root.addView(view, index, lp);
        s.views.add(view);
    }

    private FrameLayout.LayoutParams full() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private FrameLayout.LayoutParams from(float top, float tall) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(tall * scale), Gravity.TOP);
        lp.topMargin = Math.round(top * scale);
        return lp;
    }

    /** The sky's views go over the old stage's and under the clock; the land's over the front layer, under the rain. */
    private void begin(Stage next, int ms) {
        int under = stage == null ? root.indexOfChild(scene.back()) + 1 : firstAbove(stage);
        if (next.skyView != null) add(next, next.skyView, under++, full());
        // Between the cloud layers: the near clouds stand dark against the flash and keep their shapes, and the sky still lights up.
        if (next.far != null) add(next, next.far, under++, next.far.params(0));
        if (next.flashView != null) add(next, next.flashView, under++, full());
        if (next.near != null) add(next, next.near, under++, next.near.params(0));
        // In the distant sky: over the clouds, behind the clock and the land.
        if (next.bolt != null) add(next, next.bolt, under++, full());
        int over = root.indexOfChild(sky);
        if (next.mistBack != null) {
            add(next, next.mistBack, over++, next.mistBack.params(Math.round(MIST_TOP * scale)));
            add(next, next.mistFront, over++, next.mistFront.params(Math.round((MIST_TOP + MIST_APART) * scale)));
        }
        if (next.treesView != null) add(next, next.treesView, over++, from(TREES_TOP, 1080 - TREES_TOP));
        if (next.wetView != null) add(next, next.wetView, over++, from(TREES_TOP, 1080 - TREES_TOP));
        if (next.hazeView != null) add(next, next.hazeView, over++, from(HAZE_TOP, 1080 - HAZE_TOP));
        if (next.edgeView != null) {
            next.edgeView.setVisibility(View.INVISIBLE);
            next.edgeView.setAlpha(1f);
            add(next, next.edgeView, over, from(HAZE_TOP, 1080 - HAZE_TOP));
        }
        scene.back().setVisibility(View.VISIBLE);
        leaving = stage;
        stage = next;
        next.show(0f, true);
        toneFrom = toneNow;
        toneTo = toneOf(next.look);
        fading = true;
        fadeStart = System.nanoTime();
        fadeMs = ms;
        glass.show(next.look);
        run();
    }

    private int firstAbove(Stage s) {
        View top = s.near != null ? s.near : s.skyView;
        return top == null ? root.indexOfChild(scene.back()) + 1 : root.indexOfChild(top) + 1;
    }

    private void run() {
        if (running || !onScreen || stage == null) return;
        if (!fading && !stage.look.moves()) return;
        running = true;
        lastFrame = 0;
        scheduleFlash();
        choreographer.postFrameCallback(this);
    }

    @Override
    public void doFrame(long nanos) {
        if (!running) return;
        choreographer.postFrameCallback(this);
        if (idle && (skipFrame = !skipFrame)) return;
        float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (nanos - lastFrame) / 1e9f);
        lastFrame = nanos;
        seconds += dt * (idle ? IDLE_SPEED : 1f);

        float amount = 1f;
        if (fading) {
            amount = Math.min(1f, (System.nanoTime() - fadeStart) / 1e6f / fadeMs);
            stage.show(amount, true);
            // A sky under the new one stays solid: fading both would let the photo through. With no new sky it fades away.
            if (leaving != null) leaving.show(1f - amount, stage.skyView == null);
            applyTone(amount);
            glass.fade(amount);
        }
        moveClouds(stage);
        if (leaving != null) moveClouds(leaving);
        drops(amount);
        if (fading && amount >= 1f) finishFade();
        if (!fading && stage.look.flash > 0) lightning();
        if (!fading && !stage.look.moves()) {
            running = false;
            choreographer.removeFrameCallback(this);
        }
    }

    private void moveClouds(Stage s) {
        float drift = s.look.cloudDrift * scale * seconds;
        if (s.far != null) s.far.place(drift);
        if (s.near != null) s.near.place(drift * NEAR_SPEED + 900 * scale);
        float mist = MIST_SPEED * scale * seconds;
        if (s.mistBack != null) s.mistBack.place(mist);
        if (s.mistFront != null) s.mistFront.place(-mist * 1.7f + 420 * scale);
    }

    private void drops(float amount) {
        WeatherLooks.Look to = stage.look, from = leaving == null ? null : leaving.look;
        float rain = to.rain * amount, close = to.rainClose * amount, snow = to.snow * amount;
        float alpha = to.rainAlpha / 100f, fast = to.rainSpeed / 100f, size = to.rainSize / 100f;
        if (from != null) {
            rain += from.rain * (1f - amount);
            close += from.rainClose * (1f - amount);
            snow += from.snow * (1f - amount);
            if (to.rain == 0) {
                alpha = from.rainAlpha / 100f;
                fast = from.rainSpeed / 100f;
                size = from.rainSize / 100f;
            }
        }
        sky.set(seconds, rain, alpha, fast, size, close, snow);
        sky.setVisibility(sky.empty() ? View.GONE : View.VISIBLE);
    }

    private void finishFade() {
        fading = false;
        Stage old = leaving;
        leaving = null;
        if (old != null) old.remove();
        stage.show(1f, true);
        applyTone(1f);
        glass.fade(1f);
        scene.back().setVisibility(stage.skyView != null ? View.INVISIBLE : View.VISIBLE);
        drops(1f);
        change();
    }

    private void applyTone(float amount) {
        float[] now = new float[4];
        for (int i = 0; i < 4; i++) now[i] = toneFrom[i] + (toneTo[i] - toneFrom[i]) * amount;
        toneNow = now;
        ImageView front = scene.front();
        if (amount >= 1f && now[0] == 1 && now[1] == 1 && now[2] == 1 && now[3] == 1) {
            front.setColorFilter(null);
            return;
        }
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(now[3]);
        ColorMatrix colour = new ColorMatrix();
        colour.setScale(now[0], now[1], now[2], 1f);
        matrix.postConcat(colour);
        front.setColorFilter(new ColorMatrixColorFilter(matrix));
    }

    private static float[] toneOf(WeatherLooks.Look look) {
        return new float[]{Color.red(look.land) / 255f, Color.green(look.land) / 255f, Color.blue(look.land) / 255f, look.landSaturation / 100f};
    }

    private void strikeBolt(long now) {
        if (stage.bolt == null || now < boltAt) return;
        boltAt = now + (long) ((BOLT_MIN_S + random.nextFloat() * (BOLT_MAX_S - BOLT_MIN_S)) * 1e9f);
        stage.bolt.strike(random, width, scale);
        stage.bolt.setVisibility(View.VISIBLE);
    }

    private void scheduleFlash() {
        float wait = FLASH_MIN_S + random.nextFloat() * (FLASH_MAX_S - FLASH_MIN_S);
        flashAt = System.nanoTime() + (long) (wait * 1e9f);
        flashStart = 0;
    }

    /** How bright a flash is `ms` in: a stroke and a weaker return, gone in 0.7 s. */
    static float flash(float ms) {
        if (ms < 60) return ms / 60;
        if (ms < 140) return 1f - 0.65f * (ms - 60) / 80;
        if (ms < 200) return 0.35f - 0.1f * (ms - 140) / 60;
        if (ms < 250) return 0.25f + 0.5f * (ms - 200) / 50;
        if (ms < 700) return 0.75f * (1f - (ms - 250) / 450);
        return 0;
    }

    private void lightning() {
        long now = System.nanoTime();
        if (flashStart == 0) {
            if (now < flashAt || idle && random.nextBoolean()) {
                if (now >= flashAt) scheduleFlash();
                return;
            }
            flashStart = now;
            if (stage.flashView != null) stage.flashView.setVisibility(View.VISIBLE);
            if (stage.edgeView != null) stage.edgeView.setVisibility(View.VISIBLE);
            strikeBolt(now);
        }
        float ms = (now - flashStart) / 1e6f, f = flash(ms) * stage.look.flash / 100f;
        if (stage.bolt != null) {
            stage.bolt.setAlpha(Math.min(1f, flash(ms) * 1.3f));
            if (ms >= BOLT_MS) stage.bolt.setVisibility(View.INVISIBLE);
        }
        if (stage.flashView != null) stage.flashView.setAlpha(Math.min(1f, f * 1.2f));
        if (stage.edgeView != null) stage.edgeView.setAlpha(f);
        if (ms >= 700) {
            endFlash();
            scheduleFlash();
        }
    }

    private void endFlash() {
        if (stage == null) return;
        if (stage.flashView != null) stage.flashView.setVisibility(View.INVISIBLE);
        if (stage.edgeView != null) stage.edgeView.setVisibility(View.INVISIBLE);
        if (stage.bolt != null) stage.bolt.setVisibility(View.INVISIBLE);
        flashStart = 0;
    }

    void close() {
        running = false;
        choreographer.removeFrameCallback(this);
        root.removeCallbacks(testOver);
        decoder.shutdownNow();
    }

    /**
     * A texture that repeats across the screen and only moves: it is drawn side by side and the view slides, so a frame changes
     * one number and records nothing. Two copies of one GPU picture, so one texture however long the screen.
     */
    private static final class CloudLayer extends View {
        private final Bitmap bitmap;
        private final int wide, tall;
        private final boolean mirrored;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF place = new RectF();

        CloudLayer(Context context, Bitmap bitmap, int wide, int tall, int tint, boolean mirrored) {
            super(context);
            this.bitmap = bitmap;
            this.wide = wide;
            this.tall = tall;
            this.mirrored = mirrored;
            setFocusable(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setWillNotDraw(false);
            setAlpha(0f);
            if ((tint & 0xFFFFFF) != 0xFFFFFF) paint.setColorFilter(new PorterDuffColorFilter(tint, PorterDuff.Mode.MULTIPLY));
        }

        /** Two widths at the screen's left edge. */
        FrameLayout.LayoutParams params(int top) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(wide * 2, tall);
            lp.topMargin = top;
            return lp;
        }

        @Override
        public boolean hasOverlappingRendering() {
            return false;
        }

        void place(float offset) {
            float x = offset % wide;
            setTranslationX(-(x < 0 ? x + wide : x));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            // Turned round, the same texture at another size doesn't read as a copy of the far layer.
            if (mirrored) canvas.scale(-1f, 1f, wide, 0);
            for (int i = 0; i < 2; i++) {
                place.set(i * wide, 0, (i + 1) * wide, tall);
                canvas.drawBitmap(bitmap, null, place, paint);
            }
        }
    }
}
