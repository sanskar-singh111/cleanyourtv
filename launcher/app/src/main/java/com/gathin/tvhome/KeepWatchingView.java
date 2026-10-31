package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Keep watching row above the dock, drawn as one view like the watchlist: a heading and cards with a small picture,
 * the app, the title and the episode. Three cards fit the width and the row slides for more. The focused card grows by
 * focus_grow with a soft white glow and no box. Each picture is decoded small, at most once.
 */
final class KeepWatchingView extends View {
    interface Listener {
        void onKeepWatchingUp();
        void onKeepWatchingDown();
        void onKeepWatchingOpen(KeepWatching.Item item);
    }

    private static final String TAG = "TvHome.KeepWatching";
    /** Cards across the row; more slide in. */
    private static final int PER_SCREEN = 3;
    private static final int FILL_ALPHA = 0x73;
    /** A card is wide, so it grows less than a dock icon does. */
    private static final float GROW = 1.05f;
    /** Sizes in dp at a 960 dp wide screen. */
    private static final float CARD_H = 76, GAP = 14, HEADING_H = 26, ROOM = 8, THUMB_W = 96, RADIUS = 12, INSET = 7, BADGE = 12;
    /** A picture is decoded at most this wide, and fetched up to this many bytes. */
    private static final int THUMB_PX = 320, MAX_BYTES = 4_000_000;

