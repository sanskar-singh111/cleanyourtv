package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MemoryCareTest {
    /** As `dumpsys activity lru` printed it on a Xiaomi MOOQ3. */
    private static final String LRU = "ACTIVITY MANAGER LRU PROCESSES (dumpsys activity lru)\n  Activities:\n"
            + "  #32: fg     TOP  LCM 16329:com.gathin.tvhome/u0a86 act:activities\n"
            + "  #31: fg     BFGS --- 2355:com.mediatek.wwtv.tvcenter/1000 act:activities|recents\n"
            + "  #30: vis    BFGS LCM 1905:com.google.android.katniss:interactor/u0a57 act:treated\n"
            + "  Other:\n"
            + "  #29: vis    FGS  LCM 1699:com.google.android.gms/u0a54\n"
            + "  #28: cch+ 5 CEM  --- 2992:com.android.vending/u0a56\n"
            + "  #27: cch+15 CEM  --- 7001:com.google.android.youtube.tv/u0a90\n"
            + "  #26: cch+25 CEM  --- 7002:com.netflix.ninja:background/u0a91\n"
            + "  #25: prcp   FGS  LCM 7003:com.spotify.tv.android/u0a92\n"
            + "  # 9: pers   PER  LCM 2065:com.mi.bluetooth/1002\n"
            + "  # 0: sys    PER  LCM 905:system/1000\n";

    private static Set<String> set(String... s) {
        return new HashSet<>(Arrays.asList(s));
    }

    @Test
    public void freeMemoryIsTheSumTvMonitorShows() {
        // MemAvailable is ignored: this TV says 3 TB.
        String meminfo = "MemTotal:        1740488 kB\nMemFree:           47228 kB\nMemAvailable:   3029243856 kB\nBuffers:           44604 kB\n"
                + "Cached:           731324 kB\nSReclaimable:      50000 kB\nMapped:           400000 kB\n";
        assertEquals((47228 + 44604 + 731324 + 50000 - 400000) / 1024, MemoryCare.freeMb(meminfo));
    }

    @Test
    public void freeMemoryNeverGoesUnderWhatIsEmptyOrOverTheTotal() {
        assertEquals(100, MemoryCare.freeMb("MemTotal: 2000000 kB\nMemFree: 102400 kB\nBuffers: 0 kB\nCached: 0 kB\nSReclaimable: 0 kB\nMapped: 900000 kB\n"));
        assertEquals(1, MemoryCare.freeMb("MemTotal: 1024 kB\nMemFree: 100 kB\nBuffers: 0 kB\nCached: 90000 kB\nSReclaimable: 0 kB\nMapped: 0 kB\n"));
    }

    @Test
    public void aMissingPartMeansNoNumber() {
        assertEquals(-1, MemoryCare.freeMb("MemTotal: 2000000 kB\nMemFree: 102400 kB\n"));
        assertEquals(-1, MemoryCare.freeMb(""));
    }

    @Test
    public void readsEveryProcessAndItsPackage() {
        Map<Integer, String> p = MemoryCare.processes(LRU);
        assertEquals("com.google.android.youtube.tv", p.get(7001));
        assertEquals("com.netflix.ninja", p.get(7002));
        assertEquals("com.google.android.katniss", p.get(1905));
        assertEquals("com.mediatek.wwtv.tvcenter", p.get(2355));
        assertEquals(10, p.size());
    }

    @Test
    public void aDeniedDumpIsNotAnEmptyTv() {
        assertNull(MemoryCare.processes("Permission Denial: can't dump ActivityManager from pid=1, uid=10086\n"));
        assertNull(MemoryCare.processes(null));
        assertNull(MemoryCare.mediaPackages("Permission Denial: can't dump MediaSessionService\n"));
        assertNull(MemoryCare.tvSession("Permission Denial\n"));
    }

    @Test
    public void sectionsComeApart() {
        String text = "@@media\n  Sessions Stack - have 0 sessions:\n@@lru\nline one\nline two\n@@audio\n@@tv\n  sessionStateMap: x\n";
        assertEquals("  Sessions Stack - have 0 sessions:", MemoryCare.section(text, "media"));
        assertEquals("line one\nline two", MemoryCare.section(text, "lru"));
        assertEquals("", MemoryCare.section(text, "audio"));
        assertTrue(MemoryCare.section(text, "tv").contains("sessionStateMap"));
        assertNull(MemoryCare.section(text, "nope"));
    }

    @Test
    public void playingAndPausedSessionsAreBusyAndOthersAreNot() {
        String media = "  Sessions Stack - have 3 sessions:\n    package=com.spotify.tv.android\n      state=PlaybackState {state=2, position=5}\n"
                + "    package=com.google.android.youtube.tv\n      state=PlaybackState {state=3, position=9}\n"
                + "    package=com.netflix.ninja\n      state=PlaybackState {state=1, position=0}\n"
                + "    package=in.startv.hotstar\n      state=null\n";
        assertEquals(set("com.spotify.tv.android", "com.google.android.youtube.tv"), MemoryCare.mediaPackages(media));
        assertEquals(set(), MemoryCare.mediaPackages("  Sessions Stack - have 0 sessions:\n"));
    }

    @Test
    public void anAppWithNoSessionShowsByItsAudioPlayer() {
        Map<Integer, String> p = MemoryCare.processes(LRU);
        String audio = "  AudioPlaybackConfiguration piid:15 type:android.media.SoundPool u/pid:1000/905 state:idle attr:AudioAttributes: usage=X\n"
                + "  AudioPlaybackConfiguration piid:16 type:android.media.AudioTrack u/pid:10090/7001 state:paused attr:AudioAttributes: usage=X\n"
                + "  AudioPlaybackConfiguration piid:17 type:android.media.AudioTrack u/pid:10091/7002 state:stopped attr:AudioAttributes: usage=X\n";
        assertEquals(set("com.google.android.youtube.tv"), MemoryCare.audioPackages(audio, p));
    }

    @Test
    public void aSessionInTvInputIsSomethingOnHdmiOrLiveTv() {
        assertEquals(Boolean.FALSE, MemoryCare.tvSession("  sessionStateMap: ITvInputSession -> SessionState\n  mCallbacks:\n"));
        assertEquals(Boolean.TRUE, MemoryCare.tvSession("  sessionStateMap: ITvInputSession -> SessionState\n"
                + "    android.os.BinderProxy@1: com.android.server.tv.TvInputManagerService$SessionState@2\n"
                + "      inputId: com.mediatek.tvinput/.hdmi.HDMIInputService/HW4\n  mCallbacks:\n"));
    }

    @Test
    public void onlyListedAppsGo() {
        Set<String> apps = set("com.google.android.youtube.tv", "com.netflix.ninja", "com.spotify.tv.android", "com.android.vending", "com.gathin.tvhome");
        List<String> t = MemoryCare.targets(MemoryCare.processes(LRU).values(), apps, set(), set(), false);
        assertTrue(t.containsAll(Arrays.asList("com.google.android.youtube.tv", "com.netflix.ninja", "com.spotify.tv.android", MemoryCare.LIVE_TV)));
        assertEquals(4, t.size());
    }

    @Test
    public void whatAlwaysStaysStays() {
        for (String core : new String[]{"com.gathin.tvhome", "com.google.android.katniss", "com.google.android.gms", "com.android.bluetooth", "com.mi.bluetooth",
                "com.android.systemui", "com.android.tv.settings", "com.mediatek.tvinput", "com.xiaomi.mitv.tvmanager", "system_server", "android"}) {
            assertTrue(core, MemoryCare.isCore(core));
            assertFalse(core, MemoryCare.targets(Collections.singletonList(core), set(core), set(), set(), false).contains(core));
        }
    }

    @Test
    public void theKeepListAndAnythingBusyStay() {
        Set<String> apps = set("com.netflix.ninja", "com.spotify.tv.android");
        List<String> running = Arrays.asList("com.netflix.ninja", "com.spotify.tv.android");
        assertEquals(Collections.singletonList("com.spotify.tv.android"), MemoryCare.targets(running, apps, set("com.netflix.ninja"), set(), false));
        assertEquals(Collections.singletonList("com.netflix.ninja"), MemoryCare.targets(running, apps, set(), set("com.spotify.tv.android"), false));
    }

    @Test
    public void liveTvStaysWhenAnythingIsOnHdmiOrKeptOrPlaying() {
        List<String> running = Collections.singletonList(MemoryCare.LIVE_TV);
        assertEquals(running, MemoryCare.targets(running, set(), set(), set(), false));
        assertTrue(MemoryCare.targets(running, set(), set(), set(), true).isEmpty());
        assertTrue(MemoryCare.targets(running, set(), set(MemoryCare.LIVE_TV), set(), false).isEmpty());
        assertTrue(MemoryCare.targets(running, set(), set(), set(MemoryCare.LIVE_TV), false).isEmpty());
    }

    @Test
    public void onlyWhatReallyLeftCountsAsGone() {
        Map<Integer, String> before = new HashMap<>(), after = new HashMap<>();
        before.put(1, "a.b");
        before.put(2, "c.d");
        before.put(3, "e.f");
        after.put(2, "c.d");      // Android kept it
        after.put(9, "e.f");      // a new process: the old one went
        assertEquals(Arrays.asList("a.b", "e.f"), MemoryCare.gone(Arrays.asList("a.b", "c.d", "e.f"), before, after));
    }
}
