package com.gathin.tvhome;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** The theme keys behind a personal Home: taglines, colours, theme files and the weather icon. */
public class PersonalHomeTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void taglinesTakeTurnsOnEachVisit() {
        List<String> lines = Arrays.asList("Say my name.", "Friends don't lie.", "Bella ciao.");
        assertEquals("Say my name.", Taglines.pick(lines, 0));
        assertEquals("Friends don't lie.", Taglines.pick(lines, 1));
        assertEquals("Say my name.", Taglines.pick(lines, 3));
        assertEquals("Bella ciao.", Taglines.pick(lines, -1));
        assertEquals("One more episode.", Taglines.pick(Collections.singletonList("One more episode."), 7));
        assertEquals("", Taglines.pick(null, 2));
        assertEquals("", Taglines.pick(Collections.emptyList(), 2));
    }

    @Test
    public void packageLinesKeepEachAppsLinesInOrder() {
        Map<String, List<String>> lines = Config.packageLines(Arrays.asList(
                "com.netflix.ninja=Say my name.", "bad entry", "=no package", "com.netflix.ninja=Bella ciao.",
                "app.netmirror.newtv=One more episode.", "in.startv.hotstar="));
        assertEquals(Arrays.asList("Say my name.", "Bella ciao."), lines.get("com.netflix.ninja"));
        assertEquals(Collections.singletonList("One more episode."), lines.get("app.netmirror.newtv"));
        assertNull(lines.get("in.startv.hotstar"));
        assertEquals(2, lines.size());
    }

    @Test
    public void coloursParseOrAreLeftOut() {
        assertEquals(Integer.valueOf(0xFFE50914), Config.parseColor("#E50914"));
        assertEquals(Integer.valueOf(0xFF00A8E1), Config.parseColor(" #8000a8e1 "));
        assertNull(Config.parseColor("red"));
        assertNull(Config.parseColor("#E5091"));
        assertNull(Config.parseColor(null));
        Map<String, Integer> colors = Config.colorMap(Config.packageMap(Arrays.asList("a=#FF0000", "b=blue")));
        assertEquals(1, colors.size());
        assertEquals(Integer.valueOf(0xFFFF0000), colors.get("a"));
    }

    @Test
    public void themeFilesStayInTheThemeAndMustExist() throws IOException {
        File dir = tmp.getRoot();
        new File(dir, "logos").mkdirs();
        write(new File(dir, "logos/netflix.png"));
        write(new File(dir, "wallpaper.png"));
        new File(dir, "logos/empty.png").createNewFile();
        assertEquals(new File(dir, "logos/netflix.png"), Config.themeFile(dir, "logos/netflix.png"));
        assertEquals(new File(dir, "wallpaper.png"), Config.themeFile(dir, " wallpaper.png "));
        assertNull(Config.themeFile(dir, "logos/missing.png"));
        assertNull(Config.themeFile(dir, "logos/empty.png"));
        assertNull(Config.themeFile(dir, "../logos/netflix.png"));
        assertNull(Config.themeFile(dir, "logos/../wallpaper.png"));
        assertNull(Config.themeFile(dir, "/etc/hosts"));
        assertNull(Config.themeFile(dir, "config.json"));
        assertNull(Config.themeFile(dir, "a/b/c.png"));
        assertNull(Config.themeFile(dir, ""));
        assertNull(Config.themeFile(dir, null));
        assertNull(Config.themeFile(null, "wallpaper.png"));
    }

    @Test
    public void weatherCodesMapToIcons() {
        assertEquals(WeatherIcon.CLEAR_DAY, WeatherIcon.forCode(0, true));
        assertEquals(WeatherIcon.CLEAR_NIGHT, WeatherIcon.forCode(0, false));
        assertEquals(WeatherIcon.PARTLY_DAY, WeatherIcon.forCode(2, true));
        assertEquals(WeatherIcon.PARTLY_NIGHT, WeatherIcon.forCode(1, false));
        assertEquals(WeatherIcon.CLOUDY, WeatherIcon.forCode(3, true));
        assertEquals(WeatherIcon.FOG, WeatherIcon.forCode(45, true));
        assertEquals(WeatherIcon.RAIN, WeatherIcon.forCode(61, true));
        assertEquals(WeatherIcon.RAIN, WeatherIcon.forCode(81, false));
        assertEquals(WeatherIcon.SNOW, WeatherIcon.forCode(73, true));
        assertEquals(WeatherIcon.STORM, WeatherIcon.forCode(95, true));
        assertEquals(WeatherIcon.CLOUDY, WeatherIcon.forCode(1234, true));
    }

    private static void write(File f) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[]{1, 2, 3});
        }
    }
}
