package com.gathin.tvhome;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class IdleKeyTest {
    @Test
    public void threeQuickPressesAreEnough() {
        IdleKey key = new IdleKey(3);
        assertFalse(key.press(1000));
        assertFalse(key.press(1270));
        assertTrue(key.press(1540));
    }

    @Test
    public void threePressesCanTakeOneAndAHalfSeconds() {
        IdleKey key = new IdleKey(3);
        assertFalse(key.press(1000));
        assertFalse(key.press(1750));
        assertTrue(key.press(2500));
    }

    @Test
    public void aSlowPressStartsAgain() {
        IdleKey key = new IdleKey(3);
        assertFalse(key.press(1000));
        assertFalse(key.press(1500));
        assertFalse(key.press(2251));
        assertFalse(key.press(2500));
        assertTrue(key.press(2750));
    }

    @Test
    public void theCountStartsAgainAfterItIsReached() {
        IdleKey key = new IdleKey(2);
        assertFalse(key.press(1000));
        assertTrue(key.press(1100));
        assertFalse(key.press(1200));
        assertTrue(key.press(1300));
    }

    @Test
    public void resetForgetsThePresses() {
        IdleKey key = new IdleKey(2);
        assertFalse(key.press(1000));
        key.reset();
        assertFalse(key.press(1100));
    }
}
