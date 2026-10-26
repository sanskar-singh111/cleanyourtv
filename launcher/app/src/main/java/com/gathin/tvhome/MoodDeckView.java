package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The mood deck under the watchlist: one view that draws the deck. The top card is the mood's colour fading to near
 * black, its first three posters fanned out, the mood's name and how many titles it has; the next two moods peek out
 * behind it. Behind everything, a darkened black-and-white backdrop of one of the top mood's titles and a very soft
 * glow in its colour. Left throws the card away, Right or OK spins a reel of its posters that lands on one title at
 * random, then a three-second countdown opens it. Only the posters on screen (and the next card's three) are decoded;
 * no blur, no video, nothing resized into new bitmaps here.
 */
@SuppressLint("ViewConstructor")   // made in code only, never from a layout
final class MoodDeckView extends View {
    interface Listener {
        /** Up, or Back on the deck: back to the watchlist. */
        void onDeckUp();
        void onDeckOpen(MoodDeck.Title title);
    }

    private enum State { IDLE, SPIN, COUNTDOWN }

    private static final long SWIPE_MS = 380, REEL_SHOW_MS = 220, SPIN_MS = 2800, BACKDROP_MS = 450, LAND_MS = 260;
    private static final long TICK_MS = 70;
    private static final int NEAR_BLACK = 0xFF0C0C0E;

    private final List<MoodDeck.Title> titles;
    private final List<MoodDeck.Mood> moods;
    private final Listener listener;
    private final String heading;
    private final Random random = new Random();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float density;

    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint posterPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint placeholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backdropPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint glowPaint = new Paint();
    private final Paint shadePaint = new Paint();
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint headingPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint namePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint countPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint smallPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF(), cardRect = new RectF();
    private final Matrix shaderMatrix = new Matrix();
    private final ArgbEvaluator argb = new ArgbEvaluator();
    /** Each mood's card gradient, made once per size. */
    private final Map<Integer, LinearGradient> cardShaders = new HashMap<>();
    private final Map<Bitmap, BitmapShader> posterShaders = new HashMap<>();

    /** Decoded posters by title index: only those on screen (and the next card's fan, so it rises with them). */
    private final Map<Integer, Bitmap> posters = new HashMap<>();
    private final Set<Integer> loading = new HashSet<>();
    private Bitmap backdrop, previousBackdrop;
    private File backdropFile;
    private float backdropMix = 1f;
    private int backdropGeneration;

    private State state = State.IDLE;
    private int top;
    /** 0 to 1 while the top card flies off to the left and the rest move up one place. */
    private float swipe;
    /** 0 is the fan of posters, 1 the reel window. */
    private float reelShow;
    private List<Integer> reel = new ArrayList<>();
    private float reelPos;
    private int lastTick = -1;
    private long lastTickAt;
    private int picked = -1, lastPicked = -1;
    private float land;
    private float countdown;
    private long countdownStart;
    private ValueAnimator swipeAnimator, reelShowAnimator, spinAnimator, landAnimator, countdownAnimator, backdropAnimator;
    private boolean released = true;

    private final AccelerateInterpolator flyCurve = new AccelerateInterpolator(1.4f);
    private final DecelerateInterpolator riseCurve = new DecelerateInterpolator(1.6f);
    private int glowColor;
    private float shadeHeight;

    // Geometry, from the view's height (a 1080-line screen is the reference).
    private float cw, ch, cx, cy, ph, pw, radius;

    MoodDeckView(Context context, List<MoodDeck.Title> titles, List<MoodDeck.Mood> moods, String heading,
                 Typeface lightFont, Typeface mediumFont, Listener listener) {
        super(context);
        this.titles = titles;
        this.moods = MoodDeck.usable(moods);
        this.heading = heading == null ? "" : heading;
        this.listener = listener;
        density = context.getResources().getDisplayMetrics().density;
        setFocusable(true);
        setFocusableInTouchMode(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        for (TextPaint p : new TextPaint[]{headingPaint, namePaint, countPaint, titlePaint, smallPaint}) {
            p.setColor(Color.WHITE);
            if (lightFont != null) p.setTypeface(lightFont);
        }
        if (mediumFont != null) {
            namePaint.setTypeface(mediumFont);
            smallPaint.setTypeface(mediumFont);
        }
        smallPaint.setLetterSpacing(0.12f);
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setColor(0x1FFFFFFF);
        placeholderPaint.setColor(0x1AFFFFFF);
        barPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    boolean isEmpty() {
        return moods.isEmpty();
    }

    /** Arriving on the deck: the same mood as last time, at rest. */
    void enter() {
        reset();
        load();
    }

    /** Back to the top card at rest: no reel, no countdown. */
    void reset() {
        for (ValueAnimator a : new ValueAnimator[]{swipeAnimator, reelShowAnimator, spinAnimator, landAnimator, countdownAnimator}) {
            if (a != null) a.cancel();
        }
        if (swipe > 0f) top = MoodDeck.behind(top, 1, moods.size());
        swipe = 0f;
        reelShow = 0f;
        land = 0f;
        countdown = 0f;
        picked = -1;
        state = State.IDLE;
        reel = new ArrayList<>();
        loadPosters();
        invalidate();
    }

    /** Brings back what release() let go: the posters on screen and a backdrop of the top mood. */
    void load() {
        released = false;
        loadPosters();
        if (backdrop == null) chooseBackdrop(false);
    }

    /** Lets go of every picture (the deck is off screen or memory is short); {@link #load()} brings them back. */
    void release() {
        released = true;
        backdropGeneration++;
        posters.clear();
        posterShaders.clear();
        loading.clear();
        backdrop = previousBackdrop = null;
        backdropFile = null;
    }

    // ---- pictures --------------------------------------------------------------------------------

    private Set<Integer> wantedPosters() {
        Set<Integer> want = new HashSet<>();
        if (moods.isEmpty()) return want;
        want.addAll(MoodDeck.fan(moods.get(top)));
        want.addAll(MoodDeck.fan(moods.get(MoodDeck.behind(top, 1, moods.size()))));
        // While a swipe runs, the card after the next is on its way up too.
        if (swipeAnimator != null && swipeAnimator.isRunning()) want.addAll(MoodDeck.fan(moods.get(MoodDeck.behind(top, 2, moods.size()))));
        if (state != State.IDLE) want.addAll(reel);
        return want;
    }

    private void loadPosters() {
        if (released) return;
        Set<Integer> want = wantedPosters();
        posters.keySet().retainAll(want);
        posterShaders.keySet().retainAll(posters.values());
        List<Integer> missing = new ArrayList<>();
        for (int i : want) if (!posters.containsKey(i) && !loading.contains(i)) missing.add(i);
        if (missing.isEmpty()) return;
        loading.addAll(missing);
        new Thread(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;   // posters are opaque: half the memory
            Map<Integer, Bitmap> done = new HashMap<>();
            for (int i : missing) {
                Bitmap b = decode(titles.get(i).poster, o);
                if (b != null) done.put(i, b);
            }
            main.post(() -> {
                loading.removeAll(missing);
                if (released) return;
                Set<Integer> still = wantedPosters();
                for (Map.Entry<Integer, Bitmap> e : done.entrySet()) {
                    if (still.contains(e.getKey())) posters.put(e.getKey(), e.getValue());
                }
                invalidate();
            });
        }, "mood-posters").start();
    }

    /** A backdrop from one of the top mood's titles, at random. */
    private void chooseBackdrop(boolean animate) {
        if (moods.isEmpty()) return;
        List<Integer> with = new ArrayList<>();
        for (int i : moods.get(top).titles) if (titles.get(i).backdrop != null) with.add(i);
        if (with.isEmpty()) return;
        showBackdrop(titles.get(with.get(random.nextInt(with.size()))).backdrop, animate);
    }

    private void showBackdrop(File file, boolean animate) {
        if (file == null || released || file.equals(backdropFile)) return;
        backdropFile = file;
        int generation = ++backdropGeneration;
        new Thread(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;   // already black and white
            Bitmap b = decode(file, o);
            main.post(() -> {
                if (generation != backdropGeneration || b == null) return;
                if (backdropAnimator != null) backdropAnimator.cancel();
                previousBackdrop = backdrop;   // at most the two pictures of the crossfade
                backdrop = b;
                if (!animate || previousBackdrop == null) {
                    previousBackdrop = null;
                    backdropMix = 1f;
                    invalidate();
                    return;
                }
                backdropMix = 0f;
                backdropAnimator = ValueAnimator.ofFloat(0f, 1f).setDuration(BACKDROP_MS);
                backdropAnimator.addUpdateListener(a -> {
                    backdropMix = (float) a.getAnimatedValue();
                    if (backdropMix >= 1f) previousBackdrop = null;
                    invalidate();
                });
                backdropAnimator.start();
            });
        }, "mood-backdrop").start();
    }

    private static Bitmap decode(File file, BitmapFactory.Options o) {
        if (file == null || !file.isFile()) return null;
        try {
            return BitmapFactory.decodeFile(file.getPath(), o);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    // ---- what the keys do ------------------------------------------------------------------------

    /** Left: the card flies off to the left and the next mood comes up. */
    private void nope() {
        if (moods.size() < 2) return;
        finishSwipe();
        swipe = 0f;
        swipeAnimator = ValueAnimator.ofFloat(0f, 1f).setDuration(SWIPE_MS);
        swipeAnimator.setInterpolator(new LinearInterpolator());
        swipeAnimator.addUpdateListener(a -> {
            swipe = (float) a.getAnimatedValue();
            invalidate();
        });
        swipeAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (cancelled) return;
                top = MoodDeck.behind(top, 1, moods.size());
                swipe = 0f;
                loadPosters();
                invalidate();
            }
        });
        swipeAnimator.start();
        // The backdrop follows the card that is coming up.
        int next = MoodDeck.behind(top, 1, moods.size());
        List<Integer> with = new ArrayList<>();
        for (int i : moods.get(next).titles) if (titles.get(i).backdrop != null) with.add(i);
        if (!with.isEmpty()) showBackdrop(titles.get(with.get(random.nextInt(with.size()))).backdrop, true);
        // Its posters are already decoded (the next card's fan is kept); the one after it starts now.
        loadPosters();
    }

    /** A swipe still running ends at once, so the next key acts on the card now on top. */
    private void finishSwipe() {
        if (swipeAnimator != null && swipeAnimator.isRunning()) swipeAnimator.end();
    }

    /** Right or OK: the reel spins and lands on one of the mood's titles at random. */
    private void spin() {
        if (moods.isEmpty()) return;
        finishSwipe();
        if (countdownAnimator != null) countdownAnimator.cancel();
        if (spinAnimator != null) spinAnimator.cancel();
        if (landAnimator != null) landAnimator.cancel();
        MoodDeck.Mood m = moods.get(top);
        picked = MoodDeck.pick(random, m, lastPicked);
        lastPicked = picked;
        reel = MoodDeck.reel(random, m, picked);
        state = State.SPIN;
        land = 0f;
        countdown = 0f;
        lastTick = -1;
        loadPosters();
        animateReelShow(1f);
        reelPos = 0f;
        spinAnimator = ValueAnimator.ofFloat(0f, reel.size() - 1).setDuration(SPIN_MS);
        spinAnimator.setInterpolator(new DecelerateInterpolator(1.6f));   // fast, then slowing down
        spinAnimator.addUpdateListener(a -> {
            reelPos = (float) a.getAnimatedValue();
            int passing = Math.round(reelPos);
            long now = SystemClock.uptimeMillis();
            if (passing != lastTick && now - lastTickAt >= TICK_MS) {
                lastTick = passing;
                lastTickAt = now;
                playSoundEffect(SoundEffectConstants.NAVIGATION_DOWN);
            }
            invalidate();
        });
        spinAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (!cancelled) landed();
            }
        });
        spinAnimator.start();
    }

    private void landed() {
        state = State.COUNTDOWN;
        playSoundEffect(SoundEffectConstants.CLICK);
        showBackdrop(titles.get(picked).backdrop, true);
        landAnimator = ValueAnimator.ofFloat(0f, 1f).setDuration(LAND_MS);
        landAnimator.setInterpolator(new DecelerateInterpolator());
        landAnimator.addUpdateListener(a -> {
            land = (float) a.getAnimatedValue();
            invalidate();
        });
        landAnimator.start();
        countdownStart = SystemClock.uptimeMillis();
        countdownAnimator = ValueAnimator.ofFloat(0f, 1f).setDuration(MoodDeck.COUNTDOWN_MS);
        countdownAnimator.setInterpolator(new LinearInterpolator());
        countdownAnimator.addUpdateListener(a -> {
            countdown = (float) a.getAnimatedValue();
            invalidate();
        });
        countdownAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (!cancelled) openPicked();
            }
        });
        countdownAnimator.start();
    }

    private void openPicked() {
        if (picked < 0) return;
        MoodDeck.Title t = titles.get(picked);
        listener.onDeckOpen(t);
        // The app covers Home now; when it comes back, the deck is at rest on the same mood.
        main.postDelayed(this::cancelReel, 600);
        if (countdownAnimator != null) countdownAnimator.cancel();
    }

    /** Left or Back during the reel or the countdown: back to the card at rest. */
    private void cancelReel() {
        if (spinAnimator != null) spinAnimator.cancel();
        if (countdownAnimator != null) countdownAnimator.cancel();
        if (landAnimator != null) landAnimator.cancel();
        state = State.IDLE;
        picked = -1;
        land = 0f;
        countdown = 0f;
        animateReelShow(0f);
        chooseBackdrop(true);
        reel = new ArrayList<>();
        loadPosters();
        invalidate();
    }

    private void animateReelShow(float to) {
        if (reelShowAnimator != null) reelShowAnimator.cancel();
        reelShowAnimator = ValueAnimator.ofFloat(reelShow, to).setDuration(REEL_SHOW_MS);
        reelShowAnimator.addUpdateListener(a -> {
            reelShow = (float) a.getAnimatedValue();
            invalidate();
        });
        reelShowAnimator.start();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (state == State.IDLE) {
                    if (moods.size() > 1) playSoundEffect(SoundEffectConstants.NAVIGATION_LEFT);
                    nope();
                } else {
                    playSoundEffect(SoundEffectConstants.NAVIGATION_LEFT);
                    cancelReel();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (state != State.SPIN) spin();   // during the countdown: spin again
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                playSoundEffect(SoundEffectConstants.NAVIGATION_UP);
                reset();
                listener.onDeckUp();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BACK:
                event.startTracking();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (event.isCanceled()) return true;
                if (state == State.IDLE) {
                    playSoundEffect(SoundEffectConstants.CLICK);
                    spin();
                } else if (state == State.COUNTDOWN) {
                    playSoundEffect(SoundEffectConstants.CLICK);
                    openPicked();   // now, without waiting
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (event.isCanceled()) return true;
                if (state == State.IDLE) {
                    reset();
                    listener.onDeckUp();
                } else {
                    cancelReel();
                }
                return true;
            default:
                return super.onKeyUp(keyCode, event);
        }
    }

    // ---- drawing ---------------------------------------------------------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        ch = 0.58f * h;
        cw = 0.44f * h;
        cx = w / 2f;
        cy = 0.17f * h + ch / 2f;
        ph = 0.30f * h;
        pw = ph * 2f / 3f;
        radius = 0.028f * h;
        edgePaint.setStrokeWidth(Math.max(1f, 0.0012f * h));
        headingPaint.setTextSize(0.036f * h);
        namePaint.setTextSize(0.058f * h);
        countPaint.setTextSize(0.024f * h);
        titlePaint.setTextSize(0.048f * h);
        smallPaint.setTextSize(0.021f * h);
        barPaint.setStrokeWidth(0.007f * h);
        cardShaders.clear();
        glowPaint.setShader(null);
        shadePaint.setShader(null);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.drawColor(Color.BLACK);
        if (w == 0 || h == 0 || moods.isEmpty()) return;
        int n = moods.size();
        float fly = flyCurve.getInterpolation(swipe);
        float rise = riseCurve.getInterpolation(swipe);

        // The backdrop, crossfading.
        if (backdropMix < 1f) drawBackdrop(canvas, previousBackdrop, 1f, w, h);
        drawBackdrop(canvas, backdrop, backdropMix, w, h);

        // A very soft glow in the top mood's colour, turning to the next one's as it comes up.
        int glow = (int) argb.evaluate(rise, moods.get(top).color, moods.get(MoodDeck.behind(top, 1, n)).color);
        if (glow != glowColor || glowPaint.getShader() == null) {
            // Made again only when the colour changes (during a swipe), never while the deck is at rest.
            glowColor = glow;
            glowPaint.setShader(new RadialGradient(cx, cy - 0.02f * h, 0.78f * h,
                    new int[]{withAlpha(glow, 0x3A), withAlpha(glow, 0x14), withAlpha(glow, 0)}, new float[]{0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP));
        }
        canvas.drawRect(0, 0, w, h, glowPaint);

        if (!heading.isEmpty()) {
            float hw = headingPaint.measureText(heading);
            headingPaint.setAlpha(0xD9);
            canvas.drawText(heading, (w - hw) / 2f, 0.088f * h, headingPaint);
        }

        // Back to front: the cards behind (the third one fading in while a swipe moves everything up one place).
        int behind = MoodDeck.peeking(n);
        for (int k = Math.min(behind + (swipe > 0f ? 1 : 0), n - 1); k >= 1; k--) {
            drawCard(canvas, MoodDeck.behind(top, k, n), k - rise, 0f, 0f, 255, h);
        }
        if (swipe > 0f) {
            drawCard(canvas, top, 0f, -fly * (w / 2f + cw), -12f * fly, Math.round(255 * (1f - fly)), h);
        } else {
            drawCard(canvas, top, 0f, 0f, 0f, 255, h);
        }

        drawBelow(canvas, w, h);
    }

    private void drawBackdrop(Canvas canvas, Bitmap b, float alpha, int w, int h) {
        if (b == null || alpha <= 0f) return;
        float scale = Math.max((float) w / b.getWidth(), (float) h / b.getHeight());
        float dw = b.getWidth() * scale, dh = b.getHeight() * scale;
        rect.set((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f);
        backdropPaint.setAlpha(Math.round(alpha * 255));
        canvas.drawBitmap(b, null, rect, backdropPaint);
    }

    /**
     * One card. `level` 0 is the top card, 1 and 2 the ones peeking out behind it (smaller, higher, darker), 3 hidden
     * behind those; in between while the deck moves up. The top card can be moved and tilted as it flies off.
     */
    private void drawCard(Canvas canvas, int moodIndex, float level, float dx, float degrees, int alpha, int h) {
        MoodDeck.Mood m = moods.get(moodIndex);
        float s = 1f - 0.08f * level;
        float y = -0.048f * h * level;
        int a = level <= 2f ? alpha : Math.round(alpha * Math.max(0f, 3f - level));
        if (a <= 0) return;
        canvas.save();
        canvas.translate(cx + dx, cy + y);
        if (degrees != 0f) canvas.rotate(degrees);
        canvas.scale(s, s);
        cardRect.set(-cw / 2f, -ch / 2f, cw / 2f, ch / 2f);
        cardPaint.setShader(cardShader(moodIndex, m.color));
        cardPaint.setAlpha(a);
        canvas.drawRoundRect(cardRect, radius, radius, cardPaint);
        edgePaint.setAlpha(Math.round(0x1F * a / 255f));
        canvas.drawRoundRect(cardRect, radius, radius, edgePaint);

        // What is on it shows only on the top card (and the one rising to take its place).
        float content = Math.max(0f, Math.min(1f, 1f - level));
        if (content > 0f) {
            int ca = Math.round(a * content);
            boolean isTop = level == 0f && moodIndex == top;
            float show = isTop ? reelShow : 0f;
            if (show < 1f) drawFan(canvas, m, Math.round(ca * (1f - show)));
            if (show > 0f) drawReel(canvas, m, Math.round(ca * show), h);
            float nameY = -ch / 2f + 0.45f * h;
            String name = TextUtils.ellipsize(m.name, namePaint, cw - 0.06f * h, TextUtils.TruncateAt.END).toString();
            namePaint.setAlpha(ca);
            canvas.drawText(name, -namePaint.measureText(name) / 2f, nameY, namePaint);
            String count = getResources().getQuantityString(R.plurals.mood_titles, m.titles.size(), m.titles.size());
            countPaint.setAlpha(Math.round(ca * 0.7f));
            canvas.drawText(count, -countPaint.measureText(count) / 2f, nameY + 0.055f * h, countPaint);
        }
        // The cards behind are darker.
        float dim = Math.min(0.62f, 0.34f * level);
        if (dim > 0f) {
            dimPaint.setColor(Color.BLACK);
            dimPaint.setAlpha(Math.round(dim * a));
            canvas.drawRoundRect(cardRect, radius, radius, dimPaint);
        }
        canvas.restore();
    }

    private Shader cardShader(int moodIndex, int color) {
        LinearGradient g = cardShaders.get(moodIndex);
        if (g == null) {
            int mid = (int) argb.evaluate(0.62f, color, NEAR_BLACK);
            g = new LinearGradient(0, -ch / 2f, 0, ch / 2f, new int[]{color, mid, NEAR_BLACK}, new float[]{0f, 0.42f, 0.92f},
                    Shader.TileMode.CLAMP);
            cardShaders.put(moodIndex, g);
        }
        return g;
    }

    /** The first three posters fanned out: the first in the middle and in front, the others tilted behind it. */
    private void drawFan(Canvas canvas, MoodDeck.Mood m, int alpha) {
        List<Integer> fan = MoodDeck.fan(m);
        float fy = -ch / 2f + 0.05f * getHeight() + ph / 2f;
        float off = 0.115f * getHeight();
        if (fan.size() >= 3) drawTilted(canvas, fan.get(2), off, fy + 0.018f * getHeight(), 10f, 0.88f, alpha);
        if (fan.size() >= 2) drawTilted(canvas, fan.get(1), fan.size() >= 3 ? -off : -off * 0.6f,
                fy + 0.018f * getHeight(), fan.size() >= 3 ? -10f : -8f, 0.88f, alpha);
        drawTilted(canvas, fan.get(0), fan.size() == 2 ? off * 0.35f : 0f, fy, fan.size() == 2 ? 4f : 0f, 1f, alpha);
    }

    private void drawTilted(Canvas canvas, int title, float x, float y, float degrees, float scale, int alpha) {
        canvas.save();
        canvas.translate(x, y);
        if (degrees != 0f) canvas.rotate(degrees);
        float hw = pw * scale / 2f, hh = ph * scale / 2f;
        drawPoster(canvas, posters.get(title), -hw, -hh, hw, hh, alpha);
        canvas.restore();
    }

    /** The slot-machine window where the fan was: the reel's posters roll down through it and stop on the pick. */
    private void drawReel(Canvas canvas, MoodDeck.Mood m, int alpha, int h) {
        float fy = -ch / 2f + 0.05f * h + ph / 2f;
        float pulse = 1f + 0.05f * (float) Math.sin(Math.PI * land);
        float hw = pw / 2f * pulse, hh = ph / 2f * pulse;
        float step = ph + 0.02f * h;
        canvas.save();
        canvas.clipRect(-hw, fy - hh, hw, fy + hh);
        if (reel.isEmpty()) {
            drawPoster(canvas, null, -hw, fy - hh, hw, fy + hh, alpha);
        } else {
            int at = (int) Math.floor(reelPos);
            for (int i = Math.max(0, at - 1); i <= Math.min(reel.size() - 1, at + 2); i++) {
                float y = fy - (i - reelPos) * step;
                drawPoster(canvas, posters.get(reel.get(i)), -hw, y - hh, hw, y + hh, alpha);
            }
            // Darker at the window's top and bottom while it rolls, like a drum.
            float shade = state == State.SPIN ? 1f : 1f - land;
            if (shade > 0f) {
                if (shadePaint.getShader() == null || shadeHeight != hh) {
                    shadeHeight = hh;
                    shadePaint.setShader(new LinearGradient(0, fy - hh, 0, fy + hh, new int[]{0xC0000000, 0, 0, 0xC0000000},
                            new float[]{0f, 0.24f, 0.76f, 1f}, Shader.TileMode.CLAMP));
                }
                shadePaint.setAlpha(Math.round(255 * shade * alpha / 255f));
                canvas.drawRect(-hw, fy - hh, hw, fy + hh, shadePaint);
            }
        }
        canvas.restore();
    }

    private void drawPoster(Canvas canvas, Bitmap b, float l, float t, float r, float bottom, int alpha) {
        float rr = 0.012f * getHeight();
        rect.set(l, t, r, bottom);
        if (b == null) {
            placeholderPaint.setAlpha(Math.round(0x1A * alpha / 255f));
            canvas.drawRoundRect(rect, rr, rr, placeholderPaint);
            return;
        }
        BitmapShader s = posterShaders.get(b);
        if (s == null) {
            s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            posterShaders.put(b, s);
        }
        shaderMatrix.setScale((r - l) / b.getWidth(), (bottom - t) / b.getHeight());
        shaderMatrix.postTranslate(l, t);
        s.setLocalMatrix(shaderMatrix);
        posterPaint.setShader(s);
        posterPaint.setAlpha(alpha);
        canvas.drawRoundRect(rect, rr, rr, posterPaint);
    }

    /** Under the deck: the picked title, "Opening in 3…" with its bar, and what the keys do. */
    private void drawBelow(Canvas canvas, int w, int h) {
        float margin = 0.06f * h;
        if (state == State.COUNTDOWN && picked >= 0) {
            MoodDeck.Title t = titles.get(picked);
            int a = Math.round(255 * Math.min(1f, land * 1.4f));
            String title = TextUtils.ellipsize(t.title, titlePaint, w - 2 * margin, TextUtils.TruncateAt.END).toString();
            titlePaint.setAlpha(a);
            canvas.drawText(title, (w - titlePaint.measureText(title)) / 2f, 0.825f * h, titlePaint);
            int left = MoodDeck.secondsLeft(SystemClock.uptimeMillis() - countdownStart);
            String line = getResources().getString(R.string.mood_opening, left)
                    + (t.appName.isEmpty() ? "" : "  ·  " + t.appName.toUpperCase(Locale.ROOT));
            smallPaint.setAlpha(Math.round(a * 0.8f));
            canvas.drawText(line, (w - smallPaint.measureText(line)) / 2f, 0.868f * h, smallPaint);
            float bw = 0.34f * h, by = 0.897f * h, bx = (w - bw) / 2f;
            barPaint.setColor(0x26FFFFFF);
            barPaint.setAlpha(Math.round(0x26 * a / 255f));
            canvas.drawLine(bx, by, bx + bw, by, barPaint);
            barPaint.setColor(moods.get(top).color);
            barPaint.setAlpha(a);
            canvas.drawLine(bx, by, bx + Math.max(0.001f, bw * countdown), by, barPaint);
        }
        int hint = state == State.COUNTDOWN ? R.string.mood_keys_countdown
                : state == State.SPIN ? R.string.mood_keys_spinning
                : moods.size() > 1 ? R.string.mood_keys : R.string.mood_keys_one;
        String keys = getResources().getString(hint);
        smallPaint.setAlpha(0x80);
        canvas.drawText(keys, (w - smallPaint.measureText(keys)) / 2f, 0.962f * h, smallPaint);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }
}
