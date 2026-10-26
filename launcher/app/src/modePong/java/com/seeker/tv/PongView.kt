package com.seeker.tv

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Choreographer
import android.view.KeyEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Seeker Pong: HOME MODE = PONG. START screen (START / SETTINGS), then Pong against a CPU that never misses.
 * The right edge is a beam of light in one service's brand color; every return of yours moves it to the next
 * service. Miss the ball and the service on the beam opens (SETTINGS is in the cycle as a gray beam).
 * Layout, physics, beam, takeover, screens and sounds are ported 1:1 from design/seeker-pong-mockup-v1.html
 * (frame-based at 60 fps, 1920x1080 reference pixels). Same fixed-60 Hz Choreographer loop as InvadersView.
 */
@SuppressLint("ViewConstructor")
class PongView(ctx: Context, private val host: GameHost) : View(ctx), GameScreen {

    private companion object {
        const val W = 1920f
        const val H = 1080f
        const val MID = 580f
        const val TOP = 150f         // walls: dot rows centered on TOP and BOT
        const val BOT = 1010f
        const val CELL = 16f
        const val IN_T = TOP + 10
        const val IN_B = BOT - 10
        const val PAD_H = 8 * CELL
        const val PAD_W = CELL
        const val PX = 150f
        const val CX = 1720f
        const val PAD_SPEED = 13f
        const val CPU_SPEED = 12f    // always enough to reach the predicted spot: the CPU never misses
        const val CPU_IDLE = 6f
        const val CPU_AIM = 44f
        const val BALL = 28f
        const val BALL_CELL = 14f
        const val SERVE_AT = 45
        // Original Pong pacing: the ball speeds up only after the 4th and the 12th hit (both paddles), a little faster here.
        val SPEEDS = floatArrayOf(10f, 13f, 16f)
        const val HITS_MID = 4
        const val HITS_FAST = 12
        const val MAX_ANG = (PI / 4).toFloat()
        const val SERVE_ANG = (20 * PI / 180).toFloat()
        const val BEAM_X = 1880f
        const val BEAM_R = 900f
        const val BAND = 260f
        const val FADE = 12
        const val START_CYCLE = 90
        const val SETTINGS_C = 0xFF8F969F.toInt()
        const val LAUNCH_AT = 54   // frames: the intent fires ~900 ms into the miss, like the other games
        const val CRASH_END = 130  // frames: back to START if nothing launched (mockup)
        const val TO_SETTINGS = 16 // frames: gray burst, then settings (mockup)
        const val STEP_NS = 1_000_000_000L / 60
        const val BG = 0xFF0B0C0E.toInt()
        const val GRID_DOT = 0xFF16181C.toInt()
        const val GRID_TOP = 48f

        val SETTINGS = GameApp("", "SETTINGS", SETTINGS_C, GameApps.INK_DARK)
    }

    private class Ball(var x: Float, var y: Float, var vx: Float, var vy: Float)
    private class Boom(val x: Float, val y: Float, val c: Int, var t: Int = 0)

    /** The beam's two gradients for one color, built at full strength; drawn with paint alpha for fades. */
    private class Beam(val glow: Shader, val band: Shader)

    private enum class Mode { START, PLAY, CRASH, TO_SETTINGS }

    // ---------- game state (mockup names) ----------
    private var mode = Mode.START
    private var t = 0
    private var menuSel = 0
    private var py = MID - PAD_H / 2
    private var cy = MID - PAD_H / 2
    private var up = false
    private var down = false
    private var ball: Ball? = null
    private var speed = SPEEDS[0]
    private var hits = 0
    private var serveT = 0
    private var rally = 0
    private var beam = 0
    private var beamFrom = -1
    private var beamT = FADE
    private var cpuAim = MID
    private var hit: GameApp? = null
    private var hitT = 0
    private var boom: Boom? = null
    private var launched = false
    private var cycle = GameApps.ALL + SETTINGS   // the beam's order: the installed services, then SETTINGS
    private var beams = cycle.map(::beamFor)