    private final Listener listener;
    private final String heading;
    private final float density;
    private final int growMs;
    private final Paint cardFill = new Paint(Paint.ANTI_ALIAS_FLAG), cardLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint glowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final TextPaint headingPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG), appPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG),
            titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG), episodePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Rect glowRect = new Rect();
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Map<String, Bitmap> thumbs = new HashMap<>();
    private final Map<String, Drawable> badges = new HashMap<>();
    private final List<String> asked = new ArrayList<>();
    private List<KeepWatching.Item> items = new ArrayList<>();
    private int selected;
    private float slide, grow;
    private ValueAnimator slider, grower;
    private Bitmap glow;
    /** An item was opened from here: focus comes back to the row with Home. */
    boolean opened;

    KeepWatchingView(Context context, String heading, Typeface regular, Typeface medium, int growMs, Listener listener) {
        super(context);
        this.listener = listener;
        this.heading = heading;
        this.growMs = growMs;
        density = getResources().getDisplayMetrics().density;
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDefaultFocusHighlightEnabled(false);
        setVisibility(GONE);
        cardFill.setColor(0xFF101014);
        cardFill.setAlpha(FILL_ALPHA);
        glowPaint.setColor(0xFFFFFFFF);
        cardLine.setStyle(Paint.Style.STROKE);
        cardLine.setStrokeWidth(density * 0.75f);
        cardLine.setColor(0x2EFFFFFF);
        headingPaint.setTypeface(regular);
        headingPaint.setTextSize(13 * density);
        headingPaint.setColor(0xD9FFFFFF);
        appPaint.setTypeface(medium);
        appPaint.setTextSize(10 * density);
        appPaint.setLetterSpacing(0.08f);
        appPaint.setColor(0xB3FFFFFF);
        titlePaint.setTypeface(medium);
        titlePaint.setTextSize(15 * density);
        titlePaint.setColor(0xFFFFFFFF);
        episodePaint.setTypeface(regular);
        episodePaint.setTextSize(11 * density);
        episodePaint.setColor(0xB3FFFFFF);
    }

    /** Height of the whole row: the heading, a card and room for it to grow. */
    int rowHeight() {
        return Math.round((HEADING_H + CARD_H + ROOM * 2) * density);
    }

    boolean hasItems() {
        return !items.isEmpty();
    }

    /** New rows from the list. A focused row that went empty is left by the caller. */
    void setItems(List<KeepWatching.Item> fresh) {
        String keep = selected < items.size() ? key(items.get(selected)) : null;
        items = fresh;
        int at = 0;
        for (int i = 0; i < fresh.size(); i++) if (key(fresh.get(i)).equals(keep)) at = i;
        selected = at;
        slide = target(at);
        setVisibility(fresh.isEmpty() ? GONE : VISIBLE);
        for (KeepWatching.Item item : fresh) {
            loadThumb(item);
            if (!badges.containsKey(item.packageName)) {
                try {
                    badges.put(item.packageName, getContext().getPackageManager().getApplicationIcon(item.packageName));
                } catch (Exception e) {
                    badges.put(item.packageName, null);
                }
            }
        }
        invalidate();
    }

    private static String key(KeepWatching.Item item) {
        return item.packageName + '\n' + item.title;
    }

    private float cardWidth() {
        return (getWidth() - GAP * density * (PER_SCREEN - 1)) / PER_SCREEN;
    }

    /** The row's slide that keeps this card on screen, the selected one in the last place at most. */
    private float target(int index) {
        return Math.max(0, index - (PER_SCREEN - 1));
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previously) {
        super.onFocusChanged(focused, direction, previously);
        if (grower != null) grower.cancel();
        grower = ValueAnimator.ofFloat(grow, focused ? 1f : 0f).setDuration(growMs);
        grower.setInterpolator(new DecelerateInterpolator());
        grower.addUpdateListener(a -> {
            grow = (float) a.getAnimatedValue();
            invalidate();
        });
        grower.start();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                int next = selected + (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                if (next >= 0 && next < items.size()) {
                    selected = next;
                    playSoundEffect(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? SoundEffectConstants.NAVIGATION_RIGHT : SoundEffectConstants.NAVIGATION_LEFT);
                    slideTo(target(next));
                    invalidate();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                listener.onKeepWatchingUp();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                listener.onKeepWatchingDown();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (event.getRepeatCount() == 0 && selected < items.size()) {
                    opened = true;
                    listener.onKeepWatchingOpen(items.get(selected));
                }
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private void slideTo(float to) {
        if (to == slide) return;
        if (slider != null) slider.cancel();
        slider = ValueAnimator.ofFloat(slide, to).setDuration(220);
        slider.setInterpolator(new DecelerateInterpolator());
        slider.addUpdateListener(a -> {
            slide = (float) a.getAnimatedValue();
            invalidate();
        });
        slider.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (items.isEmpty()) return;
        float top = HEADING_H * density + ROOM * density, cardH = CARD_H * density, cardW = cardWidth();
        canvas.drawText(heading, 2 * density, 16 * density, headingPaint);
        float pitch = cardW + GAP * density;
        for (int i = 0; i < items.size(); i++) {
            float x = (i - slide) * pitch;
            if (x > getWidth() || x + cardW < 0 || i == selected) continue;
            drawCard(canvas, items.get(i), x, top, cardW, cardH, 1f);
        }
        // The selected card last, over its neighbours as it grows.
        float x = (selected - slide) * pitch, g = 1f + (GROW - 1f) * grow;
        if (grow > 0f) drawGlow(canvas, x, top, cardW, cardH, g);
        drawCard(canvas, items.get(selected), x, top, cardW, cardH, g);
    }

    private void drawGlow(Canvas canvas, float x, float y, float w, float h, float scale) {
        float pad = 18 * density;
        if (glow == null || glow.getWidth() != Math.round((w + 2 * pad) / 2)) glow = makeGlow(w, h, pad);
        float cx = x + w / 2, cy = y + h / 2;
        glowRect.set(Math.round(cx - (w * scale + 2 * pad) / 2), Math.round(cy - (h * scale + 2 * pad) / 2),
                Math.round(cx + (w * scale + 2 * pad) / 2), Math.round(cy + (h * scale + 2 * pad) / 2));
        glowPaint.setAlpha(Math.round(255 * grow));
        canvas.drawBitmap(glow, null, glowRect, glowPaint);
    }

    /** A soft white rounded square, at half size: it is stretched over the card and all of it is blur. */
    private Bitmap makeGlow(float w, float h, float pad) {
        int bw = Math.round((w + 2 * pad) / 2), bh = Math.round((h + 2 * pad) / 2);
        Bitmap out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0x66FFFFFF);
        p.setMaskFilter(new BlurMaskFilter(pad / 3, BlurMaskFilter.Blur.NORMAL));
        new Canvas(out).drawRoundRect(new RectF(pad / 2, pad / 2, bw - pad / 2, bh - pad / 2), RADIUS * density / 2, RADIUS * density / 2, p);
        return out;
    }

    private void drawCard(Canvas canvas, KeepWatching.Item item, float x, float y, float w, float h, float scale) {
        int saved = canvas.save();
        canvas.scale(scale, scale, x + w / 2, y + h / 2);
        float r = RADIUS * density, inset = INSET * density;
        rect.set(x, y, x + w, y + h);
        // The focused card is nearly solid, so the glow stays around it and doesn't grey it.
        cardFill.setAlpha(scale > 1f ? Math.round(FILL_ALPHA + (0xF2 - FILL_ALPHA) * grow) : FILL_ALPHA);
        canvas.drawRoundRect(rect, r, r, cardFill);
        canvas.drawRoundRect(rect, r, r, cardLine);
        float thumbW = THUMB_W * density, thumbH = h - 2 * inset, tx = x + inset, ty = y + inset;
        Bitmap thumb = thumbs.get(item.picture);
        Drawable badge = badges.get(item.packageName);
        if (thumb != null) {
            BitmapShader shader = new BitmapShader(thumb, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float s = Math.max(thumbW / thumb.getWidth(), thumbH / thumb.getHeight());
            Matrix m = new Matrix();
            m.setScale(s, s);
            m.postTranslate(tx + (thumbW - thumb.getWidth() * s) / 2, ty + (thumbH - thumb.getHeight() * s) / 2);
            shader.setLocalMatrix(m);
            thumbPaint.setShader(shader);
            rect.set(tx, ty, tx + thumbW, ty + thumbH);
            canvas.drawRoundRect(rect, r / 2, r / 2, thumbPaint);
        } else if (badge != null) {
            // No picture from the app: its own icon in the same place.
            float size = thumbH * 0.72f, left = tx + (thumbW - size) / 2, topIcon = ty + (thumbH - size) / 2;
            badge.setBounds(Math.round(left), Math.round(topIcon), Math.round(left + size), Math.round(topIcon + size));
            badge.draw(canvas);
        }
        float textX = tx + thumbW + 10 * density, textW = x + w - inset - textX, line = y + 22 * density;
        if (badge != null && thumb != null) {
            int s = Math.round(BADGE * density);
            badge.setBounds(Math.round(textX), Math.round(line - s + density), Math.round(textX) + s, Math.round(line + density));
            badge.draw(canvas);
        }
        float appX = textX + (badge != null && thumb != null ? (BADGE + 5) * density : 0);
        canvas.drawText(TextUtils.ellipsize(item.app.toUpperCase(java.util.Locale.getDefault()), appPaint, x + w - inset - appX, TextUtils.TruncateAt.END).toString(), appX, line, appPaint);
        line += 21 * density;
        canvas.drawText(TextUtils.ellipsize(item.title, titlePaint, textW, TextUtils.TruncateAt.END).toString(), textX, line, titlePaint);
        if (!item.episode.isEmpty()) {
            line += 17 * density;
            canvas.drawText(TextUtils.ellipsize(item.episode, episodePaint, textW, TextUtils.TruncateAt.END).toString(), textX, line, episodePaint);
        }
        canvas.restoreToCount(saved);
    }

    private void loadThumb(KeepWatching.Item item) {
        String uri = item.picture;
        if (uri.isEmpty() || thumbs.containsKey(uri) || asked.contains(uri)) return;
        asked.add(uri);
        loader.execute(() -> {
            Bitmap bitmap = decode(uri);
            if (bitmap != null) post(() -> {
                thumbs.put(uri, bitmap);
                invalidate();
            });
        });
    }

    private Bitmap decode(String uri) {
        try {
            byte[] data = read(uri);
            if (data == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(new ByteArrayInputStream(data), null, o);
            o.inJustDecodeBounds = false;
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= THUMB_PX) o.inSampleSize *= 2;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeStream(new ByteArrayInputStream(data), null, o);
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "no picture: " + e);
            return null;
        }
    }

    /** The picture's bytes: from the web, or through the app's own content link. */
    private byte[] read(String uri) throws Exception {
        Uri parsed = Uri.parse(uri);
        String scheme = parsed.getScheme();
        HttpURLConnection web = null;
        InputStream in;
        if ("https".equals(scheme) || "http".equals(scheme)) {
            web = (HttpURLConnection) new URL(uri).openConnection();
            web.setConnectTimeout(6_000);
            web.setReadTimeout(6_000);
            in = web.getInputStream();
        } else if (ContentResolver.SCHEME_CONTENT.equals(scheme) || ContentResolver.SCHEME_ANDROID_RESOURCE.equals(scheme)) {
            in = getContext().getContentResolver().openInputStream(parsed);
        } else {
            return null;
        }
        try (InputStream stream = new BufferedInputStream(in)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = stream.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) return null;
            }
            return out.toByteArray();
        } finally {
            if (web != null) web.disconnect();
        }
    }

    void close() {
        loader.shutdownNow();
    }
}
