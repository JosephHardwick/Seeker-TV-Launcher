# Seeker Slate: a clean HOME MODE (idea 2026-10-26)

Status: DRAFT, mockup v1. Another value of HOME MODE: WHEEL / FLAP / INVADERS / HUNT / PONG / SLATE. Playable mockup: `design/seeker-slate-mockup-v1.html` (arrows = dpad, Enter = OK, hold Enter = item menu, Esc = BACK). Port the layout constants, colors, timings and sound recipes 1:1 from its CSS and JavaScript.

## Idea
The opposite of the dot matrix: an Apple-like home screen. A rotating nature/city photo, an iOS-lock-screen clock, real app artwork in rounded tiles, one moving thing at a time. No grid, no glow dots, no pixel font, no games, no greeting. Same data as the wheel (apps, clock, weather, stats, toasts, settings), new look.

v1 owner notes (2026-10-26, from screenshots of the first draft): ditch the greeting; center the time and weather; rotating nature/city photos behind everything (Google's own screensaver photos if possible); a thicker, translucent clock like the iOS lock screen.

## Look
- Font: **Inter** (SIL Open Font License, bundled in `res/font`). San Francisco is licensed for Apple platforms only, so it can't ship. Tabular figures for the clock and the stats.
- Colors (Apple system dark palette):
  - base #0A0A0C (shown until the first photo loads, and offline with no cache)
  - text #F5F5F7 / secondary #8E8E93 / tertiary #48484A
  - cards over the photo: #1C1C1E at 72% with a 1 px white 8% border; menus #2C2C2E
  - switches on #30D158, destructive #FF453A
- Photo backdrop: full-screen 1920x1080 photo, center-cropped.
  - Rotates every 30 s with a 1.2 s crossfade. Photos are still: no Ken Burns zoom (owner, 2026-10-26: not worth redrawing every frame).
  - Scrim on top for legibility: vertical gradient, black 45% at the top → 20% at 32% → 0 at 48% → 60% at the bottom.
  - Credit bottom left at x=150, baseline y=1010: the photo's place, 20 px weight 500, white 60%, soft shadow.
- Source: Bing's image-of-the-day archive (`https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8`, then `idx=7`; no key). It gives ~15 recent 1920x1080 landscape photos with a caption; use the caption up to " (©" as the credit.
  - Refresh the list once a day. Keep the last 30 photos as JPEGs in the app's cache so it works offline.
  - Google TV's own Ambient mode photos can't be used: they come from Google's private servers through `com.google.android.apps.tv.dreamx`, and its files are private to that app.
  - Later option: a folder of the owner's own photos.
- Easing: focus moves use 300 ms `cubic-bezier(.25,1.25,.5,1)` (a few % of overshoot, tvOS-like); scroll and fades use 350 ms `cubic-bezier(.2,.7,.2,1)`.

## Layout (1920x1080 reference)
- Centered on x=960, iOS lock screen style:
  - date "Monday, October 26", 34 px weight 600, white 85%, top y=92
  - clock "9:41", 230 px weight 600, -3.5% tracking, white 74% (translucent: the photo shows through), soft shadow 0 2 40 black 18%, top y=128 (12-hour, no AM/PM)
  - weather row, top y=382: icon (44 px box) + "68°" (32 px weight 500, white 85%) + "Sunny · Denver" (white 62%), 14 px gaps
  - Icons drawn with Canvas paths: sun = yellow #FFD60A disc + 8 rays; cloud = white union of three circles and a rounded base; rain = cloud + 3 slanted #64D2FF strokes; snow = cloud + 4 white dots; storm = cloud + #FFD60A bolt.
- App row: tiles 336x189 (16:9), corner radius 22, 36 px apart, top y=610, starting x=150.
  - Tile art: the app's TV banner (PackageManager, already loaded in Apps.kt). No banner: its icon centered on a card filled with the brand color.
  - Focused tile: scale 1.14 about its center, shadow 0 30 60 black 60%, 1.5 px inner highlight white 20%. A light sheen (white 18% diagonal band) sweeps across it once, 700 ms, each time it gains focus.
  - Unfocused tiles: 82% brightness.
  - Name under the focused tile only, centered, 30 px weight 500 #F5F5F7, baseline 56 px below the scaled tile's bottom. Fades in 200 ms.
  - Scrolling: the row slides so the focused tile stays inside x 150..1770, 350 ms. Tiles past the right edge bleed off the screen.
- Settings button: circle 72 px, card fill, gear glyph white 80%, at x=1698, y=930. Focused: white fill, dark glyph, scale 1.1.
- STATS FOR NERDS cards (only when ON): top right, right-aligned at x=1770.
  - Card: 360x84, radius 20, card fill, first card at y=92 then 188 / 284.
  - Ring: radius 24, stroke 7, centered at (x0+44, mid).
  - Label 18 px weight 600 uppercase #8E8E93 at (x0+84, y0+32); value "12%" 34 px weight 600 #F5F5F7 at (x0+84, y0+68); detail 20 px #8E8E93 right-aligned to x0+340, baseline y0+68.
  - Colors and the 90% red rule as the wheel's v5 widgets. Same sampling (SysStats), same redraw rules.

## Remote
- LEFT / RIGHT: move focus along the row (stops at the ends). DOWN: settings button. UP: back to the row.
- OK: launch. Hold OK 600 ms: item menu.
- Item menu: a popover under the tile (radius 18, #2C2C2E, 300 wide), rows **Move** / **Hide** (in red) / **Cancel**, 64 px tall. The focused row gets a white 12% fill. UP/DOWN picks, OK confirms, BACK cancels.
  - Move: every tile wiggles (±1.2°, 260 ms alternate, iOS-style). The moving tile stays lifted. LEFT/RIGHT swap it along the row. OK or BACK saves the order.
  - Hide: the tile shrinks to 0 (250 ms) and the row closes the gap. Same hidden set as the wheel.
- BACK on the home screen: nothing.

## Launch
- The focused tile presses in like a button: 94% and back over 220 ms.
- From 120 ms the whole screen fades to black (330 ms, ease). No name screen, no "Opening".
- The launch intent fires at 450 ms, once the screen is dark; the app fades in over it.
- v1 had the tile grow to fill the screen, then a brand-color name screen. Owner, 2026-10-26: TV banners are only ~320x180, so blown up to full screen they look soft, and the name screen didn't fit SLATE.
- If nothing came up, the screen resets after 4 s (MainActivity), and always on return.
- Settings button: opens settings straight away (no growth), like the wheel's gear.

## Settings (Slate style)
Shown when HOME MODE is SLATE; the other modes keep their own settings looks.
- The photo stays behind settings under a #0A0A0C veil at 86%.
- Large title "Settings", 64 px weight 700, x=460, baseline y=160.
- Grouped list, x=460 to 1460, rows 72 px, 20 px text weight 500 → 28 px. Section headers 20 px uppercase #8E8E93. Groups are rounded (radius 22) card-fill blocks with #38383A separators inset 32 px.
- Focused row (tvOS): white fill, #0A0A0C text, scale 1.03, 250 ms.
- Rows: values right-aligned #8E8E93, chevrons for pages, iOS switches (52x32, on #30D158) for SOUND and STATS FOR NERDS. Sections:
  - **General**: System Settings › / Inputs ›
  - **Home**: Home Mode (value) / Sound (switch) / Stats for Nerds (switch)
  - **You**: Name / Weather Location
  - **Apps**: Hidden Apps (count ›) / About ›
- The list scrolls to keep the focused row inside y 230..1000. BACK returns one level.

## Toasts (com.seeker.tv.NOTIFY): not in the first port; SLATE shows the existing toast for now
iOS-style banner, top center: 760 wide, radius 28, #2C2C2E at 96%.
- Left: a 64 px rounded-square icon (radius 14) with a white bell.
- Title 26 px weight 600; body 24 px at 60%; "now" top right 20 px #8E8E93.
- Slides down from above the screen with a 500 ms overshoot ease, holds 4.2 s, slides back up.

## Sound (synthesized, quiet; SOUND setting)
- focus move: sine 1600 Hz, 14 ms, gain 0.035, fast decay
- launch / OK: sine 880→1320 Hz, 60 ms, gain 0.05
- menu open: sine 660 Hz, 40 ms, gain 0.04
- switch: two ticks 30 ms apart

## Build notes
- SLATE is a home screen, not a game: it doesn't fit `GameScreen` (no START, it owns the app list, panel, stats and toasts like SeekerView).
  - `HomeScreen` (HomeScreen.kt): what MainActivity calls on a home screen (`showApps`, `setWeather`, `tick`, `setStats` / `hideStats`, `resetLaunch`, `resetModes`, `onKey`).
  - SeekerView and `SlateView` both implement it. MainActivity builds SLATE the first time it's shown and feeds every built home screen.
  - Lives in `src/main` like the wheel, so every flavor has WHEEL and SLATE. HOME MODE order: WHEEL, SLATE, then the games.
- Canvas at 1920x1080 like the others, but no fixed 60 Hz loop: draw only while something animates (focus spring, sheen, wiggle, takeover, photo crossfade), so a still idle screen costs nothing. The clock ticks once a minute, the stats every 2 s.
- Cache each tile's rounded, scaled banner bitmap once (refresh with the app list), and decode only the current and next photo (RGB_565 is enough), never the whole set.
- Inter: the variable TTF (weight + optical size, ~860 KB) in `assets/fonts`, with its OFL licence next to it (Inter.kt).
