package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
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
import android.view.animation.DecelerateInterpolator;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The watchlist section under the dock: one view that draws the lineup, a page at a time. Every figure is black and
 * white except the selected one, which turns to colour, grows a little and gets a soft glow in its own colour, with
 * its title and app logo above it and its scene crossfading in behind. Pages slide sideways, with small dots under
 * them. Only the page on screen and the next one hold their pictures; nothing is resized or filtered into new bitmaps
 * here (the colour changes are draw-time filters).
 */
@SuppressLint("ViewConstructor")   // made in code only, never from a layout
final class WatchlistView extends View {
    interface Listener {
        /** Up from the top row, or Back: scroll back to the dock. */
        void onWatchlistExit();
        void onWatchlistOpen(Watchlist.Item item);
        /** Down from any page: to the mood deck if there is one (true), else nothing happens. */
        boolean onWatchlistDown();
        /** watchlist_remove: the title was taken off the lineup (already hidden here); record it. */
        void onWatchlistRemove(Watchlist.Item item);
    }

    private static final long SELECT_MS = 240, SCENE_MS = 450, SLIDE_MS = 420;
    /** How long the page takes to scroll down from the dock, before the first character's effect plays. */
    private static final long ENTER_MS = 460;
    private static final float GROW = 0.06f;
    /** watchlist_remove: a press shorter than this opens the title; the ring only shows after it. */
    private static final long RING_DELAY_MS = 350;

    private final List<Watchlist.Item> items;
    private final Map<String, File> logos;
    private final Listener listener;
    private Bitmap[] figures, glows;
    /** 0 = black and white, 1 = fully selected, per item, animated. */
    private float[] selectedness;
    private final Map<File, Bitmap> scenes = new HashMap<>();
    private final Map<String, Bitmap> logoBitmaps = new HashMap<>();
    private final Paint figurePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint scenePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint shadePaint = new Paint();
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint headingPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint appPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final ColorMatrix matrix = new ColorMatrix(), dimMatrix = new ColorMatrix();
    private final RectF rect = new RectF(), glowRect = new RectF();
    /** Each item's glow tint, made once. */
    private PorterDuffColorFilter[] tints;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float density;
    private final String heading;

    private int pages;
    private int selected = -1;
    /** The page on screen, and while a slide runs the page it comes from (slide 0 to 1, +1 for a slide to the right). */
    private int page, fromPage;
    private float slide = 1f;
    private int slideDirection = 1;
    private File scene, previousScene;
    private float sceneMix = 1f;
    private ValueAnimator selectAnimator, sceneAnimator, slideAnimator, effectAnimator;
    /** watchlist_effects: the selected character's moment; null when the switch is off. */
    private final WatchlistEffect effect;
    /** Bumped on every page change and release, so a decode that finishes late doesn't bring back unwanted pictures. */
    private int loadGeneration;
    private boolean logosLoaded;
    private int bottomShadeHeight = -1;

