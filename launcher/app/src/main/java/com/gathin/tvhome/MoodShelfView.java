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
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * mood_posters: a mood's posters in a row, opened from its card on the mood deck. The centre poster is the biggest,
 * with a play icon; its neighbours are smaller and turned slightly towards it, and the ones further out fade at the
 * screen's edges. Behind, the centre title's darkened black-and-white backdrop. The row moves on slowly by itself until
 * a key is pressed. Left and Right browse, OK opens the centre title, Down goes to "Surprise me" (the deck's reel and
 * countdown), Up or Back go back to the cards. With mood_watched, holding OK on the centre poster offers "I've watched
 * it". Only the posters on screen and one more each way are decoded, and at most the two backdrops of a crossfade.
 */
@SuppressLint("ViewConstructor")   // made in code only, never from a layout
final class MoodShelfView extends View {
    interface Listener {
        /** Up or Back: back to the mood cards. */
        void onShelfBack();
        void onShelfOpen(MoodDeck.Title title);
        /** "Surprise me": the deck's reel for this mood. */
        void onShelfSurprise();
        /** "I've watched it": the title leaves every mood (this row has already let it go). */
        void onShelfWatched(MoodDeck.Title title);
    }

    private static final long BACKDROP_MS = 450, RING_DELAY_MS = 350, FOCUS_MS = 180;
    private static final float TILT_DEGREES = 24f;

    private final List<MoodDeck.Title> titles;
    private final Listener listener;
    private final String heading;
    private final long watchedHoldMs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float density;

    private final Paint posterPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint placeholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backdropPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint shadePaint = new Paint();
    private final Paint glowPaint = new Paint();
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint buttonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint menuPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint headingPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint namePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint smallPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint buttonText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path triangle = new Path();
    private final Matrix shaderMatrix = new Matrix(), tilt = new Matrix();
    private final Camera camera = new Camera();
    private final Map<Bitmap, BitmapShader> posterShaders = new HashMap<>();

    /** The mood shown and its titles (indexes into `titles`), in the theme's order. */
    private MoodDeck.Mood mood;
    private final List<Integer> items = new ArrayList<>();
    /** Decoded posters by title index: the centre, those on screen and one more each way. */
    private final Map<Integer, Bitmap> posters = new HashMap<>();
    private final Set<Integer> loading = new HashSet<>();
    private boolean released = true;
    /** Bumped on every release (and each new backdrop), so a decode that finishes late is dropped. */
    private int posterGeneration, backdropGeneration;
    private Bitmap backdrop, previousBackdrop;
    private File backdropFile;
    private float backdropMix = 1f;
    private ValueAnimator backdropAnimator;

    /** The poster in the middle (an index into items), and the row's position while it moves (fractional). */
    private int centre;
    private float position;
    private ValueAnimator moveAnimator;
    private boolean moving;
    /** Moving on by itself until a key is pressed. */
    private boolean autoplay;
    private final Runnable autoStep = this::autoStep;
    /** Down: the focus is on "Surprise me" (0 to 1 while it moves there). */
    private boolean onButton;
    private float buttonFocus;
    private ValueAnimator focusAnimator;

    /** mood_watched: the ring while OK is held, then the small menu. */
    private ValueAnimator holdAnimator;
    private float holdProgress;
    private boolean holdShown, menuOpen, swallowOkUp;
    private int menuChoice;

    private final AccelerateDecelerateInterpolator glideCurve = new AccelerateDecelerateInterpolator();
    private final DecelerateInterpolator moveCurve = new DecelerateInterpolator(1.8f);
    private final ArgbEvaluator argb = new ArgbEvaluator();
    private int glowColor;

    // Geometry, from the view's height (a 1080-line screen is the reference).
    private float rowY, ph, pw, near, far, iconR;

