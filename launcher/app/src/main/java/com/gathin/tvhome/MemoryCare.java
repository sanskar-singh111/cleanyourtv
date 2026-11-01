package com.gathin.tvhome;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Process;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto memory care: when Home comes back and free memory is under a limit, background apps that aren't playing go, with
 * Android's own killBackgroundProcesses. It works with no computer in the room. TV Studio's TV Monitor page writes
 * {@link #SETTINGS_FILE} into the theme folder and reads {@link #LOG_FILE}; no file means it's off, and nothing else runs.
 *
 * What is safe to clear is read from dumpsys, as a launcher can't ask Android: running processes are hidden from apps, and
 * media sessions need a notification listener, which low-RAM TVs don't allow. That takes the DUMP and PACKAGE_USAGE_STATS
 * permissions, granted by build-and-install with MEMORY_CARE=1. If anything can't be read, nothing is cleared. Android itself leaves any process that
 * isn't in the background, so an app that plays with a foreground service stays even without these checks.
 */
final class MemoryCare implements Runnable {
    static final String SETTINGS_FILE = "memory_care.json", LOG_FILE = "memory_care_log.json";
    /** This long after Home comes back, so the wait for it to settle is over and nothing here falls in its first frames. */
    static final long DELAY_MS = 1500;
    static final int DEFAULT_LIMIT_MB = 250;
    /** Android takes a moment to hand the memory back. */
    private static final long SETTLE_MS = 2000;
    private static final int MAX_RUNS = 20;
    private static final String TAG = "MemoryCare";
    /** Live TV is one of the system's parts, and the one exception: it has an app's memory and comes back when it's opened. */
    static final String LIVE_TV = "com.mediatek.wwtv.tvcenter";
    /** Never cleared, the same list as MonitorStop in TV Studio (a test there compares them). */
    static final Set<String> CORE = new HashSet<>(Arrays.asList(
            "com.gathin.tvhome", "system_server", "android", "com.google.android.gms", "com.google.android.gsf", "com.google.android.gsf.login",
            "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx", "com.google.android.katniss", "com.google.android.backdrop",
            "com.google.android.tts", "com.google.android.webview", "com.google.android.tungsten.setupwraith", "com.google.android.tv.remote.service",
            "com.google.android.apps.mediashell", "com.google.android.marvin.talkback"));
    /** Whole families of the system's parts: Android's own (Bluetooth, system UI, settings), the board's, keyboards and setup. */
    static final String[] CORE_PREFIXES = {
            "android.", "com.android.", "com.google.android.ext.", "com.google.android.inputmethod", "com.google.android.leanbackime",
            "com.google.android.permissioncontroller", "com.google.android.packageinstaller", "com.google.android.overlay", "com.google.android.modulemetadata",
            "com.google.android.tv", "com.mediatek.", "com.mstar.", "com.cvte.", "com.xiaomi.mitv.", "com.mi."};
    /** Sections, each read on the TV and cut down there: what's playing, the processes, audio players, TV input sessions. */
    private static final String READ = "echo @@media; dumpsys media_session | grep -E 'Sessions Stack|package=|state=PlaybackState'; "
            + "echo @@lru; dumpsys activity lru; "
            + "echo @@audio; dumpsys audio | grep AudioPlaybackConfiguration; "
            + "echo @@tv; dumpsys tv_input | sed -n '/sessionStateMap/,/mCallbacks/p'";
    private static final String CHECK_LRU = "echo @@lru; dumpsys activity lru";
    /** "  #28: cch+ 5 CEM  --- 2992:com.android.vending/u0a56". */
    private static final Pattern PROCESS = Pattern.compile("#\\s*\\d+: .*?\\s(\\d+):([A-Za-z][A-Za-z0-9_.]*)(?::[^/\\s]*)?/\\S+");
    private static final Pattern PLAYER = Pattern.compile("u/pid:\\S+/(\\d+) state:(\\w+)");
    private static final Pattern MEDIA_STATE = Pattern.compile("PlaybackState \\{state=(\\d+)");
    /** Everything but none, stopped and error: playing, paused, winding, buffering, connecting, skipping. */
    private static final Set<Integer> LIVE_STATES = new HashSet<>(Arrays.asList(2, 3, 4, 5, 6, 8, 9, 10, 11));
    /** One run at a time, however quickly Home comes and goes. */
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private final Context context;
    private final BooleanSupplier onHome;

    private MemoryCare(Context context, BooleanSupplier onHome) {
        this.context = context.getApplicationContext();
        this.onHome = onHome;
    }

    /** Looks at the memory on a thread of its own. `onHome` says Home is still in front: when it isn't, no more apps go. */
    static void start(Context context, BooleanSupplier onHome) {
        if (!RUNNING.compareAndSet(false, true)) return;
        new Thread(new MemoryCare(context, onHome), TAG).start();
    }

    /** Free memory in MB: free, plus cache that isn't mapped by an app, with the slab's reclaimable part. The same sum as TV
     *  Monitor's Free, as MemAvailable can be wrong (162 GB on a Xiaomi MOOQ3). -1 when a part is missing. */
    static int freeMb(String meminfo) {
        Map<String, Long> kb = new HashMap<>();
        for (String line : meminfo.split("\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length >= 2 && f[0].endsWith(":")) {
                try {
                    kb.put(f[0].substring(0, f[0].length() - 1), Long.parseLong(f[1]));
                } catch (NumberFormatException e) {
                    // a line that isn't one of these
                }
            }
        }
        for (String part : new String[]{"MemTotal", "MemFree", "Buffers", "Cached", "SReclaimable", "Mapped"}) {
            if (!kb.containsKey(part)) return -1;
        }
        long free = kb.get("MemFree"), available = free + kb.get("Buffers") + kb.get("Cached") + kb.get("SReclaimable") - kb.get("Mapped");
        return (int) (Math.min(kb.get("MemTotal"), Math.max(free, available)) / 1024);
    }

    static boolean isCore(String pkg) {
        if (CORE.contains(pkg)) return true;
        for (String prefix : CORE_PREFIXES) if (pkg.startsWith(prefix)) return true;
        return false;
    }

    /** The text after each "@@name" line, or null for a section that's missing. */
    static String section(String text, String name) {
        int at = text.startsWith("@@" + name + "\n") ? 0 : text.indexOf("\n@@" + name + "\n") + 1;
        if (at == 0 && !text.startsWith("@@" + name + "\n")) return null;
        int start = text.indexOf('\n', at) + 1, end = text.indexOf("\n@@", start - 1);
        return text.substring(start, end < 0 ? text.length() : Math.max(start, end));
    }

    /** pid to package from `dumpsys activity lru`: an app's other processes ("com.x:service") count as the app's. Null if it isn't that. */
    static Map<Integer, String> processes(String lru) {
        if (lru == null || !lru.contains("LRU PROCESSES")) return null;
        Map<Integer, String> out = new HashMap<>();
        for (String line : lru.split("\n")) {
            Matcher m = PROCESS.matcher(line);
            if (m.find()) out.put(Integer.parseInt(m.group(1)), m.group(2));
        }
        return out;
    }

    /** The packages with a session playing or paused, from `dumpsys media_session`. Null if it isn't that. */
    static Set<String> mediaPackages(String media) {
        if (media == null || !media.contains("Sessions Stack")) return null;
        Set<String> out = new HashSet<>();
        String pkg = null;
        for (String raw : media.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("package=")) pkg = line.substring(8);
            Matcher m = MEDIA_STATE.matcher(line);
            if (pkg != null && m.find() && LIVE_STATES.contains(Integer.parseInt(m.group(1)))) out.add(pkg);
        }
        return out;
    }

    /** The packages with an audio player started or paused: apps with no media session show here. */
    static Set<String> audioPackages(String audio, Map<Integer, String> processes) {
        Set<String> out = new HashSet<>();
        if (audio == null) return out;
        for (String line : audio.split("\n")) {
            Matcher m = PLAYER.matcher(line);
            if (!m.find() || !("started".equals(m.group(2)) || "paused".equals(m.group(2)))) continue;
            String pkg = processes.get(Integer.parseInt(m.group(1)));
            if (pkg != null) out.add(pkg);
        }
        return out;
    }

    /** True if `dumpsys tv_input` shows an open session: something on HDMI or Live TV. Null if it isn't that. */
    static Boolean tvSession(String tv) {
        if (tv == null || !tv.contains("sessionStateMap")) return null;
        for (String line : tv.split("\n")) {
            String t = line.trim();
            if (t.startsWith("inputId:")) return true;
        }
        return false;
    }

    /** What may go: running, an app the TV lists (or Live TV), and none of the things that always stay. */
    static List<String> targets(Collection<String> running, Set<String> apps, Set<String> keep, Set<String> busy, boolean tvBusy) {
        Set<String> out = new LinkedHashSet<>();
        for (String pkg : running) {
            if (keep.contains(pkg) || busy.contains(pkg)) continue;
            if (LIVE_TV.equals(pkg) ? !tvBusy : apps.contains(pkg) && !isCore(pkg)) out.add(pkg);
        }
        return new ArrayList<>(out);
    }

    /** Those of `targets` with none of the processes they had: an app that came straight back with a new one counts as gone. */
    static List<String> gone(List<String> targets, Map<Integer, String> before, Map<Integer, String> after) {
        List<String> out = new ArrayList<>();
        for (String pkg : targets) {
            boolean left = false;
            for (Map.Entry<Integer, String> p : after.entrySet()) {
                if (pkg.equals(p.getValue()) && pkg.equals(before.get(p.getKey()))) left = true;
            }
            if (!left) out.add(pkg);
        }
        return out;
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        try {
            check();
        } catch (Exception e) {
            // Whatever goes wrong here, Home carries on and the apps stay.
            Log.w(TAG, "stopped: " + e);
        } finally {
            RUNNING.set(false);
        }
    }

    private void check() throws Exception {
        File dir = Config.dir(context);
        if (dir == null) return;
        File settings = new File(dir, SETTINGS_FILE);
        if (!settings.isFile()) return;
        JSONObject s = new JSONObject(new String(read(settings), StandardCharsets.UTF_8));
        if (!s.optBoolean("on", false)) return;
        int limit = s.optInt("limit_mb", DEFAULT_LIMIT_MB);
        int before = freeMb(new String(read(new File("/proc/meminfo")), StandardCharsets.US_ASCII));
        if (before < 0 || before >= limit) return;

        String text = shell(READ);
        Map<Integer, String> processes = processes(section(text, "lru"));
        Set<String> media = mediaPackages(section(text, "media"));
        Boolean tvBusy = tvSession(section(text, "tv"));
        if (processes == null || media == null || tvBusy == null) {
            log(dir, before, limit, "Couldn't read what's running or playing, so nothing was cleared.", null, null, null);
            return;
        }
        Set<String> busy = new HashSet<>(media);
        busy.addAll(audioPackages(section(text, "audio"), processes));
        Set<String> keep = new HashSet<>();
        JSONArray kept = s.optJSONArray("keep");
        for (int i = 0; kept != null && i < kept.length(); i++) keep.add(kept.getString(i));
        List<String> targets = targets(processes.values(), listedApps(), keep, busy, tvBusy);
        if (targets.isEmpty()) {
            log(dir, before, limit, "Nothing to clear.", null, null, null);
            return;
        }

        ActivityManager am = context.getSystemService(ActivityManager.class);
        List<String> asked = new ArrayList<>();
        for (String pkg : targets) {
            if (!onHome.getAsBoolean()) break;
            am.killBackgroundProcesses(pkg);
            asked.add(pkg);
        }
        Thread.sleep(SETTLE_MS);
        int after = freeMb(new String(read(new File("/proc/meminfo")), StandardCharsets.US_ASCII));
        Map<Integer, String> still = processes(section(shell(CHECK_LRU), "lru"));
        List<String> gone = still == null ? asked : gone(asked, processes, still);
        List<String> stayed = new ArrayList<>(asked);
        stayed.removeAll(gone);
        log(dir, before, limit, gone.isEmpty() ? "Android kept what it was asked to clear." : "", gone, stayed, after);
    }

    /** The apps a person can open on this TV, as TV Monitor's Stop offers: anything that isn't known to be an app stays. */
    private Set<String> listedApps() {
        Set<String> out = new HashSet<>();
        PackageManager pm = context.getPackageManager();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                out.add(r.activityInfo.packageName);
            }
        }
        return out;
    }

    /** Keeps the last check, whatever it found, and every run that cleared something, newest first. */
    private void log(File dir, int before, int limit, String note, List<String> gone, List<String> stayed, Integer after) throws Exception {
        long now = System.currentTimeMillis();
        Log.i(TAG, "free " + before + " MB, limit " + limit + ": " + note + " cleared " + gone + ", kept " + stayed + ", free after " + after);
        File file = new File(dir, LOG_FILE);
        JSONObject log = new JSONObject();
        JSONArray runs = new JSONArray();
        if (file.isFile()) {
            try {
                log = new JSONObject(new String(read(file), StandardCharsets.UTF_8));
                JSONArray old = log.optJSONArray("runs");
                if (old != null) runs = old;
            } catch (Exception e) {
                log = new JSONObject();   // a broken log starts again
            }
        }
        log.put("last", new JSONObject().put("at", now).put("free_mb", before).put("limit_mb", limit).put("note", note)
                .put("kept", new JSONArray(stayed == null ? new ArrayList<String>() : stayed)));
        if (gone != null && !gone.isEmpty()) {
            JSONArray next = new JSONArray();
            next.put(new JSONObject().put("at", now).put("before_mb", before).put("after_mb", after).put("apps", new JSONArray(gone))
                    .put("kept", new JSONArray(stayed)));
            for (int i = 0; i < runs.length() && next.length() < MAX_RUNS; i++) next.put(runs.get(i));
            runs = next;
        }
        log.put("runs", runs);
        File temp = new File(dir, LOG_FILE + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(log.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!temp.renameTo(file)) throw new IOException("log not written");
    }

    private static byte[] read(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return readAll(in);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        for (int n; (n = in.read(chunk)) > 0; ) out.write(chunk, 0, n);
        return out.toByteArray();
    }

    private static String shell(String command) throws Exception {
        java.lang.Process p = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) out.append(line).append('\n');
        }
        p.waitFor();
        return out.toString();
    }
}
