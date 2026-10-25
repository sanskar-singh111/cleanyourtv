# Switches on a TV

Every key a theme can set (the Keys table in [`themes/README.md`](../themes/README.md)) gets a
screenshot of what it does on a real TV. This table is where they are listed: the repo and
[cleanyour.tv/addons#switches](https://cleanyour.tv/addons#switches) both show them from here.

## Adding a key

A new key needs a screenshot. Put it in `docs/switches/<key>.jpg` (an existing screenshot in the
repo that shows the key at work also counts) and add a row below:

- From a real TV, with the key set, as a JPEG or PNG, 16:9 (1280×720 is plenty), under 300 KB.
- Show where the key changes something you can see. For a key that maps a remote button, show the
  screen the button opens; for one that changes behaviour only, show the setting's effect, such as
  the panel, the menu or the app that comes up.
- Blur anything personal: Wi-Fi names, people, addresses.

`tests/test_switches.py` checks that every key has a row here with an image that exists and fits
the rules. Keys from before this rule are on its wanted list; a screenshot for one of them is welcome.

## Screenshots

| Key | Screenshot |
|---|---|
| `columns` | <img src="../themes/six-columns/screenshot.jpg" width="320" alt="columns on a TV"> |
| `sky_intensity` | <img src="sky-sunset.jpg" width="320" alt="sky_intensity on a TV"> |
| `wallpaper_rotation_minutes` | <img src="../themes/scenes/screenshot.jpg" width="320" alt="wallpaper_rotation_minutes on a TV"> |
| `festival` | <img src="../themes/dussehra/screenshot.jpg" width="320" alt="festival on a TV"> |
| `settings_key_panel` | <img src="quick-settings-panel.jpg" width="320" alt="settings_key_panel on a TV"> |
| `home_idle_clock_minutes` | <img src="clock-screensaver.png" width="320" alt="home_idle_clock_minutes on a TV"> |
| `clock_only` | <img src="../themes/sanskar/screenshot.jpg" width="320" alt="clock_only on a TV"> |
| `bare_buttons` | <img src="../themes/sanskar/screenshot.jpg" width="320" alt="bare_buttons on a TV"> |
| `plain_focus` | <img src="switches/plain_focus.jpg" width="320" alt="plain_focus on a TV"> |
| `more_apps` | <img src="switches/more_apps.jpg" width="320" alt="more_apps on a TV"> |
| `idle_fade_minutes` | <img src="switches/idle_fade_minutes.jpg" width="320" alt="idle_fade_minutes on a TV: Home faded to the black background"> |
| `bottom_shade` | <img src="switches/personal_home.jpg" width="320" alt="bottom_shade on a TV: the dock row on a dark gradient"> |
| `weather_city` | <img src="switches/personal_home.jpg" width="320" alt="weather_city on a TV: an outline sun and the temperature beside the clock"> |
| `dock_logos` | <img src="switches/personal_home.jpg" width="320" alt="dock_logos on a TV: white logos floating on the wallpaper"> |
| `dock_logo_size` | <img src="switches/personal_home.jpg" width="320" alt="dock_logo_size on a TV"> |
| `dock_taglines` | <img src="switches/personal_home.jpg" width="320" alt="dock_taglines on a TV: a line above the dock for the focused app"> |
| `app_colors` | <img src="switches/personal_home.jpg" width="320" alt="app_colors on a TV: the glow in YouTube's red"> |
| `tagline_align` | <img src="switches/personal_home.jpg" width="320" alt="tagline_align on a TV: the line starting at the dock's first logo"> |
| `dock_bottom_room` | <img src="switches/personal_home.jpg" width="320" alt="dock_bottom_room on a TV: the dock low on the screen"> |
| `scroll_dim` | <img src="switches/scroll_dim.jpg" width="320" alt="scroll_dim on a TV: More apps over a darker wallpaper"> |
| `home_returns_to_last` | <img src="switches/home_returns_to_last.jpg" width="320" alt="home_returns_to_last on a TV: Home from Netflix lands on Netflix"> |
| `strict_dock_nav` | <img src="switches/strict_dock_nav.jpg" width="320" alt="strict_dock_nav on a TV: Right on the last app keeps focus there"> |
| `more_apps_names` | <img src="switches/more_apps_names.jpg" width="320" alt="more_apps_names on a TV: More apps with the art alone"> |
| `tagline_rotate` | <img src="switches/tagline_rotate.jpg" width="320" alt="tagline_rotate on a TV: YouTube's second line after focus came back to it, with the glow behind the line"> |
| `glow_strength` | <img src="switches/glow_strength.jpg" width="320" alt="glow_strength on a TV: glow_strength 90 makes the horizon glow in YouTube's red much stronger"> |
| `glow_size` | <img src="switches/glow_strength.jpg" width="320" alt="glow_size on a TV: glow_size 150 spreads the horizon glow over the whole screen"> |
| `focus_grow` | <img src="switches/glow_strength.jpg" width="320" alt="focus_grow on a TV: the focused YouTube logo grown 20 percent"> |
| `focus_grow_ms` | <img src="switches/glow_strength.jpg" width="320" alt="focus_grow_ms on a TV: the focused YouTube logo after its grow has finished"> |
| `screensaver` | <img src="switches/screensaver.jpg" width="320" alt="screensaver on a TV: clock_weather, a big clock and the temperature over the dimmed wallpaper"> |
| `screensaver_dim` | <img src="switches/screensaver_dim.jpg" width="320" alt="screensaver_dim on a TV: the clock screensaver over the wallpaper darkened 40 percent"> |
| `screensaver_fade_ms` | <img src="switches/screensaver_dim.jpg" width="320" alt="screensaver_fade_ms on a TV: the clock screensaver once Home has faded into it"> |
| `screensaver_slide_minutes` | <img src="switches/screensaver_slide_minutes.jpg" width="320" alt="screensaver_slide_minutes on a TV: the slideshow's second picture a minute after the first"> |
| `watchlist` | <img src="switches/watchlist.jpg" width="320" alt="watchlist on a TV: the lineup under the dock, The Flash selected in colour with a red glow and the Prime Video logo, the rest in black and white"> |
| `watchlist_items` | <img src="switches/watchlist.jpg" width="320" alt="watchlist_items on a TV: seven cut-out characters in two rows, the selected one's scene behind them"> |
| `watchlist_heading` | <img src="switches/watchlist.jpg" width="320" alt="watchlist_heading on a TV: the small Watchlist label at the top left of the lineup"> |