    /** watchlist_remove: how long OK is held for the menu (0 = off), the ring's fill, and the small menu. */
    private final long removeHoldMs;
    private ValueAnimator holdAnimator;
    private float holdProgress;
    private boolean holdShown, menuOpen, swallowOkUp;
    /** 0 = "Remove from watchlist", 1 = "Cancel". */
    private int menuChoice;
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG), menuPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint menuText = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    WatchlistView(Context context, List<Watchlist.Item> items, Map<String, File> logos, String heading,
                  Typeface titleFont, Typeface headingFont, boolean effects, long removeHoldMs, Listener listener) {
        super(context);
        this.items = Watchlist.usable(items);
        this.logos = logos;
        this.listener = listener;
        this.heading = heading == null ? "" : heading;
        figures = new Bitmap[this.items.size()];
        glows = new Bitmap[this.items.size()];
        selectedness = new float[this.items.size()];
        tints = new PorterDuffColorFilter[this.items.size()];
        for (int i = 0; i < tints.length; i++) {
            tints[i] = new PorterDuffColorFilter(this.items.get(i).color | 0xFF000000, PorterDuff.Mode.SRC_IN);
        }
        pages = Watchlist.pageCount(this.items);
        density = context.getResources().getDisplayMetrics().density;
        effect = effects ? new WatchlistEffect(density) : null;
        this.removeHoldMs = Math.max(0, removeHoldMs);
        menuText.setTextSize(22 * density);
        if (headingFont != null) menuText.setTypeface(headingFont);
        setFocusable(true);
        setFocusableInTouchMode(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        titlePaint.setColor(Color.WHITE);
        titlePaint.setTextSize(34 * density);
        if (titleFont != null) titlePaint.setTypeface(titleFont);
        titlePaint.setShadowLayer(12 * density, 0, 2 * density, 0xCC000000);
        headingPaint.setColor(0x8CFFFFFF);
        headingPaint.setTextSize(12 * density);
        headingPaint.setLetterSpacing(0.22f);
        if (headingFont != null) headingPaint.setTypeface(headingFont);
        appPaint.setColor(0xB3FFFFFF);
        appPaint.setTextSize(13 * density);
        appPaint.setLetterSpacing(0.16f);
        if (headingFont != null) appPaint.setTypeface(headingFont);
    }

    boolean isEmpty() {
        return items.isEmpty();
    }

    /**
     * Decodes the pictures of the page on screen and the next one (and the page being slid away from) off the main
     * thread, and lets go of every other page's; the section draws figures as they arrive.
     */
    void load() {
        int generation = ++loadGeneration;
        List<Integer> wanted = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            boolean keep = Watchlist.keeps(items.get(i).page, page, slide < 1f ? fromPage : page);
            if (!keep) { figures[i] = null; glows[i] = null; }
            else if (figures[i] == null) wanted.add(i);
        }
        boolean needLogos = !logosLoaded;
        if (wanted.isEmpty() && !needLogos) return;
        new Thread(() -> {
            BitmapFactory.Options argb = new BitmapFactory.Options();
            argb.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Map<Integer, Bitmap[]> done = new HashMap<>();
            for (int i : wanted) {
                Watchlist.Item it = items.get(i);
                done.put(i, new Bitmap[]{decode(it.image, argb), it.glow == null ? null : decode(it.glow, argb)});
            }
            Map<String, Bitmap> l = new HashMap<>();
            if (needLogos) {
                for (Watchlist.Item it : items) {
                    File file = logos.get(it.packageName);
                    if (file != null && !l.containsKey(it.packageName)) {
                        Bitmap b = decode(file, argb);
                        if (b != null) l.put(it.packageName, b);
                    }
                }
            }
            main.post(() -> {
                if (needLogos) { logoBitmaps.putAll(l); logosLoaded = true; }
                if (generation != loadGeneration) return;   // the page changed meanwhile; that load has its own
                for (Map.Entry<Integer, Bitmap[]> e : done.entrySet()) {
                    figures[e.getKey()] = e.getValue()[0];
                    glows[e.getKey()] = e.getValue()[1];
                }
                if (selected >= 0 && scene == null) showScene(items.get(selected).scene, false);
                invalidate();
            });
        }, "watchlist").start();
    }

    /** Lets go of every picture (Home is in the background and memory is short); {@link #load()} brings them back. */
    void release() {
        loadGeneration++;
        stopEffect();
        cancelHold();
        menuOpen = false;
        for (int i = 0; i < figures.length; i++) { figures[i] = null; glows[i] = null; }
        scenes.clear();
        scene = previousScene = null;
        logoBitmaps.clear();
        logosLoaded = false;
    }

    private static Bitmap decode(File file, BitmapFactory.Options o) {
        if (file == null || !file.isFile()) return null;
        try {
            return BitmapFactory.decodeFile(file.getPath(), o);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    /** Down from the dock: always the first character of the first page, never the one selected last time. */
    void enter() {
        if (slideAnimator != null) slideAnimator.cancel();
        int i = Watchlist.entry(items);
        if (i >= 0) page = fromPage = items.get(i).page;
        slide = 1f;
        load();
        select(i, false);
    }

    int selected() {
        return selected;
    }

    private void select(int index, boolean animate) {
        if (index < 0 || index >= items.size()) return;
        int itemPage = items.get(index).page;
        boolean turnsPage = itemPage != page;
        if (turnsPage) slideTo(itemPage, animate);
        selected = index;
        // Down from the dock waits for the page to scroll in; a new page waits for its slide.
        playEffect(index, !animate ? ENTER_MS : turnsPage ? SLIDE_MS : 0);
        if (selectAnimator != null) selectAnimator.cancel();
        if (!animate) {
            for (int i = 0; i < selectedness.length; i++) selectedness[i] = i == index ? 1f : 0f;
            showScene(items.get(index).scene, false);
            invalidate();
            return;
        }
        float[] from = selectedness.clone();
        selectAnimator = ValueAnimator.ofFloat(0f, 1f).setDuration(SELECT_MS);
        selectAnimator.setInterpolator(new DecelerateInterpolator(1.5f));
        selectAnimator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            for (int i = 0; i < selectedness.length; i++) {
                float to = i == selected ? 1f : 0f;
                selectedness[i] = from[i] + (to - from[i]) * t;
            }
            invalidate();
        });
        selectAnimator.start();
        showScene(items.get(index).scene, true);
    }

    /** Plays the item's effect once, from the start, after `delay` ms; any effect still running stops. */
    private void playEffect(int index, long delay) {
        if (effect == null) return;
        stopEffect();
        String id = items.get(index).effect;
        if (id == null) return;
        effect.start(id, android.os.SystemClock.uptimeMillis() * 31 + index);
        effect.stop();   // nothing shows until the animator's first frame
        effectAnimator = ValueAnimator.ofFloat(0f, 1f).setDuration(effect.duration());
        effectAnimator.setStartDelay(delay);
        effectAnimator.setInterpolator(null);   // linear: t is the effect's own clock
        effectAnimator.addUpdateListener(a -> {
            effect.at(a.getAnimatedFraction() * effect.duration());
            invalidate();
        });
        effectAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                effect.stop();
                invalidate();
            }
        });
        effectAnimator.start();
    }

    private void stopEffect() {
        if (effectAnimator != null) {
            effectAnimator.removeAllListeners();
            effectAnimator.cancel();
            effectAnimator = null;
        }
        if (effect != null) effect.stop();
    }

    /** True while the selected character's effect is on screen (never mid-slide). */
    private boolean effectShowing() {
        return effect != null && effect.running() && selected >= 0 && slide >= 1f;
    }

    /** The page moves sideways: the new one comes in from the right going forward, from the left going back. */
    private void slideTo(int to, boolean animate) {
        if (slideAnimator != null) slideAnimator.cancel();
        fromPage = page;
        slideDirection = to > page ? 1 : -1;
        page = to;
        slide = animate ? 0f : 1f;
        load();
        if (!animate) return;
        slideAnimator = ValueAnimator.ofFloat(0f, 1f).setDuration(SLIDE_MS);
        slideAnimator.setInterpolator(new DecelerateInterpolator(2f));
        slideAnimator.addUpdateListener(a -> {
            slide = (float) a.getAnimatedValue();
            if (slide >= 1f) {
                fromPage = page;
                load();   // the page slid away from can go now
            }
            invalidate();
        });
        slideAnimator.start();
    }

    private void showScene(File next, boolean animate) {
        if (next != null && !scenes.containsKey(next)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;   // already black and white: half the memory, no visible loss
            Bitmap b = decode(next, o);
            if (b != null) scenes.put(next, b);
        }
        if (next == scene) return;
        previousScene = scene;
        scene = next;
        // Keep at most the two pictures of the crossfade.
        scenes.keySet().retainAll(java.util.Arrays.asList(scene, previousScene));
        if (sceneAnimator != null) sceneAnimator.cancel();
        if (!animate) {
            sceneMix = 1f;
            invalidate();
            return;
        }
        sceneMix = 0f;
        sceneAnimator = ValueAnimator.ofFloat(0f, 1f).setDuration(SCENE_MS);
        sceneAnimator.addUpdateListener(a -> {
            sceneMix = (float) a.getAnimatedValue();
            invalidate();
        });
        sceneAnimator.start();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (menuOpen) return menuKeyDown(keyCode) || super.onKeyDown(keyCode, event);
        Watchlist.Direction d = null;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: d = Watchlist.Direction.LEFT; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: d = Watchlist.Direction.RIGHT; break;
            case KeyEvent.KEYCODE_DPAD_UP: d = Watchlist.Direction.UP; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: d = Watchlist.Direction.DOWN; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (event.getRepeatCount() == 0) {
                    event.startTracking();
                    startHold();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
        cancelHold();
        int next = Watchlist.move(items, selected, d);
        if (next == Watchlist.EXIT) {
            playSoundEffect(SoundEffectConstants.NAVIGATION_UP);
            listener.onWatchlistExit();
        } else if (next == Watchlist.BELOW) {
            // The page and the character stay as they are, for Up from the deck.
            if (listener.onWatchlistDown()) playSoundEffect(SoundEffectConstants.NAVIGATION_DOWN);
        } else if (next != selected) {
            playSoundEffect(soundFor(d));
            select(next, true);
        }
        return true;
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
                if (!event.isCanceled() && selected >= 0 && !held) {
                    playSoundEffect(SoundEffectConstants.CLICK);
                    listener.onWatchlistOpen(items.get(selected));
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (menuOpen) closeMenu();
                else if (!event.isCanceled()) listener.onWatchlistExit();
                return true;
            default:
                return super.onKeyUp(keyCode, event);
        }
    }

    // ---- watchlist_remove -------------------------------------------------------------------------------------

    /** OK went down: after a short while a ring fills around the head; full, the small menu opens. */
    private void startHold() {
        cancelHold();
        if (removeHoldMs <= RING_DELAY_MS || selected < 0 || slide < 1f) return;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f).setDuration(removeHoldMs - RING_DELAY_MS);
        a.setStartDelay(RING_DELAY_MS);
        a.setInterpolator(null);
        a.addUpdateListener(v -> {
            holdShown = true;
            holdProgress = (float) v.getAnimatedValue();
            invalidate();
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animator) {
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
        boolean remove = menuChoice == 0;
        closeMenu();
        playSoundEffect(SoundEffectConstants.CLICK);
        if (remove) removeSelected();
    }

    /** Takes the selected character off the lineup at once; the next one on its page (or a neighbour) is selected. */
    private void removeSelected() {
        if (selected < 0 || selected >= items.size()) return;
        int gone = selected;
        Watchlist.Item item = items.get(gone);
        int next = Watchlist.afterRemoving(items, gone);
        stopEffect();
        if (selectAnimator != null) selectAnimator.cancel();
        items.remove(gone);
        figures = without(figures, gone, new Bitmap[figures.length - 1]);
        glows = without(glows, gone, new Bitmap[glows.length - 1]);
        tints = without(tints, gone, new PorterDuffColorFilter[tints.length - 1]);
        float[] s = new float[selectedness.length - 1];
        for (int i = 0, j = 0; i < selectedness.length; i++) if (i != gone) s[j++] = selectedness[i];
        selectedness = s;
        pages = Watchlist.pageCount(items);
        selected = -1;
        listener.onWatchlistRemove(item);
        if (items.isEmpty()) {
            invalidate();
            listener.onWatchlistExit();
            return;
        }
        select(next > gone ? next - 1 : next, true);
        load();
    }

    private static <T> T[] without(T[] from, int gone, T[] to) {
        for (int i = 0, j = 0; i < from.length; i++) if (i != gone) to[j++] = from[i];
        return to;
    }

    /** The ring around the selected character's head while OK is held, over a dark disc so it reads on any scene. */
    private void drawHoldRing(Canvas canvas, int w, int h) {
        if (!holdShown || selected < 0) return;
        Watchlist.Item it = items.get(selected);
        drawnRect(selected, w, h, rect);
        float cx = rect.left + it.headX * rect.width(), cy = rect.top + it.headY * rect.height();
        float r = 26 * density, stroke = 5 * density;
        cy = Math.max(r + 20 * density, cy);
        ringPaint.setStyle(Paint.Style.FILL);
        ringPaint.setColor(0x8C000000);
        canvas.drawCircle(cx, cy, r + 9 * density, ringPaint);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(stroke);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
        ringPaint.setColor(0x4DFFFFFF);
        canvas.drawCircle(cx, cy, r, ringPaint);
        ringPaint.setColor(Color.WHITE);
        glowRect.set(cx - r, cy - r, cx + r, cy + r);
        canvas.drawArc(glowRect, -90f, 360f * holdProgress, false, ringPaint);
    }

    /** "Remove from watchlist" over "Cancel", in a small dark panel by the character; the chosen line is white. */
    private void drawMenu(Canvas canvas, int w, int h) {
        if (!menuOpen || selected < 0) return;
        String[] lines = {getResources().getString(R.string.watchlist_remove),
                getResources().getString(R.string.watchlist_remove_cancel)};
        float pad = 28 * density, row = 58 * density, gap = 8 * density, inset = 10 * density;
        float textW = Math.max(menuText.measureText(lines[0]), menuText.measureText(lines[1]));
        float pw = textW + 2 * pad + 2 * inset, ph = 2 * row + gap + 2 * inset;
        // The scene dims, and the panel sits under the title (placed as drawTitle places it).
        menuPaint.setColor(0x73000000);
        canvas.drawRect(0, 0, w, h, menuPaint);
        figureRect(selected, w, h, rect);
        float titleBaseline = Math.max(110 * density, rect.bottom - rect.height() * (1f + GROW) - 18 * density);
        float margin = 60 * density;
        float left = Math.max(margin, Math.min(w - margin - pw, rect.centerX() - pw / 2f));
        float top = Math.min(h - margin - ph, titleBaseline + 30 * density);
        menuPaint.setColor(0xF2111111);
        glowRect.set(left, top, left + pw, top + ph);
        canvas.drawRoundRect(glowRect, 18 * density, 18 * density, menuPaint);
        Paint.FontMetrics fm = menuText.getFontMetrics();
        for (int i = 0; i < 2; i++) {
            float y = top + inset + i * (row + gap);
            boolean on = i == menuChoice;
            if (on) {
                menuPaint.setColor(Color.WHITE);
                glowRect.set(left + inset, y, left + pw - inset, y + row);
                canvas.drawRoundRect(glowRect, row / 2f, row / 2f, menuPaint);
            }
            menuText.setColor(on ? 0xFF111111 : 0xD9FFFFFF);
            float baseline = y + row / 2f - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(lines[i], left + inset + pad, baseline, menuText);
        }
    }

    private static int soundFor(Watchlist.Direction d) {
        switch (d) {
            case LEFT: return SoundEffectConstants.NAVIGATION_LEFT;
            case RIGHT: return SoundEffectConstants.NAVIGATION_RIGHT;
            case UP: return SoundEffectConstants.NAVIGATION_UP;
            default: return SoundEffectConstants.NAVIGATION_DOWN;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        bottomShadeHeight = -1;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.drawColor(Color.BLACK);
        if (w == 0 || h == 0) return;

        // The scene behind, crossfading.
        if (sceneMix < 1f) drawScene(canvas, scenes.get(previousScene), 1f, w, h);
        drawScene(canvas, scenes.get(scene), sceneMix, w, h);

        // Top edge darker, so the heading reads; the figures go over it.
        if (bottomShadeHeight != h) {
            bottomShadeHeight = h;
            shadePaint.setShader(new LinearGradient(0, 0, 0, h, new int[]{0xB3000000, 0x00000000, 0x00000000, 0xE6000000, 0xFF000000},
                    new float[]{0f, 0.22f, 0.62f, 0.9f, 1f}, Shader.TileMode.CLAMP));
        }

        if (slide < 1f && fromPage != page) {
            // Mid-slide: the old page leaves on one side while the new one comes in from the other.
            drawPage(canvas, fromPage, -slideDirection * slide * w, w, h);
            drawPage(canvas, page, slideDirection * (1f - slide) * w, w, h);
        } else {
            drawPage(canvas, page, 0f, w, h);
        }
        // Legs fade into black at the bottom edge, for both rows.
        canvas.drawRect(0, 0, w, h, shadePaint);
        if (effectShowing()) effect.drawFront(canvas, figures[selected], w, h);
        drawDots(canvas, w, h);

        if (!heading.isEmpty()) {
            canvas.drawText(heading.toUpperCase(java.util.Locale.ROOT), 60 * density, 54 * density, headingPaint);
        }
        if (selected >= 0 && slide >= 1f) drawTitle(canvas, selected, w, h);
        drawHoldRing(canvas, w, h);
        drawMenu(canvas, w, h);
    }

    private void drawPage(Canvas canvas, int p, float dx, int w, int h) {
        if (dx != 0f) {
            canvas.save();
            canvas.translate(dx, 0f);
        }
        for (int i : Watchlist.drawOrder(items, p, selected)) {
            if (i == selected && p == page && effectShowing()) {
                Watchlist.Item it = items.get(i);
                drawnRect(i, w, h, rect);
                effect.place(rect, it.headX, it.headY, it.headSize);
                effect.drawBehind(canvas, figures[i], w, h);
            }
            drawFigure(canvas, i, w, h);
        }
        if (dx != 0f) canvas.restore();
    }

    /** Small dots under the lineup when there is more than one page; the one on screen is white. */
    private void drawDots(Canvas canvas, int w, int h) {
        if (pages < 2) return;
        // Only pages with someone on them (a page can empty out after removals on the TV).
        List<Integer> shown = new ArrayList<>();
        for (int p = 0; p < pages; p++) if (!Watchlist.onPage(items, p).isEmpty()) shown.add(p);
        int n = shown.size();
        if (n < 2) return;
        float r = 3.5f * density, gap = 14 * density;
        float x0 = w / 2f - (n - 1) * gap / 2f, y = h - 30 * density;
        for (int k = 0; k < n; k++) {
            boolean on = shown.get(k) == page;
            dotPaint.setColor(on ? 0xF2FFFFFF : 0x59FFFFFF);
            canvas.drawCircle(x0 + k * gap, y, on ? r * 1.15f : r, dotPaint);
        }
    }

    private void drawScene(Canvas canvas, Bitmap b, float alpha, int w, int h) {
        if (b == null || alpha <= 0f) return;
        float scale = Math.max((float) w / b.getWidth(), (float) h / b.getHeight());
        float dw = b.getWidth() * scale, dh = b.getHeight() * scale;
        rect.set((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f);
        scenePaint.setAlpha(Math.round(alpha * 255));
        canvas.drawBitmap(b, null, rect, scenePaint);
    }

    /** Where item i is drawn at full size (before the selected one's grow), in px. */
    private void figureRect(int i, int w, int h, RectF out) {
        Watchlist.Item it = items.get(i);
        Bitmap b = figures[i];
        float dh = it.height * h;
        float dw = b == null ? dh * 0.5f : b.getWidth() * dh / b.getHeight();
        float cx = it.x * w, bottom = it.bottom * h;
        out.set(cx - dw / 2f, bottom - dh, cx + dw / 2f, bottom);
    }

    /** Where item i is drawn this frame, with the selected one's grow. */
    private void drawnRect(int i, int w, int h, RectF out) {
        figureRect(i, w, h, out);
        float grow = 1f + GROW * selectedness[i];
        float cx = out.centerX(), bottom = out.bottom;
        float dw = out.width() * grow, dh = out.height() * grow;
        out.set(cx - dw / 2f, bottom - dh, cx + dw / 2f, bottom);
    }

    private void drawFigure(Canvas canvas, int i, int w, int h) {
        Bitmap b = figures[i];
        if (b == null) return;
        float s = selectedness[i];
        drawnRect(i, w, h, rect);
        float cx = rect.centerX();
        float dh = rect.height();
        boolean moving = i == selected && effectShowing();
        if (moving) {
            canvas.save();
            effect.move(canvas);
        }

        Bitmap g = glows[i];
        if (g != null && s > 0.01f) {
            // The halo is a quarter of the figure's size plus its blur margin; scaled back up around the same centre.
            float k = dh / b.getHeight() * 4f;
            float gw = g.getWidth() * k, gh = g.getHeight() * k;
            float gy = rect.centerY();
            glowPaint.setColorFilter(tints[i]);
            glowPaint.setAlpha(Math.round(230 * s));
            glowRect.set(cx - gw / 2f, gy - gh / 2f, cx + gw / 2f, gy + gh / 2f);
            canvas.drawBitmap(g, null, glowRect, glowPaint);
        }

        // Black and white, a little dimmed, easing into full colour as it is selected.
        matrix.setSaturation(s);
        float dim = 0.62f + 0.38f * s;
        dimMatrix.setScale(dim, dim, dim, 1f);
        matrix.postConcat(dimMatrix);
        // A filter per frame only while a selection animates; the settled ones are reused.
        figurePaint.setColorFilter(filterFor(s));
        canvas.drawBitmap(b, null, rect, figurePaint);
        if (moving) canvas.restore();
    }

    private final ColorMatrixColorFilter grey = filter(0f), colour = filter(1f);

    private ColorMatrixColorFilter filterFor(float s) {
        if (s <= 0f) return grey;
        if (s >= 1f) return colour;
        return new ColorMatrixColorFilter(matrix);
    }

    private static ColorMatrixColorFilter filter(float s) {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(s);
        float dim = 0.62f + 0.38f * s;
        ColorMatrix d = new ColorMatrix();
        d.setScale(dim, dim, dim, 1f);
        m.postConcat(d);
        return new ColorMatrixColorFilter(m);
    }

    private void drawTitle(Canvas canvas, int i, int w, int h) {
        float s = selectedness[i];
        if (s <= 0.01f) return;
        Watchlist.Item it = items.get(i);
        figureRect(i, w, h, rect);
        float top = rect.bottom - rect.height() * (1f + GROW);
        float margin = 60 * density;
        String title = TextUtils.ellipsize(it.title, titlePaint, w - 2 * margin, TextUtils.TruncateAt.END).toString();
        float tw = titlePaint.measureText(title);
        float cx = Math.max(margin + tw / 2f, Math.min(w - margin - tw / 2f, rect.centerX()));
        float baseline = Math.max(110 * density, top - 18 * density);
        int alpha = Math.round(255 * s);
        titlePaint.setAlpha(alpha);
        canvas.drawText(title, cx - tw / 2f, baseline, titlePaint);

        // The app's logo (the dock's own) or its name, small, above the title.
        float logoBottom = baseline - titlePaint.getTextSize() - 6 * density;
        Bitmap logo = logoBitmaps.get(it.packageName);
        if (logo != null) {
            float lh = 26 * density, lw = logo.getWidth() * lh / logo.getHeight();
            if (lw > 140 * density) { lw = 140 * density; lh = logo.getHeight() * lw / logo.getWidth(); }
            rect.set(cx - lw / 2f, logoBottom - lh, cx + lw / 2f, logoBottom);
            figurePaint.setColorFilter(null);
            figurePaint.setAlpha(alpha);
            canvas.drawBitmap(logo, null, rect, figurePaint);
            figurePaint.setAlpha(255);
        } else if (!it.appName.isEmpty()) {
            String name = it.appName.toUpperCase(java.util.Locale.ROOT);
            appPaint.setAlpha(Math.round(0.7f * alpha));
            canvas.drawText(name, cx - appPaint.measureText(name) / 2f, logoBottom - 4 * density, appPaint);
        }
    }
}