    // ---------- loop ----------
    private var running = false
    private var lastNs = 0L
    private var acc = 0L
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!running) return
            if (lastNs != 0L) acc += min(now - lastNs, 100_000_000L)
            lastNs = now
            var n = 0
            while (acc >= STEP_NS - 2_000_000L && n < 4) { step(); acc -= STEP_NS; n++ }
            if (n == 4) acc = 0
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // ---------- paints ----------
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = Paint()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val beamPaint = Paint()
    // Opaque tile with the background baked in, like Invaders: the only full-screen fill.
    private val gridPaint = Paint().apply {
        val tile = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        tile.eraseColor(BG)
        Canvas(tile).drawCircle(12f, 12f, 1.6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GRID_DOT })
        shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
            setLocalMatrix(Matrix().apply { setTranslate(12f, 12f) })
        }
    }
    private val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, 500, false)
        textSize = 22f
        letterSpacing = 0.3f
        textAlign = Paint.Align.CENTER
    }
    private var firstFrameSent = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        reset()
    }

    // ---------- public API ----------
    /** Coming back HOME, from an app or from settings: always the START screen, never mid-game. */
    override fun toStart() {
        mode = Mode.START
        menuSel = 0
        reset()
        invalidate()
    }

    /** The beam cycles through the spec's eight services that are installed (all eight if none resolve), then SETTINGS. */
    override fun refreshApps() {
        cycle = GameApps.installed(host::flapEntry) + SETTINGS
        beams = cycle.map(::beamFor)
        if (beam >= cycle.size) beam = 0
        if (beamFrom >= cycle.size) beamFrom = -1
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && !running) {
            running = true
            lastNs = 0L
            acc = 0L
            Choreographer.getInstance().postFrameCallback(frame)
        } else if (!isVisible && running) {
            running = false
            Choreographer.getInstance().removeFrameCallback(frame)
        }
    }

    // ---------- game (mockup step()) ----------
    private fun reset() {
        py = MID - PAD_H / 2; cy = py; ball = null; hits = 0; speed = SPEEDS[0]; serveT = 0; rally = 0
        beam = 0; beamFrom = -1; beamT = FADE; cpuAim = MID; hit = null; hitT = 0; boom = null; up = false; down = false; launched = false
    }

    private fun clampPad(y: Float) = y.coerceIn(IN_T, IN_B - PAD_H)

    private fun tier(hits: Int) = SPEEDS[if (hits >= HITS_FAST) 2 else if (hits >= HITS_MID) 1 else 0]

    private fun nextBeam(sound: Boolean) {
        beamFrom = beam; beam = (beam + 1) % cycle.size; beamT = 0
        if (sound) Sfx.play(Sfx.Id.PONG_CYCLE)
    }

    /** Where the ball's center will be when it reaches the CPU's face, wall bounces folded in. */
    private fun predict(b: Ball): Float {
        val lo = IN_T + BALL / 2
        val span = IN_B - BALL / 2 - lo
        val faceX = CX - PAD_W / 2 - BALL / 2
        val y = b.y + BALL / 2 + b.vy * ((faceX - (b.x + BALL / 2)) / b.vx)
        val m = ((y - lo) % (2 * span) + 2 * span) % (2 * span)
        return if (m <= span) lo + m else lo + 2 * span - m
    }

    /** Meet the ball off-center so returns vary. */
    private fun aimCpu(b: Ball) { cpuAim = predict(b) + (Random.nextFloat() * 2 - 1) * CPU_AIM }

    private fun serve() {
        val a = (Random.nextFloat() * 2 - 1) * SERVE_ANG
        speed = tier(hits)
        val b = Ball(W / 2 - BALL / 2, MID - BALL / 2, speed * cos(a), speed * sin(a))
        ball = b
        aimCpu(b)
        Sfx.play(Sfx.Id.MOVE)
    }

    /** [dir] +1 = to the right. The angle comes from where the ball meets the paddle. */
    private fun bounce(b: Ball, padY: Float, dir: Int) {
        val off = (((b.y + BALL / 2) - (padY + PAD_H / 2)) / (PAD_H / 2 + BALL / 2)).coerceIn(-1f, 1f)
        val a = off * MAX_ANG
        b.vx = dir * speed * cos(a)
        b.vy = speed * sin(a)
    }

    private fun Ball.overlapsY(padY: Float) = y + BALL >= padY && y <= padY + PAD_H

    private fun step() {
        t++
        if (beamT < FADE) beamT++
        if (mode == Mode.START && t % START_CYCLE == 0) nextBeam(false)
        if (mode == Mode.PLAY) {
            if (up) py -= PAD_SPEED
            if (down) py += PAD_SPEED
            py = clampPad(py)
            if (ball == null && ++serveT >= SERVE_AT) serve()
            // CPU: toward the predicted contact point while the ball comes at it, back to the middle otherwise
            val coming = (ball?.vx ?: 0f) > 0
            val want = (if (coming) cpuAim else MID) - PAD_H / 2
            val cap = if (coming) CPU_SPEED else CPU_IDLE
            cy = clampPad(cy + (want - cy).coerceIn(-cap, cap))
            ball?.let { b ->
                b.x += b.vx; b.y += b.vy
                if (b.y < IN_T) { b.y = 2 * IN_T - b.y; b.vy = -b.vy; Sfx.play(Sfx.Id.PONG_WALL) }
                if (b.y + BALL > IN_B) { b.y = 2 * (IN_B - BALL) - b.y; b.vy = -b.vy; Sfx.play(Sfx.Id.PONG_WALL) }
                if (b.vx < 0 && b.x <= PX + PAD_W / 2 && b.x + BALL >= PX - PAD_W / 2 && b.overlapsY(py)) {
                    b.x = PX + PAD_W / 2; speed = tier(++hits); bounce(b, py, 1); rally++
                    Sfx.play(Sfx.Id.PONG_PADDLE); nextBeam(true); aimCpu(b)
                } else if (b.vx > 0 && b.x + BALL >= CX - PAD_W / 2) {
                    b.x = CX - PAD_W / 2 - BALL; speed = tier(++hits); bounce(b, cy, -1); Sfx.play(Sfx.Id.PONG_CPU)  // the CPU never misses
                } else if (b.x + BALL < 0) {
                    val app = cycle[beam]
                    ball = null; hitT = 0; Sfx.play(Sfx.Id.HIT)
                    if (app === SETTINGS) { boom = Boom(BEAM_X, MID, app.c); mode = Mode.TO_SETTINGS }
                    else { hit = app; mode = Mode.CRASH }
                }
            }
        }
        boom?.let { it.t++; if (it.t > 14) boom = null }
        if (mode == Mode.CRASH) {
            hitT++
            if (hitT == LAUNCH_AT) {
                val entry = hit?.let { host.flapEntry(it.pkg) }
                if (entry != null) { launched = true; host.launch(entry) }
            }
            // A launched app covers us and onStop resets to START; MainActivity's 4 s fallback covers a no-show.
            if (hitT > CRASH_END && !launched) toStart()
        }
        if (mode == Mode.TO_SETTINGS) {
            hitT++
            if (hitT > TO_SETTINGS) { toStart(); host.openSettings() }
        }
    }

    // ---------- remote ----------
    override fun onKey(e: KeyEvent): Boolean {
        val code = e.keyCode
        val isCenter = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
            code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A
        val handled = isCenter || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_UP ||
            code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        if (!handled) return false
        if (e.action == KeyEvent.ACTION_UP) {
            // mockup keyup: the paddle moves while UP / DOWN is held
            if (code == KeyEvent.KEYCODE_DPAD_UP) up = false
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) down = false
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount > 0) return true  // mockup ignores key repeat
        when (mode) {
            Mode.START -> when {
                code == KeyEvent.KEYCODE_DPAD_DOWN -> menuSel = 1
                code == KeyEvent.KEYCODE_DPAD_UP -> menuSel = 0
                isCenter -> if (menuSel == 0) { reset(); mode = Mode.PLAY } else { toStart(); host.openSettings() }
            }
            Mode.PLAY -> when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> up = true
                KeyEvent.KEYCODE_DPAD_DOWN -> down = true
                KeyEvent.KEYCODE_BACK -> toStart()
            }
            Mode.CRASH, Mode.TO_SETTINGS -> Unit
        }
        return true
    }

    // ---------- drawing (mockup draw*()) ----------
    override fun isOpaque() = true

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.scale(width / W, height / H)
        drawBackdrop(canvas)
        when (mode) {
            Mode.START -> drawStart(canvas)
            else -> {
                drawPlay(canvas)
                drawBoom(canvas)
                if (mode == Mode.CRASH) drawTakeover(canvas)
            }
        }
        canvas.restore()
        if (!firstFrameSent) { firstFrameSent = true; post { host.firstFrame() } }
    }

    private fun beamFor(app: GameApp): Beam {
        val rgb = app.c and 0xFFFFFF
        val glow = RadialGradient(W, MID, BEAM_R, intArrayOf(Dots.fade(app.c, 0.30f), rgb, rgb), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        val band = LinearGradient(W, 0f, W - BAND, 0f, Dots.fade(app.c, 0.26f), rgb, Shader.TileMode.CLAMP)
        return Beam(glow, band)
    }

    /** dots() for [cols] x [rows], all lit: paddles and the ball. */
    private fun block(c: Canvas, cols: Int, rows: Int, px: Float, py: Float, cell: Float, color: Int) {
        val r = cell * 0.42f
        fill.color = color
        for (row in 0 until rows) for (col in 0 until cols) c.drawCircle(px + col * cell + cell / 2, py + row * cell + cell / 2, r, fill)
    }

    /** The beam: a brand-color glow from the right edge (the wheel's left-edge glow, mirrored and stronger), a band, and a lamp column. */
    private fun drawBeam(c: Canvas, i: Int, a: Float) {
        if (a <= 0f) return
        val b = beams[i]
        beamPaint.alpha = (a * 255).toInt().coerceIn(0, 255)
        beamPaint.shader = b.glow
        c.drawRect(W - BEAM_R, 0f, W, H, beamPaint)
        beamPaint.shader = b.band
        c.drawRect(W - BAND, 0f, W, H, beamPaint)
        fill.color = Dots.fade(cycle[i].c, a)
        var y = TOP
        while (y <= BOT) { c.drawCircle(BEAM_X, y, CELL * 0.36f, fill); y += CELL }
    }

    private fun drawBackdrop(c: Canvas) {
        rect.color = BG
        c.drawRect(0f, 0f, W, GRID_TOP, rect)
        c.drawRect(0f, GRID_TOP, W, H, gridPaint)
        val k = beamT / FADE.toFloat()
        val s = if (mode == Mode.START) 0.6f else 1f
        if (beamFrom >= 0 && k < 1f) drawBeam(c, beamFrom, (1 - k) * s)
        drawBeam(c, beam, (if (beamFrom >= 0) k else 1f) * s)
        Rainbow.draw(c, full = mode == Mode.PLAY || mode == Mode.CRASH, t = t, style = Rainbow.INVADERS)
    }

    private fun drawCourt(c: Canvas) {
        fill.color = Dots.DIM
        var x = 96f
        while (x <= 1824f) { c.drawCircle(x, TOP, CELL * 0.3f, fill); c.drawCircle(x, BOT, CELL * 0.3f, fill); x += CELL }
        fill.color = Dots.DOT
        var y = TOP + 36
        while (y < BOT - 18) { c.drawCircle(W / 2, y, 4f, fill); y += 36 }
    }

    private fun drawLabel(c: Canvas) {
        val app = cycle[beam]
        val cell = 6f
        Dots.text(c, app.n, BEAM_X - Dots.textW(app.n, cell), 66f, cell, app.c, false)
        val nx = "NEXT " + cycle[(beam + 1) % cycle.size].n
        val nc = 3.5f
        Dots.text(c, nx, BEAM_X - Dots.textW(nx, nc), 116f, nc, Dots.MUTE, false)
    }

    private fun drawPlay(c: Canvas) {
        drawCourt(c)
        drawLabel(c)
        block(c, 1, 8, PX - PAD_W / 2, py, CELL, Dots.FG)
        block(c, 1, 8, CX - PAD_W / 2, cy, CELL, Dots.FG)
        ball?.let { block(c, 2, 2, it.x, it.y, BALL_CELL, Dots.FG) }
        val r = "RALLY $rally"
        val rc = 8f
        Dots.text(c, r, (W - Dots.textW(r, rc)) / 2, 66f, rc, Dots.FG, true)
    }

    private fun drawStart(c: Canvas) {
        val title = "SEEKER PONG"
        val cell = 16f
        Dots.text(c, title, (W - Dots.textW(title, cell)) / 2, 300f, cell, Dots.FG, true)
        // blinking START, old-school
        if (floor(t / 30f).toInt() % 2 == 0 || menuSel == 1) {
            val s = "START"
            val sc = 10f
            Dots.text(c, s, (W - Dots.textW(s, sc)) / 2, 560f, sc, if (menuSel == 0) Dots.FG else Dots.MUTE, menuSel == 0)
        }
        val g = "SETTINGS"
        val gc = 7f
        Dots.text(c, g, (W - Dots.textW(g, gc)) / 2, 700f, gc, if (menuSel == 1) Dots.FG else Dots.DIM, menuSel == 1)
        val h = "RALLY TO CYCLE  MISS TO OPEN"
        val hc = 4f
        Dots.text(c, h, (W - Dots.textW(h, hc)) / 2, 840f, hc, Dots.DIM, false)
        block(c, 1, 8, PX - PAD_W / 2, MID - PAD_H / 2, CELL, Dots.DIM)
        block(c, 1, 8, CX - PAD_W / 2, MID - PAD_H / 2, CELL, Dots.DIM)
        drawLabel(c)
    }

    private fun drawBoom(c: Canvas) {
        val b = boom ?: return
        val k = b.t / 14f
        val r = 20 + k * 70
        line.color = Dots.fade(b.c, 1 - k)
        line.strokeWidth = 6 * (1 - k)
        for (i in 0 until 8) {
            val ang = i * PI / 4
            val ca = cos(ang).toFloat()
            val sa = sin(ang).toFloat()
            c.drawLine(b.x + ca * r * 0.4f, b.y + sa * r * 0.4f, b.x + ca * r, b.y + sa * r, line)
        }
    }

    private fun drawTakeover(c: Canvas) {
        val app = hit ?: return
        val k = min(1f, hitT / 22f)
        val r = k * k * 2300
        fill.color = app.c
        c.drawCircle(W, MID, r, fill)  // grows out of the beam
        if (hitT > 20) {
            val n = app.n
            val cell = if (n.length > 7) 18f else 24f
            val w = Dots.textW(n, cell)
            val faint = if (app.ink == GameApps.INK_WHITE) 0x38FFFFFF else 0x2E000000  // rgba(255,255,255,.22) / rgba(0,0,0,.18)
            block(c, 2, 2, W / 2 - BALL_CELL, 540 - 7 * cell / 2 - 100, BALL_CELL, app.ink)
            Dots.text(c, n, (W - w) / 2, 540 - 7 * cell / 2, cell, app.ink, true, faint)
            monoPaint.color = app.ink
            c.drawText("OPENING", 960f, 540 + 7 * cell / 2 + 70, monoPaint)
            val s = "RALLY $rally"
            val sc = 6f
            Dots.text(c, s, (W - Dots.textW(s, sc)) / 2, 540 + 7 * cell / 2 + 120, sc, app.ink, false)
        }
    }
}
