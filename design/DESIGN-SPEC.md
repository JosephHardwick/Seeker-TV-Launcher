# Seeker TV launcher: design spec (approved v3, 2026-10-21)

Source of truth for the look and behavior: `design/seeker-tv-mockup-v3.html`. Open it in a browser. The dot font, icon bitmaps, brand colors, layout coordinates, wheel math, and animation timings are all in that file's JavaScript; port them, don't reinvent them.

## Screen (1920x1080 reference, scale by density)
- Background #0B0C0E with a faint dot grid (24 px pitch, dot color #16181C).
- Top right, right-aligned at x=1770, starting y=120, stacked: greeting (cell 12) / time (cell 11) / date (cell 5, color #8B9098) / weather icon 9x9 (cell 7) + temperature (cell 7). Gaps as in the mockup.
- Left: the app wheel, x=150, centered vertically on y=540. Selected app at center. Spacing 118 px per slot. Scale = max(0.42, 1 - 0.14*|offset|), x curve = 14*offset^2, opacity 1 / 0.78 / 0.56 / 0 for |offset| 0/1/2/3+. Transition 300 ms, ease (.2,.7,.2,1).
- Cursor dot at x=96 on the selected row, in the app's brand color with a soft glow. Brand-color radial glow bleeding in from the left edge (14% tint).
- Bottom right: settings gear 9x9 dot icon (cell 8) at x=1698, y=858. Dim (#3B3F46) until focused, then #E8E9EB and the glow moves to that corner.
- No on-screen instructions.

## Dot matrix
- 5x7 glyph font, pitch = cell, lit dot radius 0.36*cell, unlit dot radius 0.25*cell in #2A2D33. Glyphs, 9x9 weather icons (sun, cloud, rain, snow, storm), gear, and bell bitmaps are in the mockup JS.
- App names are dot matrix too, cell 10, uppercase. Idle names #70757E, far names #3B3F46, selected name in brand color with the unlit dots shown faintly (white at 7%).
- Two-tone names: YouTube is "YOU" #FF0000 + "TUBE" #FFFFFF when selected. Support a `parts` list per app.

## Brand colors (fallback: dominant color of the app's icon)
Netflix #E50914, YouTube #FF0000 (two-tone), Hulu #1CE783, Prime Video #00A8E1, Disney+ #2E6BFF, Max #3D34FF, Peacock #FFC72C, Apple TV #F5F5F7, Inputs #9AA3AD, Settings #9AA3AD.

## Greeting
"HELLO <NAME>". NAME is a setting, default SEEKER (v0.9.0).

## Time, date, weather
- Clock in the TV's time zone, 12-hour, "9:41 PM". Date "WED OCT 21". Redraw once a minute.
- Weather: Open-Meteo, no key. Location is the WEATHER LOCATION setting (default Denver, lat 39.7392, lon -104.9903), `current=temperature_2m,weather_code`, `temperature_unit=fahrenheit`. Map WMO weather_code to sun / cloud / rain / snow / storm. Refresh every 20 min. Cache last result; if offline, show the cached one, never a blank.

## Remote (Android key events)
- DPAD_UP / DPAD_DOWN: move the wheel (wrap off, stop at ends).
- DPAD_CENTER / ENTER short press: launch. Long press (600 ms): enter reorder mode.
- Reorder mode: selected item gets a dashed brand-color frame, cursor dot bobs; UP/DOWN move the app through the list; CENTER or BACK exits and saves the order (DataStore/SharedPreferences).
- DPAD_RIGHT from the wheel: focus the gear. DPAD_LEFT: back to the wheel. CENTER on the gear: open `android.settings.SETTINGS`.
- BACK on the home screen: nothing.
- "Inputs" entry launches the Hisense inputs activity (resolve `com.vt.tv.input` main activity at runtime; fall back to the TV input picker intent).

## Launch animation
- Circle in the brand color expands from the selected row (or from the gear) to cover the screen, 550 ms, ease (.3,0,.1,1).
- Then the app name in dot matrix (cell 24, or 18 if longer than 8 chars), centered, ink = #0B0C0E on light brands else #FFFFFF, with "OPENING" in small mono below. On the TV, also show the app's own banner/icon (PackageManager) above the name.
- Fire the launch intent at ~400 ms so the app is coming up behind the color.

## App list
- Query `CATEGORY_LEANBACK_LAUNCHER` (fall back to `CATEGORY_LAUNCHER`). Exclude self. Show user-visible apps only.
- Listen for PACKAGE_ADDED / REMOVED / CHANGED and refresh.
- Saved order first, new apps appended at the end (above Inputs).

## Toast notifications (Phase 3, build the hook now)
- BroadcastReceiver on action `com.seeker.tv.NOTIFY` with string extras `title`, `body`, `source`. Test from the Mac: `adb shell am broadcast -a com.seeker.tv.NOTIFY --es title "Code · Build done" --es body "Restore kit finished." --es source "Mac"`.
- Draw: box at x=150, y=110, width 620, background #0E0F12, 1 px border #1F2126, 4 px left bar in the current brand color, bell icon (cell 6), title 22 px mono uppercase, body 20 px mono #70757E, source + time top right in 14 px #3B3F46. Slides in from the left 450 ms, holds 4.2 s, slides out.
- Must draw over other apps: foreground service with TYPE_APPLICATION_OVERLAY, permission granted once via `adb shell appops set com.seeker.tv SYSTEM_ALERT_WINDOW allow`. If the overlay isn't available, show it only when the launcher is in front.

## Non-negotiables
- Native Kotlin + Jetpack Compose for TV (or Views + Canvas if Compose for TV is too heavy on 1.8 GB). No WebView.
- Idle RAM target under 60 MB. Measure with `dumpsys meminfo com.seeker.tv`.
- minSdk 30 (Android 11), targetSdk 34. Package `com.seeker.tv`.
- Declare `android.intent.category.HOME` + DEFAULT + LEANBACK_LAUNCHER so it can be set as HOME later. Do NOT set it as HOME during development; launch it manually with `adb shell am start -n com.seeker.tv/.MainActivity`.
- If the launcher crashes at startup it must not loop: catch, log, show a plain fallback list.

## v4 changes (owner, 2026-10-21 19:38, after seeing M2 on the TV)

### Long-press OK on a wheel item = item menu, not straight to reorder
A small dot-matrix menu appears to the right of the selected item, same brand color: **MOVE** / **HIDE** / **CANCEL**. UP/DOWN picks, OK confirms, BACK cancels.
- MOVE enters reorder mode (dashed frame, bobbing cursor, UP/DOWN moves, OK or BACK saves).
- HIDE removes the app from the wheel immediately (wheel closes the gap with the normal animation). Hidden set saved in SharedPreferences.

### Settings gear opens Seeker's own settings screen
Full-screen, same visual language (dot-matrix headings, mono values). A vertical list, UP/DOWN, OK, BACK returns to the wheel. Entries, in order:
1. **SYSTEM SETTINGS**: opens `android.settings.SETTINGS`.
2. **INPUTS**: opens the Hisense inputs activity. Inputs leaves the wheel; it lives here now.
3. **NAME** and **WEATHER LOCATION** (v0.9.0): OK opens the TV keyboard. NAME default SEEKER, location default Denver.
4. **HIDDEN APPS**: list of every launchable app. Hidden ones are grayed (dim dots). OK on a grayed one unhides it (it returns to the wheel at the end, above Play Store). OK on a visible one hides it. Same effect as the item menu, just the other way in.
5. **ABOUT**: version, idle RAM, last weather refresh, HOME status (is Seeker HOME: yes/no).
Gray group at the bottom of the wheel is now just Play Store (Inputs moved to settings). Default hidden: Hisense protector, Projectivy, Live TV, USB media center, Device/Google Assistant launcher entry (if it shows). All of them visible and grayed in HIDDEN APPS so any can come back.

### Open-and-return
BACK on the settings screen or any sub-list returns one level. BACK on the wheel does nothing.

## v5 changes (owner, 2026-10-26): system widgets on the wheel
Mockup: `design/seeker-tv-mockup-v4.html` (v3 plus the widgets; Widgets buttons simulate Calm / Busy / Storage full).

### STATS FOR NERDS (settings toggle, OFF by default)
- New settings row under SOUND: **STATS FOR NERDS: ON / OFF**, saved as `stats` (default false), in both the dot-matrix and the HUNT-style settings. OFF = the wheel looks exactly like v4 and nothing is sampled; ON = the cards below.

### CPU / MEMORY / STORAGE cards under the weather
- Three Apple-style cards stacked under the weather row, right-aligned with the panel at x=1770: 400x88, corner radius 22, fill #121316, 1 px border #1F2126, 12 px apart, first at y=532 (so 532 / 632 / 732; ~40 px clear of the weather above and the gear below). They sit under the takeover like the panel does.
- Each card: ring gauge centered (x0+46, mid), radius 26, stroke 8, round caps, track = the ring color at 20%, arc from 12 o'clock clockwise. Label at (x0+92, y0+13) cell 3.5 #70757E; value "NN%" at (x0+92, y0+45) cell 5 #E8E9EB; detail right-aligned to x=1748 at y0+54, cell 3, #70757E.
- Colors (Apple system): CPU #30D158, MEMORY #0A84FF, STORAGE #FF9F0A; any ring at 90% or more turns #FF453A.
- Details: CPU = fastest core's current clock ("1.4 GHZ"); MEMORY = used/total in GB ("1.8/2.9 GB"); STORAGE = used/total GB ("9/24 GB").
- New glyph: '%' = 11001 11010 00010 00100 01000 01011 10011.

### Readings (TV)
- CPU: apps can't read /proc/stat (Android 8+). Every core's `/sys/devices/system/cpu/cpuN/cpuidle/state*/time` (µs) is readable: busy = 1 - Δidle / (Δwall x online cores) between samples. Checked on the onn 4K Pro: ~5% idle-screen load, matching `top`.
- MEMORY: `ActivityManager.MemoryInfo`, used = total - avail (avail counts reclaimable cache, as Settings does).
- STORAGE: `StatFs` on the data partition, decimal GB.
- CPU and MEMORY every 2 s, STORAGE every 30 s, only while the wheel is the screen shown (stopped in game modes, other apps and screen off). The first CPU reading comes 1 s after the wheel starts (counters primed on start).
- CPU is averaged with the previous reading (steadier ring). Rings sweep 400 ms only on a change of 10 points or more; smaller changes snap in a single redraw, so an idle home screen redraws about once every 2 s. (First cut swept on 3 points: CPU jitter made the wheel redraw ~7 frames/s at idle.)
