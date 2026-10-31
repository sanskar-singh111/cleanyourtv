package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WeatherLooksTest {
    private static String look(int code) {
        return WeatherLooks.look(code, -1, -1);
    }

    @Test
    public void everyWeatherCodeHasItsLook() {
        for (int code : new int[]{0, 1}) assertEquals(WeatherLooks.CLEAR, look(code));
        assertEquals(WeatherLooks.PARTLY, look(2));
        assertEquals(WeatherLooks.OVERCAST, look(3));
        for (int code : new int[]{45, 48}) assertEquals(WeatherLooks.FOG, look(code));
        for (int code : new int[]{51, 53, 55, 56, 57, 61, 80}) assertEquals(WeatherLooks.DRIZZLE, look(code));
        for (int code : new int[]{63, 65, 66, 67, 81, 82}) assertEquals(WeatherLooks.RAIN, look(code));
        for (int code : new int[]{95, 96, 99}) assertEquals(WeatherLooks.STORM, look(code));
        for (int code : new int[]{71, 73, 75, 77}) assertEquals(WeatherLooks.SNOW, look(code));
    }

    @Test
    public void noReadingIsAClearSky() {
        assertEquals(WeatherLooks.CLEAR, look(-1));
    }

    @Test
    public void lowVisibilityOrBadAirIsHazeUnderAnOpenSky() {
        assertEquals(WeatherLooks.HAZE, WeatherLooks.look(0, 3000, -1));
        assertEquals(WeatherLooks.HAZE, WeatherLooks.look(2, -1, 150));
        assertEquals(WeatherLooks.HAZE, WeatherLooks.look(3, 24000, 90));
        assertEquals(WeatherLooks.CLEAR, WeatherLooks.look(0, 7660, 50.5f));
    }

    @Test
    public void realWeatherWinsOverHaze() {
        assertEquals(WeatherLooks.RAIN, WeatherLooks.look(63, 1000, 200));
        assertEquals(WeatherLooks.FOG, WeatherLooks.look(45, 200, 200));
        assertEquals(WeatherLooks.STORM, WeatherLooks.look(95, 1000, 200));
    }

    @Test
    public void somethingFallingStaysOnItsWayAndComesRound() {
        assertEquals(10f, WeatherSky.fall(0.1f, 0, 99, 100), 0.001f);
        assertEquals(30f, WeatherSky.fall(0.1f, 20, 1, 100), 0.001f);
        assertEquals(10f, WeatherSky.fall(0.1f, 100, 1, 100), 0.001f);
    }

    @Test
    public void aFlashIsGoneInSevenTenthsOfASecond() {
        assertEquals(0f, WeatherScene.flash(0), 0.001f);
        assertEquals(1f, WeatherScene.flash(60), 0.001f);
        assertTrue(WeatherScene.flash(250) > WeatherScene.flash(200));
        assertEquals(0f, WeatherScene.flash(700), 0.001f);
        assertEquals(0f, WeatherScene.flash(5000), 0.001f);
    }
}
