package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Each app's picture as a Home tile shows it, for ThemeProvider to hand out read-only as a PNG:
 *
 *   content read --uri content://<app id>.theme/app_art/com.netflix.ninja.png > netflix.png
 *
 * The picture is the app's banner, or its icon when it has none, by AppAdapter's own rules (app_banners, the banner
 * at xhdpi or better, a square "banner" counting as none). Only apps Home shows get one: a TV entry that isn't in
 * hidden_apps or taken off Home, or an app added from the App library. Each is drawn once per app version and kept
 * in the launcher's cache folder (cache/app_art/<package>@<versionCode>@<lastUpdateTime>.png), so asking again only
 * opens a file; a newer version of the app draws a new one and the old one is removed.
 */
final class AppArt {
    /** The URI folder, reserved: ThemeFiles never lets a theme write or delete anything under this name. */
    static final String FOLDER = "app_art";
    private static final String PNG = ".png";
    /** A Java package name: two or more dot-separated parts, each a letter and then letters, digits or _. */
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    static final int MAX_PACKAGE = 150;
    /** A banner fits in 400 x 225 px (a 1080p tile is 360 x 202, and focus zooms it); an icon is drawn 128 px square. */
    static final int BANNER_W = 400, BANNER_H = 225, ICON = 128;

    private AppArt() {}

    /** True for any URI under app_art/, valid or not: those never reach the theme files. */
    static boolean isFolder(List<String> segments) {
        return segments != null && !segments.isEmpty() && FOLDER.equals(segments.get(0));
    }

    /** The package of app_art/<package>.png, or null for anything else. */
    static String packageOf(List<String> segments) {
        if (segments == null || segments.size() != 2 || !FOLDER.equals(segments.get(0))) return null;
        String name = segments.get(1);
        if (name == null || !name.endsWith(PNG)) return null;
        String pkg = name.substring(0, name.length() - PNG.length());
        return pkg.length() <= MAX_PACKAGE && PACKAGE.matcher(pkg).matches() ? pkg : null;
    }

    static String cacheName(String pkg, long versionCode, long updated) {
        return pkg + "@" + versionCode + "@" + updated + PNG;
    }

    /** True for any cached picture of [pkg], whatever its version. */
    static boolean isCacheOf(String name, String pkg) {
        return name != null && name.startsWith(pkg + "@") && name.endsWith(PNG);
    }

    /**
     * The size to draw a [w] x [h] picture at inside [maxW] x [maxH], in proportion: scaled down to fit, and up too
     * when [grow]. A drawable with no size of its own (a colour, a shape) fills the box.
     */
    static int[] fit(int w, int h, int maxW, int maxH, boolean grow) {
        if (w <= 0 || h <= 0) return new int[]{maxW, maxH};
        float s = Math.min(maxW / (float) w, maxH / (float) h);
        if (!grow) s = Math.min(1f, s);
        return new int[]{Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s))};
    }

    /**
     * The cached PNG of [pkg]'s tile picture, drawn now if there is none for its current version. Null when Home
     * doesn't show [pkg]. One at a time: two readers of a new picture never draw it twice at once.
     */
    static synchronized File get(Context ctx, String pkg) throws IOException {
        if (pkg.equals(ctx.getPackageName())) return null;
        PackageManager pm = ctx.getPackageManager();
        Config cfg = Config.load(ctx);
        ResolveInfo ri = shown(ctx, pm, pkg, cfg);
        if (ri == null) return null;
        PackageInfo pi;
        try {
            pi = pm.getPackageInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        long version = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        File dir = new File(ctx.getCacheDir(), FOLDER);
        File f = new File(dir, cacheName(pkg, version, pi.lastUpdateTime));
        if (f.isFile() && f.length() > 0) return f;
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no cache folder");

        Drawable banner = AppAdapter.bundledBanner(ctx, cfg.appBanners.get(pkg));
        if (banner == null) banner = AppAdapter.loadBanner(ctx, pm, ri);
        Drawable art = banner != null ? banner : ri.loadIcon(pm);
        if (art == null) return null;
        int[] size = banner != null
                ? fit(art.getIntrinsicWidth(), art.getIntrinsicHeight(), BANNER_W, BANNER_H, false)
                : fit(art.getIntrinsicWidth(), art.getIntrinsicHeight(), ICON, ICON, true);
        Bitmap b = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
        try {
            art.setBounds(0, 0, size[0], size[1]);
            art.draw(new Canvas(b));
            // Written whole to a temporary file, synced and renamed, so a reader never gets half a picture.
            File tmp = File.createTempFile("." + pkg + "-", ".writing", dir);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                if (!b.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("PNG not written");
                out.getFD().sync();
            } catch (IOException | RuntimeException e) {
                tmp.delete();
                throw e;
            }
            if (!tmp.renameTo(f)) {
                tmp.delete();
                throw new IOException("PNG not renamed");
            }
        } finally {
            b.recycle();
        }
        File[] old = dir.listFiles((d, name) -> isCacheOf(name, pkg) && !name.equals(f.getName()));
        if (old != null) for (File o : old) o.delete();
        return f;
    }

    /** [pkg]'s entry as AppAdapter.reload() would show it, or null when Home doesn't show it. */
    private static ResolveInfo shown(Context ctx, PackageManager pm, String pkg, Config cfg) {
        ResolveInfo tv = first(pm, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setPackage(pkg));
        Set<String> added = new HashSet<>(LibraryApps.load(ctx));
        added.addAll(cfg.libraryApps);
        boolean isAdded = added.contains(pkg);
        if (!LibraryApps.visible(tv != null, cfg.hiddenApps.contains(pkg), isAdded,
                LibraryApps.loadRemoved(ctx).contains(pkg))) return null;
        if (tv != null) return tv;
        return first(pm, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg));
    }

    private static ResolveInfo first(PackageManager pm, Intent intent) {
        for (ResolveInfo ri : pm.queryIntentActivities(intent, 0)) {
            if (ri.activityInfo != null && ri.activityInfo.exported) return ri;
        }
        return null;
    }
}
