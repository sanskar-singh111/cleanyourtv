package com.gathin.tvhome;

/**
 * idle_key: counts quick presses of one remote button for HomeKeyService. Each press has GAP_MS to follow the one before,
 * so three presses take at most 1.5 s; a slower press starts again.
 */
final class IdleKey {
    /** How long a press waits for the next one. */
    static final long GAP_MS = 750;
    static final int MIN_PRESSES = 2, MAX_PRESSES = 5;

    private final int presses;
    private int count;
    private long last;

    IdleKey(int presses) {
        this.presses = presses;
    }

    /** A press at `at`, in milliseconds: true when it is the last one wanted, and the count starts again. */
    boolean press(long at) {
        count = count > 0 && at - last <= GAP_MS ? count + 1 : 1;
        last = at;
        if (count < presses) return false;
        count = 0;
        return true;
    }

    /** No more presses came. */
    void reset() {
        count = 0;
    }
}
