package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
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
import android.os.SystemClock;
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
import java.util.Random;
import java.util.Set;

/**
 * mood_posters: a mood's posters in a row across the whole screen, opened from its card on the mood deck. The centre
 * poster is the biggest, with a small play icon; the others touch, each smaller and darker than the last, turned so their
 * outer edges are further away, and the outermost fade at the screen's edges. Behind, the centre title's darkened black-and-white backdrop. The row moves on slowly by itself until
 * a key is pressed. Left and Right browse, OK opens the centre title, Down goes to "Surprise me", Up or Back go back to
 * the cards. "Surprise me" is the deck's reel and countdown with mood_surprise_reel; without it the row itself spins left
 * to right, slows down and stops on a title at random, then a countdown under it opens the title. With mood_watched,
 * holding OK on the centre poster offers "I've watched it". Only the posters on screen and a few more are decoded (the
 * spin too), and at most the two backdrops of a crossfade.
 */
@SuppressLint("ViewConstructor")   // made in code only, never from a layout
final class MoodShelfView extends View {
    interface Listener {
        /** Up or Back: back to the mood cards. */
        void onShelfBack();
        void onShelfOpen(MoodDeck.Title title);
        /** "Surprise me" with mood_surprise_reel: the deck's reel for this mood. */
        void onShelfSurprise();
        /** "I've watched it": the title leaves every mood (this row has already let it go). */
        void onShelfWatched(MoodDeck.Title title);
    }

    private enum Surprise { NONE, SPIN, COUNTDOWN }

    private static final long BACKDROP_MS = 450, RING_DELAY_MS = 350, FOCUS_MS = 180;
    private static final long SURPRISE_SHOW_MS = 220, TICK_MS = 70, AFTER_OPEN_MS = 600;
    private static final float TILT_DEGREES = 32f;
    /** The play triangle's height against the centre poster's, and how solid it is (slightly see-through). */
    private static final float PLAY_SHARE = 0.18f;
    private static final int PLAY_ALPHA = 0xB8;

    private final List<MoodDeck.Title> titles;
    private final Listener listener;
    private final String heading;
    private final long watchedHoldMs;
    /** mood_surprise_reel: "Surprise me" leaves for the deck's reel instead of spinning this row. */
    private final boolean deckReel;
    private final Random random = new Random();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float density;

    private final Paint posterPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint placeholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backdropPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint shadePaint = new Paint();
    private final Paint glowPaint = new Paint();
    private final Paint buttonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint menuPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint headingPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint namePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint smallPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint buttonText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path triangle = new Path();
    private final Paint playPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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

    /** "Surprise me" on the row: spinning, then counting down under the title it stopped on. */
    private Surprise surprise = Surprise.NONE;
    /** Where the spin stops: the place in the row (numbered on past its ends) and the poster that will be there. */
    private int spinEnd, spinTarget = -1;
    private int lastPicked = -1;
    private long lastTickAt, countdownStart;
    private float countdown;
    /** 0 is the row at rest, 1 the button and play icon gone for the spin and countdown. */
    private float surpriseShow;
    /** OK went down during the countdown (the press that started the spin may still be held); the title has opened. */
    private boolean okArmed, opened;
    private ValueAnimator spinAnimator, countdownAnimator, surpriseShowAnimator;
    private final Runnable afterOpen = () -> stopSurprise(false);

    private final AccelerateDecelerateInterpolator glideCurve = new AccelerateDecelerateInterpolator();
    private final DecelerateInterpolator moveCurve = new DecelerateInterpolator(1.8f);
    private final DecelerateInterpolator spinCurve = new DecelerateInterpolator(1.5f);   // fast, then slowing down
    private final ArgbEvaluator argb = new ArgbEvaluator();
    private int glowColor;

    // Geometry, from the view's height (a 1080-line screen is the reference).
    private float rowY, ph, pw, playH;

