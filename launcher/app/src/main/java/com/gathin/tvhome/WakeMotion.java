package com.gathin.tvhome;

import android.view.Choreographer;
import android.view.View;

import java.util.Collections;
import java.util.List;

/**
 * wake_animation: how Home comes back from the idle fade, and how it leaves. The dock rises from below the screen on a spring
 * with a little overshoot, fading in as it comes, the Keep watching row a moment after it, and what sits above them fades in;
 * leaving is the same, downwards, the row first. The big clock stays through it all. Everything runs from one clock, and a frame only sets a translation and an
 * alpha: nothing is drawn again. Nothing here runs without the switch.
 */
final class WakeMotion implements Choreographer.FrameCallback {
    /** Takes where a group of views stands: its alpha and how far down it is, in pixels. */
    interface Mover {
        void move(float alpha, float y);
    }

    /** The spring: a little under critical, so it overshoots by about 4 % and is within 1 % of rest 0.5 s from the start. */
    private static final float DAMPING = 0.72f, FREQUENCY = 14f;
    /** How long the fade takes, how long the row waits behind the dock, and when the spring counts as stopped, in seconds. */
    static final float FADE_S = 0.3f, ROW_DELAY_S = 0.09f, END_S = 0.7f;
    /** 254 of 255: enough for the GPU to draw these views as translucent, not enough to see. */
    static final float WARM_ALPHA = 0.996f;

    /** One group's place: where it stands and where this run started from, 0 away and 1 at rest (more in the overshoot). */
    private static final class Part {
        final Mover mover;
        final float delayIn, delayOut;
        final boolean slides;
        float place = 1f, alpha = 1f, fromPlace, fromAlpha;

        Part(Mover mover, float delayIn, float delayOut, boolean slides) {
            this.mover = mover;
            this.delayIn = delayIn;
            this.delayOut = delayOut;
            this.slides = slides;
        }
    }

    private final Part dock, row, rest;
    private final float speed;
    private final Runnable settled;
    private List<View> fading = Collections.emptyList();
    private float distance;
    private boolean coming, running;
    private long start;

    /**
     * `dock` and `row` slide; `row` may be null. `settled` runs when Home has finished coming back.
     * speedPercent is wake_animation_speed: 100 settles in about half a second, 200 in half that.
     */
    WakeMotion(Mover dock, Mover row, int speedPercent, Runnable settled) {
        this.dock = new Part(dock, 0f, ROW_DELAY_S, true);
        this.row = row == null ? null : new Part(row, ROW_DELAY_S, 0f, true);
        rest = new Part((alpha, y) -> {
            for (View v : fading) v.setAlpha(alpha);
        }, 0f, 0f, false);
        speed = speedPercent / 100f;
        this.settled = settled;
    }

    /** Where the spring is `seconds` after it let go: 0 at the start, past 1 at the overshoot, then 1. */
    static float spring(float seconds) {
        if (seconds <= 0f) return 0f;
        double decay = DAMPING * FREQUENCY, wobble = FREQUENCY * Math.sqrt(1 - DAMPING * DAMPING);
        return (float) (1 - Math.exp(-decay * seconds) * (Math.cos(wobble * seconds) + decay / wobble * Math.sin(wobble * seconds)));
    }

    /** The fade, soft at both ends. */
    static float ease(float amount) {
        float a = Math.max(0f, Math.min(1f, amount));
        return a * a * (3 - 2 * a);
    }

    /** Home comes back from where it stands now. `fading` fades without moving; `distance` is how far down the dock starts. */
    void come(List<View> fading, float distance) {
        run(true, fading, distance);
    }

    /** Home leaves, as far as `distance` pixels down. */
    void leave(List<View> fading, float distance) {
        run(false, fading, distance);
    }

    private void run(boolean coming, List<View> fading, float distance) {
        this.coming = coming;
        this.fading = fading;
        this.distance = distance;
        for (Part p : parts()) {
            p.fromPlace = p.place;
            p.fromAlpha = p.alpha;
        }
        start = 0;
        if (!running) Choreographer.getInstance().postFrameCallback(this);
        running = true;
    }

    /** At rest at once, for leaving Home: nothing is left to wait for a frame that won't come. */
    void jump(List<View> fading, float distance) {
        if (running) Choreographer.getInstance().removeFrameCallback(this);
        running = false;
        this.fading = fading;
        this.distance = distance;
        for (Part p : parts()) {
            p.place = 1f;
            p.alpha = 1f;
            apply(p);
        }
        settled.run();
    }

    /**
     * Draws everything that moves for one frame just under full alpha, nothing anyone can see. The first translucent draw of
     * these views makes the GPU build its programs, 130 ms on a Xiaomi MOOQ3 after an install, 33 ms from Android's shader cache
     * after that, which would otherwise fall in the first frames of the first leave. Done while Home is still, it costs nothing seen.
     */
    void warmUp(List<View> fading) {
        if (running) return;
        this.fading = fading;
        for (Part p : parts()) p.mover.move(WARM_ALPHA, 0f);
        Choreographer choreographer = Choreographer.getInstance();
        choreographer.postFrameCallback(drawing -> choreographer.postFrameCallback(drawn -> {
            if (running) return;
            for (Part p : parts()) apply(p);
        }));
    }

    /** Gone at once, for arriving on the idle screen from an app: the dock is never seen leaving. */
    void away(List<View> fading, float distance) {
        if (running) Choreographer.getInstance().removeFrameCallback(this);
        running = false;
        this.fading = fading;
        this.distance = distance;
        for (Part p : parts()) {
            p.place = 0f;
            p.alpha = 0f;
            apply(p);
        }
    }

    private Part[] parts() {
        return row == null ? new Part[]{dock, rest} : new Part[]{dock, row, rest};
    }

    @Override
    public void doFrame(long now) {
        if (start == 0) start = now;
        float seconds = (now - start) / 1e9f * speed;
        boolean done = seconds >= END_S;
        float target = coming ? 1f : 0f;
        for (Part p : parts()) {
            float t = Math.max(0f, seconds - (coming ? p.delayIn : p.delayOut));
            p.place = done ? target : p.fromPlace + (target - p.fromPlace) * spring(t);
            p.alpha = done ? target : p.fromAlpha + (target - p.fromAlpha) * ease(t / FADE_S);
            apply(p);
        }
        if (!done) {
            Choreographer.getInstance().postFrameCallback(this);
            return;
        }
        running = false;
        if (coming) settled.run();
    }

    private void apply(Part p) {
        p.mover.move(p.alpha, p.slides ? (1f - p.place) * distance : 0f);
    }
}
