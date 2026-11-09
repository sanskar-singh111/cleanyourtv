package com.gathin.tvhome;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * How ThemeProvider writes a theme file without ever leaving it empty or half written: the writer gets a temporary
 * file in the same folder, and only when it closes cleanly is that file synced to storage and renamed over the real
 * one. A rename either happens or it doesn't, so until then the real file is the old one, whole. A writer that dies
 * or reports an error leaves the real file untouched, and its temporary file is removed (or, if the launcher itself
 * stops, cleared the next time it starts).
 */
final class ThemeWrites {
    /** Temporary files are hidden and end in this, so no theme pattern ever reads one as a picture or a font. */
    static final String PREFIX = ".tvhome-", SUFFIX = ".writing";

    private final Map<String, CountDownLatch> pending = new HashMap<>();

    static boolean isTemp(String name) {
        return name.startsWith(PREFIX) && name.endsWith(SUFFIX);
    }

    /** A new temporary file next to [target], for a writer to fill. */
    static File temp(File target) throws IOException {
        return File.createTempFile(PREFIX + target.getName() + "-", SUFFIX, target.getParentFile());
    }

    /**
     * After the writer closed [tmp]: with no [error], syncs it and renames it over [target]; otherwise removes it.
     * Returns whether [target] now holds what was written.
     */
    static boolean finish(File tmp, File target, IOException error) {
        if (error != null) {
            tmp.delete();
            return false;
        }
        try (FileOutputStream out = new FileOutputStream(tmp, true)) {
            out.getFD().sync();
        } catch (IOException e) {
            tmp.delete();
            return false;
        }
        if (!tmp.renameTo(target)) {
            tmp.delete();
            return false;
        }
        return true;
    }

    /** Removes temporary files left by writes that never finished: in [dir] and one folder down. */
    static int clearLeftovers(File dir) {
        int n = 0;
        File[] top = dir == null ? null : dir.listFiles();
        if (top == null) return 0;
        for (File f : top) {
            if (f.isDirectory()) {
                File[] inner = f.listFiles();
                if (inner != null) for (File g : inner) if (isTemp(g.getName()) && g.delete()) n++;
            } else if (isTemp(f.getName()) && f.delete()) {
                n++;
            }
        }
        return n;
    }

    /** A write to [target] has started; reads and deletes of it wait until {@link #done}. */
    synchronized void started(File target) {
        pending.put(target.getPath(), new CountDownLatch(1));
    }

    synchronized void done(File target) {
        CountDownLatch l = pending.remove(target.getPath());
        if (l != null) l.countDown();
    }

    /**
     * Waits for a write to [target] that's still being finished, so whoever reads right after `content write`
     * returns gets the new file, not the old one. Returns false if it's still going after [ms].
     */
    boolean awaitDone(File target, long ms) {
        CountDownLatch l;
        synchronized (this) {
            l = pending.get(target.getPath());
        }
        if (l == null) return true;
        try {
            return l.await(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
