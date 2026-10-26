# Seeker Pong: fifth HOME MODE (idea 2026-10-25)

Status: DRAFT, mockup v1. Another value of HOME MODE: WHEEL / FLAP / INVADERS / HUNT / PONG. Playable mockup: `design/seeker-pong-mockup-v1.html` (Enter = OK, arrows = dpad). Port the layout constants, physics, sound recipes and screens 1:1 from its JavaScript.

## Idea
Classic Pong against the CPU. The right edge of the screen is a beam of light in one service's brand color, with its name. Every back-and-forth (you return the ball) moves the beam to the next service. **Miss the ball and the service on the beam opens** (brand takeover, launch at 900 ms, same as the wheel and the other games). So you rally until the one you want is lit, then let the ball past you. The CPU never misses: the only way a point ends is you missing. SETTINGS is in the cycle as a gray beam (like Invaders' meteor). HOME or returning from an app always lands on START.

## Layout (1920x1080 reference)
- Court: walls are dotted rows (cell 16, #3B3F46) at y=150 and y=1010; dotted center line at x=960 (every 36 px, #2A2D33).
- Your paddle: 1x8 dots at cell 16 (16x128), x=150, #E8E9EB. UP/DOWN held = 13 px/frame. Clamped inside the walls.
- CPU paddle: same size, x=1720, #E8E9EB. Predicts where the ball reaches its x (wall bounces included), aims a random spot within ±44 px of its center there, moves at most 12 px/frame. That is always fast enough (the slowest crossing is ~137 frames, its longest move ~60), so it never misses. When the ball moves away it drifts back to the middle at 6 px/frame.
- Ball: 2x2 dots at cell 14 (28x28), #E8E9EB. Serve 45 frames after START (or after a point), from the center toward the CPU at a random angle within ±20°.
- Return angle: where the ball hits the paddle, -1 (top) to +1 (bottom), times 45°. Walls reflect vy.
- Speed, original Pong pacing (a little faster): 10 px/frame, 13 after the 4th hit, 16 after the 12th, counting both paddles; resets each point. Court crossing ~2.6 s, ~2.0 s, ~1.6 s. (v1 draft was 13 +0.5 per return up to 22: owner found it too fast.)
- Beam (right edge): radial glow centered (1920, 580), radius 900, brand color 30% alpha fading to 0 at 70%; plus a 260 px linear band from the right edge, 26% alpha to 0; plus a lamp column of lit dots at x=1880 between the walls (cell 16) in the brand color. Changing service crossfades over 12 frames.
- Beam label: service name at cell 6 in the brand color, right-aligned to x=1880, y=66. Under it NEXT <name> at cell 3.5 in #70757E, y=116.
- Rally count: centered at y=66, cell 8, #E8E9EB with grid.
- Rainbow strip on top, calm on START, full in play (Invaders' numbers).
- START: title SEEKER PONG, blinking START, SETTINGS under it on DOWN (like Flap), hint "RALLY TO CYCLE  MISS TO OPEN" in #3B3F46. The beam cycles slowly (every 90 frames) behind the menu.
- Miss: ball leaves the left edge. The beam's service is picked; the takeover circle grows from the beam (1920, 580) over 22 frames, then the ball above the name, OPENING, RALLY n. Launch at frame 54; back to START at 130 if nothing launched. SETTINGS: gray burst, Seeker settings at frame 16.

## Sound (synthesized, no samples)
- paddle (yours): square 440 Hz, 50 ms, gain 0.10.
- paddle (CPU): square 330 Hz, 50 ms, gain 0.08.
- wall: square 220 Hz, 40 ms, gain 0.07.
- cycle: triangle 660→990 Hz, 90 ms, gain 0.06, each time the beam moves on.
- miss: Invaders' hit (noise burst 350 ms through 900 Hz low-pass + square 120→40 Hz thump).
- serve: Invaders' move blip.

## Remote
- START: UP/DOWN pick START / SETTINGS, OK confirms.
- PLAY: UP/DOWN held move your paddle (key down starts, key up stops; key repeat ignored). BACK = START.

## Art
Paddles, ball and court are plain dots. Nothing from the original arcade game beyond the idea.

## Build notes
- Same Canvas view, fixed 60 Hz Choreographer loop and 1920x1080 scaling as InvadersView; share Dots, GameApps, Rainbow, Sfx.
- Unlike the other games, PLAY needs key UP events (held paddle), like Hunt's hold-OK.
- The beam is two cached shaders per color; rebuild only when the service changes (12-frame crossfade = draw old and new with alpha).
