# Seeker Pocket: a handheld-style HOME MODE (idea 2026-10-26)

Status: DRAFT, mockup v3. Another value of HOME MODE. Playable mockup: `design/seeker-pocket-mockup-v3.html` (← → move, Enter = OK, hold Enter = item menu, Esc = BACK). Working name POCKET; the owner may rename it. Port the font, layout, colors and frame-by-frame motion 1:1 from its JavaScript.

## Idea
The owner asked for a menu that looks "like the DSi bottom home screen", as close to 1:1 as possible: pixelated edges, the same proportions and animations, and pixelated streaming-service logos.

v1 was a smooth, modern take on the layout; the owner wanted it pixel-true, so v2 is drawn on the handheld's pixel grid. v3 fixes the proportions against a photo of the real menu.

**Matched:**
- the pixel size and layout proportions
- the chunky framed icon tiles
- the centered selection in a tall accent frame with START
- the big message box, the status bar, and the edge-to-edge scrollbar with its window
- the stepped, snappy motion

**Original, not copied:**
- the font: hand-drawn here, since the console's system font is proprietary
- all art and the sounds
- the logos: the public repo only has stand-ins; on the TV, every tile is the app's own launcher icon shrunk to 32x32 pixels at runtime

## The pixel grid
- **Grid:** everything is drawn on a 384x216 canvas, the handheld's 256x192 pixel size widened to 16:9, then scaled x5 to 1920x1080 with nearest-neighbour. One "pixel" = 5x5 TV pixels.
- **Drawing:** filled pixel rects only, no anti-aliasing. Rounded corners are stepped cut-outs (radius 1: [1]; 2: [2,1]; 3: [3,1,1]; 4: [4,2,1,1]; 5: [5,3,2,1,1]; 6: [6,4,3,2,1,1]; 8: [8,6,4,3,2,2,1,1]). A 1-px outline follows the steps.
- **Motion:** every position is rounded to whole pixels each frame, so motion steps like the handheld.
- **Font:** proportional bitmap, 7-pixel caps, 5-pixel x-height, 2-pixel descenders, 1-pixel letter gap, space = 3. Glyphs are in the mockup's `G` table. Everything is drawn at 1x.

## Layout (logical pixels, 384x216)
v3, sized against the owner's photo of the real menu (2026-10-26): the message box and icons were too small, and the scrollbar has to run edge to edge.
- **Background:** #F6F6F6, with #EFEFEF LCD lines on every other row between the bar and the scrollbar.
- **Status bar:** rows 0..14 a gradient #FFFFFF → #D0D0D0, row 15 #A0A0A0.
  - Name pill at (3, 3): text width + 10 by 11, radius 2, the accent color with a darker bottom row, white text at (8, 5).
  - Right-aligned from x=380, 10 px apart: clock (#383838; the colon blinks once a second), date "10/26", temperature, and the 9x9 weather icon.
- **Message box:** 296x62 at (44, 20), radius 8, fill #E8E8E8, 1-px #6C6C6C outline, a #F4F4F4 highlight row at the top.
  - A 7-row outlined pointer hangs from the bottom center toward the selection.
  - Two centered lines at 1x, both #404040: the app name at y+18 and the subtitle at y+34.
- **Tiles:** 52x52 frames, 6 apart (pitch 58), centered on y=129.
  - Neighbors of the selection sit 65 px from its center.
  - Frame: radius 6, white, 1-px #B4B4B4 outline, 3-px #C4C4C4 shadow ledge, a 6-row soft gray shade at the bottom.
  - Icon: 32x32 at +10.
  - SETTINGS is a gray tile at the end, then 5 dotted empty slots.
- **Selection:** a 66x82 frame at y=96, centered on x=192.
  - Frame: radius 8, the accent color with a darker outline and 3-px ledge, and a light top highlight row.
  - The icon sits in a 52x52 white box at +7, with **START** (OPEN on SETTINGS) in white underneath at y+67.
  - The frame breathes 1 px out and in (15 frames each).
- **Item menu (hold OK):** shown inside the message box. "What would you like to do?" at y+12, then three 70x16 buttons (radius 4, ledge), 8 apart, at y+32: MOVE / HIDE / CANCEL.
  - Focused: a tint of the accent with an accent outline (HIDE red #E83838).
  - Others: #FAFAFA with a #9A9A9A outline.
- **Scrollbar band:** rows 192..215, edge to edge. A #9C9C9C top line, #DCDCDC fill, #F2F2F2 highlight.
  - 26-px accent tabs at both edges, a light tint of the accent with the arrow in the accent color. A tab lightens for 6 frames on Left/Right.
  - A dotted track from x=40 to 344: a 3x3 #505050 dot per app, a 1-px dot per empty slot, 1-px dots every 3 px between.
  - A window (accent outline, white 55%) spans 7 slots and slides with the row. The selection's own dot is a 5x5 blue #2878E8.
- **Accent ("favorite color"):** a new setting, FAVORITE COLOR (shown in settings only in POCKET). Light gray #A9ADB5 by default (the owner swapped it for pink, 2026-10-26); blue #2878E8, red #E83838, orange #F88800, green #28A838, purple #8848D8.

## Motion (60 fps)
- **Row:** the position (in slots) moves 1/3 of the remaining distance each frame toward the selection, and centers are rounded to whole pixels. Snappy, with no overshoot.
- **Launch:**
  - The selected tile hops (0,3,5,6,6,5,3,0,1,0 px up over 10 frames).
  - From frame 8 the screen goes white in 6 hard steps, one every 3 frames.
  - The app launches once it's white (~450 ms).
- **MOVE:** the tile lifts 4 px and every tile jitters ±1 px every 8 frames. Left/Right carries it along; OK or BACK drops it and saves.
- **HIDE:** the tile is removed and the row closes up. Same hidden set as the other modes.

## Remote
- **LEFT / RIGHT:** move the selection, with a low "bonk" at the ends.
- **OK:** open; on SETTINGS, it opens Seeker's settings.
- **Hold OK 600 ms:** the item menu. LEFT/RIGHT picks, OK confirms, BACK cancels.
- **BACK:** nothing.

## Sound (synthesized square-wave blips, original; SOUND setting)
- move: 1760 Hz, 22 ms, gain 0.03
- edge: 220→200 Hz, 50 ms, gain 0.04
- OK: 988 Hz 60 ms, then 1319 Hz 120 ms, gain 0.04
- menu: 1175 Hz, 40 ms, gain 0.035

## Build notes
- A full home screen like WHEEL and SLATE: a third `HomeScreen` (PocketView), built into every flavor.
- **Rendering:**
  - Draw into a 384x216 ARGB bitmap, then blit it scaled x5 with filtering off. That's one cheap full-screen draw, and every edge stays crisp.
  - Redraw only while something moves. The brackets breathe 4 times a second, so an idle screen redraws ~4 times a second.
- **Icons:** `getActivityIcon` → draw at 128 px, downscale to 32x32 with filtering, alpha hard-thresholded at 110. Cached per app.
- **Font:** the `G` glyph table ports directly (like Dots.kt).
