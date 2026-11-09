package com.gathin.tvhome;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ThemeWritesTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static byte[] bytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    private static void writeHalf(File tmp, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data, 0, data.length / 2);
        }
    }

    private static int temps(File dir) {
        int n = 0;
        File[] all = dir.listFiles();
        if (all != null) for (File f : all) {
            if (f.isDirectory()) n += temps(f);
            else if (ThemeWrites.isTemp(f.getName())) n++;
        }
        return n;
    }

    @Test public void aWriteCutShortLeavesTheOldFileWhole() throws IOException {
        File dir = folder.newFolder("files");
        File target = new File(dir, "wallpaper.jpg");
        byte[] before = bytes(1 << 20, 1), after = bytes(1 << 20, 2);
        Files.write(target.toPath(), before);

        File tmp = ThemeWrites.temp(target);
        assertEquals(dir, tmp.getParentFile());
        writeHalf(tmp, after);
        assertArrayEquals("the real file is never truncated while a write is going", before,
                Files.readAllBytes(target.toPath()));

        // The writer's process died halfway: the provider's close listener gets an error.
        assertFalse(ThemeWrites.finish(tmp, target, new IOException("Remote side is dead")));
        assertArrayEquals(before, Files.readAllBytes(target.toPath()));
        assertEquals(0, temps(dir));
    }

    @Test public void aCleanCloseReplacesTheFileWhole() throws IOException {
        File dir = folder.newFolder("files");
        new File(dir, "logos").mkdirs();
        File target = new File(dir, "logos/netflix.png");
        byte[] before = bytes(5000, 3), after = bytes(7000, 4);
        Files.write(target.toPath(), before);
        File tmp = ThemeWrites.temp(target);
        Files.write(tmp.toPath(), after);
        assertTrue(ThemeWrites.finish(tmp, target, null));
        assertArrayEquals(after, Files.readAllBytes(target.toPath()));
        assertEquals(0, temps(dir));
    }

    @Test public void aNewFileAppearsOnlyWhenItIsComplete() throws IOException {
        File dir = folder.newFolder("files");
        File target = new File(dir, "config.json");
        File tmp = ThemeWrites.temp(target);
        writeHalf(tmp, bytes(4000, 5));
        assertFalse(target.exists());
        assertFalse(ThemeWrites.finish(tmp, target, new IOException("closed with an error")));
        assertFalse("no empty file left behind", target.exists());
        assertEquals(0, temps(dir));
    }

    @Test public void leftoversFromAStoppedLauncherAreClearedAtStart() throws IOException {
        File dir = folder.newFolder("files");
        new File(dir, "wallpapers").mkdirs();
        File a = new File(dir, "config.json"), b = new File(dir, "wallpapers/dusk.jpg");
        byte[] ca = bytes(300, 6), cb = bytes(9000, 7);
        Files.write(a.toPath(), ca);
        Files.write(b.toPath(), cb);
        // The launcher was stopped while both were being written: no close listener ever ran.
        writeHalf(ThemeWrites.temp(a), bytes(300, 8));
        writeHalf(ThemeWrites.temp(b), bytes(9000, 9));
        File other = new File(dir, "memory_care.json");
        Files.write(other.toPath(), bytes(10, 10));

        assertEquals(2, ThemeWrites.clearLeftovers(dir));
        assertEquals(0, temps(dir));
        assertArrayEquals(ca, Files.readAllBytes(a.toPath()));
        assertArrayEquals(cb, Files.readAllBytes(b.toPath()));
        assertTrue("other files stay", other.exists());
        assertEquals(0, ThemeWrites.clearLeftovers(null));
    }

    @Test public void temporaryFilesAreNeverThemeFiles() throws IOException {
        File dir = folder.newFolder("files");
        String name = ThemeWrites.temp(new File(dir, "dusk.jpg")).getName();
        assertTrue(ThemeWrites.isTemp(name));
        assertFalse(ThemeFiles.collectionImage(name));
        assertNull(ThemeFiles.path(Arrays.asList(ThemeFiles.WALLPAPERS, name)));
        assertNull(ThemeFiles.path(Arrays.asList(name)));
        assertFalse(ThemeWrites.isTemp("dusk.jpg"));
    }

    @Test public void aReadWaitsForAWriteBeingFinished() throws InterruptedException {
        ThemeWrites w = new ThemeWrites();
        File f = new File("config.json");
        assertTrue(w.awaitDone(f, 10));
        w.started(f);
        assertFalse(w.awaitDone(f, 50));
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }
            w.done(f);
        });
        long start = System.nanoTime();
        t.start();
        assertTrue(w.awaitDone(f, 5000));
        assertTrue((System.nanoTime() - start) / 1_000_000 >= 150);
        t.join();
    }
}
