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
    }

    private static final long SELECT_MS = 240, SCENE_MS = 450, SLIDE_MS = 420;
    private static final float GROW = 0.06f;

    private final List<Watchlist.Item> items;
    private final Map<String, File> logos;
    private final Listener listener;
    private final Bitmap[] figures, glows;
    /** 0 = black and white, 1 = fully selected, per item, animated. */
    private final float[] selectedness;
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
    private final PorterDuffColorFilter[] tints;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float density;
    private final String heading;

    private final int pages;
    private int selected = -1;
    /** The page on screen, and while a slide runs the page it comes from (slide 0 to 1, +1 for a slide to the right). */
    private int page, fromPage;
    private float slide = 1f;
    private int slideDirection = 1;
    private File scene, previousScene;
    private float sceneMix = 1f;
    private ValueAnimator selectAnimator, sceneAnimator, slideAnimator;
    /** Bumped on every page change and release, so a decode that finishes late doesn't bring back unwanted pictures. */
    private int loadGeneration;
    private boolean logosLoaded;
    private int bottomShadeHeight = -1;

    WatchlistView(Context context, List<Watchlist.Item> items, Map<String, File> logos, String heading,
                  Typeface titleFont, Typeface headingFont, Listener listener) {
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

    /** Selects the character nearest `x` (0 to 1) on the page shown last, as Down from the dock does. */
    void enter(float x) {
        int i = Watchlist.entry(items, page, x);
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
        if (itemPage != page) slideTo(itemPage, animate);
        selected = index;
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
        Watchlist.Direction d = null;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: d = Watchlist.Direction.LEFT; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: d = Watchlist.Direction.RIGHT; break;
            case KeyEvent.KEYCODE_DPAD_UP: d = Watchlist.Direction.UP; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: d = Watchlist.Direction.DOWN; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                event.startTracking();
                return true;
            case KeyEvent.KEYCODE_BACK:
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
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
                if (!event.isCanceled() && selected >= 0) {
                    playSoundEffect(SoundEffectConstants.CLICK);
                    listener.onWatchlistOpen(items.get(selected));
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!event.isCanceled()) listener.onWatchlistExit();
                return true;
            default:
                return super.onKeyUp(keyCode, event);
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
        drawDots(canvas, w, h);

        if (!heading.isEmpty()) {
            canvas.drawText(heading.toUpperCase(java.util.Locale.ROOT), 60 * density, 54 * density, headingPaint);
        }
        if (selected >= 0 && slide >= 1f) drawTitle(canvas, selected, w, h);
    }

    private void drawPage(Canvas canvas, int p, float dx, int w, int h) {
        if (dx != 0f) {
            canvas.save();
            canvas.translate(dx, 0f);
        }
        for (int i : Watchlist.drawOrder(items, p, selected)) drawFigure(canvas, i, w, h);
        if (dx != 0f) canvas.restore();
    }

    /** Small dots under the lineup when there is more than one page; the one on screen is white. */
    private void drawDots(Canvas canvas, int w, int h) {
        if (pages < 2) return;
        float r = 3.5f * density, gap = 14 * density;
        float x0 = w / 2f - (pages - 1) * gap / 2f, y = h - 30 * density;
        for (int p = 0; p < pages; p++) {
            dotPaint.setColor(p == page ? 0xF2FFFFFF : 0x59FFFFFF);
            canvas.drawCircle(x0 + p * gap, y, p == page ? r * 1.15f : r, dotPaint);
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

    private void drawFigure(Canvas canvas, int i, int w, int h) {
        Bitmap b = figures[i];
        if (b == null) return;
        float s = selectedness[i];
        figureRect(i, w, h, rect);
        float grow = 1f + GROW * s;
        float cx = rect.centerX(), bottom = rect.bottom;
        float dw = rect.width() * grow, dh = rect.height() * grow;
        rect.set(cx - dw / 2f, bottom - dh, cx + dw / 2f, bottom);

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
