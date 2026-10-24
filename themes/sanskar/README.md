# sanskar

My home screen for the Xiaomi MiTV-MOOQ3: my wallpaper, the time and the weather top left, and one row of white
logos floating on the wallpaper, with a line above the focused one and a glow in its colour.

Everything personal lives in this folder, so a change here needs no rebuild of the launcher:

| What | Where |
|---|---|
| Settings, dock order, taglines, colours, weather city | `config.json` |
| Wallpaper | `wallpaper/wallpaper.png` |
| Dock logos | `logos/` (white on a transparent background, PNG) |
| Watchlist pictures, later | `watchlist/` |

My original files stay in `~/Code/TV/tv-assets`; only the copies here are used.

## Push a change to the TV

After any change below, run this one command from the repository folder (`~/Code/TV/cleanyourtv`):

```bash
TV_HOST=127.0.0.1 TV_PORT=15555 tools/theme.sh themes/sanskar
```

It copies the folder to the TV and restarts Home. Keep `tools/relay.py` running, as for everything else.

If something in `config.json` is wrong, Home still starts: a bad value or a missing picture is skipped and the rest
keeps working. Only a file that is not valid JSON at all is refused, and `theme.sh` says so before it touches the TV.

## Change the wallpaper

1. Put the new picture in `wallpaper/`, for example `wallpaper/beach.png` (1920×1080 looks best).
2. In `config.json`, change `"wallpaper": "wallpaper/wallpaper.png"` to `"wallpaper": "wallpaper/beach.png"`.
   Or keep the line and save the new picture as `wallpaper/wallpaper.png`.
3. Push.

File names may use letters, digits, `.`, `-` and `_`, with no spaces.

## Change a logo

1. Save the logo as a PNG with a transparent background, white on transparent, in `logos/`. Keeping the old name
   (for example `logos/netflix.png`) is all it takes.
2. If you used a new name, change it next to the app in `"dock_logos"`.
3. Push.

You don't need to crop or resize: the launcher trims empty edges and sizes every logo by how heavy it looks, so a thin
logo and a solid one come out equally big. `"dock_logo_size": 100` makes them all bigger (`120`) or smaller (`80`).

## Change a tagline

In `"dock_taglines"`, edit the text next to the app, keeping the quotes:

```json
"com.netflix.ninja": ["Say my name.", "Friends don't lie.", "Bella ciao."]
```

An app with several lines shows the next one each time you come back to Home. Push.

## Change the dock order

`"dock_apps"` is the order, left to right. Move a line up or down, keeping a comma after every line but the last.
The first five are the dock; the rest come first in More apps. Push.

## Change the weather city

Change `"weather_city": "New Delhi, Delhi, India"` to another place, for example `"Pune, Maharashtra, India"`.
The name alone works for most places; the region and country pick the right one when the name is shared. Push. The
place name never shows on screen, and `""` turns the weather off.

## Other things you can change here

- `"app_colors"`: the glow colour behind each app, as `#RRGGBB`.
- `"bottom_shade"`: how dark the bottom of the screen is behind the dock, 0 to 100.
- `"scroll_dim"`: how much darker the wallpaper gets as you scroll down More apps, 0 to 90.

Every key is described in [`themes/README.md`](../README.md).