    MoodShelfView(Context context, List<MoodDeck.Title> titles, String heading, Typeface lightFont, Typeface mediumFont,
                  long watchedHoldMs, Listener listener) {
        super(context);
        this.titles = titles;
        this.heading = heading == null ? "" : heading;
        this.watchedHoldMs = Math.max(0, watchedHoldMs);
        this.listener = listener;
        density = context.getResources().getDisplayMetrics().density;
        setFocusable(true);
        setFocusableInTouchMode(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        for (TextPaint p : new TextPaint[]{headingPaint, namePaint, titlePaint, smallPaint, buttonText}) {
            p.setColor(Color.WHITE);
            if (lightFont != null) p.setTypeface(lightFont);
        }
        if (mediumFont != null) {
            namePaint.setTypeface(mediumFont);
            smallPaint.setTypeface(mediumFont);
            buttonText.setTypeface(mediumFont);
        }
        smallPaint.setLetterSpacing(0.12f);
        buttonText.setLetterSpacing(0.04f);
        placeholderPaint.setColor(0x1AFFFFFF);
        dimPaint.setColor(Color.BLACK);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    /** Opens on a mood: its first poster in the middle, moving on by itself. */
    void show(MoodDeck.Mood m) {
        mood = m;
        items.clear();
        items.addAll(m.titles);
        cancelHold();
        menuOpen = false;
        onButton = false;
        buttonFocus = 0f;
        if (moveAnimator != null) moveAnimator.cancel();
        centre = items.isEmpty() ? -1 : 0;
        position = 0f;
        moving = false;
        released = false;
        glowPaint.setShader(null);
        loadPosters();
        showBackdrop(false);
        startAuto();
        invalidate();
    }

    /** Back in front after the pictures were let go (Home went off screen, or memory was short): the same poster. */
    void load() {
        if (mood == null || !released) return;
        released = false;
        loadPosters();
        backdropFile = null;
        showBackdrop(false);
        invalidate();
    }

    /** Lets go of every picture and stops moving (the row is closed or memory is short). */
    void release() {
        released = true;
        stopAuto();
        cancelHold();
        menuOpen = false;
        if (moveAnimator != null) moveAnimator.cancel();
        posterGeneration++;
        backdropGeneration++;
        posters.clear();
        posterShaders.clear();
        loading.clear();
        backdrop = previousBackdrop = null;
        backdropFile = null;
    }

    private MoodDeck.Title centreTitle() {
        return centre < 0 || centre >= items.size() ? null : titles.get(items.get(centre));
    }

    // ---- pictures --------------------------------------------------------------------------------

    private void loadPosters() {
        if (released || items.isEmpty()) return;
        Set<Integer> want = new HashSet<>();
        for (int i : MoodShelf.wanted(centre, items.size())) want.add(items.get(i));
        posters.keySet().retainAll(want);
        posterShaders.keySet().retainAll(posters.values());
        List<Integer> missing = new ArrayList<>();
        for (int i : want) if (!posters.containsKey(i) && !loading.contains(i)) missing.add(i);
        if (missing.isEmpty()) return;
        // The centre first, then outwards.
        Collections.sort(missing, (a, b) -> Integer.compare(distance(a), distance(b)));
        loading.addAll(missing);
        int gen = posterGeneration;
        new Thread(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;   // posters are opaque: half the memory
            for (int i : missing) {
                Bitmap b = decode(titles.get(i).poster, o);
                main.post(() -> {
                    loading.remove(i);
                    if (gen != posterGeneration || released || b == null) return;
                    Set<Integer> still = new HashSet<>();
                    for (int k : MoodShelf.wanted(centre, items.size())) still.add(items.get(k));
                    if (still.contains(i)) {
                        posters.put(i, b);
                        invalidate();
                    }
                });
            }
        }, "shelf-posters").start();
    }

    private int distance(int titleIndex) {
        int at = items.indexOf(titleIndex);
        return at < 0 ? Integer.MAX_VALUE : Math.round(Math.abs(MoodShelf.offset(at, centre, items.size())));
    }

    /** The centre title's backdrop, crossfading from the last one. */
    private void showBackdrop(boolean animate) {
        MoodDeck.Title t = centreTitle();
        File file = t == null ? null : t.backdrop;
        if (released || file == null || file.equals(backdropFile)) return;
        backdropFile = file;
        int gen = ++backdropGeneration;
        new Thread(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;   // already black and white
            Bitmap b = decode(file, o);
            main.post(() -> {
                if (gen != backdropGeneration || released || b == null) return;
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
        }, "shelf-backdrop").start();
    }

    private static Bitmap decode(File file, BitmapFactory.Options o) {
        if (file == null || !file.isFile()) return null;
        try {
            return BitmapFactory.decodeFile(file.getPath(), o);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    // ---- moving ----------------------------------------------------------------------------------

    private void startAuto() {
        autoplay = true;
        main.removeCallbacks(autoStep);
        if (items.size() > 1) main.postDelayed(autoStep, MoodShelf.REST_MS);
    }

    private void stopAuto() {
        autoplay = false;
        main.removeCallbacks(autoStep);
    }

    private void autoStep() {
        if (!autoplay || released || menuOpen || holdShown || items.size() < 2) return;
        int next = MoodShelf.next(centre, items.size());
        // A round row always glides one to the right; a short one glides back to its first poster at the end.
        moveTo(next, MoodShelf.goesRound(items.size()) ? 1 : next - centre, MoodShelf.GLIDE_MS, true);
        main.postDelayed(autoStep, MoodShelf.GLIDE_MS + MoodShelf.REST_MS);
    }

    /** Glides the row to `target`, `steps` posters away (negative to the left); the backdrop follows at once. */
    private void moveTo(int target, int steps, long ms, boolean glide) {
        if (target < 0 || target == centre) return;
        if (moveAnimator != null && moveAnimator.isRunning()) moveAnimator.end();
        float from = centre;
        centre = target;
        loadPosters();
        showBackdrop(true);
        moving = true;
        moveAnimator = ValueAnimator.ofFloat(from, from + steps).setDuration(ms);
        moveAnimator.setInterpolator(glide ? glideCurve : moveCurve);
        moveAnimator.addUpdateListener(a -> {
            position = (float) a.getAnimatedValue();
            invalidate();
        });
        moveAnimator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                position = centre;
                moving = false;
                invalidate();
            }
        });
        moveAnimator.start();
    }

    private void move(int direction) {
        int next = MoodShelf.step(centre, direction, items.size());
        if (next == centre || next < 0) return;
        playSoundEffect(direction > 0 ? SoundEffectConstants.NAVIGATION_RIGHT : SoundEffectConstants.NAVIGATION_LEFT);
        moveTo(next, direction, MoodShelf.MOVE_MS, false);
    }

    private void focusButton(boolean on) {
        if (onButton == on) return;
        onButton = on;
        playSoundEffect(on ? SoundEffectConstants.NAVIGATION_DOWN : SoundEffectConstants.NAVIGATION_UP);
        if (focusAnimator != null) focusAnimator.cancel();
        focusAnimator = ValueAnimator.ofFloat(buttonFocus, on ? 1f : 0f).setDuration(FOCUS_MS);
        focusAnimator.addUpdateListener(a -> {
            buttonFocus = (float) a.getAnimatedValue();
            invalidate();
        });
        focusAnimator.start();
    }

    // ---- keys ------------------------------------------------------------------------------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (items.isEmpty()) return super.onKeyDown(keyCode, event);
        // Any key stops the row moving by itself; a glide under way lands at once.
        if (autoplay) {
            stopAuto();
            if (moveAnimator != null && moveAnimator.isRunning()) moveAnimator.end();
        }
        if (menuOpen) return menuKeyDown(keyCode) || super.onKeyDown(keyCode, event);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                cancelHold();
                if (!onButton) move(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                cancelHold();
                if (!onButton) move(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                cancelHold();
                focusButton(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                cancelHold();
                if (onButton) {
                    focusButton(false);
                } else {
                    playSoundEffect(SoundEffectConstants.NAVIGATION_UP);
                    listener.onShelfBack();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (event.getRepeatCount() == 0) {
                    event.startTracking();
                    if (!onButton) startHold();
                }
                return true;
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
                if (menuOpen) {
                    // The release of the hold that opened the menu does nothing; the next press chooses.
                    if (swallowOkUp) swallowOkUp = false;
                    else chooseMenu();
                    return true;
                }
                // Let go once the ring showed: the hold is given up, and the title doesn't open either.
                boolean held = holdShown;
                cancelHold();
                if (event.isCanceled() || held || items.isEmpty()) return true;
                playSoundEffect(SoundEffectConstants.CLICK);
                if (onButton) {
                    listener.onShelfSurprise();
                } else {
                    MoodDeck.Title t = centreTitle();
                    if (t != null) listener.onShelfOpen(t);
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (menuOpen) closeMenu();
                else if (!event.isCanceled()) listener.onShelfBack();
                return true;
            default:
                return super.onKeyUp(keyCode, event);
        }
    }

    // ---- mood_watched ----------------------------------------------------------------------------

    /** OK went down on the centre poster: after a short while a ring fills around the play icon; full, the menu. */
    private void startHold() {
        cancelHold();
        if (watchedHoldMs <= RING_DELAY_MS || centreTitle() == null || moving) return;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f).setDuration(watchedHoldMs - RING_DELAY_MS);
        a.setStartDelay(RING_DELAY_MS);
        a.setInterpolator(null);
        a.addUpdateListener(v -> {
            holdShown = true;
            holdProgress = (float) v.getAnimatedValue();
            invalidate();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animator) {
                if (holdProgress >= 1f) openMenu();
            }
        });
        holdAnimator = a;
        a.start();
    }

    private void cancelHold() {
        holdProgress = 0f;
        if (holdAnimator != null) {
            holdAnimator.removeAllListeners();
            holdAnimator.cancel();
            holdAnimator = null;
        }
        if (holdShown) {
            holdShown = false;
            invalidate();
        }
    }

    private void openMenu() {
        holdAnimator = null;
        holdShown = false;
        holdProgress = 0f;
        menuOpen = true;
        menuChoice = 0;
        swallowOkUp = true;   // OK is still held down
        playSoundEffect(SoundEffectConstants.CLICK);
        invalidate();
    }

    private void closeMenu() {
        menuOpen = false;
        swallowOkUp = false;
        invalidate();
    }

    /** With the menu open the arrows move between its two lines and nothing else moves; Back closes it. */
    private boolean menuKeyDown(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (menuChoice != 0) { menuChoice = 0; playSoundEffect(SoundEffectConstants.NAVIGATION_UP); invalidate(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (menuChoice != 1) { menuChoice = 1; playSoundEffect(SoundEffectConstants.NAVIGATION_DOWN); invalidate(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BACK:
                return true;   // they act on release
            default:
                return false;
        }
    }

    private void chooseMenu() {
        boolean watched = menuChoice == 0;
        closeMenu();
        playSoundEffect(SoundEffectConstants.CLICK);
        if (watched) watchedCentre();
    }

    /** "I've watched it": the centre poster leaves the row at once; the next one takes its place. */
    private void watchedCentre() {
        MoodDeck.Title t = centreTitle();
        if (t == null) return;
        int gone = centre;
        int next = MoodShelf.afterRemoving(gone, centre, items.size());
        int titleIndex = items.remove(gone);
        posters.remove(titleIndex);
        centre = next;
        position = Math.max(0, next);
        listener.onShelfWatched(t);
        if (items.isEmpty()) {
            listener.onShelfBack();
            return;
        }
        loadPosters();
        showBackdrop(true);
        invalidate();
    }

    /** A title gone from every mood elsewhere (or watched here): out of this row too. */
    void hide(Set<String> keys) {
        boolean changed = false;
        for (int k = items.size() - 1; k >= 0; k--) {
            MoodDeck.Title t = titles.get(items.get(k));
            if (keys.contains(WatchlistRemovals.keyOf(t.key, t.title))) {
                centre = MoodShelf.afterRemoving(k, centre, items.size());
                items.remove(k);
                changed = true;
            }
        }
        if (!changed) return;
        position = Math.max(0, centre);
        loadPosters();
        showBackdrop(true);
        invalidate();
    }

    // ---- drawing ---------------------------------------------------------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rowY = 0.50f * h;
        ph = 0.46f * h;
        pw = ph * 2f / 3f;
        near = 0.34f * h;   // centre to a neighbour
        far = 0.25f * h;    // each poster further out
        iconR = 0.044f * h;
        headingPaint.setTextSize(0.030f * h);
        namePaint.setTextSize(0.062f * h);
        titlePaint.setTextSize(0.040f * h);
        smallPaint.setTextSize(0.020f * h);
        buttonText.setTextSize(0.024f * h);
        camera.setLocation(0, 0, -12f * density / 2f);
        shadePaint.setShader(new LinearGradient(0, 0, 0, h, new int[]{0xCC000000, 0x33000000, 0x22000000, 0xE6000000},
                new float[]{0f, 0.24f, 0.62f, 1f}, Shader.TileMode.CLAMP));
        glowPaint.setShader(null);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.drawColor(Color.BLACK);
        if (w == 0 || h == 0 || mood == null) return;
        if (backdropMix < 1f) drawBackdrop(canvas, previousBackdrop, 1f, w, h);
        drawBackdrop(canvas, backdrop, backdropMix, w, h);
        canvas.drawRect(0, 0, w, h, shadePaint);
        // A very soft light in the mood's colour behind the centre poster.
        if (glowPaint.getShader() == null || glowColor != mood.color) {
            glowColor = mood.color;
            glowPaint.setShader(new RadialGradient(w / 2f, rowY, 0.55f * h,
                    new int[]{withAlpha(mood.color, 0x30), withAlpha(mood.color, 0x0C), withAlpha(mood.color, 0)},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        }
        canvas.drawRect(0, 0, w, h, glowPaint);

        if (!heading.isEmpty()) {
            headingPaint.setAlpha(0xC0);
            canvas.drawText(heading, (w - headingPaint.measureText(heading)) / 2f, 0.072f * h, headingPaint);
        }
        String name = TextUtils.ellipsize(mood.name, namePaint, w * 0.8f, TextUtils.TruncateAt.END).toString();
        namePaint.setAlpha(0xFF);
        float nw = namePaint.measureText(name);
        canvas.drawText(name, (w - nw) / 2f, 0.150f * h, namePaint);
        buttonPaint.setStyle(Paint.Style.FILL);
        buttonPaint.setColor(mood.color);
        float bar = 0.045f * h;
        rect.set((w - bar) / 2f, 0.172f * h, (w + bar) / 2f, 0.172f * h + 0.0045f * h);
        canvas.drawRoundRect(rect, rect.height(), rect.height(), buttonPaint);

        drawRow(canvas, w, h);
        drawBelow(canvas, w, h);
        drawMenu(canvas, w, h);
    }

    private void drawBackdrop(Canvas canvas, Bitmap b, float alpha, int w, int h) {
        if (b == null || alpha <= 0f) return;
        float scale = Math.max((float) w / b.getWidth(), (float) h / b.getHeight());
        float dw = b.getWidth() * scale, dh = b.getHeight() * scale;
        rect.set((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f);
        backdropPaint.setAlpha(Math.round(alpha * 255));
        canvas.drawBitmap(b, null, rect, backdropPaint);
    }

    /** The posters, the far ones first so the centre one is drawn over its neighbours. */
    private void drawRow(Canvas canvas, int w, int h) {
        int n = items.size();
        if (n == 0) return;
        List<float[]> shown = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            float d = MoodShelf.offset(i, position, n);
            if (Math.abs(d) < MoodShelf.SIDE + 1f) shown.add(new float[]{i, d});
        }
        Collections.sort(shown, (a, b) -> Float.compare(Math.abs(b[1]), Math.abs(a[1])));
        for (float[] s : shown) drawPoster(canvas, (int) s[0], s[1], w, h);
    }

    private void drawPoster(Canvas canvas, int item, float d, int w, int h) {
        float a = Math.abs(d);
        float near1 = Math.min(1f, a), beyond = Math.max(0f, Math.min(1f, a - 1f));
        float scale = 1f - 0.28f * near1 - 0.12f * beyond;
        float x = w / 2f + Math.signum(d) * (near * near1 + far * Math.max(0f, a - 1f));
        // Fading out past the second poster each side, at the screen's edges.
        float fade = Math.max(0f, Math.min(1f, MoodShelf.SIDE + 1f - a));
        int alpha = Math.round(255 * fade);
        if (alpha <= 0) return;
        float degrees = -Math.signum(d) * TILT_DEGREES * near1;
        canvas.save();
        canvas.translate(x, rowY);
        if (degrees != 0f) {
            camera.save();
            camera.rotateY(degrees);
            camera.getMatrix(tilt);
            camera.restore();
            canvas.concat(tilt);
        }
        float hw = pw * scale / 2f, hh = ph * scale / 2f;
        rect.set(-hw, -hh, hw, hh);
        float rr = 0.012f * h;
        Bitmap b = posters.get(items.get(item));
        if (b == null) {
            placeholderPaint.setAlpha(Math.round(0x1A * fade));
            canvas.drawRoundRect(rect, rr, rr, placeholderPaint);
        } else {
            BitmapShader s = posterShaders.get(b);
            if (s == null) {
                s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                posterShaders.put(b, s);
            }
            shaderMatrix.setScale(rect.width() / b.getWidth(), rect.height() / b.getHeight());
            shaderMatrix.postTranslate(rect.left, rect.top);
            s.setLocalMatrix(shaderMatrix);
            posterPaint.setShader(s);
            posterPaint.setAlpha(alpha);
            canvas.drawRoundRect(rect, rr, rr, posterPaint);
        }
        // The neighbours sit a little darker; the centre one is untouched.
        float dim = 0.32f * near1 + 0.22f * beyond;
        if (dim > 0f) {
            dimPaint.setAlpha(Math.round(255 * dim * fade));
            canvas.drawRoundRect(rect, rr, rr, dimPaint);
        }
        // The play icon on the centre poster (fading as it moves away).
        float icon = Math.max(0f, 1f - a * 2f) * (1f - buttonFocus * 0.6f);
        if (icon > 0f) drawPlay(canvas, icon, h);
        if (holdShown && a < 0.01f) drawRing(canvas);
        canvas.restore();
    }

    private void drawPlay(Canvas canvas, float alpha, int h) {
        iconPaint.setStyle(Paint.Style.FILL);
        iconPaint.setColor(Color.BLACK);
        iconPaint.setAlpha(Math.round(0x73 * alpha));
        canvas.drawCircle(0, 0, iconR, iconPaint);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeWidth(Math.max(2f, 0.0028f * h));
        iconPaint.setColor(Color.WHITE);
        iconPaint.setAlpha(Math.round(0xE6 * alpha));
        canvas.drawCircle(0, 0, iconR, iconPaint);
        float t = iconR * 0.42f;
        triangle.reset();
        triangle.moveTo(-t * 0.62f, -t);
        triangle.lineTo(t * 1.05f, 0);
        triangle.lineTo(-t * 0.62f, t);
        triangle.close();
        iconPaint.setStyle(Paint.Style.FILL);
        canvas.drawPath(triangle, iconPaint);
    }

    /** The ring around the play icon while OK is held. */
    private void drawRing(Canvas canvas) {
        float r = iconR + 9 * density, stroke = 5 * density;
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(stroke);
        ringPaint.setColor(0x4DFFFFFF);
        canvas.drawCircle(0, 0, r, ringPaint);
        ringPaint.setColor(Color.WHITE);
        rect.set(-r, -r, r, r);
        canvas.drawArc(rect, -90f, 360f * holdProgress, false, ringPaint);
    }

    /** Under the row: the centre title, its year and app, "Surprise me", and what the keys do. */
    private void drawBelow(Canvas canvas, int w, int h) {
        MoodDeck.Title t = centreTitle();
        float settle = moving ? Math.max(0f, 1f - 2f * Math.abs(position - centre)) : 1f;
        if (t != null && settle > 0f) {
            int a = Math.round(255 * settle);
            String title = TextUtils.ellipsize(t.title, titlePaint, w * 0.7f, TextUtils.TruncateAt.END).toString();
            titlePaint.setAlpha(a);
            canvas.drawText(title, (w - titlePaint.measureText(title)) / 2f, 0.797f * h, titlePaint);
            String meta = t.year.isEmpty() ? t.appName.toUpperCase(Locale.ROOT)
                    : t.appName.isEmpty() ? t.year : t.year + "  ·  " + t.appName.toUpperCase(Locale.ROOT);
            smallPaint.setAlpha(Math.round(a * 0.7f));
            canvas.drawText(meta, (w - smallPaint.measureText(meta)) / 2f, 0.838f * h, smallPaint);
        }
        // "Surprise me": an outline at rest, white when Down moved the focus to it.
        String label = getResources().getString(R.string.shelf_surprise);
        float tw = buttonText.measureText(label), bh = 0.054f * h, bw = tw + 0.07f * h, by = 0.902f * h;
        rect.set((w - bw) / 2f, by - bh / 2f, (w + bw) / 2f, by + bh / 2f);
        buttonPaint.setStyle(Paint.Style.FILL);
        buttonPaint.setColor(Color.WHITE);
        buttonPaint.setAlpha(Math.round(0x1F + (0xFF - 0x1F) * buttonFocus));
        canvas.drawRoundRect(rect, bh / 2f, bh / 2f, buttonPaint);
        buttonText.setColor((int) argb.evaluate(buttonFocus, 0xD9FFFFFF, 0xFF0C0C0E));
        canvas.drawText(label, (w - tw) / 2f, by + buttonText.getTextSize() * 0.36f, buttonText);
        String keys = getResources().getString(onButton ? R.string.shelf_keys_button : R.string.shelf_keys);
        smallPaint.setAlpha(0x80);
        canvas.drawText(keys, (w - smallPaint.measureText(keys)) / 2f, 0.968f * h, smallPaint);
    }

    /** "I've watched it" over "Cancel", in a small dark panel under the centre poster; the chosen line is white. */
    private void drawMenu(Canvas canvas, int w, int h) {
        if (!menuOpen) return;
        String[] lines = {getResources().getString(R.string.shelf_watched),
                getResources().getString(R.string.watchlist_remove_cancel)};
        menuPaint.setColor(0x8C000000);
        canvas.drawRect(0, 0, w, h, menuPaint);
        buttonText.setColor(Color.WHITE);
        float pad = 0.03f * h, row = 0.058f * h, gap = 0.008f * h, inset = 0.01f * h;
        float textW = Math.max(buttonText.measureText(lines[0]), buttonText.measureText(lines[1]));
        float mw = textW + 2 * pad + 2 * inset, mh = 2 * row + gap + 2 * inset;
        float left = (w - mw) / 2f, top = rowY + ph / 2f - mh * 0.15f;
        rect.set(left, top, left + mw, top + mh);
        menuPaint.setColor(0xF2141416);
        canvas.drawRoundRect(rect, 0.016f * h, 0.016f * h, menuPaint);
        for (int i = 0; i < 2; i++) {
            float y = top + inset + i * (row + gap);
            if (i == menuChoice) {
                rect.set(left + inset, y, left + mw - inset, y + row);
                menuPaint.setColor(Color.WHITE);
                canvas.drawRoundRect(rect, 0.012f * h, 0.012f * h, menuPaint);
            }
            buttonText.setColor(i == menuChoice ? 0xFF0C0C0E : 0xD9FFFFFF);
            canvas.drawText(lines[i], left + inset + pad, y + row / 2f + buttonText.getTextSize() * 0.36f, buttonText);
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }
}