    MoodShelfView(Context context, List<MoodDeck.Title> titles, String heading, Typeface lightFont, Typeface mediumFont,
                  long watchedHoldMs, boolean deckReel, Listener listener) {
        super(context);
        this.titles = titles;
        this.heading = heading == null ? "" : heading;
        this.watchedHoldMs = Math.max(0, watchedHoldMs);
        this.deckReel = deckReel;
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
        playPaint.setStyle(Paint.Style.FILL);
        playPaint.setColor(Color.WHITE);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
        barPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    /** Opens on a mood: its first poster in the middle, moving on by itself. */
    void show(MoodDeck.Mood m) {
        stopSurprise(false);
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
        stopSurprise(false);
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
        Set<Integer> want = wantedPosters();
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
                    if (wantedPosters().contains(i)) {
                        posters.put(i, b);
                        invalidate();
                    }
                });
            }
        }, "shelf-posters").start();
    }

    /** The title indexes to hold decoded now. */
    private Set<Integer> wantedPosters() {
        Set<Integer> want = new HashSet<>();
        Set<Integer> around = surprise == Surprise.SPIN ? MoodShelf.wantedSpinning(centre, items.size())
                : MoodShelf.wanted(centre, items.size());
        for (int i : around) want.add(items.get(i));
        return want;
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
        glide(from, from + steps, ms, glide ? glideCurve : moveCurve);
    }

    private void glide(float from, float to, long ms, TimeInterpolator curve) {
        moving = true;
        moveAnimator = ValueAnimator.ofFloat(from, to).setDuration(ms);
        moveAnimator.setInterpolator(curve);
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

    // ---- "Surprise me" on the row -------------------------------------------------------------------

    /** The row spins left to right and stops on a title at random: again from the countdown, on another title. */
    private void spin() {
        if (items.isEmpty()) return;
        if (moveAnimator != null && moveAnimator.isRunning()) moveAnimator.end();
        stopSurpriseAnimators();
        lastPicked = MoodDeck.pick(random, new MoodDeck.Mood(mood.name, mood.color, items), lastPicked);
        spinTarget = items.indexOf(lastPicked);
        int from = centre, count = items.size();
        spinEnd = from - MoodShelf.spinSteps(from, spinTarget, count);
        surprise = Surprise.SPIN;
        onButton = false;
        countdown = 0f;
        okArmed = false;
        animateSurpriseShow(1f);
        spinAnimator = ValueAnimator.ofFloat(from, spinEnd).setDuration(MoodShelf.SPIN_MS);
        spinAnimator.setInterpolator(spinCurve);
        spinAnimator.addUpdateListener(a -> {
            position = (float) a.getAnimatedValue();
            int at = MoodShelf.wrap(Math.round(position), count);
            if (at != centre) {
                centre = at;
                loadPosters();
                long now = SystemClock.uptimeMillis();
                if (now - lastTickAt >= TICK_MS) {
                    lastTickAt = now;
                    playSoundEffect(SoundEffectConstants.NAVIGATION_RIGHT);
                }
                // Backdrops are big: only the last few, when the row has slowed down.
                if (Math.abs(position - spinEnd) <= MoodShelf.SPIN_BACKDROPS) showBackdrop(true);
            }
            invalidate();
        });
        spinAnimator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { landed(); }
        });
        spinAnimator.start();
    }

    /** It stopped: "Opening in 3…" under the title, then it opens. */
    private void landed() {
        surprise = Surprise.COUNTDOWN;
        centre = spinTarget;
        position = centre;
        loadPosters();
        showBackdrop(true);
        playSoundEffect(SoundEffectConstants.CLICK);
        countdownStart = SystemClock.uptimeMillis();
        countdownAnimator = ValueAnimator.ofFloat(0f, 1f).setDuration(MoodDeck.COUNTDOWN_MS);
        countdownAnimator.setInterpolator(null);
        countdownAnimator.addUpdateListener(a -> {
            countdown = (float) a.getAnimatedValue();
            invalidate();
        });
        countdownAnimator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { openLanded(); }
        });
        countdownAnimator.start();
        invalidate();
    }

    private void openLanded() {
        MoodDeck.Title t = centreTitle();
        if (opened || t == null) return;
        opened = true;
        listener.onShelfOpen(t);
        // The app covers Home now; when it comes back, the row is at rest on the same poster.
        main.postDelayed(afterOpen, AFTER_OPEN_MS);
    }

    private void stopSurpriseAnimators() {
        for (ValueAnimator a : new ValueAnimator[]{spinAnimator, countdownAnimator}) {
            if (a != null) {
                a.removeAllListeners();
                a.cancel();
            }
        }
        spinAnimator = countdownAnimator = null;
    }

    /** Back to browsing on the poster in the middle; `animate` lets a spin glide to it and the button fade back. */
    private void stopSurprise(boolean animate) {
        main.removeCallbacks(afterOpen);
        if (surprise == Surprise.NONE) return;
        boolean spinning = surprise == Surprise.SPIN;
        stopSurpriseAnimators();
        surprise = Surprise.NONE;
        opened = okArmed = false;
        countdown = 0f;
        onButton = false;
        buttonFocus = 0f;
        if (focusAnimator != null) focusAnimator.cancel();
        float at = position;
        position = centre;
        if (animate) {
            animateSurpriseShow(0f);
            if (spinning) {
                glide(at - Math.round(at) + centre, centre, MoodShelf.MOVE_MS, moveCurve);
                showBackdrop(true);
            }
        } else {
            if (surpriseShowAnimator != null) surpriseShowAnimator.cancel();
            surpriseShow = 0f;
        }
        invalidate();
    }

    private void animateSurpriseShow(float to) {
        if (surpriseShowAnimator != null) surpriseShowAnimator.cancel();
        surpriseShowAnimator = ValueAnimator.ofFloat(surpriseShow, to).setDuration(SURPRISE_SHOW_MS);
        surpriseShowAnimator.addUpdateListener(a -> {
            surpriseShow = (float) a.getAnimatedValue();
            invalidate();
        });
        surpriseShowAnimator.start();
    }

    /** Whatever takes Home's place, nothing may open by itself behind it. */
    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) stopSurprise(false);
    }

    /** During the spin or countdown: Left, Up or Back stop it, OK opens now, Right spins again. */
    private boolean surpriseKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_UP:
                playSoundEffect(SoundEffectConstants.NAVIGATION_LEFT);
                stopSurprise(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (surprise == Surprise.COUNTDOWN && !opened && event.getRepeatCount() == 0) spin();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (event.getRepeatCount() == 0) {
                    okArmed = surprise == Surprise.COUNTDOWN;
                    event.startTracking();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                event.startTracking();
                return true;
            default:
                return false;
        }
    }

    private boolean surpriseKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (okArmed && !event.isCanceled() && surprise == Surprise.COUNTDOWN) {
                    playSoundEffect(SoundEffectConstants.CLICK);
                    openLanded();
                }
                okArmed = false;
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!event.isCanceled()) stopSurprise(true);
                return true;
            default:
                return false;
        }
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
        if (surprise != Surprise.NONE) return surpriseKeyDown(keyCode, event) || super.onKeyDown(keyCode, event);
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
        if (surprise != Surprise.NONE) return surpriseKeyUp(keyCode, event) || super.onKeyUp(keyCode, event);
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
                    if (deckReel) listener.onShelfSurprise();
                    else spin();
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

    // ---- drawing ---------------------------------------------------------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rowY = 0.465f * h;
        ph = 0.42f * h;
        pw = ph * 2f / 3f;
        headingPaint.setTextSize(0.032f * h);
        namePaint.setTextSize(0.056f * h);
        titlePaint.setTextSize(0.036f * h);
        smallPaint.setTextSize(0.019f * h);
        buttonText.setTextSize(0.026f * h);
        barPaint.setStrokeWidth(0.007f * h);
        // Further from the screen than the default, so a turned poster's far edge is only a little shorter.
        camera.setLocation(0, 0, -24f * h / 1080f);
        shadePaint.setShader(new LinearGradient(0, 0, 0, h, new int[]{0xB3000000, 0x14000000, 0x14000000, 0x8C000000},
                new float[]{0f, 0.22f, 0.80f, 1f}, Shader.TileMode.CLAMP));
        // A solid rounded triangle, standing on its centre.
        playH = PLAY_SHARE * ph;
        float half = playH / 2f, wide = playH * 0.96f;
        triangle.reset();
        triangle.moveTo(-wide * 0.40f, -half);
        triangle.lineTo(wide * 0.60f, 0f);
        triangle.lineTo(-wide * 0.40f, half);
        triangle.close();
        playPaint.setPathEffect(new CornerPathEffect(0.16f * playH));
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
                    new int[]{withAlpha(mood.color, 0x1C), withAlpha(mood.color, 0x08), withAlpha(mood.color, 0)},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        }
        canvas.drawRect(0, 0, w, h, glowPaint);

        if (!heading.isEmpty()) {
            headingPaint.setAlpha(0xC0);
            canvas.drawText(heading, (w - headingPaint.measureText(heading)) / 2f, 0.066f * h, headingPaint);
        }
        String name = TextUtils.ellipsize(mood.name, namePaint, w * 0.8f, TextUtils.TruncateAt.END).toString();
        namePaint.setAlpha(0xFF);
        float nw = namePaint.measureText(name);
        canvas.drawText(name, (w - nw) / 2f, 0.134f * h, namePaint);
        buttonPaint.setStyle(Paint.Style.FILL);
        buttonPaint.setColor(mood.color);
        float bar = 0.045f * h;
        rect.set((w - bar) / 2f, 0.153f * h, (w + bar) / 2f, 0.153f * h + 0.0045f * h);
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
        if (surprise == Surprise.SPIN) {
            // Every place on screen, numbered on past the row's ends: any row goes round while it spins.
            float repeats = Math.min(1f, Math.abs(position - spinEnd));
            int first = (int) Math.ceil(position - MoodShelf.SIDE - 1f), last = (int) Math.floor(position + MoodShelf.SIDE + 1f);
            for (int place = first; place <= last; place++) {
                shown.add(new float[]{MoodShelf.wrap(place, n), place - position,
                        MoodShelf.staysAfterSpin(place, spinEnd, spinTarget, n) ? 1f : repeats});
            }
        } else {
            for (int i = 0; i < n; i++) {
                float d = MoodShelf.offset(i, position, n);
                if (Math.abs(d) < MoodShelf.SIDE + 1f) shown.add(new float[]{i, d, 1f});
            }
        }
        Collections.sort(shown, (a, b) -> Float.compare(Math.abs(b[1]), Math.abs(a[1])));
        for (float[] s : shown) drawPoster(canvas, (int) s[0], s[1], s[2], w, h);
    }

    private void drawPoster(Canvas canvas, int item, float d, float opacity, int w, int h) {
        float a = Math.abs(d);
        float near1 = Math.min(1f, a);
        float scale = MoodShelf.scale(a);
        float x = w / 2f + Math.signum(d) * MoodShelf.place(a) * h;
        // Fading out past the last poster each side, at the screen's edges.
        float fade = opacity * Math.max(0f, Math.min(1f, MoodShelf.SIDE + 1f - a));
        int alpha = Math.round(255 * fade);
        if (alpha <= 0) return;
        // Turned so its outer edge is further away: the row curves back from the centre.
        float degrees = Math.signum(d) * TILT_DEGREES * near1;
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
        float rr = 0.008f * h;
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
        // Each poster out is a little darker; the centre one is untouched.
        float dim = MoodShelf.dim(a);
        if (dim > 0f) {
            dimPaint.setAlpha(Math.round(255 * dim * fade));
            canvas.drawRoundRect(rect, rr, rr, dimPaint);
        }
        // The play icon on the centre poster (fading as it moves away).
        float icon = Math.max(0f, 1f - a * 2f) * (1f - buttonFocus * 0.6f) * (1f - surpriseShow);
        if (icon > 0f) {
            playPaint.setAlpha(Math.round(PLAY_ALPHA * icon));
            canvas.drawPath(triangle, playPaint);
        }
        if (holdShown && a < 0.01f) drawRing(canvas);
        canvas.restore();
    }

    /** The ring around the play icon while OK is held. */
    private void drawRing(Canvas canvas) {
        float r = playH * 0.95f, stroke = 5 * density;
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(stroke);
        ringPaint.setColor(0x8C000000);
        canvas.drawCircle(0, 0, r, ringPaint);
        ringPaint.setColor(Color.WHITE);
        rect.set(-r, -r, r, r);
        canvas.drawArc(rect, -90f, 360f * holdProgress, false, ringPaint);
    }

    /** Under the row: the centre title, its year and app, "Surprise me", and what the keys do; or the countdown. */
    private void drawBelow(Canvas canvas, int w, int h) {
        MoodDeck.Title t = centreTitle();
        float settle = surprise == Surprise.SPIN ? Math.max(0f, 1f - 2f * Math.abs(position - spinEnd))
                : moving ? Math.max(0f, 1f - 2f * Math.abs(position - centre)) : 1f;
        if (t != null && settle > 0f) {
            int a = Math.round(255 * settle);
            String title = TextUtils.ellipsize(t.title, titlePaint, w * 0.7f, TextUtils.TruncateAt.END).toString();
            titlePaint.setAlpha(a);
            canvas.drawText(title, (w - titlePaint.measureText(title)) / 2f, 0.755f * h, titlePaint);
            String meta = surprise != Surprise.NONE ? openingLine(t)
                    : t.year.isEmpty() ? t.appName.toUpperCase(Locale.ROOT)
                    : t.appName.isEmpty() ? t.year : t.year + "  ·  " + t.appName.toUpperCase(Locale.ROOT);
            smallPaint.setAlpha(Math.round(a * 0.7f));
            canvas.drawText(meta, (w - smallPaint.measureText(meta)) / 2f, 0.795f * h, smallPaint);
            if (surprise != Surprise.NONE) drawCountdownBar(canvas, a, w, h);
        }
        // "Surprise me": an outline at rest, white when Down moved the focus to it.
        String label = getResources().getString(R.string.shelf_surprise);
        float tw = buttonText.measureText(label), bh = 0.070f * h, bw = tw + 0.10f * h, by = 0.880f * h;
        float there = 1f - surpriseShow;
        if (there > 0f) {
            rect.set((w - bw) / 2f, by - bh / 2f, (w + bw) / 2f, by + bh / 2f);
            buttonPaint.setStyle(Paint.Style.FILL);
            buttonPaint.setColor(Color.WHITE);
            buttonPaint.setAlpha(Math.round((0x24 + (0xFF - 0x24) * buttonFocus) * there));
            canvas.drawRoundRect(rect, bh / 2f, bh / 2f, buttonPaint);
            buttonPaint.setStyle(Paint.Style.STROKE);
            buttonPaint.setStrokeWidth(Math.max(1f, 0.0014f * h));
            buttonPaint.setAlpha(Math.round(0x4D * there));
            canvas.drawRoundRect(rect, bh / 2f, bh / 2f, buttonPaint);
            int ink = (int) argb.evaluate(buttonFocus, 0xE6FFFFFF, 0xFF0C0C0E);
            buttonText.setColor(withAlpha(ink, Math.round(Color.alpha(ink) * there)));
            canvas.drawText(label, (w - tw) / 2f, by + buttonText.getTextSize() * 0.36f, buttonText);
        }
        String keys = getResources().getString(surprise == Surprise.COUNTDOWN ? R.string.mood_keys_countdown
                : surprise == Surprise.SPIN ? R.string.mood_keys_spinning
                : onButton ? R.string.shelf_keys_button : R.string.shelf_keys);
        smallPaint.setAlpha(0x80);
        canvas.drawText(keys, (w - smallPaint.measureText(keys)) / 2f, 0.962f * h, smallPaint);
    }

    /** "Opening in 3…" and the title's app (3 while it spins, so nothing jumps when it stops). */
    private String openingLine(MoodDeck.Title t) {
        long elapsed = surprise == Surprise.COUNTDOWN ? SystemClock.uptimeMillis() - countdownStart : 0;
        String opening = getResources().getString(R.string.mood_opening, MoodDeck.secondsLeft(elapsed));
        return t.appName.isEmpty() ? opening : opening + "  ·  " + t.appName.toUpperCase(Locale.ROOT);
    }

    /** The countdown's bar in the mood's colour, under the title. */
    private void drawCountdownBar(Canvas canvas, int alpha, int w, int h) {
        float bw = 0.34f * h, y = 0.832f * h, x = (w - bw) / 2f;
        barPaint.setColor(Color.WHITE);
        barPaint.setAlpha(Math.round(0x26 * alpha / 255f));
        canvas.drawLine(x, y, x + bw, y, barPaint);
        barPaint.setColor(mood.color);
        barPaint.setAlpha(alpha);
        canvas.drawLine(x, y, x + Math.max(0.001f, bw * countdown), y, barPaint);
    }

    /** "I've watched it" over "Cancel", in a small dark panel on the centre poster; the chosen line is white. */
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
        float left = (w - mw) / 2f, top = rowY - mh / 2f;
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
