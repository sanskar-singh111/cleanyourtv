package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ChangedPackages;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.SoundEffectConstants;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    private static final int LAUNCHER_SETTINGS_REQUEST = 42;
    /** Sent by QuickSettingsActivity: open Launcher settings from here so Home rebuilds when they close. */
    static final String ACTION_LAUNCHER_SETTINGS = "com.gathin.tvhome.action.LAUNCHER_SETTINGS";
    /** The system input switcher, forwarded here by InputsRequestActivity while handle_view_inputs is on. */
    static final String ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";
    /** idle_key: HomeKeyService asks for the idle screen now. */
    static final String ACTION_IDLE_NOW = "com.gathin.tvhome.action.IDLE_NOW";
    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private boolean rtlAppGrid;
    private static final long MINUTE_MS = 60_000L;
    private static final long STATS_MS = 10_000L;
    /**
     * 2025-01-01. TVs without a clock battery boot at the epoch (08:00 in China) and keep it until network
     * time arrives; the clock, date and greeting stay hidden before then.
     */
    private static final long TIME_VALID_AFTER_MS = 1_735_689_600_000L;
    private String liveTvPkg;
    /** Launcher settings' change count this Home was built with (see onResume). */
    private long settingsRevision;
    /** Idle time on Home before ClockActivity; 0 is off (config.xml, home_idle_clock_minutes). */
    private long idleClockMs;
    private final Runnable showIdleClock = () -> startActivity(new Intent(this, ClockActivity.class));
    /** Idle time on Home before it fades to the background; 0 is off (config.xml, idle_fade_minutes). */
    private long idleFadeMs;
    /** Home has faded: the next key only brings it back. The key whose press woke it, so its release is eaten too. */
    private boolean faded;
    /** Between onResume and onPause. */
    private volatile boolean resumedHome;
    /** Home is resumed, for HomeKeyService's key_filter_home_only when it connects with no window change to go by. */
    static volatile boolean inFront;
    private int wakeKey = KeyEvent.KEYCODE_UNKNOWN;
    /** screensaver: what the faded Home shows (Screensaver), its dim, fade, slides and their pictures. */
    private String saverMode = Screensaver.WALLPAPER;
    private int saverDim;
    private int saverFadeMs = 1000;
    private long saverSlideMs;
    private List<File> saverImages = Collections.emptyList();
    private Bitmap.Config saverBitmapConfig = Bitmap.Config.RGB_565;
    private FrameLayout saverLayer;
    private ImageView saverSlide;
    private Bitmap saverBitmap;
    private int saverIndex = -1;
    /** Bumped on every show and hide, so a slide decoded for an earlier showing is dropped. */
    private int saverRun;
    private View saverWeatherRow;
    private WeatherIcon saverWeatherIcon;
    private TextView saverWeatherText;
    private Weather.Reading lastWeather;
    private final Runnable nextSlide = this::nextSlide;
    /** glow_strength / glow_size: the app-colour glow's alpha factor and size; 1 is the built-in glow. */
    private float glowFactor = 1f, glowScale = 1f;
    /** focus_grow / focus_grow_ms for bare_buttons' icons. */
    private float growScale = AppAdapter.FOCUS_SCALE;
    private int growMs = 180;
    private final Runnable fadeHome = () -> goIdle(false);
    /** wake_animation's warm-up, once per Home, a moment after it starts: the dock's tiles and panel are in place by then. */
    private static final long WARM_UP_MS = 2500;
    private boolean warmedUp;
    private final Runnable warmUpMotion = () -> {
        warmedUp = true;
        if (!faded) this.wakeMotion.warmUp(wakeFades());
    };
    /** idle_key asked while Home wasn't resumed: onResume does it, at once if Home was covered by an app. */
    private boolean idleOnResume, idleAtOnce;
    /** Home paused on the idle screen comes back at once, unless onNewIntent was on its way and wakes it. */
    private final Runnable unfadeAfterPause = () -> unfade(0);
    /** Home came back from behind an app: MemoryCare looks at the memory once it has settled. */
    private boolean memoryCareDue;
    private final Runnable memoryCare = () -> MemoryCare.start(this, () -> resumedHome);
    private String liveTvAction;
    private String liveTvInputId;
    /** The app library is on (config.xml, app_library): only then can the tile menu take an app off Home. */
    private boolean appLibraryEnabled;
    /** The same opt-in menu is available from either hold-OK or the remote's Menu key. */
    private boolean tileMenuEnabled;
    private String inputAction;
    private String inputUriScheme;
    private String sourcePanelService;
    private String sourcePanelAction;
    private String sourcePanelExtra;
    private boolean coocaaSources;

    /** watchlist: the lineup under the dock, and whether the page is scrolled down to it. */
    private WatchlistView watchlistView;
    private boolean inWatchlist;
    /** mood_deck: the deck under the watchlist, and whether the page is scrolled on down to it (inWatchlist stays true). */
    private MoodDeckView moodDeckView;
    /** watchlist_remove: the config the lineup and deck were made from, and the titles taken off on this TV. */
    private Config watchlistConfig;
    private final Set<String> watchlistHidden = new HashSet<>();
    private boolean inDeck;
    /** mood_posters: the row of a mood's posters, shown over the deck while inShelf. */
    private MoodShelfView moodShelfView;
    private boolean inShelf;
    private ValueAnimator pageScroll;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private WallpaperPlayer wallpaperPlayer;
    /** scene, big_clock, keep_watching and icon_dock (null or false when off). */
    private SceneLayers scene;
    private BigClock bigClock;
    private KeepWatchingView keepWatching;
    private final java.util.concurrent.ExecutorService keepWatchingReads = java.util.concurrent.Executors.newSingleThreadExecutor();
    private Map<String, String> keepWatchingLabels = Collections.emptyMap();
    private boolean iconDock;
    private DockGlass dockGlass;
    /** weather_scene (null when off), and Test weather: `am broadcast -a com.gathin.tvhome.WEATHER_TEST --es look rain --ei seconds 300`. */
    private WeatherScene weatherScene;
    /** wake_animation (null when off, and with animations or reduced_motion saying no). */
    private WakeMotion wakeMotion;
    /** fonts: the theme's font for labels (null keeps each label's own), and what the fonts key says. */
    private Typeface labelsFont;
    private ThemeFonts themeFonts;
    private final BroadcastReceiver weatherTest = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (weatherScene != null) weatherScene.test(intent.getStringExtra("look"), intent.getIntExtra("seconds", 300));
        }
    };
    private boolean topButtonsShown;
    /** More apps was opened from the dock's "..." tile, not the top bar. */
    private boolean moreFromDock;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    /** The top shelf: heroName, plus the cinematic shelf's "Last opened" line and dock position dots. */
    private View hero;
    private TextView heroMeta;
    private LinearLayout heroDots;
    /** Cinematic top shelf (config.xml, cinematic_home): the focused dock app's artwork behind a larger shelf. */
    private boolean cinematic;
    /** config.xml ambient_glow, overridable by a theme. Off: the glow view is gone and no fade or colour animator
     *  is started for it, so a focus move costs nothing extra on a launcher that runs all day. */
    private boolean ambientGlow;
    /** config.xml app_glow: the highlight takes the focused app's colour on every tile. */
    private boolean appGlow;
    /** config.xml glow_style: the shape setAmbient paints the glow mask in. */
    private String glowStyle;
    /** config.xml cinematic_white_focus: the cinematic shelf keeps the white highlight. */
    private boolean cinematicWhiteFocus;
    private ImageView heroArt;
    /** config.xml cinematic_dots: false leaves the dock position dots out of the cinematic shelf. */
    private boolean heroDotsShown = true;
    /** The artwork's opacity behind the cinematic shelf (config.xml cinematic_art); 0 leaves it out. */
    private float heroArtAlpha = 0.5f;
    /** Softened artwork per app (HeroArt, about 5 KB each), made the first time its dock tile is focused. */
    private final Map<String, Bitmap> heroArtCache = new HashMap<>();
    /** The app whose artwork heroArt shows, so focusing it again only fades it back in. */
    private String heroArtKey;
    /** The focus glow's colour while the cinematic shelf tints it, 0 for plain white glass. */
    private int glassAccent;
    private int ambientColor = -1;
    private ValueAnimator ambientAnimator;
    private boolean heroShown;
    private boolean animations;
    private boolean advancedEffects;
    private TextView greeting;
    private View clockBlock;
    private View btnInputs;
    /** config show_inputs_pill: false keeps the Inputs pill hidden even when the TV has inputs. */
    private boolean showInputsPill = true;
    private TextView btnWifi;
    /** The network pill's icon as last set, so a refresh that changes nothing does not lay the top bar out again. */
    private int wifiIcon;
    /** The pills have been filled in once; later resumes refresh them after the first frame. */
    private boolean pillsShown;
    private TextView shamsiDate;
    private TextView stats;
    private List<RaceWeekend.Race> raceCalendar;
    private View racePanel;
    private TextView raceKicker, raceHeadline, raceSessions;
    /** config.xml show_stats, overridable by a theme. Off: the line is gone and nothing is read. */
    private boolean showStats;
    private boolean shamsi;
    /** greeting_name: said after the greeting, which is shown in capitals. */
    private String greetingName = "";
    /** Optional time-of-day backdrop (config.xml, sky_intensity); 0 keeps it hidden. */
    private SkyView sky;
    private String vpnApp;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    // How far the grid has scrolled the glass. springY works without it, in grid content space, so a
    // scroll moves the glass in lockstep with its tile instead of becoming a moving spring target.
    private float glassScrollShift;
    private int glassCardW;   // width of the tile the glass was last fitted to
    private boolean glassShown;
    private final Rect tileRect = new Rect();
    private final Rect revealRect = new Rect();
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;
    /** Module cards (res/values/config.xml, module_cards): discovered on resume, shown only after the owner's Show. */
    private boolean moduleCards;
    /** config settings_key_keycodes / settings_key_panel, so the Settings button works on Home without HomeKeyService. */
    private int[] settingsKeycodes = new int[0];
    private boolean settingsKeyPanel;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): stiff spring, short fades, no replay. */
    private boolean reducedMotion;
    /** config.xml dock_row: the first row is a separate dock (band, parked low, top shelf) rather than plain grid. */
    private boolean dockRow;
    /** The highlight springs past its target (config.xml, focus_overshoot); false goes straight there. */
    private boolean focusOvershoot;
    /** Festive skin from the theme (see Festival); applied while it is active today. */
    private Festival festival = Festival.NONE;
    private boolean festive;
    private DiyaDrawable diya;
    private BellDrawable bell;
    private MarigoldDrawable marigold;
    private int greetingColor;
    /** The day the petal shower last played, so it greets the first Home of each day only. */
    private static LocalDate lastPetalShower;
    /** boot_to_live_tv: open the Live TV app on the first Home of each boot. */
    private boolean bootToLiveTv;
    /** hidden_apps as the adapter sees them (config.xml or a theme's config.json): a hidden app gets no card either. */
    private Set<String> hiddenApps = Collections.emptySet();
    private List<Cards.Card> cards = Collections.emptyList();
    /** The grid has been laid out and focused since the last resume; the card prompt waits for this. */
    private boolean tilesReady;
    private boolean settingsRecreationPending;
    private Dialog cardPrompt;
    /** Set when a decision could not be saved: no more prompts until the next resume, instead of asking in a loop. */
    private boolean promptsPaused;
    /** Between onStop and onStart: Home was fully covered by an app, not just by the translucent quick panel. */
    private boolean stopped;
    /** Between onStart and onStop: Home is (at least partly) on screen. */
    private boolean started;
    /** Rebuild the tiles on the way back to Home only when apps changed meanwhile (TileRefresh). */
    private final TileRefresh tileRefresh = new TileRefresh();
    /** PackageManager's change counter at the last rebuild, to catch a package broadcast that never arrived. */
    private int packageSequence;
    /** The Inputs or Module cards dropdown while one is open, so onPause can close it. */
    private PopupWindow openPopup;
    /** Reorder mode is on (top-bar button). */
    private boolean reordering;
    /** Key of the tile being carried in reorder mode, or null. */
    private String movingKey;
    /** Tile order when reorder mode started, to save only when something moved. */
    private List<String> orderAtStart;
    /**
     * fill_dock false: tiles in the dock when reorder mode started, plus those carried or swapped into it since
     * (#499). A grid app that only slid into a free slot, which reorder mode does not show, is not one of them.
     */
    private Set<String> dockInSession;
    /** fill_dock false: how many dock slots held an app when reorder mode started. */
    private int dockedAtStart;
    private View btnReorder;
    /** dock_apps as loaded (config.xml or a theme): a saved Order only applies while these are unchanged. */
    private List<String> dockApps;
    /** fill_dock false: the dock can have empty slots, which reorder mode leaves out and restores afterwards. */
    private boolean fillDockLeavesSlots;
    /** Edit-dock mode (Settings menu): a reorder session that swaps one chosen app into one dock place. */
    private boolean dockEditing;
    /** Dock place being changed in edit-dock mode, or -1 while the place is still being chosen. */
    private int dockEditSlot = -1;
    /** Tile menu's "Add to dock": the app waiting for a dock place in edit-dock mode, or null. */
    private String dockEditAppKey;
    private AppLibraryDialog appLibrary;
    private boolean appLibrarySelection;
    private boolean libraryFocusPending;
    private RecyclerView.OnScrollListener pendingFocusScroll;

    /** weather_city: the weather beside the clock, or null when the theme has no city. */
    private Weather weather;
    private View weatherBlock;
    private WeatherIcon weatherIcon;
    private TextView weatherText;
    /** dock_taglines by package; empty leaves the shelf to dock_title. */
    private Map<String, List<String>> dockTaglines = Collections.emptyMap();
    /** Returns to Home so far, kept across restarts: each one shows the next of an app's lines (Taglines). */
    private int taglineVisit;
    /** scroll_dim: the black laid over the background as the page scrolls, and its strength at full scroll. */
    private View scrollShade;
    private float scrollDimMax;
    /** glow_style dock: where the dock's middle sits, as a share of the screen height, for the glow behind it. */
    private float dockGlowY = 0.78f;
    /** glow_style tagline: where the line sits, as shares of the screen, for the glow behind it. */
    private float taglineGlowX = 0.2f, taglineGlowY = 0.62f;
    /** tagline_rotate focus: the dock tile whose landing already moved its line on, so a refit or refocus does not. */
    private View taglineCard;
    private boolean taglineOnFocus;
    private boolean homeReturnsToLast;
    /** strict_dock_nav: Up from the dock goes to the top-right buttons, Left and Right stop at the dock's ends. */
    private boolean strictDockNav;
    /** config dock_title: the dock app's name above the dock (dock_taglines may show a line there instead). */
    private boolean dockTitleShown;
    /** tagline_align center; otherwise the line starts where the dock's first logo does. */
    private boolean taglineCentered;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    /** Shows the Inputs pill once the TV registers an input: at boot HDMI inputs can appear after Home is shown. */
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            updateInputsButton();
        }

        @Override
        public void onInputRemoved(String inputId) {
            updateInputsButton();
        }
    };
    /** Listens for any VPN coming up or going down, even one this app's traffic is excluded from. */
    private final ConnectivityManager.NetworkCallback vpnCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final IntentFilter packageFilter = new IntentFilter();
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Updates send REMOVED followed by ADDED; keep the tile until the new APK is ready.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            // In the background this only marks the tiles stale; onResume rebuilds them once.
            if (!tileRefresh.onPackagesChanged()) return;
            reloadApps(false);
            if (appLibrary != null) appLibrary.refresh();
        }
    };
    // Right after boot the clock is often wrong until network time arrives; don't wait a minute to catch up.
    private final IntentFilter timeFilter = new IntentFilter(Intent.ACTION_TIME_CHANGED);
    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateClockVisibility();
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateRace();
            // The pill follows the network callbacks and each return to Home; only the diya's flame follows the
            // signal level, so only it needs this minute's binder calls (about 7 ms on the UI thread).
            if (festive) updateWifiLabel();
            sky.update();
            wallpaperPlayer.update();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        // config.xml, overlaid by config.json from a pushed theme (see Config and tools/theme.sh).
        Config cfg = Config.load(this);
        appLibrarySelection = cfg.appLibrarySelection;
        // restore_key_services: after a force stop, put back the remote's accessibility helpers that Android took
        // off the list (needs WRITE_SECURE_SETTINGS, granted by build-and-install with RESTORE_KEY_SERVICES=1).
        if (cfg.restoreKeyServices) ServiceRepair.restore(this);
        settingsRevision = settingsRevision();
        applyViewInputsHandler(cfg.handleViewInputs);
        settingsKeycodes = cfg.settingsKeycodes;
        settingsKeyPanel = cfg.settingsKeyPanel;
        // icon_dock: the dock's own Settings and More apps come after the apps "columns" asks for.
        COLUMNS = cfg.columns + (cfg.iconDock ? AppAdapter.DOCK_ACTIONS : 0);
        iconDock = cfg.iconDock;
        animations = cfg.animations;
        advancedEffects = cfg.animations && cfg.advancedEffects;
        reducedMotion = cfg.reducedMotion;
        dockRow = cfg.dockRow;
        focusOvershoot = cfg.focusOvershoot;
        festival = cfg.festival;
        festive = festival.active(LocalDate.now());
        liveTvPkg = cfg.liveTvPackage;
        idleClockMs = cfg.homeIdleClockMinutes * MINUTE_MS;
        idleFadeMs = cfg.idleFadeMinutes * MINUTE_MS;
        saverMode = cfg.screensaver;
        saverDim = cfg.screensaverDim;
        saverFadeMs = cfg.screensaverFadeMs;
        saverSlideMs = cfg.screensaverSlideMinutes * MINUTE_MS;
        saverImages = cfg.screensaverImages;
        saverBitmapConfig = cfg.wallpaperTrueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        glowFactor = Screensaver.glowFactor(cfg.glowStrength);
        glowScale = cfg.glowSize / 100f;
        growScale = 1f + cfg.focusGrow / 100f;
        growMs = cfg.focusGrowMs;
        bootToLiveTv = cfg.bootToLiveTv;
        liveTvAction = cfg.liveTvAction;
        liveTvInputId = cfg.liveTvInputId;
        appLibraryEnabled = cfg.appLibrary;
        tileMenuEnabled = cfg.tileMenu;
        inputAction = cfg.inputAction;
        inputUriScheme = cfg.inputUriScheme;
        sourcePanelService = cfg.sourcePanelService;
        sourcePanelAction = cfg.sourcePanelAction;
        sourcePanelExtra = cfg.sourcePanelExtra;
        coocaaSources = cfg.coocaaSources;

        root = findViewById(R.id.root);
        root.setBackgroundColor(cfg.backgroundColor);
        // root fills the window with an opaque colour (Config keeps background_color opaque), so the window's own
        // black under it is a second full-screen fill on every frame for nothing. The starting window still uses
        // the theme's background, so launch looks the same.
        getWindow().setBackgroundDrawable(null);
        // wallpaper_true_color: a 32-bit window too, so a TV that picks 16-bit for opaque windows does not band the
        // picture again on the way to the screen.
        if (cfg.wallpaperTrueColor) getWindow().setFormat(PixelFormat.RGBA_8888);
        wallpaperPlayer = new WallpaperPlayer(this, root, cfg);
        sky = findViewById(R.id.sky);
        sky.setIntensity(cfg.skyIntensity);
        themeFonts = cfg.fonts;
        labelsFont = themeFonts.get(this, ThemeFonts.LABELS, null);
        if (cfg.bigClock) {
            bigClock = new BigClock(this, cfg.bigClockSize, cfg.bigClockTop, cfg.bigClockOpacity,
                    themeFonts.get(this, ThemeFonts.LINE, UiFont.get(this, R.string.font_inter_regular)),
                    themeFonts.get(this, ThemeFonts.CLOCK, UiFont.get(this, R.string.font_clock)), themeFonts.has(this, ThemeFonts.CLOCK),
                    cfg.bigClockGlass, cfg.glassShine);
        }
        if (!cfg.scene.isEmpty()) {
            scene = new SceneLayers(this, root, root.indexOfChild(sky), bigClock == null ? null : bigClock.view, cfg.scene);
        } else if (bigClock != null) {
            root.addView(bigClock.view, root.indexOfChild(sky));
        }
        glass = findViewById(R.id.glass);
        if (festive) glass.setAccent(festival.accentColor());
        // plain_focus: the growing tile is the only sign of focus, so the highlight is never drawn.
        plainFocus = cfg.plainFocus;
        if (plainFocus) glass.setVisibility(View.GONE);
        ambient = findViewById(R.id.ambient);
        // The glow is one bitmap, so its alpha can be applied while drawing it. Otherwise every frame of
        // a fade renders it into a full-screen offscreen buffer first.
        ambient.forceHasOverlappingRendering(false);
        ambientGlow = cfg.ambientGlow;
        appGlow = cfg.appGlow;
        if (!ambientGlow) ambient.setVisibility(View.GONE);
        // Under the glow, over the wallpaper: the bottom gradient, then the scroll shade. Neither exists when off.
        if (cfg.bottomShade > 0) addBottomShade(cfg.bottomShade);
        scrollDimMax = cfg.scrollDim / 100f;
        if (scrollDimMax > 0f) {
            scrollShade = new View(this);
            scrollShade.setBackgroundColor(Color.BLACK);
            scrollShade.setAlpha(0f);
            scrollShade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            root.addView(scrollShade, root.indexOfChild(ambient), new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        homeReturnsToLast = cfg.homeReturnsToLast;
        strictDockNav = cfg.strictDockNav;
        if (cfg.watchlist) setUpWatchlist(cfg);
        heroName = findViewById(R.id.heroName);
        hero = findViewById(R.id.hero);
        // The hero is now a group (name, and the cinematic lines): without this every fade of the default
        // shelf would render it through an offscreen buffer first, as the glow above notes.
        hero.forceHasOverlappingRendering(false);
        heroMeta = findViewById(R.id.heroMeta);
        heroDots = findViewById(R.id.heroDots);
        heroArt = findViewById(R.id.heroArt);
        cinematic = cfg.cinematicHome;
        glowStyle = cfg.glowStyle;
        heroDotsShown = cfg.cinematicDots;
        cinematicWhiteFocus = cfg.cinematicWhiteFocus;
        heroArtAlpha = cfg.cinematicArt / 100f;
        if (cinematic) setUpCinematicShelf(cfg.cinematicScrim);
        // dock_title false: the name above the dock (and the cinematic lines under it) never shows; the glow
        // still follows the dock.
        dockTaglines = cfg.dockTaglines;
        dockTitleShown = cfg.dockTitle;
        taglineOnFocus = cfg.taglineOnFocus;
        taglineVisit = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).getInt("tagline_visit", 0);
        if (!cfg.dockTitle && dockTaglines.isEmpty()) hero.setVisibility(View.GONE);
        taglineCentered = cfg.taglineCentered;
        if (!dockTaglines.isEmpty()) {
            // A line, not a name: smaller, centred over the dock's floating row, or (tagline_align left) starting
            // where its first logo does, which alignTagline works out once the dock is laid out.
            if (taglineCentered) {
                FrameLayout.LayoutParams hlp = (FrameLayout.LayoutParams) hero.getLayoutParams();
                hlp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                hlp.setMarginStart(0);
                hero.setLayoutParams(hlp);
                ((LinearLayout) hero).setGravity(Gravity.CENTER_HORIZONTAL);
            }
            heroName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f);
            heroName.setLetterSpacing(0f);
        }
        greeting = findViewById(R.id.greeting);
        clockBlock = findViewById(R.id.leftBlock);
        greetingColor = greeting.getCurrentTextColor();
        if (getResources().getBoolean(R.bool.rtl_menu_bar)) {
            // Center Hebrew greetings over the numeric time, independent of a visible AM/PM label.
            findViewById(R.id.clock).addOnLayoutChangeListener((v, l, t, right, bottom, ol, ot, or, ob) -> {
                ViewGroup.LayoutParams params = greeting.getLayoutParams();
                if (params.width != right - l) {
                    params.width = right - l;
                    greeting.setLayoutParams(params);
                }
                greeting.setTranslationX(l);
            });
        }
        btnWifi = findViewById(R.id.btnWifi);
        shamsiDate = findViewById(R.id.shamsiDate);
        stats = findViewById(R.id.stats);
        raceCalendar = cfg.raceCalendar;
        racePanel = findViewById(R.id.racePanel);
        raceKicker = findViewById(R.id.raceKicker);
        raceHeadline = findViewById(R.id.raceHeadline);
        raceSessions = findViewById(R.id.raceSessions);
        showStats = cfg.showStats;
        shamsi = cfg.shamsiDate;
        greetingName = cfg.greetingName;
        if (shamsi) {
            shamsiDate.setVisibility(View.VISIBLE);
            TextView date = findViewById(R.id.date);
            date.setTextSize(11f);
            date.setPadding(0, (int) (4 * density), 0, 0);
        }
        if (cfg.clockOnly) {
            // The time alone: the greeting, then the date block and the thin divider in front of it.
            greeting.setVisibility(View.GONE);
            View dateBlock = (View) findViewById(R.id.date).getParent();
            ViewGroup clockRow = (ViewGroup) dateBlock.getParent();
            clockRow.getChildAt(clockRow.indexOfChild(dateBlock) - 1).setVisibility(View.GONE);
            dateBlock.setVisibility(View.GONE);
        }
        if (!cfg.weatherCity.isEmpty()) {
            if (bigClock != null) addBigClockWeather(cfg);
            else addWeather(cfg.weatherCity);
        }
        vpnApp = cfg.vpnApp;
        moduleCards = cfg.moduleCards;
        hiddenApps = new HashSet<>(cfg.hiddenApps);

        grid = findViewById(R.id.grid);
        rtlAppGrid = getResources().getBoolean(R.bool.rtl_app_grid);
        // Mirror only the app grid; the header and other locales keep their own layouts.
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS) {
            @Override
            protected boolean isLayoutRTL() {
                return rtlAppGrid;
            }

            /**
             * Every "bring this tile on screen" scroll lands here, both RecyclerView's own on focus and
             * onTileFocused's. The stock version treats the grid's top padding as the visible top, but that
             * padding only pushes the dock down the screen: grid rows scroll up into it. With the stock rule,
             * Up into a row above the padding dragged that row down to the padding line, cut it off at the
             * bottom of the screen and could scroll the focused tile out entirely, dropping focus into the
             * top bar. Grid tiles now scroll just enough to show the rect between the grid's top edge and its
             * bottom; dock tiles are left to onTileFocused, which parks the dock row on the padding line.
             */
            @Override
            public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                         @NonNull Rect rect, boolean immediate,
                                                         boolean focusedChildVisible) {
                if (dockRow && getPosition(child) < COLUMNS) return false;
                int top = child.getTop() + rect.top;
                int bottom = top + rect.height();
                // A row already fully on screen stays put, even inside the bottom padding (the last row
                // rows_below_dock leaves visible ends there; nudging it up was the bug). A row that has to scroll
                // in still stops at the padding line, clear of the screen edge, as before.
                int limitBottom = getHeight() - getPaddingBottom();
                int dy;
                if (top < 0) dy = top;
                else if (bottom <= getHeight()) dy = 0;
                else dy = Math.min(top, bottom - limitBottom);
                if (dy == 0) return false;
                if (immediate || reducedMotion || !animations) parent.scrollBy(0, dy);
                else parent.smoothScrollBy(0, dy);
                return true;
            }
        });
        ((GridLayoutManager) grid.getLayoutManager()).setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) { return adapter != null && adapter.isLibraryAt(position) ? COLUMNS : 1; }
        });
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        // Tiles that scroll out while one is carried come back from the view cache without a rebind; set their
        // dimming again whenever one attaches, so none stays dim after the drop.
        grid.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(@NonNull View v) {
                v.setAlpha(tileAlpha(grid.getChildAdapterPosition(v)));
            }

            @Override
            public void onChildViewDetachedFromWindow(@NonNull View v) {
            }
        });
        if (dockRow && !cfg.plainFocus) grid.addItemDecoration(new DockDecoration(COLUMNS, density, cinematic, cfg.alwaysShowNames, cfg.dockOpacity, cfg.dockGlass, cfg.dockEvenPadding));
        if (cfg.fadeRows) TopFade.attach(grid, 48 * density);
        // Reduced motion: the tiles are simply there, with no entrance (reloadApps skips it there too).
        if (animations && !reducedMotion) {
            grid.setLayoutAnimation(new LayoutAnimationController(
                    AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        }
        if (cfg.iconDock) {
            // The row is as wide as its icons and their gaps: each cell is one icon's pitch. Set before the first
            // layout: a grid of fixed size doesn't lay its tiles out again for a change of padding.
            int icon = Math.round(cfg.iconDockSize * density);
            int pitch = Math.round(icon * (1f + AppAdapter.ICON_GAP));
            int width = getResources().getDisplayMetrics().widthPixels - ((View) grid.getParent().getParent()).getPaddingLeft() * 2;
            int side = cfg.glassIntensity > 0 ? Math.round(DockGlass.SIDE_DP * density) : 0;
            int start = DockGlass.rowStart(width, pitch * COLUMNS - (pitch - icon), side, cfg.dockCentered);
            grid.setPadding(start, grid.getPaddingTop(), Math.max(0, width - start - pitch * COLUMNS), grid.getPaddingBottom());
            if (cfg.glassIntensity > 0) {
                dockGlass = new DockGlass(this, (ViewGroup) grid.getParent(), root, cfg.glassStrip, cfg.glassRim, cfg.glassIntensity, cfg.dockTransparency, cfg.glassShine, cfg.glassSweep);
            }
            // The first icon grows and glows past the row's left edge.
            ((ViewGroup) grid.getParent()).setClipChildren(false);
        }
        adapter = new AppAdapter(this, this, COLUMNS, cfg);
        dockApps = cfg.dockApps;
        fillDockLeavesSlots = !cfg.fillDock;
        Order savedOrder = Order.load(Order.file(this));
        adapter.setSavedOrder(Order.ranks(savedOrder, dockApps), Order.docked(savedOrder, dockApps));
        grid.setAdapter(adapter);
        if (cfg.moreApps) setUpMoreApps(cfg);
        if (cfg.keepWatching) setUpKeepWatching(cfg);
        if (!themeFonts.isEmpty()) useThemeFonts();
        if (cfg.wakeAnimation && animations && !reducedMotion) setUpWakeMotion(cfg);

        // tvOS layout: push the dock row towards the bottom of the screen, leaving room for
        // rows_below_dock grid rows under it; the rest of the grid stays below until you press Down.
        int rowsBelowDock = cfg.rowsBelowDock;
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            // Item height follows the tile size, which follows the columns and the rows that must fit (AppAdapter.tileWidthPx).
            int inner = (r - l) - grid.getPaddingLeft() - grid.getPaddingRight();
            int tileW = AppAdapter.tileWidthPx(inner, h, COLUMNS, rowsBelowDock, cfg.dockTitle, cfg.dockRow, density);
            int iconPx = Math.round(cfg.iconDockSize * density);
            int itemH = AppAdapter.itemHeightPx(cfg.iconDock ? iconPx : AppAdapter.tileHeightPx(tileW), density);
            int dockRow = itemH + (int) (AppAdapter.DOCK_EXTRA_DP * density);   // item height plus DockDecoration offsets
            int gridRows = itemH * rowsBelowDock;
            // Keep the hero below the header when the library is enabled on a short screen,
            // even when the theme requests an extra visible row below the dock.
            int shelfClearance = cfg.appLibrary ? hero.getMeasuredHeight() + (int) (40 * density) : 0;
            // dock_bottom_room: the theme says how much room stays under the dock, which sets how low it sits.
            int bottomRoom = (int) ((cfg.dockBottomRoom >= 0 ? cfg.dockBottomRoom : AppAdapter.BOTTOM_ROOM_DP) * density);
            int want = Math.max(shelfClearance, h - dockRow - gridRows - bottomRoom);
            // Where the rows cannot fit even at the smallest tile (3 rows with the title on), the title keeps its
            // room above the dock and the last rows run off the bottom instead of the title drawing over the clock.
            if (MainActivity.this.dockRow && cfg.dockTitle && rowsBelowDock > 0) want = Math.max(want, (int) (AppAdapter.TITLE_ROOM_DP * density));
            // more_apps: the dock rises by the button's row (row_app_library, 84dp) so the button fits under it.
            // Not when the button is in the top bar (library_button), or the theme placed the dock itself.
            if (cfg.moreApps && !cfg.libraryButton && cfg.dockBottomRoom < 0) want -= (int) (84 * density);
            // No dock to push down: the grid starts at the top, with room for the first row's zoom and glass.
            if (!MainActivity.this.dockRow) want = (int) (AppAdapter.TOP_ROOM_DP * density);
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
            if (dockGlass != null) {
                int icons = COLUMNS * Math.round(iconPx * (1f + AppAdapter.ICON_GAP)) - Math.round(iconPx * AppAdapter.ICON_GAP);
                dockGlass.place(grid.getPaddingLeft(), want + grid.getTop() + Math.round(8 * density), icons, iconPx, iconPx * AppAdapter.ICON_RADIUS);
            }
            if (keepWatching != null) placeKeepWatching(want);
            // Cold start or column count > 4: the first focus can run before the grid has its final width or
            // padding, leaving the glass at the XML design size until a key is pressed.
            grid.post(this::refitGlass);
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (scrollShade != null) updateScrollShade(rv);
                if (!glassShown) return;
                glassTargetY -= dy;
                glassScrollShift -= dy;
                glass.setTranslationY(glass.getTranslationY() - dy);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, new FloatPropertyCompat<View>("glassContentY") {
            @Override
            public float getValue(View v) {
                return v.getTranslationY() - glassScrollShift;
            }

            @Override
            public void setValue(View v, float value) {
                v.setTranslationY(value + glassScrollShift);
            }
        });
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            if (reducedMotion) {
                Motion.reduce(s);
            } else if (!focusOvershoot) {
                // Critically damped: glides straight onto the tile without swinging past it.
                s.setSpring(new SpringForce().setStiffness(advancedEffects ? 520f : 440f).setDampingRatio(1f));
            } else {
                s.setSpring(new SpringForce().setStiffness(advancedEffects ? 320f : 260f)
                        .setDampingRatio(advancedEffects ? 0.76f : 0.58f));
            }
        }

        btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> openNetworkPill());
        btnWifi.setOnLongClickListener(v -> {
            open(systemIntent(Settings.ACTION_WIFI_SETTINGS));
            return true;
        });
        launcherSettingsButton = cfg.launcherSettingsButton;
        if (launcherSettingsButton) {
            ((TextView) btnSettings).setText(R.string.launcher_settings);
            ((TextView) btnSettings).setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_home_settings_pill, 0, 0, 0);
        }
        btnSettings.setOnClickListener(v -> {
            if (launcherSettingsButton) startActivityForResult(
                    new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST);
            else open(systemIntent(Settings.ACTION_SETTINGS));
        });
        btnReorder = findViewById(R.id.btnReorder);
        btnReorder.setOnClickListener(v -> toggleReorder());
        btnReorder.setVisibility(cfg.reorderButton ? View.VISIBLE : View.GONE);
        if (!cfg.showNetworkPill) btnWifi.setVisibility(View.GONE);
        showInputsPill = cfg.showInputsPill;
        btnLibraryTop = findViewById(R.id.btnLibraryTop);
        btnLibraryTop.setOnClickListener(v -> {
            onLibraryRequested();
            libraryFromTopBar = true;   // closing it goes back to this button, not to the pill after the last row
        });
        btnLibraryTop.setVisibility(cfg.libraryButton && (cfg.appLibrary || cfg.moreApps) ? View.VISIBLE : View.GONE);
        if (cfg.libraryButton && cfg.moreApps) {
            // more_apps: the top-bar button opens the More apps grid, as an outline grid like the other icons.
            ((TextView) btnLibraryTop).setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_more_apps, 0, 0, 0);
            btnLibraryTop.setContentDescription(getString(R.string.more_apps));
        }
        iconOnlyButtons = cfg.iconOnlyButtons;
        topMenu = cfg.topMenu;
        wifiIconOnly = iconOnlyButtons && cfg.wifiLabelWidth <= 0;
        if (cfg.wifiStatusColor) {
            wifiPlainTint = btnWifi.getCompoundDrawableTintList();
            wifiOnlineTint = getColorStateList(R.color.pill_icon_online);
        }
        if (cfg.topMenu) {
            // One gear: Inputs and Wi-Fi move into its dropdown. The Wi-Fi pill stays hidden but keeps its
            // label up to date, which the menu's Wi-Fi row shows.
            showInputsPill = false;
            btnInputs.setVisibility(View.GONE);
            btnWifi.setVisibility(View.GONE);
            ((TextView) btnSettings).setText(R.string.settings);
            ((TextView) btnSettings).setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_settings, 0, 0, 0);
            if (!iconOnlyButtons) makeIconOnly((TextView) btnSettings);
            btnSettings.setOnClickListener(v -> showTopMenu());
            // The lone gear sits at the header's middle, not on its bottom line like the row of pills.
            LinearLayout topButtons = findViewById(R.id.topButtons);
            RelativeLayout.LayoutParams tlp = (RelativeLayout.LayoutParams) topButtons.getLayoutParams();
            tlp.addRule(RelativeLayout.ALIGN_TOP, R.id.leftBlock);
            tlp.bottomMargin = 0;
            topButtons.setLayoutParams(tlp);
            topButtons.setGravity(Gravity.CENTER_VERTICAL);
        }
        if (iconOnlyButtons) {
            if (cfg.wifiLabelWidth > 0) btnWifi.setMaxWidth(Math.round(cfg.wifiLabelWidth * density));
            else makeIconOnly(btnWifi);
            makeIconOnly((TextView) btnSettings);
        }
        if (cfg.iconOnlyInputs) makeIconOnly((TextView) btnInputs);
        topButtonsShown = cfg.topButtons;
        if (!topButtonsShown) findViewById(R.id.topButtons).setVisibility(View.GONE);
        btnSettings.setOnLongClickListener(v -> {
            showSettingsMenu();
            return true;
        });

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        boolean rtlMenuBar = getResources().getBoolean(R.bool.rtl_menu_bar);
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Back to the dock tile Up came from, not wherever the focus finder lands.
                if (keepWatchingShown()) keepWatching.requestFocus();
                else focusTile(lastFocused % COLUMNS);
                return true;
            }
            int firstButton = firstShownTopButton().getId();
            int leftButton = rtlMenuBar ? R.id.btnSettings : firstButton;
            int rightButton = rtlMenuBar ? firstButton : R.id.btnSettings;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == leftButton;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == rightButton;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);
        btnReorder.setOnKeyListener(pillKeys);
        btnLibraryTop.setOnKeyListener(pillKeys);
        if (advancedEffects) {
            for (View button : new View[]{btnInputs, btnWifi, btnReorder, btnLibraryTop, btnSettings}) {
                button.setOnFocusChangeListener((view, focused) -> view.animate()
                        .scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f)
                        .setDuration(reducedMotion ? Motion.REDUCED_MS : 160).start());
            }
        }
        if (festive) applyFestiveSkin((TextView) btnInputs, (TextView) btnSettings);
        if (cfg.bareButtons) {
            // Wi-Fi first, then Inputs and Settings: white icons with nothing behind them. The pill's focused colours
            // would turn the icon dark on the black screen, so the tint stays white and focus only grows the icon.
            wifiIconOnly = true;
            LinearLayout topButtons = findViewById(R.id.topButtons);
            topButtons.removeView(btnWifi);
            topButtons.addView(btnWifi, 0);
            ColorStateList white = ColorStateList.valueOf(Color.WHITE);
            if (wifiOnlineTint != null) wifiPlainTint = white;
            for (TextView button : new TextView[]{btnWifi, (TextView) btnInputs, (TextView) btnLibraryTop, (TextView) btnSettings}) {
                makeIconOnly(button);
                button.setBackground(null);
                button.setCompoundDrawableTintList(white);
                button.setOnFocusChangeListener(this::grow);
            }
        }

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (newF != taglineCard) taglineCard = null;   // the next landing on a dock app is a new one
            if (!onTile) hideGlass();
        });

        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");
        timeFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        // Registered for the activity's whole life, not only while it is started: an app installed or updated while
        // another app is in front must still mark the tiles stale, or Home would have to rebuild on every return.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, packageFilter);
        }

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.registerCallback(inputCallback, handler);
        if (cm != null) {
            cm.registerDefaultNetworkCallback(networkCallback);
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), vpnCallback);
        }
        // Not after recreate(): the launch intent survives it and would open the settings again.
        if (savedInstanceState == null && !openLauncherSettingsIfAsked(getIntent()) && !openInputsIfAsked(getIntent())) idleIfAsked(getIntent());
    }

    /**
     * handle_view_inputs: switch InputsRequestActivity, the VIEW_INPUTS handler, on or off. It is disabled in the
     * manifest, so a TV with its own input picker never sees it; only the sets that need it turn it on. Written
     * only when the state differs, so an ordinary start does not touch the package manager.
     */
    private void applyViewInputsHandler(boolean on) {
        ComponentName handler = new ComponentName(this, InputsRequestActivity.class);
        PackageManager pm = getPackageManager();
        int want = on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        if (pm.getComponentEnabledSetting(handler) != want) {
            pm.setComponentEnabledSetting(handler, want, PackageManager.DONT_KILL_APP);
        }
    }

    /**
     * VIEW_INPUTS, forwarded by InputsRequestActivity while handle_view_inputs is on: open the Inputs menu once
     * the pill is on screen. The intent is replaced so a later recreate() does not
     * open it again. Nothing to do when the TV has no inputs to list (the pill is hidden then).
     * top_menu hides the pill, so the list hangs under the gear there, as in showInputs().
     */
    private boolean openInputsIfAsked(Intent intent) {
        if (intent == null || !ACTION_VIEW_INPUTS.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        View anchor = topMenu ? findViewById(R.id.btnSettings) : btnInputs;
        if (anchor.getVisibility() != View.VISIBLE) return true;
        if (anchor.isLaidOut()) {
            anchor.requestFocus();
            showInputs();
            return true;
        }
        // Cold start: the popup anchors to the pill (or the gear), so wait for the first layout.
        anchor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                anchor.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                anchor.post(() -> {
                    if (isFinishing() || anchor.getVisibility() != View.VISIBLE) return;
                    anchor.requestFocus();
                    showInputs();
                });
            }
        });
        return true;
    }

    /** idle_key: the idle screen now; asked again on it, Home comes back, as any key does. */
    private boolean idleIfAsked(Intent intent) {
        if (intent == null || !ACTION_IDLE_NOW.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        if (faded) {
            unfade(250);
            restartIdleClock();
        } else if (!resumedHome) {
            idleOnResume = true;
            idleAtOnce = stopped || !root.isLaidOut();
        } else {
            goIdleWhenLaidOut(false);
        }
        return true;
    }

    private void goIdleWhenLaidOut(boolean atOnce) {
        root.removeCallbacks(fadeHome);
        if (root.isLaidOut()) {
            goIdle(atOnce);
            return;
        }
        // A cold start: wakeDistance needs the layout, and the first frame should already be the idle screen.
        root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                if (resumedHome && !faded) goIdle(atOnce);
            }
        });
    }

    private boolean openLauncherSettingsIfAsked(Intent intent) {
        if (intent == null || !ACTION_LAUNCHER_SETTINGS.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        startActivityForResult(new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST);
        return true;
    }

    /**
     * Home pressed while Home is already in front: singleTask delivers it here, then onResume runs.
     * Back to the top with the first dock tile focused, like the stock launcher. Back from an app
     * never comes this way, so returning to the app you left from is unchanged (#118).
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (openLauncherSettingsIfAsked(intent)) return;
        if (openInputsIfAsked(intent)) return;
        if (idleIfAsked(intent)) return;
        if (intent == null || !intent.hasCategory(Intent.CATEGORY_HOME)) return;
        if (faded) {
            // On the idle screen Home is any other key: it brings Home back, from where the dock stands, and the focus stays.
            unfade(250);
            restartIdleClock();
            return;
        }
        restartIdleClock();
        if (inWatchlist) {
            // Home always comes back to the dock: scrolling up on the lineup, at once on the way back from an app.
            exitWatchlist(!stopped);
            if (!stopped) return;
        }
        if (homeReturnsToLast) {
            // Back to what was used last: from an app, the tile it was opened from (in the More apps grid too, which
            // stays open); on Home already, the dock app focused last.
            if (stopped) {
                tileRefresh.onHomeAgain();
                return;
            }
            closeMoreApps(false);
            grid.post(() -> focusTile(lastFocused));
            return;
        }
        closeMoreApps(false);
        grid.scrollToPosition(0);
        lastFocused = 0;
        lastFocusedKey = adapter.getItemCount() > 0 ? adapter.keyAt(0) : null;
        if (stopped) {
            tileRefresh.onHomeAgain();
        } else {
            // Still on screen (the quick panel was over it, or Home was already in front): the tiles are current,
            // so only go back to the first one; rebuilding would replay the entrance animation for nothing.
            grid.post(() -> focusTile(0));
        }
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        memoryCareDue = true;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!dockTaglines.isEmpty()) {
            // Each time Home comes back in front, the next line. Saved, so a restart does not start over.
            taglineVisit++;
            getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).edit().putInt("tagline_visit", taglineVisit).apply();
            heroName.setText("");   // the shelf fills in again with this visit's line
        }
        stopped = false;
        started = true;
        if (inWatchlist) watchlistView.load();   // after onTrimMemory let its pictures go
        if (inShelf) moodShelfView.load();
        else if (inDeck) moodDeckView.load();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(timeReceiver, timeFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(timeReceiver, timeFilter);
        }
        updateClockVisibility();
        tileRefresh.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A festival that started or ended while Home was in the background: rebuild with or without it.
        if (festive != festival.active(LocalDate.now())) {
            recreate();
            return;
        }
        // Something changed in Launcher settings: rebuild with it, however the settings were left. Only Back
        // delivered their RESULT_OK; Home (the remote's button) clears them off this task and the result comes
        // back cancelled, so a new wallpaper or layout waited for the next process start.
        if (Config.settingsChanged(settingsRevision, settingsRevision())) {
            recreate();
            return;
        }
        restartIdleClock();
        resumedHome = true;
        inFront = true;
        updateSweep();
        if (wakeMotion != null && !warmedUp) root.postDelayed(warmUpMotion, WARM_UP_MS);
        promptsPaused = false;
        if (keepWatching != null) refreshKeepWatching();
        if (grid.getAdapter() == null) grid.setAdapter(adapter);   // dropped by onTrimMemory
        switch (tileRefresh.onResume(packagesChangedSinceRebuild())) {
            case REBUILD:
                tilesReady = false;
                reloadApps(true);
                break;
            case REFOCUS:
                // The same tiles: only the focus goes back, as after a rebuild, without replaying the entrance.
                tilesReady = false;
                reloadApps(true, false, false);
                break;
            default:
                break;
        }
        if (openLiveTvAfterBoot()) return;
        if (idleOnResume) {
            idleOnResume = false;
            goIdleWhenLaidOut(idleAtOnce);
        }
        if (memoryCareDue) {
            memoryCareDue = false;
            root.postDelayed(memoryCare, MemoryCare.DELAY_MS);
        }
        updateGreeting();
        updateRace();
        // The network and Inputs pills keep their state while Home is in the background, and callbacks update them
        // when the network or the inputs change, so this refresh only catches anything missed. Its binder calls
        // (about 7 ms on a TCL C755, most of it WifiManager.getConnectionInfo) would hold up the first frame back
        // on Home, so after the first resume it runs once that frame is drawn.
        if (pillsShown) {
            refreshPillsAfterNextDraw();
        } else {
            updateWifiLabel();
            updateInputsButton();
            pillsShown = true;
        }
        if (showStats) startStats();
        sky.update();
        wallpaperPlayer.start();
        if (weather != null) weather.resume();
        handler.postDelayed(minuteTick, MINUTE_MS);
        if (festive && diya != null) {
            diya.start();
            LocalDate today = LocalDate.now();
            if (!reducedMotion && !today.equals(lastPetalShower)) {
                lastPetalShower = today;
                handler.postDelayed(this::playPetalShower, 450);
            }
        }
    }

    /** Launcher settings' change count (Config.SETTINGS_REVISION_KEY); onResume rebuilds Home when it moved. */
    private long settingsRevision() {
        return getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).getLong(Config.SETTINGS_REVISION_KEY, 0);
    }

    /**
     * Only "Reorder tiles" in Launcher settings still answers with a result: Home rebuilds (the saved choices are
     * reloaded) and then enters reorder mode. Every other change is picked up in onResume through the change count.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == LAUNCHER_SETTINGS_REQUEST && resultCode == RESULT_OK
                && data != null && data.getBooleanExtra(LauncherSettingsActivity.EXTRA_REORDER, false)) {
            getIntent().putExtra(LauncherSettingsActivity.EXTRA_REORDER, true);
            // The old activity must not consume the pending action, or rebuild a second time in onResume.
            settingsRecreationPending = true;
            settingsRevision = settingsRevision();
            recreate();
        }
    }

    private void startPendingSettingsReorder() {
        if (settingsRecreationPending || !tilesReady || !hasWindowFocus() || isFinishing()
                || !getIntent().getBooleanExtra(LauncherSettingsActivity.EXTRA_REORDER, false)) return;
        getIntent().removeExtra(LauncherSettingsActivity.EXTRA_REORDER);
        toggleReorder();
    }

    /** Rebuilds the tiles (and the module cards), then puts focus back where it was. */
    private void reloadApps(boolean animate) {
        reloadApps(animate, true);
    }

    /** @param rebuild false keeps the tiles as they are and only puts focus back (TileRefresh.Action.REFOCUS). */
    private void reloadApps(boolean animate, boolean rebuild) {
        reloadApps(animate, rebuild, true);
    }

    /**
     * @param entrance false puts focus back as an animated reload would, without replaying the tiles' entrance:
     *                 on a return to the same tiles it would only mark a change that didn't happen.
     */
    private void reloadApps(boolean animate, boolean rebuild, boolean entrance) {
        cancelPendingFocusScroll();
        RecyclerView.ViewHolder footer = grid.findViewHolderForAdapterPosition(adapter.appCount());
        final boolean restoreLibrary = adapter.isLibraryAt(adapter.appCount()) && footer != null
                && footer.itemView.findViewById(R.id.btnLibrary) != null
                && footer.itemView.findViewById(R.id.btnLibrary).hasFocus();
        // Capture the app before rebinding can move focus and overwrite its key.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final boolean restoreFocus = appLibrary == null && (animate || grid.hasFocus());
        if (restoreFocus) {
            glassShown = false;
            glass.setAlpha(0f);
            heroShown = false;
            hero.setAlpha(0f);
            ambient.setAlpha(0f);
            heroArt.animate().cancel();
            heroArt.setAlpha(0f);
        }
        if (rebuild) reloadTiles();
        if (animate && entrance && animations && !reducedMotion) {
            grid.scheduleLayoutAnimation();
            // A rebuild redraws the grid anyway; without one the entrance needs a draw to start.
            if (!rebuild) grid.invalidate();
        }

        if (restoreFocus) {
            // Keep a focused footer at the end even when the app count changes.
            if (restoreLibrary) grid.post(this::focusLibrary);
            else restoreFocus();
        } else {
            // A package change must not steal focus from a top button or the Inputs popup.
            int pos = adapter.positionOf(returnKey);
            lastFocused = Math.max(0, Math.min(pos >= 0 ? pos : returnSlot, adapter.appCount() - 1));
            lastFocusedKey = adapter.keyAt(lastFocused);
            if (pos < 0) hideHero();
            // A newly installed app may have brought a card with it.
            tilesReady = true;
            startPendingSettingsReorder();
            showCardPrompt();
        }
    }

    /** True when PackageManager reports apps changed since the last rebuild (one binder call). */
    private boolean packagesChangedSinceRebuild() {
        ChangedPackages changed = getPackageManager().getChangedPackages(packageSequence);
        return changed != null && !changed.getPackageNames().isEmpty();
    }

    private int currentPackageSequence(int since) {
        ChangedPackages changed = getPackageManager().getChangedPackages(since);
        return changed != null ? changed.getSequenceNumber() : since;
    }

    /**
     * Rebuilds the tiles; the module cards the owner chose to show stand in for their app's tile.
     * Every step into the card code runs under CardFeature: if it fails, the tiles are the ordinary
     * ones and cards stay off for this process.
     */
    private void reloadTiles() {
        if (reordering) endReorder();   // an app installed or removed while reordering: keep the order so far
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final List<Cards.Card> none = Collections.emptyList();
        // An updated app may bring new artwork; the backdrop is cheap to make again.
        heroArtCache.clear();
        heroArtKey = null;
        // One LEANBACK query for both the tiles and the card checks.
        final List<ResolveInfo> leanback = getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        cards = moduleCards ? CardFeature.run(() -> Cards.discover(this, hiddenApps,
                () -> Cards.load(Cards.storeFile(this)), leanback), none) : none;
        adapter.reload(cards.isEmpty() ? none : CardFeature.run(() -> Cards.shown(cards), none), leanback);
        lastFocusedKey = returnKey;
        lastFocused = returnSlot;
        packageSequence = currentPackageSequence(packageSequence);
    }

    /**
     * Return to the app we left from, or the same slot if that app is gone. Always refocus: after
     * reload() the view that holds focus may no longer show the app it had.
     */
    private void restoreFocus() {
        final boolean returnToLibrary = libraryFocusPending;
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            if (moreAppsOpen()) focusMoreTile();
            else if (keepWatching != null && keepWatching.opened && keepWatchingShown()) keepWatching.requestFocus();
            else if (returnToLibrary || libraryFocusPending) focusLibrary();
            else focusTile(pos >= 0 ? pos : returnSlot);
            tilesReady = true;
            startPendingSettingsReorder();
            showCardPrompt();
        });
    }

    /** Any key on Home restarts the countdown to the idle clock. */
    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        restartIdleClock();
    }

    private void restartIdleClock() {
        root.removeCallbacks(showIdleClock);
        if (idleClockMs > 0) root.postDelayed(showIdleClock, idleClockMs);
        root.removeCallbacks(fadeHome);
        if (idleFadeMs > 0) root.postDelayed(fadeHome, idleFadeMs);
    }

    /** idle_fade_minutes: what fades away, everything but the background, sky and wallpaper under it. */
    private View[] fadingViews() {
        List<View> views = new ArrayList<>();
        views.add(homeContent());
        if (moreGrid != null) views.add(moreGrid);
        // The line above the dock and its glow go too, while they show (a hidden shelf stays hidden on the way back).
        if (heroShown) {
            views.add(hero);
            if (ambientGlow) views.add(ambient);
        }
        return views.toArray(new View[0]);
    }

    /** glass_sweep runs only while the dock is what's on screen. */
    private void updateSweep() {
        if (dockGlass != null) dockGlass.sweep(resumedHome && !faded && !inWatchlist && !moreAppsOpen());
        // weather_scene keeps moving through the idle fade, more slowly, and stops in apps, More apps, the lineup and the deck.
        if (weatherScene != null) weatherScene.motion(resumedHome && !inWatchlist && !moreAppsOpen(), faded, !moreAppsOpen());
    }

    private void goIdle(boolean atOnce) {
        if (inWatchlist) exitWatchlist(false);   // the screensaver fades Home itself, not the lineup over it
        faded = true;
        updateSweep();
        if (wakeMotion == null) {
            for (View v : fadingViews()) v.animate().alpha(0f).setDuration(atOnce ? 0 : saverFadeMs).start();
        } else if (atOnce) {
            if (!plainFocus) glass.setVisibility(View.INVISIBLE);
            wakeMotion.away(wakeFades(), wakeDistance());
        } else {
            leaveWithMotion();
        }
        showSaver();
    }

    private void unfade(int ms) {
        root.removeCallbacks(unfadeAfterPause);
        faded = false;
        updateSweep();
        if (wakeMotion == null) for (View v : fadingViews()) v.animate().alpha(1f).setDuration(ms).start();
        else if (ms == 0) wakeMotion.jump(wakeFades(), wakeDistance());
        else wakeMotion.come(wakeFades(), wakeDistance());
        hideSaver(ms);
    }

    // ---- wake_animation ---------------------------------------------------------------------------

    private void setUpWakeMotion(Config cfg) {
        // No picture of the whole dock on the way: its tiles barely touch, so each draws at the fading alpha by itself.
        grid.forceHasOverlappingRendering(false);
        if (keepWatching != null) keepWatching.forceHasOverlappingRendering(false);
        wakeMotion = new WakeMotion((alpha, y) -> {
            grid.setAlpha(alpha);
            grid.setTranslationY(y);
            if (dockGlass != null) dockGlass.wake(alpha, y);
        }, keepWatching == null ? null : (alpha, y) -> {
            keepWatching.setAlpha(alpha);
            keepWatching.setTranslationY(y);
        }, cfg.wakeAnimationSpeed, () -> {
            // The floating highlight is placed by the focused tile, which has only now stopped moving.
            if (!plainFocus) {
                glass.setVisibility(View.VISIBLE);
                refitGlass();
            }
        });
    }

    /** Fades with the motion and doesn't move: the top bar and the line above the dock with its glow. big_clock stays. */
    private List<View> wakeFades() {
        List<View> views = new ArrayList<>();
        views.add(((ViewGroup) homeContent()).getChildAt(0));
        if (moreGrid != null) views.add(moreGrid);
        if (heroShown) {
            views.add(hero);
            if (ambientGlow) views.add(ambient);
        }
        return views;
    }

    /** From the dock's top to just below the screen, its panel's top edge and shadow included. */
    private float wakeDistance() {
        Rect dock = new Rect(0, grid.getPaddingTop(), 0, grid.getPaddingTop());
        root.offsetDescendantRectToMyCoords(grid, dock);
        return root.getHeight() - dock.top + 40 * density;
    }

    private void leaveWithMotion() {
        if (!plainFocus) glass.setVisibility(View.INVISIBLE);
        wakeMotion.leave(wakeFades(), wakeDistance());
    }

    // ---- fonts --------------------------------------------------------------------------------------

    /** fonts: the views in the layout. The ones built in code ask for theirs where they are built. */
    private void useThemeFonts() {
        Typeface clock = themeFonts.get(this, ThemeFonts.CLOCK, null), line = themeFonts.get(this, ThemeFonts.LINE, null),
                tagline = themeFonts.get(this, ThemeFonts.TAGLINES, null);
        if (clock != null) {
            ((TextView) findViewById(R.id.clock)).setTypeface(clock);
            ((TextView) findViewById(R.id.ampm)).setTypeface(clock);
        }
        if (line != null) ((TextView) findViewById(R.id.date)).setTypeface(line);
        if (tagline != null) heroName.setTypeface(tagline);
    }

    /** A label's font: the theme's if it has one, else `fontString`. */
    private Typeface labelFont(int fontString) {
        return labelsFont != null ? labelsFont : UiFont.get(this, fontString);
    }

    // ---- screensaver ------------------------------------------------------------------------------

    /**
     * screensaver: fades in over the faded Home. Built on first use, and only when it has something to draw, so the
     * default (the wallpaper alone, no dim) runs nothing new. No video and no blur; a slideshow holds one picture.
     */
    private void showSaver() {
        if (!Screensaver.draws(saverMode, saverDim, saverImages.size())) return;
        if (saverLayer == null) buildSaver();
        saverRun++;
        saverLayer.animate().cancel();
        saverLayer.setAlpha(0f);
        saverLayer.setVisibility(View.VISIBLE);
        saverLayer.bringToFront();
        updateSaverWeather();
        if (saverSlide != null) nextSlide();
        saverLayer.animate().alpha(1f).setDuration(saverFadeMs).start();
    }

    private void hideSaver(int ms) {
        if (saverLayer == null || saverLayer.getVisibility() != View.VISIBLE) return;
        saverRun++;
        root.removeCallbacks(nextSlide);
        saverLayer.animate().cancel();
        saverLayer.animate().alpha(0f).setDuration(ms).withEndAction(() -> {
            saverLayer.setVisibility(View.GONE);
            releaseSlide();
        }).start();
    }

    private void buildSaver() {
        saverLayer = new FrameLayout(this);
        saverLayer.setFocusable(false);
        saverLayer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        FrameLayout.LayoutParams full = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        if (Screensaver.SLIDESHOW.equals(saverMode)) {
            saverSlide = new ImageView(this);
            saverSlide.setScaleType(ImageView.ScaleType.CENTER_CROP);
            saverLayer.addView(saverSlide, full);
        }
        if (saverDim > 0) {
            View dim = new View(this);
            dim.setBackgroundColor(Math.round(saverDim * 2.55f) << 24);
            saverLayer.addView(dim, full);
        }
        if (Screensaver.CLOCK.equals(saverMode) || Screensaver.CLOCK_WEATHER.equals(saverMode)) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            android.widget.TextClock clock = new android.widget.TextClock(this);
            clock.setFormat12Hour("h:mm");
            clock.setFormat24Hour("HH:mm");
            clock.setTextColor(0xF2FFFFFF);
            clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 150f);
            clock.setLetterSpacing(-0.04f);
            clock.setIncludeFontPadding(false);
            clock.setMaxLines(1);
            try {
                clock.setTypeface(themeFonts.get(this, ThemeFonts.CLOCK, UiFont.get(this, R.string.font_light)));
            } catch (Exception ignored) {
                // the system font is fine
            }
            box.addView(clock, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            if (Screensaver.CLOCK_WEATHER.equals(saverMode)) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                ImageView icon = new ImageView(this);
                saverWeatherIcon = new WeatherIcon();
                icon.setImageDrawable(saverWeatherIcon);
                int size = Math.round(40 * density);
                row.addView(icon, new LinearLayout.LayoutParams(size, size));
                saverWeatherText = new TextView(this);
                saverWeatherText.setTextColor(0xE6FFFFFF);
                saverWeatherText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f);
                saverWeatherText.setIncludeFontPadding(false);
                saverWeatherText.setTypeface(clock.getTypeface());
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                tlp.setMarginStart(Math.round(12 * density));
                row.addView(saverWeatherText, tlp);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = Math.round(8 * density);
                box.addView(row, rlp);
                saverWeatherRow = row;
            }
            // As wide as the screen, centred inside: wrapped to its content, the box took the weather row's width
            // before the clock had its text, and the time broke over two lines.
            saverLayer.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        }
        saverLayer.setVisibility(View.GONE);
        root.addView(saverLayer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** clock_weather: the last reading weather_city got for the clock, or nothing until there is one. */
    private void updateSaverWeather() {
        if (saverWeatherRow == null) return;
        if (lastWeather == null) {
            saverWeatherRow.setVisibility(View.GONE);
            return;
        }
        saverWeatherIcon.setKind(lastWeather.icon);
        saverWeatherText.setText(getString(R.string.weather_celsius, lastWeather.celsius));
        saverWeatherRow.setVisibility(View.VISIBLE);
    }

    /**
     * slideshow: the shown picture fades out and is let go before the next one is decoded (to the screen's size,
     * off the UI thread), so only one is ever held.
     */
    private void nextSlide() {
        root.removeCallbacks(nextSlide);
        if (!faded || saverSlide == null || saverImages.isEmpty()) return;
        int run = saverRun;
        saverIndex = Screensaver.next(saverIndex, saverImages.size());
        File file = saverImages.get(saverIndex);
        int w = root.getWidth(), h = root.getHeight();
        Bitmap.Config config = saverBitmapConfig;
        saverSlide.animate().cancel();
        saverSlide.animate().alpha(0f).setDuration(saverBitmap == null ? 0 : 600).withEndAction(() -> {
            releaseSlide();
            new Thread(() -> {
                Bitmap next = WallpaperPlayer.decodeForScreen(file, w, h, config, 0);
                root.post(() -> {
                    if (run != saverRun || !faded) {
                        if (next != null) next.recycle();
                        return;
                    }
                    if (next != null) {
                        saverBitmap = next;
                        saverSlide.setImageBitmap(next);
                        saverSlide.animate().alpha(1f).setDuration(600).start();
                    }
                    root.postDelayed(nextSlide, saverSlideMs);
                });
            }, "screensaver").start();
        }).start();
    }

    private void releaseSlide() {
        if (saverSlide != null) saverSlide.setImageDrawable(null);
        Bitmap old = saverBitmap;
        saverBitmap = null;
        // After a frame has drawn without it, as WallpaperPlayer does.
        if (old != null) root.postDelayed(old::recycle, 500);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumedHome = false;
        inFront = false;
        updateSweep();
        root.removeCallbacks(showIdleClock);
        root.removeCallbacks(fadeHome);
        root.removeCallbacks(warmUpMotion);
        root.removeCallbacks(memoryCare);
        // Android pauses Home around onNewIntent in one go, so a Home press on the idle screen gets there first, and wakes it.
        if (faded) root.post(unfadeAfterPause);
        endReorder();   // Home or another app while reordering keeps the order so far
        wallpaperPlayer.stop();
        if (weather != null) weather.pause();
        stopAmbientAnimation();
        hero.animate().cancel();
        ambient.animate().cancel();
        heroArt.animate().cancel();
        handler.removeCallbacks(minuteTick);
        stopStats();
        if (diya != null) diya.stop();
        cancelPendingFocusScroll();
        // Nothing of ours stays on screen over another app; an undecided prompt simply returns next time.
        if (cardPrompt != null) cardPrompt.dismiss();
        if (appLibrary != null) appLibrary.close();
        if (openPopup != null) openPopup.dismiss();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (faded) unfade(0);
        started = false;
        unregisterReceiver(timeReceiver);
        handler.removeCallbacks(clockCheck);
        stopped = true;
        tileRefresh.onStop();
    }

    /**
     * Another app needs the memory while Home is in the background: let go of every tile's artwork, and
     * rebuild it when Home comes back. Not at UI_HIDDEN, the ordinary trip to an app, where keeping the
     * tiles is what makes the return instant.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // RUNNING_LOW and CRITICAL also arrive while Home is visible when an accessibility service keeps the
        // process in the foreground; never blank the screen the owner is looking at.
        boolean due = level >= TRIM_MEMORY_BACKGROUND
                || level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL;
        if (!due || started) return;
        adapter.clear();
        // Detach the adapter too: with Home stopped no layout pass runs, so the tile views (and the bitmaps
        // they hold) would stay attached until Home comes back. setAdapter(null) recycles them now.
        grid.setAdapter(null);
        grid.getRecycledViewPool().clear();
        heroArtCache.clear();
        heroArtKey = null;
        tileRefresh.invalidate();
        if (watchlistView != null) watchlistView.release();
        if (moodDeckView != null) moodDeckView.release();
        if (moodShelfView != null) moodShelfView.release();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && focusBelow()) return;
        if (hasFocus) {
            startPendingSettingsReorder();
            if (libraryFocusPending && appLibrary == null) focusLibrary();
            showCardPrompt();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        root.removeCallbacks(unfadeAfterPause);
        root.removeCallbacks(memoryCare);
        unregisterReceiver(packageReceiver);
        wallpaperPlayer.close();
        if (scene != null) scene.close();
        if (keepWatching != null) keepWatching.close();
        if (dockGlass != null) dockGlass.close();
        if (weatherScene != null) {
            unregisterReceiver(weatherTest);
            weatherScene.close();
        }
        keepWatchingReads.shutdownNow();
        if (weather != null) weather.close();
        saverRun++;
        root.removeCallbacks(nextSlide);
        if (saverSlide != null) saverSlide.setImageDrawable(null);
        if (saverBitmap != null) saverBitmap.recycle();
        saverBitmap = null;
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.unregisterCallback(inputCallback);
        if (cm != null) {
            cm.unregisterNetworkCallback(networkCallback);
            cm.unregisterNetworkCallback(vpnCallback);
        }
    }

    /**
     * A Settings button sent as a keycode (settings_key_keycodes, e.g. 83 on TCL Google TV remotes) opens the same
     * thing HomeKeyService would. While that service runs it consumes the key first; without it (not enabled,
     * or switched off by a force-stop) the stock launcher that used to handle the key is gone, and the button did
     * nothing even on Home.
     */
    private boolean handleSettingsKey(KeyEvent event) {
        for (int code : settingsKeycodes) {
            if (event.getKeyCode() != code) continue;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                open(settingsKeyPanel ? new Intent(this, QuickSettingsActivity.class)
                        : systemIntent(Settings.ACTION_SETTINGS));
            }
            return true;
        }
        return false;
    }

    // ---- reorder ---------------------------------------------------------------------------------

    /** Blue the glass turns while reorder mode is on. */
    private static final int REORDER_ACCENT = 0xFF6EC1FF;

    /**
     * Reorder mode (top-bar button). While it is on, every key comes here first:
     * - nothing carried: arrows move focus as usual; OK on a tile picks it up instead of opening the app;
     *   Back ends the mode and saves the order.
     * - a tile carried: arrows move it; Back (or OK) puts it down and the mode stays on for the next one.
     * Other keys (volume, Home) pass through; leaving the launcher ends the mode and saves.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean volume = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE;
        if (faded && event.getAction() == KeyEvent.ACTION_DOWN) {
            // idle_fade_minutes: any key brings Home back and does nothing else, except volume, which still works.
            unfade(250);
            restartIdleClock();   // the key is eaten before Activity.dispatchKeyEvent, so onUserInteraction never runs
            if (!volume) wakeKey = code;
        }
        if (code == wakeKey && !volume) {
            if (event.getAction() == KeyEvent.ACTION_UP) wakeKey = KeyEvent.KEYCODE_UNKNOWN;
            return true;
        }
        if (handleSettingsKey(event)) return true;
        if (tileMenuEnabled && !reordering && openPopup == null && cardPrompt == null
                && event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            int position = focusedTilePosition();
            if (position >= 0 && position < adapter.appCount()
                    && !getPackageName().equals(adapter.packageAt(position))) {
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                    onTileLongPress(position, getCurrentFocus());
                }
                return true;
            }
        }
        if (!reordering) return super.dispatchKeyEvent(event);
        if (dockEditing) return dispatchDockEdit(event);
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;
        if (movingKey != null) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) moveBy(code);
                    return true;
                default:
                    if (ok || back) {
                        if (!down) drop();
                        return true;
                    }
                    return super.dispatchKeyEvent(event);
            }
        }
        if (back) {
            if (!down) endReorder();
            return true;
        }
        int pos = focusedTilePosition();
        View focus = getCurrentFocus();
        if (ok && focus != null && focus.getId() == R.id.btnLibrary) {
            // The App library pill sits in the grid but is no tile: OK ends the mode (saving the order) and opens it.
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                endReorder();
                onLibraryRequested();
            }
            return true;
        }
        if (ok && grid.hasFocus()) {
            // RecyclerView can briefly report NO_POSITION during a move or rebind. Still consume
            // OK on the grid: falling through would let the focused card open its app in reorder mode.
            if (!down && pos >= 0) pickUp(pos);
            return true;
        }
        return super.dispatchKeyEvent(event);   // arrows navigate; OK on a top-bar pill works as usual
    }

    /** Adapter position of the focused tile, or -1 when focus is not on a tile. */
    private int focusedTilePosition() {
        View f = getCurrentFocus();
        if (f == null || f.getId() != R.id.card || !(f.getParent() instanceof View)) return -1;
        return grid.getChildAdapterPosition((View) f.getParent());
    }

    /** The reorder button: turns the mode on (focus goes back to the last tile), or off again. */
    private void toggleReorder() {
        if (reordering) {
            endReorder();
            return;
        }
        if (!beginReorder()) return;
        focusTile(Math.max(0, Math.min(lastFocused, adapter.movableCount() - 1)));
        Toast.makeText(this, R.string.reorder_hint, Toast.LENGTH_LONG).show();
    }

    /** The start shared by reorder and edit-dock mode. False when there is nothing to move. */
    private boolean beginReorder() {
        // icon_dock: Settings and More apps are tiles too, and the theme says the order.
        if (iconDock || adapter.movableCount() < 2) return false;
        // Before the reload below: once it has dropped the empty slots, the dock row also shows grid apps.
        dockInSession = new HashSet<>();
        dockedAtStart = adapter.dockFilled();
        for (int i = 0; i < Math.min(dockedAtStart, adapter.movableCount()); i++) dockInSession.add(adapter.keyAt(i));
        adapter.setReordering(true);
        if (adapter.hasEmptySlots()) reloadTiles();   // every position must hold a tile that can move
        reordering = true;
        orderAtStart = adapter.movableKeys();
        glass.setAccent(REORDER_ACCENT);
        btnReorder.setActivated(true);
        return true;
    }

    /** Edit dock (Settings menu): focus goes to the first dock place, which OK then chooses. */
    private void startDockEdit() {
        if (iconDock) return;
        if (reordering) endReorder();
        if (!beginReorder()) return;
        dockEditing = true;
        dockEditSlot = -1;
        focusTile(0);
        Toast.makeText(this, R.string.dock_edit_hint, Toast.LENGTH_LONG).show();
    }

    /**
     * Keys in edit-dock mode. First OK on a dock tile chooses that place (the other dock tiles dim) and focus moves
     * to the grid; then OK on any tile swaps that app into the place, saves the order and ends the mode. Back goes
     * one step back: from choosing the app to choosing the place, and from there out of the mode.
     */
    private boolean dispatchDockEdit(KeyEvent event) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;
        if (back) {
            if (!down) {
                if (dockEditAppKey != null) {
                    endReorder();   // "Add to dock" cancelled before a place was chosen
                } else if (dockEditSlot >= 0) {
                    int slot = dockEditSlot;
                    dockEditSlot = -1;
                    dimOthers();
                    focusTile(slot);
                } else {
                    endReorder();
                }
            }
            return true;
        }
        int pos = focusedTilePosition();
        if (ok && pos >= 0) {
            if (!down) {
                int n = adapter.movableCount();
                if (dockEditAppKey != null) {
                    if (pos < Math.min(COLUMNS, n)) {
                        int from = adapter.positionOf(dockEditAppKey);
                        if (from >= 0) swapIntoDock(pos, from);
                        else endReorder();
                    }
                } else if (dockEditSlot < 0) {
                    if (pos < Math.min(COLUMNS, n)) {
                        dockEditSlot = pos;
                        dimOthers();
                        focusTile(Math.min(COLUMNS, n - 1));
                        Toast.makeText(this, R.string.dock_edit_pick_app, Toast.LENGTH_LONG).show();
                    }
                } else if (pos < n) {
                    swapIntoDock(dockEditSlot, pos);
                }
            }
            return true;   // never opens the app while editing
        }
        return super.dispatchKeyEvent(event);   // arrows navigate as usual
    }

    /**
     * Edit-dock mode started from the tile menu. A dock app: its place is already chosen, so focus goes to the grid
     * for the app to put there. A grid app: it is already chosen, so focus goes to the dock for the place.
     */
    private void startDockEditFor(String key, boolean inDock) {
        if (reordering) endReorder();
        if (!beginReorder()) return;
        dockEditing = true;
        int pos = adapter.positionOf(key);   // beginReorder can rebuild the tiles
        int n = adapter.movableCount();
        if (pos < 0) {
            endReorder();
        } else if (inDock) {
            dockEditSlot = pos;
            dimOthers();
            focusTile(Math.min(COLUMNS, n - 1));
            Toast.makeText(this, R.string.dock_edit_pick_app, Toast.LENGTH_LONG).show();
        } else {
            dockEditAppKey = key;
            focusTile(0);
            Toast.makeText(this, R.string.dock_edit_pick_place, Toast.LENGTH_LONG).show();
        }
    }

    /** Puts the app at pos into dock place slot, and the app that was there where the chosen one was; then saves. */
    private void swapIntoDock(int slot, int pos) {
        String slotKey = adapter.keyAt(slot);
        if (pos != slot) {
            keepScroll(() -> {
                adapter.move(pos, slot);
                adapter.move(adapter.positionOf(slotKey), pos);
            });
            dockInSession.add(adapter.keyAt(slot));
            if (pos >= COLUMNS) dockInSession.remove(slotKey);
        }
        endReorder();
        refocusAfterLayout(slot);
    }

    private void pickUp(int pos) {
        if (pos >= adapter.movableCount()) return;   // the "Default home" tile stays put
        movingKey = adapter.keyAt(pos);
        dimOthers();
    }

    /** Puts the carried tile down where it is; the mode stays on. */
    private void drop() {
        movingKey = null;
        dimOthers();
    }

    /** While a tile is carried, every other tile is dimmed so the carried one stands out; otherwise all are full. */
    private void dimOthers() {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View item = grid.getChildAt(i);
            item.setAlpha(tileAlpha(grid.getChildAdapterPosition(item)));
        }
    }

    /** A tile's alpha: dim unless it is the carried one, or, in edit-dock mode, a dock tile other than the chosen place. */
    private float tileAlpha(int pos) {
        if (movingKey != null) {
            return pos != RecyclerView.NO_POSITION && movingKey.equals(adapter.keyAt(pos)) ? 1f : 0.45f;
        }
        if (dockEditSlot >= 0 && pos >= 0 && pos < COLUMNS && pos != dockEditSlot) return 0.45f;
        return 1f;
    }

    /** Ends the mode and saves the order if it changed since the mode started. */
    private void endReorder() {
        if (!reordering) return;
        dockEditing = false;
        dockEditSlot = -1;
        dockEditAppKey = null;
        drop();
        reordering = false;
        adapter.setReordering(false);
        glass.setAccent(festive ? festival.accentColor() : 0);
        btnReorder.setActivated(false);
        List<String> keys = adapter.movableKeys();
        List<String> before = orderAtStart;
        orderAtStart = null;
        // fill_dock false: the dock is what the owner put there, saved with its size, so the empty slots that
        // come back after the mode go after it (#499). With fill_dock true every dock slot holds an app anyway.
        int docked = -1;
        if (fillDockLeavesSlots) {
            docked = DockSlots.dockCount(keys, COLUMNS, dockInSession);
            keys = DockSlots.dockFirst(keys, COLUMNS, dockInSession);
        }
        dockInSession = null;
        if (!keys.equals(before) || (fillDockLeavesSlots && docked != dockedAtStart)) {
            Order order = new Order(Order.dockKey(dockApps), keys, docked);
            if (Order.save(Order.file(this), order)) {
                adapter.setSavedOrder(Order.ranks(order, dockApps), Order.docked(order, dockApps));
            } else {
                Toast.makeText(this, R.string.reorder_not_saved, Toast.LENGTH_SHORT).show();
            }
        }
        if (fillDockLeavesSlots) reloadTiles();   // the empty dock slots left out while reordering come back
    }

    /**
     * Moves the carried tile one step. Left and Right wrap across rows, like a list; Up and Down move a whole row,
     * and Down into a short last row lands on its last tile. Nothing moves past either end or up into the top bar.
     */
    private void moveBy(int keyCode) {
        int from = adapter.positionOf(movingKey);
        int n = adapter.movableCount();
        if (from < 0 || from >= n) {
            drop();
            return;
        }
        int to;
        // rtl_app_grid (Hebrew) mirrors the grid: position 0 is on the right, so Left moves towards the end.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) to = rtlAppGrid ? from + 1 : from - 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) to = rtlAppGrid ? from - 1 : from + 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) to = from - COLUMNS;
        else if (from + COLUMNS < n) to = from + COLUMNS;
        else to = from / COLUMNS < (n - 1) / COLUMNS ? n - 1 : from;
        if (to < 0 || to >= n || to == from) return;
        keepScroll(() -> adapter.move(from, to));
        // The owner carries this tile: where it lands decides whether it is in the dock, not dock_apps (#499).
        if (to < COLUMNS) dockInSession.add(movingKey);
        else dockInSession.remove(movingKey);
        refocusAfterLayout(to);
    }

    /**
     * Applies an adapter change without the grid jumping. After a move the layout manager anchors on the focused
     * tile, keeping it at its old place on screen and shifting every other row; pinning the first visible row at its
     * current offset as the next layout's anchor keeps the scroll where it was. onTileFocused still scrolls
     * afterwards if the moved tile itself ends up off screen.
     */
    private void keepScroll(Runnable change) {
        GridLayoutManager lm = (GridLayoutManager) grid.getLayoutManager();
        int first = lm.findFirstVisibleItemPosition();
        View firstView = first != RecyclerView.NO_POSITION ? lm.findViewByPosition(first) : null;
        int offset = firstView != null ? lm.getDecoratedTop(firstView) - grid.getPaddingTop() : 0;
        change.run();
        if (firstView != null) lm.scrollToPositionWithOffset(first, offset);
    }

    /**
     * Once the grid has applied the change, put the glass (and focus, if the move dropped it) on the tile at pos.
     * The grid has a fixed size, so it applies adapter changes on the next animation frame rather than in a layout
     * pass; until then it cannot find the tile by position. Wait frame by frame (at most 10) for that. No focus
     * change fires for a moved tile, so focusTile's already-focused path calls onTileFocused itself.
     */
    private void refocusAfterLayout(int pos) {
        grid.postOnAnimation(new Runnable() {
            int frames;

            @Override
            public void run() {
                if ((grid.hasPendingAdapterUpdates() || grid.isLayoutRequested()) && ++frames < 10) {
                    grid.postOnAnimation(this);
                    return;
                }
                focusTile(pos);
                dimOthers();   // views that came into view or were rebound during the move
            }
        });
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere, except out of the More apps grid.
        closeMoreApps(true);
    }

    // ---- more apps (more_apps) -------------------------------------------------------------------

    /** The full-screen grid of every app after the dock, over the same background; null unless more_apps is on. */
    private RecyclerView moreGrid;
    private AppAdapter moreAdapter;
    /** The grid tile focused last, so coming back from an app lands on it again. */
    private int moreFocused;

    private void setUpMoreApps(Config cfg) {
        moreGrid = new RecyclerView(this);
        moreGrid.setLayoutManager(new GridLayoutManager(this, cfg.columns));
        int side = (int) (66 * density);
        moreGrid.setPadding(side, (int) (60 * density), side, (int) (40 * density));
        moreGrid.setClipToPadding(false);
        moreGrid.setClipChildren(false);
        moreGrid.setItemAnimator(null);
        moreGrid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        moreGrid.setVisibility(View.GONE);
        if (scrollShade != null) {
            moreGrid.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                    updateScrollShade(rv);
                }
            });
        }
        root.addView(moreGrid, root.indexOfChild(glass), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Its tiles only open their app: the dock's hero, glass and row rules do not apply here.
        moreAdapter = new AppAdapter(this, new AppAdapter.TileListener() {
            @Override public void onTileFocused(int position, View card) { moreFocused = position; }
            @Override public boolean onTileVerticalKey(int position, int keyCode) { return false; }
            @Override public void onLibraryRequested() { }
            @Override public void onLibraryFocused() { }
            @Override public void onTileLongPress(int position, View card) { }
            @Override public void onLibraryUp() { }
        }, cfg.columns, cfg);
    }

    private boolean moreAppsOpen() {
        return moreGrid != null && moreGrid.getVisibility() == View.VISIBLE;
    }

    private void openMoreApps() {
        // The dock's line and glow belong to Home: opened from the top-bar icon they would still show behind the grid.
        hideHero();
        moreAdapter.showRest(adapter);
        moreGrid.setAdapter(moreAdapter);
        showHome(false);
        moreGrid.setAlpha(0f);
        moreGrid.setVisibility(View.VISIBLE);
        moreGrid.animate().alpha(1f).setDuration(180).start();
        if (bigClock != null) bigClock.view.animate().alpha(0f).setDuration(180).start();
        updateSweep();
        fadeScrollShade(true);
        moreFocused = 0;
        focusMoreTile();
    }

    private void focusMoreTile() {
        moreGrid.post(() -> {
            View tile = moreGrid.getLayoutManager().findViewByPosition(moreFocused);
            if (tile != null) tile.findViewById(R.id.card).requestFocus();
        });
    }

    /** Hidden Home content must not keep focus: Android would otherwise restore it to a tile under the grid. */
    private void showHome(boolean shown) {
        ViewGroup content = (ViewGroup) homeContent();
        content.setDescendantFocusability(shown ? ViewGroup.FOCUS_AFTER_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        content.setVisibility(shown ? View.VISIBLE : View.INVISIBLE);
    }

    /** Back (to the button) or Home (refocus false: onNewIntent puts focus on the first tile itself). */
    private void closeMoreApps(boolean refocus) {
        if (!moreAppsOpen()) return;
        // With no top buttons, hiding the grid lets Android focus the first dock tile, which moves lastFocused.
        int backTo = lastFocused;
        moreGrid.setVisibility(View.GONE);
        moreGrid.setAdapter(null);   // lets go of the grid's tiles; Home's adapter keeps the artwork
        moreAdapter.clear();
        if (bigClock != null) bigClock.view.animate().alpha(1f).setDuration(180).start();
        updateSweep();
        fadeScrollShade(false);
        showHome(true);
        if (refocus && adapter.isLibraryAt(adapter.appCount())) focusLibrary();
        else if (refocus && moreFromDock) focusTile(backTo);   // icon_dock: back to its "..." tile
        else if (refocus) btnLibraryTop.requestFocus();   // library_button: back to the icon that opened it
        moreFromDock = false;
    }

    /** The header and the dock: everything but the background, the wallpaper and the More apps grid. */
    private View homeContent() {
        return (View) grid.getParent().getParent();
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onLibraryRequested() {
        if (moreGrid != null) {
            openMoreApps();
            return;
        }
        if (appLibrary != null) appLibrary.close();
        if (openPopup != null) openPopup.dismiss();
        libraryFocusPending = false;
        libraryFromTopBar = false;
        appLibrary = new AppLibraryDialog(this, animations, reducedMotion, appLibrarySelection, adapter::containsPackage, () -> reloadApps(false), () -> {
            appLibrary = null;
            openPopup = null;
            if (libraryFromTopBar) {
                libraryFromTopBar = false;
                btnLibraryTop.requestFocus();
                return;
            }
            libraryFocusPending = true;
            grid.post(() -> {
                if (libraryFocusPending && appLibrary == null && hasWindowFocus() && !isFinishing()) focusLibrary();
            });
        });
        openPopup = appLibrary.popup;
        appLibrary.show();
    }

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        // Focus restored before the first layout (cold start): the tile has no size yet, and the glass
        // would be sized to 2*pad. Run again once the tile is laid out.
        if (card.getWidth() == 0) {
            card.post(() -> {
                if (card.isFocused() && card.getWidth() > 0) onTileFocused(position, card);
            });
            return;
        }
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = tileRect;
        r.set(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float scale = adapter.focusScale();
        float w = card.getWidth() * scale + 2 * pad;
        float h = card.getHeight() * scale + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f - (advancedEffects ? 4f * density : 0f);

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h || card.getWidth() != glassCardW) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            // GlassView draws its slab inset by pad, i.e. exactly over the zoomed tile: same corners as the tile.
            glass.setRadius(AppAdapter.cornerRadius(card.getWidth(), density) * scale);
        }
        glassCardW = card.getWidth();
        glassTargetX = x;
        glassTargetY = y;
        // Cinematic shelf: the highlight glows in the dock app's colour, plain white in the grid (and on the dock with
        // cinematic_white_focus). app_glow: every tile.
        boolean coloured = (appGlow || cinematic && !cinematicWhiteFocus && position < COLUMNS) && !festive;
        int accent = coloured ? adapter.ambientColorAt(position) : 0;
        if ((cinematic || appGlow) && !festive && accent != glassAccent) {
            glassAccent = accent;
            glass.setAccent(accent);
        }
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            if (animations && !reducedMotion) glass.animate().alpha(1f).setDuration(180).start();
            else glass.setAlpha(1f);
            glassShown = true;
        } else if (!animations) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y - glassScrollShift);
        }
        View item = (View) card.getParent();
        if (dockRow && position < COLUMNS) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            // A 1px mismatch used to start a scroll on every dock move. Only scroll when the row is
            // actually off; reduced motion does it in one jump so it does not fight the highlight.
            boolean scroll = Math.abs(dy) > (int) (8 * density);
            if (scroll) {
                if (!animations || reducedMotion) grid.scrollBy(0, dy);
                else grid.smoothScrollBy(0, dy);
            }
            showHero(position, scroll ? r.top - dy : r.top);
        } else {
            // Below the dock (or in a plain grid) the glow follows the tile without the big name; letting it
            // fade out left the grid on plain black.
            showGlowOnly(position);
            // Scroll just enough so the whole glass (not only the tile) is visible.
            revealRect.set(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, revealRect, reducedMotion);
        }
    }

    /** Refits the glass to the focused holder once the grid layout and tile dimensions settle. */
    private void refitGlass() {
        if (isDestroyed() || adapter.appCount() == 0 || libraryFocusPending) return;
        int pos = Math.max(0, Math.min(lastFocused, adapter.appCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        View card = vh == null ? null : vh.itemView.findViewById(R.id.card);
        if (card == null || card.getWidth() <= 0 || !card.isFocused()) return;
        float pad = 6f * density;
        float scale = adapter.focusScale();
        int w = (int) (card.getWidth() * scale + 2 * pad);
        int h = (int) (card.getHeight() * scale + 2 * pad);
        if (!glassShown || card.getWidth() != glassCardW || glass.getWidth() != w || glass.getHeight() != h) {
            onTileFocused(pos, card);
        }
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        // dock_taglines: this visit's line for the app; an app without one shows its name only under dock_title.
        String pkg = adapter.packageAt(position);
        List<String> lines = dockTaglines.get(pkg);
        String label = lines != null ? Taglines.pick(lines, taglineOnFocus ? taglineTurn(pkg, lines) : taglineVisit)
                : dockTitleShown ? adapter.labelAt(position) : "";
        int color = adapter.ambientColorAt(position);
        if (root.getHeight() > 0) dockGlowY = (dockTopInRoot + tileRect.height() / 2f) / root.getHeight();
        if (!dockTaglines.isEmpty() && !taglineCentered) alignTagline();
        if (root.getWidth() > 0 && root.getHeight() > 0) {
            // glow_style tagline: centred a little way into the line, at its middle height.
            float lineH = hero.getHeight() > 0 ? hero.getHeight() : 40 * density;
            float lineY = dockTopInRoot - 14 * density - 26 * density - lineH / 2f;
            float lineX = taglineCentered ? root.getWidth() / 2f
                    : ((FrameLayout.LayoutParams) hero.getLayoutParams()).getMarginStart() + 180 * density;
            taglineGlowX = lineX / root.getWidth();
            taglineGlowY = lineY / root.getHeight();
        }
        float y = dockTopInRoot - 14 * density - 26 * density - hero.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());
        if (cinematic && heroArtAlpha > 0f) showHeroArt(position);

        if (!animations || reducedMotion) {
            // Snap the shelf instead of sliding and fading it: each of those frames repaints the
            // full-screen panel, and this switch exists for the GPUs that cannot afford it.
            hero.animate().cancel();
            ambient.animate().cancel();
            fillHero(position, label);
            hero.setY(y);
            hero.setTranslationX(0f);
            hero.setAlpha(1f);
            if (ambientGlow) {
                setAmbient(color);
                ambient.setAlpha(1f);
            }
            heroShown = true;
            return;
        }

        if (!heroShown) {
            stopAmbientAnimation();
            hero.animate().cancel();
            ambient.animate().cancel();
            fillHero(position, label);
            hero.setY(y);
            hero.setTranslationX(0f);
            hero.animate().alpha(1f).setDuration(260).start();
            if (ambientGlow) {
                setAmbient(color);
                ambient.animate().alpha(1f).setDuration(600).start();
            }
            heroShown = true;
        } else if (changed) {
            if (advancedEffects) hero.animate().cancel();
            // A tagline swaps with a quick fade in place; a name slides as before.
            float slide = dockTaglines.isEmpty() ? 12 * density : 0f;
            hero.animate().alpha(0f).translationX(-slide).setDuration(dockTaglines.isEmpty() ? 120 : 100).withEndAction(() -> {
                fillHero(position, label);
                hero.setY(y);
                hero.setTranslationX(slide);
                hero.animate().alpha(1f).translationX(0f).setDuration(dockTaglines.isEmpty() ? 200 : 160).start();
            }).start();
            if (ambientGlow && advancedEffects) {
                transitionAmbient(color);
            } else if (ambientGlow && color != ambientColor) {
                ambient.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
                    setAmbient(color);
                    ambient.animate().alpha(1f).setDuration(500).start();
                }).start();
            }
        } else {
            hero.setY(y);
        }
    }

    /** The shelf's text for this dock app: its name and, on the cinematic shelf, "Last opened" and the dots. */
    private void fillHero(int position, String label) {
        heroName.setText(label);
        if (!cinematic) return;
        updateHeroMeta(position);
        updateHeroDots(position);
    }

    // ---- cinematic shelf -------------------------------------------------------------------------

    /**
     * The parts that never change: a larger name, the meta line and the dots, the artwork and the scrim, at
     * scrimPercent of its strength (cinematic_scrim). cinematic_art 0 leaves the artwork out, 0 percent the scrim.
     */
    private void setUpCinematicShelf(int scrimPercent) {
        heroName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 68f);
        heroName.setLetterSpacing(-0.025f);
        heroMeta.setVisibility(View.INVISIBLE);   // keeps its height, so the name does not jump when it fills in
        heroDots.setVisibility(heroDotsShown ? View.VISIBLE : View.GONE);
        heroArt.setVisibility(heroArtAlpha > 0f ? View.VISIBLE : View.GONE);
        // One stretched bitmap: apply its alpha while drawing instead of through an offscreen buffer.
        heroArt.forceHasOverlappingRendering(false);
        if (scrimPercent <= 0) return;   // cinematic_scrim 0: no scrim at all
        View scrim = findViewById(R.id.heroScrim);
        Drawable shade = scrimDrawable();
        // On the bitmap's paint, not the view: a view's alpha would draw the full screen through an offscreen buffer.
        shade.setAlpha(scrimPercent * 255 / 100);
        scrim.setBackground(shade);
        scrim.setVisibility(View.VISIBLE);
    }

    /**
     * Darkens the side the shelf's text is on and the lower part of the screen, so the name, the dock and
     * the grid stay readable over the artwork. One small bitmap stretched over the screen, like the glow.
     */
    private Drawable scrimDrawable() {
        int w = 480, h = 270;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        boolean rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
        Paint paint = new Paint();
        paint.setShader(new LinearGradient(rtl ? w : 0, 0, rtl ? 0 : w, 0,
                new int[]{0xD1060406, 0x59060406, 0x00060406}, new float[]{0f, 0.42f, 0.7f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0x00060406, 0x00060406, 0xDB060406, 0xFF060406}, new float[]{0f, 0.28f, 0.58f, 0.76f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        BitmapDrawable scrim = new BitmapDrawable(getResources(), bmp);
        scrim.setGravity(Gravity.FILL);
        return scrim;
    }

    /** Swaps the backdrop to this app's artwork: fade out, swap, fade in, the way the shelf's name changes. */
    private void showHeroArt(int position) {
        String key = adapter.keyAt(position);
        if (key == null) return;
        heroArt.animate().cancel();
        if (key.equals(heroArtKey)) {
            fadeHeroArt(420);
            return;
        }
        Bitmap art = heroArtCache.get(key);
        if (art == null) {
            art = HeroArt.from(adapter.artworkAt(position));
            if (art != null) heroArtCache.put(key, art);
        }
        heroArtKey = key;
        final Bitmap next = art;
        if (!animations || reducedMotion || heroArt.getAlpha() == 0f) {
            heroArt.setImageBitmap(next);
            fadeHeroArt(600);
            return;
        }
        heroArt.animate().alpha(0f).setDuration(140).withEndAction(() -> {
            heroArt.setImageBitmap(next);
            heroArt.animate().alpha(heroArtAlpha).setDuration(420).start();
        }).start();
    }

    private void fadeHeroArt(int ms) {
        if (!animations) heroArt.setAlpha(heroArtAlpha);
        else heroArt.animate().alpha(heroArtAlpha).setDuration(reducedMotion ? Math.min(ms, Motion.REDUCED_MS) : ms).start();
    }

    /** "Last opened yesterday, 9:40 PM", the time in white; nothing (but the same height) when it was never seen. */
    private void updateHeroMeta(int position) {
        long when = LastOpened.get(this, adapter.packageAt(position));
        if (when <= 0) {
            heroMeta.setVisibility(View.INVISIBLE);
            return;
        }
        String rel = LastOpened.describe(this, when);
        String text = getString(R.string.last_opened, rel);
        SpannableString line = new SpannableString(text);
        int at = text.indexOf(rel);
        if (at >= 0) line.setSpan(new ForegroundColorSpan(0xFFFFFFFF), at, at + rel.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        heroMeta.setText(line);
        heroMeta.setVisibility(View.VISIBLE);
    }

    /** One dot per dock app, the focused one drawn long and white. Hebrew docks start on the right, so do the dots. */
    private void updateHeroDots(int position) {
        if (!heroDotsShown) return;
        int count = Math.min(adapter.dockFilled(), COLUMNS);
        heroDots.setVisibility(count > 1 ? View.VISIBLE : View.INVISIBLE);
        if (heroDots.getChildCount() != count) {
            heroDots.removeAllViews();
            for (int i = 0; i < count; i++) {
                View dot = new View(this);
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(3 * density);
                dot.setBackground(bg);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (6 * density), (int) (6 * density));
                if (i > 0) lp.leftMargin = (int) (6 * density);
                heroDots.addView(dot, lp);
            }
        }
        for (int i = 0; i < count; i++) {
            View dot = heroDots.getChildAt(rtlAppGrid ? count - 1 - i : i);
            boolean on = i == position;
            ((GradientDrawable) dot.getBackground()).setColor(on ? 0xFFFFFFFF : 0x4DFFFFFF);
            ViewGroup.LayoutParams lp = dot.getLayoutParams();
            int w = (int) ((on ? 20 : 6) * density);
            if (lp.width != w) {
                lp.width = w;
                dot.setLayoutParams(lp);
            }
        }
    }

    /**
     * The glow used to be a full-screen radial GradientDrawable. On Android 10 that drawable is
     * drawn on the CPU, so fading it for half a second redrew 1920×1080 every frame and the
     * highlight looked like it was running at a few frames per second. A small bitmap stretched
     * over the screen is one texture. It is painted once in white and tinted with the app's colour,
     * so changing apps neither repaints it on the CPU (about 11 ms) nor uploads it again.
     */
    private void setAmbient(int color) {
        if (color == ambientColor && ambient.getBackground() != null) return;
        ambientColor = color;
        BitmapDrawable glow = (BitmapDrawable) ambient.getBackground();
        if (glow == null) {
            int w = 480;
            int h = 270;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            if (cinematic) {
                // Higher and stronger, from the corner away from the shelf's text: the app's colour floods
                // the top of the screen over its artwork, and the scrim keeps the text side dark.
                boolean rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
                paint.setShader(new RadialGradient(
                        w * (rtl ? 0.20f : 0.80f), h * 0.06f, w * 0.78f,
                        new int[]{0xA6FFFFFF, 0x47FFFFFF, 0x00FFFFFF},
                        new float[]{0f, 0.5f, 1f},
                        Shader.TileMode.CLAMP));
            } else if (Config.GLOW_TAGLINE.equals(glowStyle)) {
                // A soft, wide light behind the dock's tagline, so the line sits in the focused app's colour.
                RadialGradient line = new RadialGradient(0f, 0f, w * 0.3f * glowScale,
                        glow(0x52FFFFFF, 0x1FFFFFFF, 0x00FFFFFF), new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
                Matrix m = new Matrix();
                m.setScale(1f, 0.45f);
                m.postTranslate(w * Math.max(0.05f, Math.min(0.95f, taglineGlowX)),
                        h * Math.max(0.1f, Math.min(0.95f, taglineGlowY)));
                line.setLocalMatrix(m);
                paint.setShader(line);
            } else if (Config.GLOW_DOCK.equals(glowStyle)) {
                // A wide, low ellipse behind the dock's row, so the focused app's colour sits under its logo.
                RadialGradient dock = new RadialGradient(0f, 0f, w * 0.42f * glowScale,
                        glow(0x4DFFFFFF, 0x1CFFFFFF, 0x00FFFFFF), new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
                Matrix m = new Matrix();
                m.setScale(1f, 0.42f);
                m.postTranslate(w * 0.5f, h * Math.max(0.3f, Math.min(0.95f, dockGlowY)));
                dock.setLocalMatrix(m);
                paint.setShader(dock);
            } else if (Config.GLOW_HORIZON.equals(glowStyle)) {
                // The colour washes down from the top edge and fades out three quarters of the way down,
                // with a brighter band along the top like light over a horizon.
                paint.setShader(new LinearGradient(0, 0, 0, Math.min(h, h * 0.75f * glowScale),
                        glow(0x66FFFFFF, 0x24FFFFFF, 0x00FFFFFF),
                        new float[]{0f, 0.4f, 1f},
                        Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, w, h, paint);
                paint.setShader(new RadialGradient(
                        w * 0.5f, -h * 0.25f, w * 0.55f * glowScale,
                        glow(0x40FFFFFF, 0x00FFFFFF),
                        null,
                        Shader.TileMode.CLAMP));
            } else {
                paint.setShader(new RadialGradient(
                        w * 0.32f, h * 0.42f, w * 0.62f * glowScale,
                        glow(0x5CFFFFFF, 0x26FFFFFF, 0x00FFFFFF),
                        new float[]{0f, 0.45f, 1f},
                        Shader.TileMode.CLAMP));
            }
            canvas.drawRect(0, 0, w, h, paint);
            glow = new BitmapDrawable(getResources(), bmp);
            glow.setGravity(Gravity.FILL);
            ambient.setBackground(glow);
        }
        // The default tint mode, SRC_IN, keeps the mask's alpha and takes the colour from the tint.
        glow.setTint(0xFF000000 | color);
    }

    /**
     * tagline_rotate focus: this app's line for a new landing on it moves to the next one (kept across restarts); a
     * refit or a refocus of the same tile shows the same line again.
     */
    private int taglineTurn(String pkg, List<String> lines) {
        SharedPreferences turns = getSharedPreferences("tagline_turns", MODE_PRIVATE);
        View focused = getCurrentFocus();
        int turn = turns.getInt(pkg, -1);
        if (focused != null && focused != taglineCard && focused.getId() == R.id.card) {
            taglineCard = focused;
            turn = (turn + 1) % Math.max(1, lines.size());
            turns.edit().putInt(pkg, turn).apply();
        }
        return Math.max(0, turn);
    }

    /**
     * tagline_align left: the line starts where the dock's first logo does (its tile when it has no logo), by layout
     * position, so the focus grow does not move it. Set as the shelf's margin: its slide in uses the translation.
     */
    private void alignTagline() {
        RecyclerView.ViewHolder first = grid.findViewHolderForAdapterPosition(0);
        if (first == null) return;
        View card = first.itemView.findViewById(R.id.card);
        View banner = card.findViewById(R.id.banner);
        View edge = banner.getVisibility() == View.VISIBLE && banner.getWidth() > 0 ? banner : card;
        Rect r = new Rect(0, 0, edge.getWidth(), edge.getHeight());
        root.offsetDescendantRectToMyCoords(edge, r);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) hero.getLayoutParams();
        if (lp.getMarginStart() != r.left) {
            lp.setMarginStart(r.left);
            hero.setLayoutParams(lp);
        }
    }

    /**
     * bottom_shade: black rising from the bottom edge, strongest under the dock, so the row sits on dark over any
     * wallpaper. One tall, narrow bitmap stretched over the screen like the glow; filtering keeps the steps smooth.
     */
    private void addBottomShade(int percent) {
        int h = 540;
        Bitmap bmp = Bitmap.createBitmap(4, h, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint();
        paint.setDither(true);
        int a = percent * 255 / 100;
        paint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0, 0, (a * 45 / 100) << 24, (a * 85 / 100) << 24, a << 24},
                new float[]{0f, 0.38f, 0.62f, 0.82f, 1f}, Shader.TileMode.CLAMP));
        new Canvas(bmp).drawRect(0, 0, 4, h, paint);
        BitmapDrawable shade = new BitmapDrawable(getResources(), bmp);
        shade.setGravity(Gravity.FILL);
        shade.setFilterBitmap(true);
        View view = new View(this);
        view.setBackground(shade);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(view, root.indexOfChild(ambient), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /**
     * scroll_dim: the further down the page, the darker the background. More apps sits below Home, so opening it is
     * already half way down (its few rows may never scroll); scrolling either grid by half a screen goes the rest.
     */
    private void updateScrollShade(RecyclerView rv) {
        int h = root.getHeight();
        if (h <= 0) return;
        float progress = Math.min(1f, rv.computeVerticalScrollOffset() / (h * 0.5f));
        float down = rv == moreGrid ? 0.5f + 0.5f * progress : progress;
        scrollShade.animate().cancel();
        scrollShade.setAlpha(scrollDimMax * down);
    }

    /** The shade's step when More apps opens or closes, eased over the grid's own fade. */
    private void fadeScrollShade(boolean moreOpen) {
        if (scrollShade == null) return;
        float target = moreOpen ? scrollDimMax * 0.5f
                : scrollDimMax * Math.min(1f, grid.computeVerticalScrollOffset() / Math.max(1f, root.getHeight() * 0.5f));
        scrollShade.animate().cancel();
        scrollShade.animate().alpha(target).setDuration(180).start();
    }

    /**
     * weather_city: a small outline icon and the temperature beside the time, on its baseline like the AM/PM mark.
     * Hidden until there is a reading (Weather keeps the last one, so that is usually at once).
     */
    private void addWeather(String city) {
        View clock = findViewById(R.id.clock);
        ViewGroup row = (ViewGroup) clock.getParent();
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.HORIZONTAL);
        block.setGravity(Gravity.CENTER_VERTICAL);
        block.setFocusable(false);
        block.setVisibility(View.GONE);
        ImageView icon = new ImageView(this);
        weatherIcon = new WeatherIcon();
        icon.setImageDrawable(weatherIcon);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int size = Math.round(26 * density);
        block.addView(icon, new LinearLayout.LayoutParams(size, size));
        weatherText = new TextView(this);
        weatherText.setTextColor(0xE6FFFFFF);
        weatherText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
        weatherText.setIncludeFontPadding(false);
        try {
            weatherText.setTypeface(themeFonts.get(this, ThemeFonts.LINE, UiFont.get(this, R.string.font_light)));
        } catch (Exception ignored) {
            // the system font is fine
        }
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.setMarginStart(Math.round(8 * density));
        block.addView(weatherText, tlp);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.BOTTOM;
        blp.setMarginStart(Math.round(22 * density));
        blp.bottomMargin = Math.round(6 * density);
        // After the time and its AM/PM mark, before the (maybe hidden) divider and date.
        row.addView(block, row.indexOfChild(findViewById(R.id.ampm)) + 1, blp);
        weatherBlock = block;
        weather = new Weather(this, city, reading -> {
            if (reading == null) {
                weatherBlock.setVisibility(View.GONE);
                lastWeather = null;
                updateSaverWeather();
                return;
            }
            weatherIcon.setKind(reading.icon);
            weatherText.setText(getString(R.string.weather_celsius, reading.celsius));
            weatherBlock.setVisibility(View.VISIBLE);
            lastWeather = reading;
            updateSaverWeather();
        });
    }

    /** weather_city with big_clock: the reading goes on the clock's line, not beside the small clock. */
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")   // both branches are right for their Android; lint only reads the second
    private void addBigClockWeather(Config cfg) {
        if (scene != null && cfg.weatherScene != null) {
            weatherScene = new WeatherScene(this, root, scene, cfg.weatherScene, new WeatherScene.Glass() {
                @Override public void show(WeatherLooks.Look look) {
                    bigClock.hazy(WeatherLooks.HAZE.equals(look.id));
                    bigClock.glass(look.clock != null ? look.clock : cfg.bigClockGlass);
                    if (dockGlass != null) dockGlass.glass(look.strip != null ? look.strip : cfg.glassStrip, look.rim != null ? look.rim : cfg.glassRim);
                }

                @Override public void fade(float amount) {
                    bigClock.glassFade(amount);
                    if (dockGlass != null) dockGlass.glassFade(amount);
                }
            });
            IntentFilter filter = new IntentFilter("com.gathin.tvhome.WEATHER_TEST");
            // Only what holds this permission reaches it: the adb shell does, ordinary apps can't.
            String sender = android.Manifest.permission.WRITE_SECURE_SETTINGS;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(weatherTest, filter, sender, null, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(weatherTest, filter, sender, null);
            }
        }
        weather = new Weather(this, cfg.weatherCity, reading -> {
            bigClock.show(reading);
            lastWeather = reading;
            updateSaverWeather();
            if (weatherScene != null) weatherScene.reading(reading);
        }, weatherScene != null);
    }

    /** keep_watching: the row sits in the same frame as the dock, so it fades and hides with it. */
    private void setUpKeepWatching(Config cfg) {
        keepWatchingLabels = cfg.appLabels;
        keepWatching = new KeepWatchingView(this, cfg.keepWatchingHeading, cfg.dockCentered, labelFont(R.string.font_inter_regular),
                labelFont(R.string.font_inter_medium), cfg.focusGrowMs, new KeepWatchingView.Listener() {
            @Override public void onKeepWatchingUp() { if (topButtonsShown) firstShownTopButton().requestFocus(); }
            @Override public void onKeepWatchingDown() { focusTile(lastFocused); }
            @Override public void onKeepWatchingOpen(KeepWatching.Item item) { openWatchNext(item); }
        });
        ((ViewGroup) grid.getParent()).addView(keepWatching, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, keepWatching.rowHeight()));
    }

    /** The row's bottom edge at the top of the dock, whose first row starts at `dockTop` in the grid. */
    private void placeKeepWatching(int dockTop) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) keepWatching.getLayoutParams();
        // The glass panel stands taller than the icons: the row keeps clear of it.
        int top = dockTop - keepWatching.rowHeight() - (dockGlass != null ? Math.round(DockGlass.ROW_LIFT_DP * getResources().getDisplayMetrics().density) : 0);
        if (lp.topMargin == top) return;
        lp.topMargin = top;
        grid.post(() -> keepWatching.setLayoutParams(lp));
    }

    private boolean keepWatchingShown() {
        return keepWatching != null && keepWatching.hasItems();
    }

    /** Off the main thread: a provider query; the row follows what was last watched each time Home comes back. */
    private void refreshKeepWatching() {
        keepWatchingReads.execute(() -> {
            List<KeepWatching.Item> items = KeepWatching.read(this, keepWatchingLabels);
            root.post(() -> {
                if (isDestroyed()) return;
                boolean wasFocused = keepWatching.isFocused();
                keepWatching.setItems(items);
                if (wasFocused && items.isEmpty()) focusTile(lastFocused);
            });
        });
    }

    /** OK on a card: the link its app published, else the app itself. */
    private void openWatchNext(KeepWatching.Item item) {
        Intent intent = KeepWatching.intent(item);
        if (intent != null) {
            try {
                startActivity(intent);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // falls through to the app itself
            }
        }
        openTitle(item.packageName, "", null, 0, Collections.emptyMap());
    }

    /** icon_dock's last two tiles. */
    @Override
    public void onDockAction(int kind) {
        if (kind == AppAdapter.Entry.MORE) {
            onLibraryRequested();
            moreFromDock = true;
        } else {
            open(systemIntent(Settings.ACTION_SETTINGS));
        }
    }

    /** A glow's gradient stops with glow_strength applied (the cinematic shelf keeps its own). */
    private int[] glow(int... colors) {
        return Screensaver.scaleAlpha(colors, glowFactor);
    }

    private void transitionAmbient(int color) {
        // A rapid focus change continues from the colour actually displayed on screen.
        stopAmbientAnimation();
        ambient.animate().cancel();
        if (color == ambientColor) {
            ambient.animate().alpha(1f).setDuration(180).start();
            return;
        }
        ambient.animate().alpha(1f).setDuration(180).start();
        ambientAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), ambientColor, color);
        ambientAnimator.setDuration(360);
        ambientAnimator.addUpdateListener(animation -> setAmbient((int) animation.getAnimatedValue()));
        ambientAnimator.start();
    }

    private void stopAmbientAnimation() {
        if (ambientAnimator != null) {
            ambientAnimator.cancel();
            ambientAnimator = null;
        }
    }

    /**
     * Grid tiles (every tile with dock_row false): the glow behind the grid takes the focused tile's colour; the big
     * name above the dock does not show (every grid tile has its name underneath).
     */
    private void showGlowOnly(int position) {
        int color = adapter.ambientColorAt(position);
        stopAmbientAnimation();
        hero.animate().cancel();
        ambient.animate().cancel();
        hero.setAlpha(0f);
        heroArt.animate().cancel();
        heroArt.setAlpha(0f);
        heroShown = false;
        if (!ambientGlow) return;
        if (!animations || reducedMotion) {
            setAmbient(color);
            ambient.setAlpha(1f);
            return;
        }
        if (color == ambientColor && ambient.getBackground() != null) {
            ambient.animate().alpha(1f).setDuration(300).start();
            return;
        }
        // The same dip-and-swap as the dock's top shelf.
        ambient.animate().alpha(0.35f).setDuration(ambient.getAlpha() > 0.35f ? 200 : 0).withEndAction(() -> {
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(500).start();
        }).start();
    }

    private void hideHero() {
        if (!heroShown) return;
        stopAmbientAnimation();
        hero.animate().cancel();
        ambient.animate().cancel();
        heroArt.animate().cancel();
        heroShown = false;
        if (animations && !reducedMotion) {
            hero.animate().alpha(0f).setDuration(160).start();
            if (ambientGlow) ambient.animate().alpha(0f).setDuration(300).start();
            heroArt.animate().alpha(0f).setDuration(300).start();
        } else {
            hero.setAlpha(0f);
            ambient.setAlpha(0f);
            heroArt.setAlpha(0f);
        }
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().cancel();
        if (animations && !reducedMotion) glass.animate().alpha(0f).setDuration(160).start();
        else glass.setAlpha(0f);
    }

    // ---- optional system stats (config.xml show_stats) --------------------------------------------

    /** Background reader. Started only while Home is resumed and show_stats is on. */
    private HandlerThread statsThread;
    /** The current run's handler; a run whose handler is no longer this one stops (volatile: read off-thread). */
    private volatile Handler statsBg;

    private void startStats() {
        if (statsThread != null) return;
        stats.setVisibility(View.VISIBLE);
        cpuTotalPrev = -1;
        idlePrev = -1;
        procStatDead = false;
        statsThread = new HandlerThread("stats");
        statsThread.start();
        // Each start gets its own handler, held by its task: stopStats on the main thread can clear the field
        // at any moment without the reader tripping over it.
        Handler bg = new Handler(statsThread.getLooper());
        statsBg = bg;
        bg.post(new Runnable() {
            @Override
            public void run() {
                if (statsBg != bg) return;
                String line = readStatsLine();
                handler.post(() -> {
                    if (showStats && stats != null && statsBg == bg) stats.setText(line);
                });
                if (statsBg == bg) bg.postDelayed(this, STATS_MS);
            }
        });
    }

    private void stopStats() {
        if (statsThread == null) return;
        statsBg.removeCallbacksAndMessages(null);
        statsThread.quit();
        statsThread = null;
        statsBg = null;
        // Paused (the quick panel or an app on top): keep the last line on screen. Taking it away moved the grid up
        // under a glass highlight that stayed put.
        if (!showStats) stats.setVisibility(View.GONE);
    }

    /** One quiet line, e.g. "CPU 8%  ·  Memory 430 MB free  ·  Storage 3.1 GB free". Runs off the main thread. */
    private static void appendStat(StringBuilder line, String part) {
        if (line.length() > 0) line.append("  ·  ");
        line.append(part);
    }

    private String readStatsLine() {
        int cpu = cpuPercent();
        if (cpu < 0) {
            try {
                Thread.sleep(200);             // second sample, so the first line already has a CPU figure
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            }
            cpu = cpuPercent();
        }
        // Android 8 and later hide /proc/stat from apps on many TVs: then the line leaves CPU out rather than
        // showing a dash that never fills in.
        StringBuilder line = new StringBuilder();
        if (cpu >= 0) line.append(getString(R.string.stats_cpu, cpu));
        long[] mem = memUsedOfTotalMb();
        if (mem != null) appendStat(line, getString(R.string.stats_memory, mem[0], mem[1]));
        String free = formatGb(getResources(), Environment.getDataDirectory().getFreeSpace());
        if (!free.isEmpty()) appendStat(line, getString(R.string.stats_storage, free));
        return StatsText.forLocale(line.toString(), getResources().getConfiguration().getLocales().get(0));
    }

    // -1: need another sample. -2: this source cannot be read (Android 9 hides /proc/stat from apps).
    private static final int CPU_UNREADABLE = -2;
    private long cpuTotalPrev = -1, cpuIdlePrev = -1;
    private boolean procStatDead;
    private long idlePrev = -1, wallPrev = -1;

    private int cpuPercent() {
        if (!procStatDead) {
            int fromStat = cpuFromProcStat();
            if (fromStat != CPU_UNREADABLE) return fromStat;
            procStatDead = true;
            idlePrev = -1;
        }
        return cpuFromIdle();
    }

    private int cpuFromProcStat() {
        String s = readFirstLine("/proc/stat");
        if (s == null || !s.startsWith("cpu")) return CPU_UNREADABLE;
        String[] t = s.trim().split("\\s+");
        if (t.length < 5) return CPU_UNREADABLE;
        try {
            long user = Long.parseLong(t[1]), nice = Long.parseLong(t[2]), sys = Long.parseLong(t[3]);
            long idle = Long.parseLong(t[4]);
            long iowait = t.length > 5 ? Long.parseLong(t[5]) : 0;
            long irq = t.length > 6 ? Long.parseLong(t[6]) : 0;
            long soft = t.length > 7 ? Long.parseLong(t[7]) : 0;
            long total = user + nice + sys + idle + iowait + irq + soft;
            long idleAll = idle + iowait;
            if (cpuTotalPrev < 0) {
                cpuTotalPrev = total;
                cpuIdlePrev = idleAll;
                return -1;
            }
            long dt = total - cpuTotalPrev, di = idleAll - cpuIdlePrev;
            cpuTotalPrev = total;
            cpuIdlePrev = idleAll;
            if (dt <= 0) return -1;
            int busy = (int) Math.round((dt - di) * 100.0 / dt);
            return Math.max(0, Math.min(100, busy));
        } catch (NumberFormatException e) {
            return CPU_UNREADABLE;
        }
    }

    /** Busy percent from cpuidle time (microseconds) across the cores. -1 until the second sample. */
    private int cpuFromIdle() {
        long idle = 0;
        int cores = 0;
        for (int i = 0; i < 8; i++) {
            boolean any = false;
            for (int state = 0; state < 8; state++) {
                String t = readFirstLine("/sys/devices/system/cpu/cpu" + i
                        + "/cpuidle/state" + state + "/time");
                if (t == null) break;
                try {
                    idle += Long.parseLong(t.trim());
                    any = true;
                } catch (NumberFormatException e) {
                    break;
                }
            }
            if (!any) break;
            cores++;
        }
        if (cores == 0) return -1;
        long wall = SystemClock.elapsedRealtime() * 1000L;
        if (idlePrev < 0) {
            idlePrev = idle;
            wallPrev = wall;
            return -1;
        }
        long di = idle - idlePrev;
        long dw = (wall - wallPrev) * cores;
        idlePrev = idle;
        wallPrev = wall;
        if (dw <= 0) return -1;
        int busy = (int) Math.round((dw - di) * 100.0 / dw);
        return Math.max(0, Math.min(100, busy));
    }

    /** RAM usage as {used, total} in MB, used = MemTotal - MemAvailable, e.g. "945 / 1536 MB". Used over
     *  free so the line reads as a load against a known whole; null when /proc/meminfo cannot be read. */
    private long[] memUsedOfTotalMb() {
        long available = -1, total = -1;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/meminfo"))) {
            String line;
            String[] t;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemAvailable:")) {
                    t = line.split("\\s+");
                    if (t.length >= 2) available = Long.parseLong(t[1]) / 1024;
                } else if (line.startsWith("MemTotal:")) {
                    t = line.split("\\s+");
                    if (t.length >= 2) total = Long.parseLong(t[1]) / 1024;
                }
            }
        } catch (IOException | NumberFormatException ignored) {
        }
        if (available < 0 || total <= 0) return null;
        return new long[]{Math.max(0, total - available), total};
    }

    private static String readFirstLine(String path) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path))) {
            return r.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private static String formatGb(Resources res, long bytes) {
        if (bytes <= 0) return "";
        double gb = bytes / (1024.0 * 1024 * 1024);
        return gb >= 10 ? res.getString(R.string.storage_gb_whole, gb)
                : res.getString(R.string.storage_gb_decimal, gb);
    }

    // ---- watchlist ---------------------------------------------------------------------------------

    /** watchlist: the lineup sits under the page, a screen's height down, until Down from the dock scrolls to it. */
    private void setUpWatchlist(Config cfg) {
        watchlistConfig = cfg;
        watchlistHidden.clear();
        // Titles removed here and not yet dropped by a push stay hidden (nothing is read with watchlist_remove off).
        if (cfg.watchlistRemove || cfg.moodWatched) watchlistHidden.addAll(WatchlistRemovals.load(this, Config.dir(this)));
        List<Watchlist.Item> items = WatchlistRemovals.without(cfg.watchlistItems, i -> i.key, watchlistHidden);
        WatchlistView view = new WatchlistView(this, items, cfg.dockLogos, cfg.watchlistHeading,
                labelFont(R.string.font_light), labelFont(R.string.font_medium), cfg.watchlistEffects,
                cfg.watchlistRemove ? cfg.watchlistRemoveHoldMs : 0, new WatchlistView.Listener() {
            @Override public void onWatchlistExit() { exitWatchlist(true); }
            @Override public void onWatchlistOpen(Watchlist.Item item) {
                openTitle(item.packageName, item.uri, item.component, item.flags, item.extras);
            }
            @Override public boolean onWatchlistDown() { return enterDeck(); }
            @Override public void onWatchlistRemove(Watchlist.Item item) { removeFromWatchlist(item); }
        });
        if (view.isEmpty()) return;
        watchlistView = view;
        ViewGroup content = findViewById(android.R.id.content);
        content.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        view.setVisibility(View.INVISIBLE);
        if (cfg.moodDeck) setUpMoodDeck(cfg, content);
    }

    /** mood_deck: the deck sits a screen's height under the lineup, until Down from any watchlist page scrolls to it. */
    private void setUpMoodDeck(Config cfg, ViewGroup content) {
        List<MoodDeck.Mood> moods = watchlistHidden.isEmpty() ? cfg.moodDeckMoods
                : WatchlistRemovals.without(cfg.moodDeckMoods, cfg.moodDeckTitles, watchlistHidden);
        if (moods.isEmpty()) return;
        MoodDeckView deck = new MoodDeckView(this, cfg.moodDeckTitles, moods, cfg.moodDeckHeading,
                labelFont(R.string.font_light), labelFont(R.string.font_medium), cfg.moodPosters,
                new MoodDeckView.Listener() {
            @Override public void onDeckUp() { exitDeck(); }
            @Override public void onDeckOpen(MoodDeck.Title t) { openTitle(t.packageName, t.uri, t.component, t.flags, t.extras); }
            @Override public void onDeckChoose(MoodDeck.Mood mood) { enterShelf(mood); }
        });
        if (deck.isEmpty()) return;
        moodDeckView = deck;
        content.addView(deck, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        deck.setVisibility(View.INVISIBLE);
        if (cfg.moodPosters && moodShelfView == null) {
            MoodShelfView shelf = new MoodShelfView(this, cfg.moodDeckTitles, cfg.moodPostersHeading,
                    labelFont(R.string.font_light), labelFont(R.string.font_medium),
                    cfg.moodWatched ? cfg.watchlistRemoveHoldMs : 0, cfg.moodSurpriseReel, new MoodShelfView.Listener() {
                @Override public void onShelfBack() { exitShelf(true); }
                @Override public void onShelfOpen(MoodDeck.Title t) { openTitle(t.packageName, t.uri, t.component, t.flags, t.extras); }
                @Override public void onShelfSurprise() {
                    exitShelf(true);
                    moodDeckView.surprise();
                }
                @Override public void onShelfWatched(MoodDeck.Title t) { watchedOnShelf(t); }
            });
            moodShelfView = shelf;
            content.addView(shelf, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            shelf.setVisibility(View.INVISIBLE);
        }
    }

    /** mood_posters: Right or OK on a card: its posters' row comes up over the deck, which lets go of its pictures. */
    private void enterShelf(MoodDeck.Mood mood) {
        if (moodShelfView == null || !inDeck || inShelf) return;
        inShelf = true;
        moodShelfView.setTranslationY(moodDeckView.getTranslationY());
        moodShelfView.bringToFront();
        moodShelfView.show(mood);
        moodShelfView.setAlpha(reducedMotion ? 1f : 0f);
        moodShelfView.setVisibility(View.VISIBLE);
        moodShelfView.requestFocus();
        moodShelfView.animate().alpha(1f).setDuration(reducedMotion ? 0 : 220).withEndAction(() -> {
            if (!inShelf) return;
            moodDeckView.setVisibility(View.INVISIBLE);
            moodDeckView.release();
        }).start();
    }

    /** Up or Back on the posters (or "Surprise me"): the deck again, on the same card; `focus` gives it the keys. */
    private void exitShelf(boolean focus) {
        if (!inShelf) return;
        inShelf = false;
        moodShelfView.animate().cancel();
        moodShelfView.release();
        moodShelfView.setVisibility(View.INVISIBLE);
        moodDeckView.setVisibility(View.VISIBLE);
        moodDeckView.load();
        if (focus) moodDeckView.requestFocus();
    }

    /**
     * mood_watched: "I've watched it" on a poster (the row has already let it go). It leaves every mood and, if it
     * is on the watchlist, the lineup; recorded for TV Studio, which takes it off the list or keeps it as watched.
     */
    private void watchedOnShelf(MoodDeck.Title t) {
        String key = WatchlistRemovals.keyOf(t.key, t.title);
        watchlistHidden.add(key);
        WatchlistRemovals.record(this, Config.dir(this), key, t.title, t.packageName);
        if (moodDeckView != null) moodDeckView.hide(watchlistHidden);
        if (watchlistView != null) watchlistView.hide(key);
    }

    /**
     * watchlist_remove: a title held and removed on the lineup (which already hid it). Recorded for TV Studio, and
     * the deck, off screen meanwhile, is made again without it.
     */
    private void removeFromWatchlist(Watchlist.Item item) {
        watchlistHidden.add(item.key);
        WatchlistRemovals.record(this, Config.dir(this), item.key, item.title, item.packageName);
        if (moodDeckView == null || inDeck || watchlistConfig == null) return;
        ViewGroup content = findViewById(android.R.id.content);
        moodDeckView.release();
        content.removeView(moodDeckView);
        moodDeckView = null;
        if (watchlistConfig.moodDeck) setUpMoodDeck(watchlistConfig, content);
        if (moodDeckView != null) setPageScroll(-root.getTranslationY() / Math.max(1, root.getHeight()), root.getHeight());
    }

    /** Down from the lineup: the page moves on down to the deck; the lineup keeps its page and character. */
    private boolean enterDeck() {
        if (moodDeckView == null || !inWatchlist || inDeck) return false;
        inDeck = true;
        moodDeckView.enter();
        moodDeckView.setVisibility(View.VISIBLE);
        moodDeckView.requestFocus();
        scrollPage(2f, true);
        return true;
    }

    /** While the page is scrolled down, the lineup or the deck keeps the focus; false when Home shows the dock. */
    private boolean focusBelow() {
        if (!inWatchlist) return false;
        View below = inShelf ? moodShelfView : inDeck ? moodDeckView : watchlistView;
        if (!below.isFocused()) below.requestFocus();
        return true;
    }

    /** Up from the deck: back to the lineup on the page and character it was left on. */
    private void exitDeck() {
        if (!inDeck) return;
        inDeck = false;
        moodDeckView.reset();
        watchlistView.load();
        watchlistView.setVisibility(View.VISIBLE);
        watchlistView.requestFocus();
        scrollPage(1f, true);
    }

    private void enterWatchlist(int dockPosition) {
        if (inWatchlist) return;
        inWatchlist = true;
        updateSweep();
        lastFocused = dockPosition;
        hideGlass();
        // Always the first character of page 1 (Up from the deck keeps its own place: exitDeck doesn't come here).
        watchlistView.enter();
        watchlistView.setVisibility(View.VISIBLE);
        watchlistView.requestFocus();
        scrollPage(1f, true);
    }

    private void exitWatchlist(boolean animate) {
        if (!inWatchlist) return;
        inWatchlist = false;
        updateSweep();
        exitShelf(false);
        if (inDeck) {
            // Home from the deck: all the way up to the dock, past the lineup.
            inDeck = false;
            moodDeckView.reset();
            watchlistView.setVisibility(View.VISIBLE);
        }
        scrollPage(0f, animate);
        focusTile(lastFocused);
    }

    /** 0 is Home as always, 1 the lineup filling the screen, 2 the mood deck; the page moves up as one. */
    private void scrollPage(float to, boolean animate) {
        if (pageScroll != null) pageScroll.cancel();
        int h = root.getHeight();
        float from = h == 0 ? 1f - to : -root.getTranslationY() / h;
        if (!animate || reducedMotion || h == 0) {
            setPageScroll(to, h);
            return;
        }
        pageScroll = ValueAnimator.ofFloat(from, to).setDuration(460);
        pageScroll.setInterpolator(new DecelerateInterpolator(2f));
        pageScroll.addUpdateListener(a -> setPageScroll((float) a.getAnimatedValue(), root.getHeight()));
        pageScroll.start();
    }

    private void setPageScroll(float p, int h) {
        root.setTranslationY(-p * h);
        watchlistView.setTranslationY((1f - p) * h);
        if (p <= 0f && !inWatchlist) watchlistView.setVisibility(View.INVISIBLE);
        if (moodDeckView != null) {
            moodDeckView.setTranslationY((2f - p) * h);
            if (moodShelfView != null) moodShelfView.setTranslationY((2f - p) * h);
            // Off screen, each lets go of what it doesn't show: the lineup under the deck, the deck's pictures above it.
            if (p >= 2f && inDeck) watchlistView.setVisibility(View.INVISIBLE);
            if (p <= 1f && !inDeck && moodDeckView.getVisibility() == View.VISIBLE) {
                moodDeckView.setVisibility(View.INVISIBLE);
                moodDeckView.release();
            }
        }
    }

    /**
     * OK on a character, or the mood deck's pick: its title in its app; if the app won't take the link, the app itself.
     */
    private void openTitle(String packageName, String uri, String component, int flags, Map<String, String> extras) {
        if (!uri.isEmpty()) {
            // A theme's flags replace the default: Netflix keeps a link through its profile question only when started
            // in a fresh task (new task, clear task), with its "source" extra.
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                    .addFlags(flags != 0 ? flags | Intent.FLAG_ACTIVITY_NEW_TASK : Intent.FLAG_ACTIVITY_NEW_TASK);
            for (Map.Entry<String, String> e : extras.entrySet()) view.putExtra(e.getKey(), e.getValue());
            ComponentName c = component == null ? null : ComponentName.unflattenFromString(component);
            if (c != null) view.setComponent(c);
            else if (!packageName.isEmpty()) view.setPackage(packageName);
            try {
                startActivity(view);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // falls through to the app itself
            }
        }
        Intent launch = packageName.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(packageName);
        if (launch == null && !packageName.isEmpty()) launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch != null) open(launch);
        else Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.appCount();
        int target;
        // Horizontal: swallow the key at a row's ends so focus never wraps to the next row or
        // escapes up into the top bar. Mid-row moves fall through to the default focus search.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            boolean forward = rtlAppGrid ? keyCode == KeyEvent.KEYCODE_DPAD_LEFT : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
            // strict_dock_nav: the dock's last app is its end; the More apps button is reached with Down.
            if (forward && position == adapter.focusablePosition(count - 1) && adapter.isLibraryAt(count)
                    && !(strictDockNav && position < COLUMNS)) {
                focusLibrary();
                return true;
            }
            // In the dock, the last app is the row's end: the empty slots after it take no focus.
            int rowCount = position < COLUMNS ? Math.min(count, adapter.dockFilled()) : count;
            return GridNavigation.atHorizontalEdge(position, rowCount, COLUMNS,
                    rtlAppGrid, keyCode == KeyEvent.KEYCODE_DPAD_LEFT);
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS && keepWatchingShown()) {
                keepWatching.requestFocus();
                return true;
            }
            if (position < COLUMNS && strictDockNav) {
                // strict_dock_nav: always the top-right buttons, never wherever the focus finder lands.
                if (topButtonsShown) firstShownTopButton().requestFocus();
                return true;
            }
            if (position < COLUMNS && !topButtonsShown) return true;
            if (position < COLUMNS) return false;          // dock row: let focus move to the top bar
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) {
                if (adapter.isLibraryAt(count)) focusLibrary();
                else if (watchlistView != null && !watchlistView.isEmpty()) enterWatchlist(position);
                return true;
            }
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        // The default focus search clicks for Left/Right; Up/Down are handled here, so click for them too.
        grid.playSoundEffect(keyCode == KeyEvent.KEYCODE_DPAD_UP
                ? SoundEffectConstants.NAVIGATION_UP : SoundEffectConstants.NAVIGATION_DOWN);
        return true;
    }

    @Override public void onLibraryFocused() {
        libraryFocusPending = false;
        hideGlass();
        hideHero();
    }

    /** The first top-bar button after Inputs that is shown: the network pill, Reorder, then Settings (always there). */
    private View firstTopButton() {
        if (btnWifi.getVisibility() == View.VISIBLE) return btnWifi;
        if (btnReorder.getVisibility() == View.VISIBLE) return btnReorder;
        return findViewById(R.id.btnSettings);
    }

    /** The leftmost shown top-bar button, whatever order the buttons are in (bare_buttons puts Wi-Fi first). */
    private View firstShownTopButton() {
        ViewGroup topButtons = findViewById(R.id.topButtons);
        for (int i = 0; i < topButtons.getChildCount(); i++) {
            if (topButtons.getChildAt(i).getVisibility() == View.VISIBLE) return topButtons.getChildAt(i);
        }
        return findViewById(R.id.btnSettings);
    }

    @Override public void onLibraryUp() {
        if (adapter.appCount() == 0) { firstTopButton().requestFocus(); return; }
        int lastRow = ((adapter.appCount() - 1) / COLUMNS) * COLUMNS;
        focusTile(Math.min(lastRow + lastFocused % COLUMNS, adapter.appCount() - 1));
    }

    private void focusLibrary() {
        cancelPendingFocusScroll();
        int position = adapter.appCount();
        if (!adapter.isLibraryAt(position)) return;
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(position);
        if (holder != null) {
            View button = holder.itemView.findViewById(R.id.btnLibrary);
            Rect visible = new Rect();
            if (button.getGlobalVisibleRect(visible) && visible.height() >= button.getHeight()) {
                if (button.requestFocus()) libraryFocusPending = false;
                return;
            }
        }
        if (!animations) {
            grid.scrollToPosition(position);
            grid.post(() -> {
                if (appLibrary != null || !hasWindowFocus()) return;
                RecyclerView.ViewHolder footer = grid.findViewHolderForAdapterPosition(adapter.appCount());
                if (footer != null && adapter.isLibraryAt(adapter.appCount())
                        && footer.itemView.findViewById(R.id.btnLibrary).requestFocus()) libraryFocusPending = false;
            });
            return;
        }
        pendingFocusScroll = new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                if (state != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                if (pendingFocusScroll != this) return;
                pendingFocusScroll = null;
                RecyclerView.ViewHolder footer = rv.findViewHolderForAdapterPosition(position);
                if (footer != null && footer.itemView.findViewById(R.id.btnLibrary).requestFocus())
                    libraryFocusPending = false;
            }
        };
        grid.addOnScrollListener(pendingFocusScroll);
        grid.smoothScrollToPosition(position);
    }

    private void cancelPendingFocusScroll() {
        if (pendingFocusScroll != null) {
            grid.removeOnScrollListener(pendingFocusScroll);
            pendingFocusScroll = null;
            grid.stopScroll();
        }
    }

    private void focusTile(int position) {
        // Scrolled down to the lineup or the deck (back from a title opened there, say): the focus stays on screen.
        if (focusBelow()) return;
        libraryFocusPending = false;
        cancelPendingFocusScroll();
        if (adapter.appCount() == 0) {
            if (adapter.isLibraryAt(0)) focusLibrary();
            else firstTopButton().requestFocus();
            return;
        }
        final int pos = adapter.focusablePosition(Math.max(0, Math.min(position, adapter.appCount() - 1)));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        if (!animations) {
            grid.scrollToPosition(pos);
            grid.post(() -> {
                RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(pos);
                if (h != null) h.itemView.findViewById(R.id.card).requestFocus();
            });
            return;
        }
        pendingFocusScroll = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                if (pendingFocusScroll != this) return;
                pendingFocusScroll = null;
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        };
        grid.addOnScrollListener(pendingFocusScroll);
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    /** Some TVs (Coocaa OS) set the network time without ACTION_TIME_CHANGED, so keep checking until it is valid. */
    private final Runnable clockCheck = this::updateClockVisibility;

    private void updateClockVisibility() {
        boolean valid = System.currentTimeMillis() >= TIME_VALID_AFTER_MS;
        clockBlock.setVisibility(valid && bigClock == null ? View.VISIBLE : View.INVISIBLE);
        if (bigClock != null) bigClock.view.setVisibility(valid ? View.VISIBLE : View.INVISIBLE);
        handler.removeCallbacks(clockCheck);
        if (valid) {
            updateGreeting();
        } else {
            handler.postDelayed(clockCheck, 1_000L);
        }
    }

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        // Locale-specific day parts (app language on Android 13+).
        // Indonesian: Pagi 00-10, Siang 10-15, Sore 15-18, Malam 18-24.
        // Spain (es-ES) and Catalan: "Buenas tardes" into the evening; "Buenas noches" from 21.
        // Latin American Spanish (es-MX, es-AR, …) keeps the default hours: evening strings are also
        // «Buenas noches», and there «Buenas noches» from ~17 matches local use better than Spain's 21.
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        String lang = locale.getLanguage();
        String country = locale.getCountry();
        if ("in".equals(lang) || "id".equals(lang)) {
            if (hour < 10) res = R.string.good_morning;
            else if (hour < 15) res = R.string.good_afternoon;
            else if (hour < 18) res = R.string.good_evening;
            else res = R.string.good_night;
        } else if (("es".equals(lang) && "ES".equals(country)) || "ca".equals(lang)) {
            if (hour < 6) res = R.string.good_night;
            else if (hour < 12) res = R.string.good_morning;
            else if (hour < 21) res = R.string.good_afternoon;
            else res = R.string.good_night;
        } else {
            if (hour < 5) res = R.string.good_night;
            else if (hour < 12) res = R.string.good_morning;
            else if (hour < 17) res = R.string.good_afternoon;
            else if (hour < 22) res = R.string.good_evening;
            else res = R.string.good_night;
        }
        String festiveGreeting = festive ? festival.greetingText(getResources()) : "";
        if (!festiveGreeting.isEmpty()) {
            greeting.setText(festiveGreeting);
            greeting.setTextColor(liftTowardsWhite(festival.accentColor(), 0.25f));
        } else {
            greeting.setText(greetingName.isEmpty() ? getString(res)
                    : getString(R.string.greeting_with_name, getString(res), greetingName));
            greeting.setTextColor(greetingColor);
        }
        if (shamsi) shamsiDate.setText(Shamsi.today(getResources()));
    }

    // ---- race-weekend panel (a theme's race_calendar) -----------------------------------------------

    private static final int RACE_RED = 0xFFFF3B2F;

    /**
     * Shows or hides the race-weekend panel for this minute. Text only, set once a minute with the clock:
     * nothing animates, so the panel costs the GPU nothing between ticks.
     */
    private void updateRace() {
        if (raceCalendar.isEmpty()) return;
        RaceWeekend.State s = RaceWeekend.at(raceCalendar, java.time.Instant.now());
        if (s == null) {
            racePanel.setVisibility(View.GONE);
            return;
        }
        RaceWeekend.Race race = s.race;
        raceKicker.setText(race.place.isEmpty()
                ? getString(R.string.race_kicker_no_place, race.round, race.shortName())
                : getString(R.string.race_kicker, race.round, race.shortName(), race.place));

        if (s.live != null) {
            SpannableString live = new SpannableString("\u25CF  " + getString(R.string.race_live, s.live.title(getResources())));
            live.setSpan(new android.text.style.ForegroundColorSpan(RACE_RED), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            raceHeadline.setText(live);
        } else if (s.next != null) {
            String in = RaceWeekend.countdown(getResources(), s.untilNext);
            raceHeadline.setText(s.next.isRace() ? getString(R.string.race_lights_out, in)
                    : getString(R.string.race_next, s.next.title(getResources()), in));
        } else {
            raceHeadline.setText(R.string.race_finished);
        }

        // FP1 FRI 11:00 PM   FP2 SAT 2:30 AM ...: done sessions fade, the live one is red, the next one bright.
        java.time.format.DateTimeFormatter when = java.time.format.DateTimeFormatter.ofPattern(
                android.text.format.DateFormat.is24HourFormat(this) ? "EEE HH:mm" : "EEE h:mm a", Locale.getDefault())
                .withZone(java.time.ZoneId.systemDefault());
        android.text.SpannableStringBuilder row = new android.text.SpannableStringBuilder();
        for (int i = 0; i < race.sessions.size(); i++) {
            RaceWeekend.Session session = race.sessions.get(i);
            if (i > 0) row.append("     ");
            int start = row.length();
            row.append(session.shortName(getResources())).append("  ").append(when.format(session.start).toUpperCase(Locale.getDefault()));
            int color;
            switch (s.statuses.get(i)) {
                case DONE: color = 0x47FFFFFF; break;
                case LIVE: color = RACE_RED; break;
                case NEXT: color = 0xFFFFFFFF; break;
                default: color = 0x99FFFFFF;
            }
            row.setSpan(new android.text.style.ForegroundColorSpan(color), start, row.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        raceSessions.setText(row);
        racePanel.setVisibility(View.VISIBLE);
    }

    private void refreshPillsAfterNextDraw() {
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                // Posted from inside the frame's traversal, so it runs after that frame is drawn. A post straight
                // from onResume would run first: the frame is only scheduled after onResume returns.
                handler.post(() -> {
                    if (isDestroyed()) return;
                    updateWifiLabel();
                    updateInputsButton();
                });
                return true;
            }
        });
        root.invalidate();   // a resume that changes nothing on screen still draws once, so the refresh runs
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        boolean vpn = false, online = false;
        int icon = R.drawable.ic_wifi;
        // The diya's flame: 0 is out (offline), 1 to 4 follow the Wi-Fi signal, Ethernet burns steady at 4.
        int flame = 0;
        boolean steady = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean wifi = false, ethernet = false;
            // All networks, not just the active one: with a VPN up the active network is the VPN itself.
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = true;
                    continue;
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (!online) {
                text = getString(R.string.offline);
                vpn = false;
            } else if (ethernet) {
                text = getString(R.string.ethernet);
                icon = R.drawable.ic_ethernet;
                flame = 4;
                steady = true;
            } else if (wifi) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                flame = info != null ? Math.max(1, Math.min(4, WifiManager.calculateSignalLevel(info.getRssi(), 5))) : 3;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        // With a VPN app configured, a VPN that is down matters too: show a red "VPN off" capsule then.
        boolean vpnDown = !vpn && online && !vpnApp.isEmpty();
        if (vpn || vpnDown) {
            // Network name, then the green lock-and-VPN (or red "VPN off") capsule drawn in place of the word.
            String badge = getString(vpn ? R.string.vpn : R.string.vpn_off);
            VpnBadge span = new VpnBadge(vpn ? getDrawable(R.drawable.ic_lock) : null, density);
            // The pill has a maximum width and ellipsizes at the end, which would cut the badge off after
            // a long network name, so shorten the name to whatever room the badge leaves. The total paddings
            // include the Wi-Fi icon.
            TextPaint paint = btnWifi.getPaint();
            float room = btnWifi.getMaxWidth() - btnWifi.getTotalPaddingStart() - btnWifi.getTotalPaddingEnd()
                    - span.getSize(paint, badge, 0, badge.length(), null) - paint.measureText(" ") - 2 * density;
            String name = TextUtils.ellipsize(text, paint, Math.max(0, room), TextUtils.TruncateAt.END).toString();
            // A long badge ("VPN отключён") can leave room for a stub like "MT…", which says nothing and, after
            // rounding, can push the badge itself out of the pill. Show the badge alone then.
            // The space stays even then: a line holding only the span gets no font metrics and collapses.
            text = name.length() < text.length() && name.length() < 5 ? "" : name;
            SpannableString label = new SpannableString(text + " " + badge);
            label.setSpan(span, text.length() + 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (!TextUtils.equals(btnWifi.getText(), label)) btnWifi.setText(label);
        } else if (!TextUtils.equals(btnWifi.getText(), text)) {
            btnWifi.setText(text);
        }
        if (wifiIconOnly) {
            btnWifi.setContentDescription(btnWifi.getText());
            btnWifi.setText(null);
        }
        if (festive && diya != null) {
            if (online && flame == 0) flame = 3;   // online some other way (cellular, USB tethering)
            diya.setSteady(steady);
            diya.setFlame(flame);
        } else {
            if (icon != wifiIcon) {
                wifiIcon = icon;
                btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
            }
            ColorStateList tint = online ? wifiOnlineTint : wifiPlainTint;
            if (wifiOnlineTint != null && btnWifi.getCompoundDrawableTintList() != tint) {
                btnWifi.setCompoundDrawableTintList(tint);
            }
        }
    }

    /** config.xml plain_focus: no highlight anywhere, focus only grows (tiles, popup rows). */
    private boolean plainFocus;
    /** config.xml icon_only_buttons. */
    private boolean iconOnlyButtons;
    /** config.xml top_menu: Inputs, Wi-Fi and Settings folded into one gear. */
    private boolean topMenu;
    private boolean launcherSettingsButton;
    /** icon_only_buttons without wifi_label_width: the Wi-Fi pill shows no network name. */
    private boolean wifiIconOnly;
    /** config.xml wifi_status_color: the Wi-Fi icon's green while online and its usual pill colours; null while off. */
    private ColorStateList wifiOnlineTint, wifiPlainTint;
    /** The app library button in the top bar (library_button). */
    private View btnLibraryTop;
    /** The open library came from that button: closing it returns there instead of scrolling to the grid's end. */
    private boolean libraryFromTopBar;

    /** Turns a labelled top pill into the round TopButtonIcon shape: no text, the icon centred by even padding. */
    private void makeIconOnly(TextView pill) {
        pill.setContentDescription(pill.getText());
        pill.setText(null);
        pill.setMaxWidth(Integer.MAX_VALUE);
        int pad = Math.round(10 * density);
        pill.setPaddingRelative(pad, 0, pad, 0);
        pill.setCompoundDrawablePadding(0);
        pill.getLayoutParams().width = Math.round(42 * density);
    }

    // ---- festive skin ------------------------------------------------------------------------------

    /**
     * Swaps the pill icons for the festival's: a bell for Inputs that swings when focused, a diya for the
     * network whose flame follows the connection, and a marigold for Settings that turns when focused.
     */
    private void applyFestiveSkin(TextView inputs, TextView settings) {
        bell = new BellDrawable(density);
        marigold = new MarigoldDrawable(density);
        diya = new DiyaDrawable(density);
        bell.setReducedMotion(reducedMotion);
        marigold.setReducedMotion(reducedMotion);
        diya.setReducedMotion(reducedMotion);
        setFestiveIcon(inputs, bell);
        setFestiveIcon(btnWifi, diya);
        setFestiveIcon(settings, marigold);
        addFocusAction(inputs, bell::ring);
        addFocusAction(settings, marigold::turn);
    }

    private static void setFestiveIcon(TextView pill, Drawable icon) {
        // The pills tint their icons to follow focus; the festive ones keep their own colours.
        pill.setCompoundDrawableTintList(null);
        pill.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
    }

    /** Runs {@code action} when the view gains focus, keeping any focus listener it already had. */
    private static void addFocusAction(View view, Runnable action) {
        View.OnFocusChangeListener previous = view.getOnFocusChangeListener();
        view.setOnFocusChangeListener((v, focused) -> {
            if (previous != null) previous.onFocusChange(v, focused);
            if (focused) action.run();
        });
    }

    private void playPetalShower() {
        if (isFinishing() || isDestroyed()) return;
        PetalShowerView shower = new PetalShowerView(this);
        root.addView(shower, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        shower.play(60, 2200, () -> root.removeView(shower));
    }

    private static int liftTowardsWhite(int argb, float amount) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        r += (int) ((255 - r) * amount);
        g += (int) ((255 - g) * amount);
        b += (int) ((255 - b) * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** The network pill opens the VPN app when one is configured and installed, otherwise Wi-Fi settings. */
    private void openNetworkPill() {
        Intent i = vpnApp.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(vpnApp);
        if (i == null && !vpnApp.isEmpty()) i = getPackageManager().getLaunchIntentForPackage(vpnApp);
        open(i != null ? i : systemIntent(Settings.ACTION_WIFI_SETTINGS));
    }


    /** Every settings entry shares the standard intent and the vendor fallback (SystemSettings). */
    private Intent systemIntent(String standardAction) {
        return SystemSettings.intent(this, standardAction);
    }

    /** Hides the Inputs pill when it would have nothing to list: streaming boxes have no HDMI-in, AV or tuner. */
    private void updateInputsButton() {
        if (!showInputsPill) {
            btnInputs.setVisibility(View.GONE);
            return;
        }
        boolean any = liveTvIntent() != null || !sourcePanelService.isEmpty()
                || (coocaaSources && !CoocaaSources.list(this).isEmpty());
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (!any && tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                // Inputs hidden in Settings > Inputs are left out of the menu, so they don't count here either.
                if (info.isPassthroughInput() && !info.isHidden(this)) {
                    any = true;
                    break;
                }
            }
        }
        btnInputs.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    /** boot_to_live_tv: the first time Home comes up after the TV starts, open the Live TV app instead. Once per
     *  boot, keyed on Settings.Global.BOOT_COUNT, so a later Home press stays on Home. */
    private boolean openLiveTvAfterBoot() {
        if (!bootToLiveTv) return false;
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        SharedPreferences prefs = getSharedPreferences("boot", MODE_PRIVATE);
        if (boot < 0 || prefs.getInt("live_tv_boot", -1) == boot) return false;
        prefs.edit().putInt("live_tv_boot", boot).apply();
        Intent i = liveTvIntent();
        if (i == null) return false;
        open(i);
        return true;
    }

    /** The Live TV app's launch intent: its leanback entry point, or the configured action if it has none. */
    private Intent liveTvIntent() {
        if (liveTvPkg.isEmpty()) return null;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (i != null || liveTvAction.isEmpty()) return i;
        Intent byAction = new Intent(liveTvAction).setPackage(liveTvPkg);
        return getPackageManager().resolveActivity(byAction, 0) != null ? byAction : null;
    }

    /** The TV's own source-selection panel, for TVs that have no TvInputManager (TCL Android TV 9): a
     *  service, or an activity on sets whose panel is a dialog (BYINTEK U4's QuickSourceActivity). */
    private void openSourcePanel() {
        Intent i = new Intent(sourcePanelAction);
        ComponentName c = ComponentName.unflattenFromString(sourcePanelService);
        if (c != null) i.setComponent(c);
        int eq = sourcePanelExtra.indexOf('=');
        if (eq > 0) {
            try {
                i.putExtra(sourcePanelExtra.substring(0, eq).trim(),
                        Integer.parseInt(sourcePanelExtra.substring(eq + 1).trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        try {
            if (getPackageManager().resolveActivity(i, 0) != null) {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
            startService(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** `input_sources` in config.xml: "HW5=23" maps the framework input whose id ends in /HW5 to a vendor
     *  source number; "HW8=-" leaves that input out of the Inputs menu (a port the board reports but the set
     *  does not have). Returns the number, -1 for a left-out input, or null when the input is not listed. */
    private Integer mappedSource(String inputId) {
        if (inputId == null) return null;
        String hw = inputId.substring(inputId.lastIndexOf('/') + 1);
        for (String m : getResources().getStringArray(R.array.input_sources)) {
            int eq = m.indexOf('=');
            if (eq <= 0 || !m.substring(0, eq).trim().equals(hw)) continue;
            String v = m.substring(eq + 1).trim();
            if (v.equals("-")) return -1;
            try { return Integer.parseInt(v); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    /** Switches to a passthrough input (HDMI, AV). An input mapped in `input_sources` is opened by sending
     *  `input_action` with its source number as the int extra "input" (Xiaomi China firmware, whose tvplayer
     *  answers com.xiaomi.mitv.tvplayer.EXTSRC_PLAY and nothing else). Otherwise the standard way is
     *  ACTION_VIEW on its channel URI, which the Live TV app handles; where nothing takes that, the
     *  configured `input_action` is sent with the same URI instead (XGIMI's HDMI player only answers
     *  com.xgimi.action.hdmiPlayer). With `input_uri_scheme` set (YaOS: live-tv) the same URI goes out
     *  with that scheme and no MIME type. A player guarded by a permission this build does not declare
     *  throws SecurityException, which used to take Home down with it (YaOS before the manifest asked
     *  for ACCESS_TV_SOURCE). */
    private void openInput(String inputId) {
        Integer source = mappedSource(inputId);
        if (source != null && source >= 0 && !inputAction.isEmpty()) {
            try {
                startActivity(new Intent(inputAction).putExtra("input", source.intValue())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                // fall through to the standard way
            }
        }
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = (inputUriScheme.isEmpty()
                ? new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                : new Intent(Intent.ACTION_VIEW, uri.buildUpon().scheme(inputUriScheme).build()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(view);
            return;
        } catch (ActivityNotFoundException | SecurityException e) {
            if (inputAction.isEmpty()) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        open(new Intent(inputAction).setDataAndType(uri, "vnd.android.cursor.item/channel"));
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        // A configured source panel and no Live TV row to offer next to it: the panel is the whole menu.
        // The BYINTEK U4 needs this, as its TvInputManager lists three HDMI ports and an AV input it doesn't have.
        List<CoocaaSources.Source> coocaa = coocaaSources ? CoocaaSources.list(this) : Collections.emptyList();
        if (coocaa.isEmpty() && !sourcePanelService.isEmpty() && liveTvIntent() == null) {
            openSourcePanel();
            return;
        }
        // top_menu: the pill is gone, so the list hangs under the gear, growing inward from the right.
        // Read the switch, not the pill: show_inputs_pill=false hides the pill too, and there the list
        // keeps its usual place.
        boolean underGear = topMenu;
        View anchor = findViewById(underGear ? R.id.btnSettings : R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = glassPopup(panel, width);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        boolean tunerIsHw = false;
        boolean tunerIsAnalog = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // An HDMI-CEC device (an Apple TV, a soundbar) gets an input of its own whose parent is the
            // port it sits on, so the port and the device would each get a row (Vestel MT7332: "HDMI 1"
            // and "HDMI Apple TV"). The device takes its port's row instead, and a bare port is listed
            // only when nothing on it speaks CEC.
            Set<String> inputIds = new HashSet<>();
            for (TvInputInfo info : inputs) inputIds.add(info.getId());
            Map<String, List<TvInputInfo>> cecDevices = new HashMap<>();
            for (TvInputInfo info : inputs) {
                String parent = info.getParentId();
                if (parent == null || !inputIds.contains(parent)) continue;
                if (!info.isPassthroughInput() || info.isHidden(this)) continue;
                List<TvInputInfo> onPort = cecDevices.get(parent);
                if (onPort == null) cecDevices.put(parent, onPort = new ArrayList<>());
                onPort.add(info);
            }
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                if (info.getParentId() != null && inputIds.contains(info.getParentId())) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (liveTvInputId.isEmpty() || liveTvInputId.equals(info.getId()))) {
                    // Prefer a hardware tuner: the framework names those ".../HW<n>". System apps such as
                    // AirPlay also register software inputs of type TUNER and may sort first (seen on TCL G05).
                    // Among tuners of the same kind, skip an analog input when another is present.
                    // MediaTek names them AnalogInputService and TunerInputService; alphabetical order
                    // would pick the analog one, and Live TV then opens a dead ATV source.
                    boolean hw = info.getId().matches(".*/HW\\d+$");
                    boolean analog = info.getId().toLowerCase(Locale.ROOT).contains("analog");
                    boolean better = tunerId == null
                            || (hw && !tunerIsHw)
                            || (hw == tunerIsHw && tunerIsAnalog && !analog)
                            || (hw == tunerIsHw && analog == tunerIsAnalog
                                    && info.getId().compareTo(tunerId) < 0);
                    if (better) {
                        tunerId = info.getId();
                        tunerIsHw = hw;
                        tunerIsAnalog = analog;
                    }
                }
                // Inputs hidden in the TV's Settings > Inputs stay out of the menu too.
                if (!info.isPassthroughInput() || info.isHidden(this)) continue;
                // input_sources can leave out ports the board reports but the set does not have ("HW8=-").
                if (Integer.valueOf(-1).equals(mappedSource(info.getId()))) continue;
                // A CEC device's row is added with its port, below.
                if (info.getParentId() != null && inputIds.contains(info.getParentId())) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                CharSequence custom = info.loadCustomLabel(this);
                boolean named = custom != null && custom.length() > 0 && !custom.toString().equals(label);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                List<TvInputInfo> devices = cecDevices.get(info.getId());
                if (devices != null) {
                    // "Apple TV · HDMI 1": the device's own name (or the one given to it or to its port in
                    // Settings > Inputs), then the port. Opening the device's input also makes it the CEC
                    // active source, which wakes it.
                    for (TvInputInfo device : devices) {
                        CharSequence name = device.loadCustomLabel(this);
                        if (name == null || name.length() == 0) name = named ? custom : device.loadLabel(this);
                        String deviceLabel = name == null || name.length() == 0 ? label : name + " \u00B7 " + label;
                        final String deviceId = device.getId();
                        int state = hdmiStateBogus ? -1 : tim.getInputState(deviceId);
                        addInputRow(rows, popup, R.drawable.ic_input, deviceLabel, state, () -> openInput(deviceId));
                    }
                    continue;
                }
                // The name given in Settings > Inputs (e.g. "MacBook Pro") goes first, the port after it.
                if (named) label = custom + " \u00B7 " + label;
                final String id = info.getId();
                // Analog AV jacks have no hotplug detection, and some TVs report them as always connected
                // (TCL Smart TV Pro G03 says "Connected" with nothing plugged in), so AV rows show no state.
                int state = !hdmi || hdmiStateBogus ? -1 : tim.getInputState(id);
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> openInput(id));
            }
        }
        // Coocaa OS registers nothing with TvInputManager; com.skyworth.tv lists its sources instead.
        if (rows.getChildCount() == 0) {
            for (CoocaaSources.Source s : coocaa) {
                int icon = "HDMI".equals(s.name) ? R.drawable.ic_input
                        : "VOD".equals(s.name) ? R.drawable.ic_livetv : R.drawable.ic_av;
                addInputRow(rows, popup, icon, s.label, -1, () -> {
                    if (!CoocaaSources.open(this, s)) {
                        Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }
        // Nothing to list where there is no TvInputManager: the TV's own source panel switches inputs.
        if (rows.getChildCount() == 0 && !sourcePanelService.isEmpty()) {
            addInputRow(rows, popup, R.drawable.ic_input, getString(R.string.inputs), -1, this::openSourcePanel);
        }
        Intent liveTv = liveTvIntent();
        if (liveTv != null && tunerId != null) {
            // Tune that input only when the Live TV package itself handles the URI.
            // The unrestricted input player also resolves it (that is how HDMI opens), but on the
            // AIWA AI PONT SA that player stays black, and the Live TV package does not handle the
            // URI at all, which used to toast "Not available on this TV". Keep the leanback activity
            // then: it is the same path as the Live TV tile. Do not drop the package restriction.
            // An explicit tuner uses its channel collection (e.g. DVB-S rather than DVB-T/C).
            // Keep the legacy passthrough route for automatic selection: some OEM players need it.
            // If the configured input is absent, tunerId stays null and the app opens normally.
            boolean configured = !liveTvInputId.isEmpty();
            Intent tuned = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(configured ? TvContract.buildChannelsUriForInput(tunerId)
                                    : TvContract.buildChannelUriForPassthroughInput(tunerId),
                            configured ? TvContract.Channels.CONTENT_TYPE : TvContract.Channels.CONTENT_ITEM_TYPE)
                    .setPackage(liveTvPkg);
            if (tuned.resolveActivity(getPackageManager()) != null) liveTv = tuned;
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, Math.min(panel.getMeasuredWidth(), (int) (360 * density))));

        int alignment = getResources().getBoolean(R.bool.rtl_menu_bar) != underGear ? Gravity.END : Gravity.START;
        dropIn(popup, panel, anchor, alignment);
        rows.getChildAt(0).requestFocus();
    }

    private PopupWindow glassPopup(View panel, int width) {
        if (plainFocus) {
            panel.setBackground(null);
            panel.setElevation(0f);
        }
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setOnDismissListener(() -> {
            if (openPopup == popup) openPopup = null;
        });
        openPopup = popup;
        return popup;
    }

    /** Drop in: fade and unfold from the top edge, hanging under the anchor pill. */
    private void dropIn(PopupWindow popup, View panel, View anchor, int gravity) {
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), gravity);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(reducedMotion ? Motion.REDUCED_MS : 220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    private void addSeparator(LinearLayout rows) {
        if (plainFocus) return;
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        plainRow(row);
        rows.addView(row);
    }

    /** plain_focus: a popup row as white text with no box behind it; focus grows it from its leading edge. */
    private void plainRow(View row) {
        if (!plainFocus) return;
        row.setBackground(null);
        ColorStateList white = ColorStateList.valueOf(Color.WHITE);
        ((ImageView) row.findViewById(R.id.icon)).setImageTintList(white);
        ((TextView) row.findViewById(R.id.title)).setTextColor(white);
        ((TextView) row.findViewById(R.id.status)).setTextColor(0x99FFFFFF);
        boolean rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
        row.setOnFocusChangeListener((v, focused) -> {
            v.setPivotX(rtl ? v.getWidth() : 0f);
            grow(v, focused);
        });
    }

    /** The one focus state of bare_buttons and plain_focus: a 10% grow over 180 ms, eased out (focus_grow, focus_grow_ms). */
    private void grow(View v, boolean focused) {
        float s = focused ? growScale : 1f;
        v.animate().scaleX(s).scaleY(s).setDuration(growMs).setInterpolator(new DecelerateInterpolator()).start();
    }

    // ---- launcher settings and module cards -------------------------------------------------------

    /** top_menu: the gear's dropdown, standing in for the Inputs, Wi-Fi and Settings pills. */
    private void showTopMenu() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (220 * density));
        // Inputs opens its own list once this one has closed.
        addInputRow(rows, popup, R.drawable.ic_input, getString(R.string.inputs), -1, () -> root.post(this::showInputs));
        addInputRow(rows, popup, R.drawable.ic_wifi, getString(R.string.quick_network), -1, this::openNetworkPill);
        CharSequence network = wifiIconOnly ? btnWifi.getContentDescription() : btnWifi.getText();
        if (!TextUtils.isEmpty(network)) {
            TextView status = rows.getChildAt(rows.getChildCount() - 1).findViewById(R.id.status);
            status.setText(network);
            status.setVisibility(View.VISIBLE);
        }
        if (launcherSettingsButton) {
            addInputRow(rows, popup, R.drawable.ic_home_settings_pill, getString(R.string.launcher_settings), -1,
                    () -> startActivityForResult(new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST));
        } else {
            addInputRow(rows, popup, R.drawable.ic_settings, getString(R.string.settings), -1,
                    () -> open(systemIntent(Settings.ACTION_SETTINGS)));
        }
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max((int) (220 * density), Math.min(panel.getMeasuredWidth(), (int) (360 * density))));
        dropIn(popup, panel, anchor, getResources().getBoolean(R.bool.rtl_menu_bar) ? Gravity.START : Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    /** The header opens launcher settings directly; hold OK keeps the extra home actions available. */
    private void showSettingsMenu() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (200 * density));
        if (!launcherSettingsButton) addSettingsMenuRow(rows, popup, R.string.launcher_settings, () -> startActivityForResult(
                new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST));
        if (moduleCards) addSettingsMenuRow(rows, popup, R.string.module_cards, this::showModuleCards);
        if (dockRow) addSettingsMenuRow(rows, popup, R.string.dock_edit, this::startDockEdit);
        addSettingsMenuRow(rows, popup, R.string.reorder, this::toggleReorder);
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max((int) (200 * density), panel.getMeasuredWidth()));
        // Hebrew puts Settings at the left of the header: grow the menu inward from it.
        dropIn(popup, panel, anchor, getResources().getBoolean(R.bool.rtl_menu_bar)
                ? Gravity.START : Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    /**
     * Hold OK on a tile: a glass menu next to it. Move starts reorder mode with this tile already picked up;
     * Remove from Home takes the shortcut off (only with the app library on, where one press adds it back);
     * App info opens the TV's page for the app, which is what holding OK used to do.
     */
    @Override
    public void onTileLongPress(int position, View card) {
        if (reordering) return;
        String key = adapter.keyAt(position);
        String pkg = adapter.packageAt(position);
        // Empty dock slots and the Default-home tile carry the launcher's own package: no menu for them.
        if (key == null || pkg == null || pkg.equals(getPackageName())) return;
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (200 * density));
        if (dockRow && !iconDock && position < adapter.movableCount()) {
            boolean inDock = position < COLUMNS;
            addSettingsMenuRow(rows, popup, inDock ? R.string.tile_replace_in_dock : R.string.tile_add_to_dock,
                    () -> startDockEditFor(key, inDock));
        }
        if (!iconDock && position < adapter.movableCount()) addSettingsMenuRow(rows, popup, R.string.tile_move, () -> {
            if (!beginReorder()) return;
            int at = adapter.positionOf(key);   // beginReorder can rebuild the tiles
            if (at < 0) return;
            focusTile(at);
            pickUp(at);
            Toast.makeText(this, R.string.reorder_hint, Toast.LENGTH_LONG).show();
        });
        if (appLibraryEnabled) addSettingsMenuRow(rows, popup, R.string.library_remove_shortcut, () -> {
            if (LibraryApps.save(this, pkg, false)) reloadApps(false);
            else Toast.makeText(this, R.string.library_save_failed, Toast.LENGTH_LONG).show();
        });
        addSettingsMenuRow(rows, popup, R.string.library_app_info, () -> open(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))));
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max((int) (200 * density), panel.getMeasuredWidth()));
        // Dock tiles sit near the bottom of the screen: open the menu above the tile when it would not fit below.
        int[] at = new int[2];
        card.getLocationInWindow(at);
        int gap = (int) (10 * density), height = panel.getMeasuredHeight();
        if (at[1] + card.getHeight() + gap + height <= root.getHeight()) {
            dropIn(popup, panel, card, Gravity.START);
        } else {
            panel.setAlpha(0f);
            panel.setPivotY(height);
            panel.setScaleY(0.92f);
            panel.setTranslationY(8 * density);
            popup.showAtLocation(root, Gravity.NO_GRAVITY, at[0], Math.max(0, at[1] - gap - height));
            panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        }
        rows.getChildAt(0).requestFocus();
    }

    private void addSettingsMenuRow(LinearLayout rows, PopupWindow popup, int title, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        plainRow(row);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        // The OK-up after opening the menu must not activate its first row.
        final boolean[] pressed = {false};
        row.setOnKeyListener((v, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) pressed[0] = true;
                return true;
            }
            boolean activate = pressed[0];
            pressed[0] = false;
            if (activate) {
                popup.dismiss();
                action.run();
            }
            return true;
        });
        rows.addView(row);
    }

    /**
     * Asks about the next card that has no decision for its current signing key. One prompt at a
     * time, only over a laid out grid with window focus (not under a popup or another app), and
     * the next one follows as soon as this one is answered. Nothing is shown until the owner says Show.
     */
    private void showCardPrompt() {
        if (!moduleCards || !tilesReady || promptsPaused || cardPrompt != null || !hasWindowFocus() || isFinishing()
                || reordering) return;
        if (cards.isEmpty() || !CardFeature.working()) return;
        CardFeature.run(() -> {
            showCardPromptNow();
            return null;
        }, null);
    }

    private void showCardPromptNow() {
        final Cards.Card card = Cards.firstPending(cards);
        if (card == null) return;

        View panel = getLayoutInflater().inflate(R.layout.dialog_card_prompt, root, false);
        ((TextView) panel.findViewById(R.id.title)).setText(getString(R.string.card_prompt_title, card.appLabel));
        ((TextView) panel.findViewById(R.id.body)).setText(card.state == Cards.State.RESIGNED
                ? getString(R.string.card_prompt_resigned, card.appLabel)
                : card.state == Cards.State.RELABELLED
                ? getString(R.string.card_prompt_relabelled, card.appLabel)
                : getString(R.string.card_prompt_body, card.appLabel));
        TextView names = panel.findViewById(R.id.names);
        if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) names.setVisibility(View.GONE);
        else names.setText(getString(R.string.card_prompt_names, card.label, card.appLabel));
        ((TextView) panel.findViewById(R.id.identity)).setText(
                getString(R.string.card_prompt_identity, card.pkg, Cards.shortDigest(card.digest)));
        TextView keep = panel.findViewById(R.id.btnKeep);
        TextView show = panel.findViewById(R.id.btnShow);

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(false);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (440 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.6f);
        }
        // One decision per prompt: the buttons and Back (cancel) all end here.
        final boolean[] decided = {false};
        View.OnClickListener choose = v -> {
            if (decided[0]) return;
            decided[0] = true;
            cardPrompt = null;
            dialog.dismiss();
            decideCard(card, v.getId() == R.id.btnShow);   // guards its own card calls; the tile reload stays outside
            restoreFocus();   // the tiles changed under the prompt; this also chains the next prompt
        };
        keep.setOnClickListener(choose);
        show.setOnClickListener(choose);
        dialog.setOnCancelListener(d -> choose.onClick(keep));
        dialog.setOnDismissListener(d -> {
            if (cardPrompt == dialog) cardPrompt = null;
        });
        // Left and Right move between the two buttons; Up and Down stay put; the ends don't wrap.
        View.OnKeyListener keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == R.id.btnKeep;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnShow;
        };
        keep.setOnKeyListener(keys);
        show.setOnKeyListener(keys);
        cardPrompt = dialog;
        dialog.show();
        keep.requestFocus();
    }

    /** Writes the owner's decision and rebuilds the tiles; focus is left where it is. */
    private void decideCard(Cards.Card card, boolean show) {
        boolean saved = CardFeature.run(() -> Cards.decide(Cards.storeFile(this), card, show), false);
        if (!saved) {
            Toast.makeText(this, R.string.card_not_saved, Toast.LENGTH_SHORT).show();
            promptsPaused = true;
        }
        reloadTiles();
    }

    /** Dropdown under the Settings pill (hold OK): every discovered card, its package and key; OK toggles Shown and Hidden. */
    private void showModuleCards() {
        if (cards.isEmpty() || !CardFeature.working()) {
            Toast.makeText(this, R.string.no_module_cards, Toast.LENGTH_SHORT).show();
            return;
        }
        CardFeature.run(() -> {
            showModuleCardsNow();
            return null;
        }, null);
    }

    private void showModuleCardsNow() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_cards, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (400 * density));
        List<Cards.Card> listed = new ArrayList<>(cards);
        Collections.sort(listed, Cards::byLabel);
        for (Cards.Card card : listed) {
            View row = getLayoutInflater().inflate(R.layout.item_card_row, rows, false);
            row.setTag(card.pkg);
            ((TextView) row.findViewById(R.id.title)).setText(card.label);
            TextView app = row.findViewById(R.id.app);
            if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) app.setVisibility(View.GONE);
            else app.setText(getString(R.string.card_app_name, card.appLabel));
            ((TextView) row.findViewById(R.id.pkg)).setText(card.pkg);
            ((TextView) row.findViewById(R.id.digest)).setText(Cards.longDigest(card.digest));
            final String pkg = card.pkg;
            row.setOnClickListener(v -> CardFeature.run(() -> {
                // The list stays open; the decision is for the card as it is now, not as it was listed.
                Cards.Card now = cardFor(pkg);
                if (now == null) return null;
                decideCard(now, now.state != Cards.State.SHOWN);
                // Every row, not only this one: a toggle can move another card across the limit of 8.
                updateCardStates(rows);
                return null;
            }, null));
            rows.addView(row);
        }
        updateCardStates(rows);
        // Scroll inside the panel instead of running off the bottom of a 720p screen.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        int room = root.getHeight() - (at[1] + anchor.getHeight()) - (int) (34 * density);
        popup.setHeight(Math.min(panel.getMeasuredHeight(), Math.max(room, (int) (120 * density))));
        // Hebrew places Settings on the left: align the outer panel inward, like its parent menu.
        dropIn(popup, panel, anchor, getResources().getBoolean(R.bool.rtl_menu_bar)
                ? Gravity.START : Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private Cards.Card cardFor(String pkg) {
        for (Cards.Card c : cards) if (c.pkg.equals(pkg)) return c;
        return null;
    }

    /** Writes the state of every row in the Module cards list from the current card list. */
    private void updateCardStates(LinearLayout rows) {
        List<Cards.Card> drawn = Cards.shown(cards);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            Cards.Card card = row.getTag() instanceof String ? cardFor((String) row.getTag()) : null;
            ((TextView) row.findViewById(R.id.state)).setText(card != null ? stateText(card, drawn.contains(card)) : "");
        }
    }

    /** The row's state: a card the owner set to Shown but which is beyond the eight drawn says so. */
    private String stateText(Cards.Card card, boolean drawn) {
        switch (card.state) {
            case SHOWN:
                return getString(drawn ? R.string.card_state_shown : R.string.card_state_over_limit);
            case HIDDEN:
                return getString(R.string.card_state_hidden);
            case RESIGNED:
                return getString(R.string.card_state_new_key);
            case RELABELLED:
                return getString(R.string.card_state_new_label);
            default:
                return getString(R.string.card_state_new);
        }
    }
}
