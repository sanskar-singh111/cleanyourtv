package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AppArtTest {
    @Test public void aPackagesPictureIsAsked() {
        assertEquals("com.netflix.ninja", AppArt.packageOf(Arrays.asList("app_art", "com.netflix.ninja.png")));
        assertEquals("in.startv.hotstar", AppArt.packageOf(Arrays.asList("app_art", "in.startv.hotstar.png")));
        assertEquals("com.google.android.youtube.tv",
                AppArt.packageOf(Arrays.asList("app_art", "com.google.android.youtube.tv.png")));
        assertEquals("org.x_b.Kodi2", AppArt.packageOf(Arrays.asList("app_art", "org.x_b.Kodi2.png")));
    }

    @Test public void anythingElseIsRefused() {
        assertNull(AppArt.packageOf(null));
        assertNull(AppArt.packageOf(Collections.singletonList("app_art")));
        assertNull(AppArt.packageOf(Arrays.asList("app_art", "netflix.png")));          // one part is no package
        assertNull(AppArt.packageOf(Arrays.asList("app_art", "com.netflix.ninja")));
        assertNull(AppArt.packageOf(Arrays.asList("app_art", "com.netflix.ninja.jpg")));
        assertNull(AppArt.packageOf(Arrays.asList("app_art", "com.netflix.ninja.PNG")));
        assertNull(AppArt.packageOf(Arrays.asList("app_art", "..png")));
        assertNull(AppArt.packageOf(Arrays.asList("app_art", "../config.json.png")));
        assertNull(AppArt.packageOf(Arrays.asList("app_art", ".com.netflix.png")));
        assertNull(AppArt.packageOf(Arrays.asList("app_art", "com..netflix.png")));
        assertNull(AppArt.packageOf(Arrays.asList("app_art", "com.1netflix.png")));
        assertNull(AppArt.packageOf(Arrays.asList("app_art", "com.net flix.png")));
        assertNull(AppArt.packageOf(Arrays.asList("app_art", "com.netflix-ninja.png")));
        assertNull(AppArt.packageOf(Arrays.asList("app_art", "com.netflix.ninja@2.png")));
        assertNull(AppArt.packageOf(Arrays.asList("app_art", "sub", "com.netflix.ninja.png")));
        assertNull(AppArt.packageOf(Arrays.asList("logos", "com.netflix.ninja.png")));
        StringBuilder longName = new StringBuilder("a");
        while (longName.length() < AppArt.MAX_PACKAGE) longName.append(".b");
        assertNull(AppArt.packageOf(Arrays.asList("app_art", longName + ".png")));
    }

    @Test public void theWholeFolderIsKeptFromTheTheme() {
        assertTrue(AppArt.isFolder(Collections.singletonList("app_art")));
        assertTrue(AppArt.isFolder(Arrays.asList("app_art", "anything")));
        assertFalse(AppArt.isFolder(Arrays.asList("logos", "app_art")));
        assertNull(ThemeFiles.path(Arrays.asList("app_art", "com.netflix.ninja.png")));
        assertNull(ThemeFiles.path(Arrays.asList("app_art", "netflix.png")));
        assertFalse(ThemeFiles.isCollection(Collections.singletonList("app_art")));
    }

    @Test public void cachedPicturesAreNamedByVersion() {
        assertEquals("com.netflix.ninja@51@1700000000000.png", AppArt.cacheName("com.netflix.ninja", 51, 1700000000000L));
        assertTrue(AppArt.isCacheOf("com.netflix.ninja@51@1.png", "com.netflix.ninja"));
        assertFalse(AppArt.isCacheOf("com.netflix.ninja.kids@3@1.png", "com.netflix.ninja"));
        assertFalse(AppArt.isCacheOf(".com.netflix.ninja-123.writing", "com.netflix.ninja"));
    }

    @Test public void picturesFitTheirBox() {
        assertArrayEquals(new int[]{320, 180}, AppArt.fit(320, 180, 400, 225, false));     // never grown
        assertArrayEquals(new int[]{400, 225}, AppArt.fit(640, 360, 400, 225, false));
        assertArrayEquals(new int[]{400, 200}, AppArt.fit(800, 400, 400, 225, false));
        assertArrayEquals(new int[]{128, 128}, AppArt.fit(96, 96, 128, 128, true));        // icons are
        assertArrayEquals(new int[]{128, 128}, AppArt.fit(432, 432, 128, 128, true));
        assertArrayEquals(new int[]{400, 225}, AppArt.fit(-1, -1, 400, 225, false));       // a colour fills it
    }

    @Test public void onlyAppLabelsIsAQuery() {
        assertTrue(AppArt.isLabels(Collections.singletonList("app_labels")));
        assertFalse(AppArt.isLabels(null));
        assertFalse(AppArt.isLabels(Collections.<String>emptyList()));
        assertFalse(AppArt.isLabels(Collections.singletonList("config.json")));
        assertFalse(AppArt.isLabels(Collections.singletonList("app_labels.txt")));
        assertFalse(AppArt.isLabels(Arrays.asList("app_labels", "com.netflix.ninja")));
        assertFalse(AppArt.isLabels(Arrays.asList("app_art", "app_labels")));
        assertNull("never a theme file", ThemeFiles.path(Collections.singletonList("app_labels")));
    }
}
