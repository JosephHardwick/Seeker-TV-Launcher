package com.seeker.tv

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * POCKET, a handheld-style home menu (design/POCKET-SPEC.md), ported from design/seeker-pocket-mockup-v3.html.
 * Everything is drawn into a 384x216 bitmap (the handheld's pixel size widened to 16:9) with plain pixel
 * rects, then blitted x5 with filtering off, so every edge is a hard pixel. The font, art and sounds are
 * original; tiles are each app's own launcher icon shrunk to 32x32. Draws only while something moves,
 * plus a 4-per-second pulse for the breathing frame and the clock colon.
 */
@SuppressLint("ViewConstructor")
class PocketView(
    ctx: Context,
    private val host: SeekerView.Host,
    private val name: () -> String,
    private val accent: () -> Int,
) : View(ctx), HomeScreen {

    private companion object {
        const val W = 384
        const val H = 216
        const val CX = 192
        // message box
        const val BOX_X = 44; const val BOX_Y = 20; const val BOX_W = 296; const val BOX_H = 62
        // tiles: 52x52, 6 apart; the selection is a 66x82 frame with START under the icon
        const val TILE = 52; const val GAP = 6; const val SEL_W = 66; const val SEL_H = 82
        const val NEAR = SEL_W / 2f + GAP + TILE / 2f  // a neighbor of the selection sits 65 out
        const val PITCH = (TILE + GAP).toFloat()       // the rest 58 apart
        const val SEL_TOP = 96
        const val ROW_CY = SEL_TOP + 7 + TILE / 2      // 129
        const val BAND = 192                           // scrollbar band, 192..215, edge to edge
        const val EMPTY = 5
        const val LONG_PRESS_MS = 600L
        const val LAUNCH_AT_MS = 450L
        const val PULSE_MS = 250L
        const val SETTINGS_KEY = "seeker.settings"
        val STREAMERS = Regex("netflix|hulu|disney|amazonvideo|wbd|peacock|appletv|plex|jellyfin|tubi|pluto|paramount|crunchyroll")
        val HOP = intArrayOf(0, 3, 5, 6, 6, 5, 3, 0, 1, 0)
        val MENU = listOf("MOVE", "HIDE", "CANCEL")
        val CORNER = mapOf(1 to intArrayOf(1), 2 to intArrayOf(2, 1), 3 to intArrayOf(3, 1, 1), 4 to intArrayOf(4, 2, 1, 1),
            5 to intArrayOf(5, 3, 2, 1, 1), 6 to intArrayOf(6, 4, 3, 2, 1, 1), 8 to intArrayOf(8, 6, 4, 3, 2, 2, 1, 1))
        val BAR = intArrayOf(0xFFFFFFFF.toInt(), 0xFFFBFBFB.toInt(), 0xFFF6F6F6.toInt(), 0xFFF2F2F2.toInt(), 0xFFEEEEEE.toInt(),
            0xFFEBEBEB.toInt(), 0xFFE8E8E8.toInt(), 0xFFE5E5E5.toInt(), 0xFFE2E2E2.toInt(), 0xFFDFDFDF.toInt(), 0xFFDCDCDC.toInt(),
            0xFFD9D9D9.toInt(), 0xFFD6D6D6.toInt(), 0xFFD3D3D3.toInt(), 0xFFD0D0D0.toInt())
        val TILE_SHADE = intArrayOf(0xFFFBFBFB.toInt(), 0xFFF6F6F6.toInt(), 0xFFF1F1F1.toInt(), 0xFFECECEC.toInt(), 0xFFE8E8E8.toInt(), 0xFFE4E4E4.toInt())
        val WX_COLOR = mapOf("sun" to 0xFFF8A800.toInt(), "cloud" to 0xFF8890A0.toInt(), "rain" to 0xFF3878D8.toInt(),
            "snow" to 0xFF88A8D8.toInt(), "storm" to 0xFFE0A000.toInt())

        // ---------- original pixel font (mockup G): 7-px caps, 5-px x-height, 2-px descenders ----------
        val GLYPHS: Map<Char, Array<BooleanArray>> = mapOf(
        'A' to ".###.|#...#|#...#|#####|#...#|#...#|#...#",
        'B' to "####.|#...#|#...#|####.|#...#|#...#|####.",
        'C' to ".###.|#...#|#....|#....|#....|#...#|.###.",
        'D' to "####.|#...#|#...#|#...#|#...#|#...#|####.",
        'E' to "#####|#....|#....|####.|#....|#....|#####",
        'F' to "#####|#....|#....|####.|#....|#....|#....",
        'G' to ".###.|#...#|#....|#.###|#...#|#...#|.###.",
        'H' to "#...#|#...#|#...#|#####|#...#|#...#|#...#",
        'I' to "###|.#.|.#.|.#.|.#.|.#.|###",
        'J' to "..###|...#.|...#.|...#.|#..#.|#..#.|.##..",
        'K' to "#...#|#..#.|#.#..|##...|#.#..|#..#.|#...#",
        'L' to "#....|#....|#....|#....|#....|#....|#####",
        'M' to "#...#|##.##|#.#.#|#.#.#|#...#|#...#|#...#",
        'N' to "#...#|##..#|#.#.#|#..##|#...#|#...#|#...#",
        'O' to ".###.|#...#|#...#|#...#|#...#|#...#|.###.",
        'P' to "####.|#...#|#...#|####.|#....|#....|#....",
        'Q' to ".###.|#...#|#...#|#...#|#.#.#|#..#.|.##.#",
        'R' to "####.|#...#|#...#|####.|#.#..|#..#.|#...#",
        'S' to ".####|#....|#....|.###.|....#|....#|####.",
        'T' to "#####|..#..|..#..|..#..|..#..|..#..|..#..",
        'U' to "#...#|#...#|#...#|#...#|#...#|#...#|.###.",
        'V' to "#...#|#...#|#...#|#...#|.#.#.|.#.#.|..#..",
        'W' to "#...#|#...#|#...#|#.#.#|#.#.#|##.##|#...#",
        'X' to "#...#|#...#|.#.#.|..#..|.#.#.|#...#|#...#",
        'Y' to "#...#|#...#|.#.#.|..#..|..#..|..#..|..#..",
        'Z' to "#####|....#|...#.|..#..|.#...|#....|#####",
        'a' to "....|....|.##.|...#|.###|#..#|.###",
        'b' to "#...|#...|###.|#..#|#..#|#..#|###.",
        'c' to "....|....|.###|#...|#...|#...|.###",
        'd' to "...#|...#|.###|#..#|#..#|#..#|.###",
        'e' to "....|....|.##.|#..#|####|#...|.###",
        'f' to ".##|#..|###|#..|#..|#..|#..",
        'g' to "....|....|.###|#..#|#..#|#..#|.###|...#|###.",
        'h' to "#...|#...|###.|#..#|#..#|#..#|#..#",
        'i' to "#|.|#|#|#|#|#",
        'j' to ".#|..|.#|.#|.#|.#|.#|.#|#.",
        'k' to "#...|#...|#..#|#.#.|##..|#.#.|#..#",
        'l' to "#|#|#|#|#|#|#",
        'm' to ".....|.....|####.|#.#.#|#.#.#|#.#.#|#.#.#",
        'n' to "....|....|###.|#..#|#..#|#..#|#..#",
        'o' to "....|....|.##.|#..#|#..#|#..#|.##.",
        'p' to "....|....|###.|#..#|#..#|#..#|###.|#...|#...",
        'q' to "....|....|.###|#..#|#..#|#..#|.###|...#|...#",
        'r' to "...|...|#.#|##.|#..|#..|#..",
        's' to "....|....|.###|#...|.##.|...#|###.",
        't' to ".#.|.#.|###|.#.|.#.|.#.|..#",
        'u' to "....|....|#..#|#..#|#..#|#..#|.###",
        'v' to ".....|.....|#...#|#...#|.#.#.|.#.#.|..#..",
        'w' to ".....|.....|#...#|#...#|#.#.#|#.#.#|.#.#.",
        'x' to ".....|.....|#...#|.#.#.|..#..|.#.#.|#...#",
        'y' to "....|....|#..#|#..#|#..#|#..#|.###|...#|###.",
        'z' to "....|....|####|..#.|.#..|#...|####",
        '0' to ".###.|#...#|#..##|#.#.#|##..#|#...#|.###.",
        '1' to "..#..|.##..|..#..|..#..|..#..|..#..|.###.",
        '2' to ".###.|#...#|....#|...#.|..#..|.#...|#####",
        '3' to "####.|....#|....#|.###.|....#|....#|####.",
        '4' to "...#.|..##.|.#.#.|#..#.|#####|...#.|...#.",
        '5' to "#####|#....|####.|....#|....#|#...#|.###.",
        '6' to ".###.|#....|#....|####.|#...#|#...#|.###.",
        '7' to "#####|....#|...#.|..#..|.#...|.#...|.#...",
        '8' to ".###.|#...#|#...#|.###.|#...#|#...#|.###.",
        '9' to ".###.|#...#|#...#|.####|....#|....#|.###.",
        ' ' to "...|...|...|...|...|...|...",
        ':' to ".|.|#|.|.|#|.",
        '/' to "..#|..#|.#.|.#.|.#.|#..|#..",
        '°' to ".#.|#.#|.#.|...|...|...|...",
        '+' to ".....|..#..|..#..|#####|..#..|..#..|.....",
        '.' to ".|.|.|.|.|.|#",
        ',' to "..|..|..|..|..|..|.#|#.",
        '&' to ".##..|#..#.|#.#..|.#...|#.#.#|#..#.|.##.#",
        '\'' to "#|#|.|.|.|.|.",
        '-' to "...|...|...|###|...|...|...",
        '!' to "#|#|#|#|#|.|#",
        '?' to ".###.|#...#|....#|...#.|..#..|.....|..#..",
        '←' to "..#..|.#...|#####|.#...|..#..|.....|.....",
        '→' to "..#..|...#.|#####|...#.|..#..|.....|.....",
        ).mapValues { (_, v) -> v.split('|').map { row -> BooleanArray(row.length) { row[it] == '#' } }.toTypedArray() }
        val BLANK = GLYPHS.getValue(' ')

        fun shade(c: Int, k: Float) = Color.rgb(min(255, (Color.red(c) * k).roundToInt()), min(255, (Color.green(c) * k).roundToInt()), min(255, (Color.blue(c) * k).roundToInt()))
        fun tint(c: Int, k: Float) = Color.rgb((Color.red(c) + (255 - Color.red(c)) * k).roundToInt(), (Color.green(c) + (255 - Color.green(c)) * k).roundToInt(), (Color.blue(c) + (255 - Color.blue(c)) * k).roundToInt())
    }

    /** One tile: an app (or the SETTINGS tile) with its 32x32 pixel icon and the message-box subtitle. */
    private class Tile(val entry: AppEntry, val icon: Bitmap, val subtitle: String) {
        val isSettings get() = entry.key == SETTINGS_KEY
    }

    // ---------- state ----------
    private var tiles: List<Tile> = emptyList()
    private val iconCache = HashMap<String, Bitmap>()
    private var sel = 0
    private var pos = 0f                 // eases toward sel, a third of the way each frame
    private var busy = false
    private var centerDown = false
    private var menuOn = false
    private var menuSel = 0
    private var moving = false
    private var launchT = -1
    private var hop = 0
    private var fade = 0f
    private var arrowL = 0
    private var arrowR = 0
    private var weather = Weather.Now("cloud", "--°", 0)
    private var live = false
    private var firstFrameSent = false
    private val longPress = Runnable { if (centerDown) { centerDown = false; openMenu() } }
    private val pulse = object : Runnable {
        override fun run() { invalidate(); if (live) postDelayed(this, if (moving) PULSE_MS / 2 else PULSE_MS) }
    }
    private val frame = Runnable { step() }
    private var framePosted = false

    private val tz = TimeZone.getDefault()
    private val dateFmt = SimpleDateFormat("M/d", Locale.US).apply { timeZone = tz }
    private val hourFmt = SimpleDateFormat("h", Locale.US).apply { timeZone = tz }
    private val minFmt = SimpleDateFormat("mm", Locale.US).apply { timeZone = tz }

    // ---------- the pixel canvas ----------
    private val screen = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val pc = Canvas(screen)
    private val px = Paint()                                  // no anti-aliasing anywhere
    private val blit = Paint().apply { isFilterBitmap = false }
    private val dst = Rect()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.WHITE)
    }

    // ---------- HomeScreen ----------
    override fun showApps(list: List<AppEntry>) {
        iconCache.values.forEach { it.recycle() }
        iconCache.clear()  // icons may have changed with an app update
        setTiles(list)
    }

    private fun setTiles(list: List<AppEntry>, keepSel: Int = -1) {
        val keep = tiles.getOrNull(sel)?.entry?.key
        val settings = AppEntry(SETTINGS_KEY, "SETTINGS", Apps.SETTINGS_COLOR, null, android.content.Intent(), null, "Settings")
        tiles = (list + settings).map { Tile(it, iconFor(it), subtitleFor(it)) }
        sel = if (keepSel >= 0) min(keepSel, tiles.size - 1)
            else tiles.indexOfFirst { it.entry.key == keep }.takeIf { it >= 0 } ?: min(sel, tiles.size - 1)
        pos = sel.toFloat()
        Toaster.brand = tiles[sel].entry.color
        invalidate()
    }

    override fun setWeather(w: Weather.Now) { weather = w; invalidate() }
    override fun tick() = invalidate()
    override fun setStats(values: FloatArray, details: Array<String>) = Unit  // no STATS FOR NERDS cards here
    override fun hideStats() = Unit

    override fun resetLaunch() {
        launchT = -1; hop = 0; fade = 0f
        busy = false
        centerDown = false
        removeCallbacks(longPress)
        invalidate()
    }

    override fun resetModes() {
        menuOn = false
        if (moving) dropMove()
    }

    override val isLaunching get() = busy

    /** NAME or FAVORITE COLOR changed. */
    fun refresh() = invalidate()

    // ---------- tiles ----------
    private fun subtitleFor(e: AppEntry): String = when {
        e.key == SETTINGS_KEY -> "Seeker and TV settings"
        e.key == Apps.PLAY_STORE -> "Get more apps"
        e.key == "com.google.android.youtube.tv" -> "Videos"
        else -> {
            val cat = try { context.packageManager.getApplicationInfo(e.key, 0).category } catch (x: Exception) { -1 }
            when (cat) {
                ApplicationInfo.CATEGORY_VIDEO -> "Movies & shows"
                ApplicationInfo.CATEGORY_AUDIO -> "Music"
                ApplicationInfo.CATEGORY_GAME -> "Games"
                ApplicationInfo.CATEGORY_NEWS -> "News"
                ApplicationInfo.CATEGORY_IMAGE -> "Photos"
                // streaming apps rarely declare a category; known ones by package name
                else -> if (e.key.contains(STREAMERS)) "Movies & shows" else "App"
            }
        }
    }

    /** The app's launcher icon drawn at 128 px and shrunk to 32x32 with a hard alpha edge (the mockup's makeIcon). */
    private fun iconFor(e: AppEntry): Bitmap = iconCache.getOrPut(e.key) {
        val big = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val c = Canvas(big)
        if (e.key == SETTINGS_KEY) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8E9096.toInt() }
            c.drawRoundRect(RectF(0f, 0f, 128f, 128f), 26f, 26f, p)
            p.color = Color.WHITE
            c.save(); c.translate(16f, 16f); c.scale(4f, 4f); c.drawPath(gearPath(), p); c.restore()
        } else {
            val pm = context.packageManager
            val d: Drawable? = e.component?.let { cn -> try { pm.getActivityIcon(cn) } catch (x: Exception) { null } }
                ?: try { pm.getApplicationIcon(e.key) } catch (x: Exception) { null }
            if (d != null) { d.setBounds(0, 0, 128, 128); d.draw(c) }
            else c.drawColor(e.color)
        }
        val small = Bitmap.createScaledBitmap(big, 32, 32, true)
        big.recycle()
        val px = IntArray(32 * 32)
        small.getPixels(px, 0, 32, 0, 0, 32, 32)
        for (i in px.indices) px[i] = if (Color.alpha(px[i]) < 110) 0 else px[i] or 0xFF000000.toInt()
        val out = Bitmap.createBitmap(px, 32, 32, Bitmap.Config.ARGB_8888)
        small.recycle()
        out
    }

    private fun gearPath() = Path().apply {
        addCircle(12f, 12f, 7.6f, Path.Direction.CW)
        for (k in 0 until 8) {
            val tooth = Path().apply { addRect(RectF(10.2f, 1.6f, 13.8f, 6f), Path.Direction.CW) }
            tooth.transform(Matrix().apply { setRotate(k * 45f, 12f, 12f) })
            op(tooth, Path.Op.UNION)
        }
        op(Path().apply { addCircle(12f, 12f, 3.4f, Path.Direction.CW) }, Path.Op.DIFFERENCE)
    }

    // ---------- remote ----------
    override fun onKey(e: KeyEvent): Boolean {
        val code = e.keyCode
        val isCenter = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
            code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A
        val handled = isCenter || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_UP ||
            code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        if (!handled) return false
        if (busy || tiles.isEmpty()) return true

        if (e.action == KeyEvent.ACTION_UP) {
            if (isCenter && centerDown) { centerDown = false; removeCallbacks(longPress); go() }
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return true
        val sideways = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        if (e.repeatCount > 0 && !(sideways && !menuOn)) return true  // holding LEFT/RIGHT runs along the row

        if (menuOn) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> if (menuSel < MENU.size - 1) { menuSel++; Sfx.play(Sfx.Id.POCKET_MOVE) }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> if (menuSel > 0) { menuSel--; Sfx.play(Sfx.Id.POCKET_MOVE) }
                KeyEvent.KEYCODE_BACK -> menuOn = false
                else -> pickMenu()
            }
            invalidate()
            return true
        }
        if (moving) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (sel < tiles.size - 2) { swap(sel, sel + 1); select(sel + 1); arrowR = 6 }   // SETTINGS stays last
                KeyEvent.KEYCODE_DPAD_LEFT -> if (sel > 0) { swap(sel, sel - 1); select(sel - 1); arrowL = 6 }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> Unit
                else -> { dropMove(); Sfx.play(Sfx.Id.POCKET_OK) }
            }
            kick()
            return true
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> { arrowR = 6; if (sel < tiles.size - 1) select(sel + 1) else Sfx.play(Sfx.Id.POCKET_EDGE) }
            KeyEvent.KEYCODE_DPAD_LEFT -> { arrowL = 6; if (sel > 0) select(sel - 1) else Sfx.play(Sfx.Id.POCKET_EDGE) }
            KeyEvent.KEYCODE_BACK -> host.back()
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> Unit
            else -> if (tiles[sel].isSettings) { Sfx.play(Sfx.Id.POCKET_OK); host.openSettings() } else {
                // launch on key up; held 600 ms = item menu
                centerDown = true
                postDelayed(longPress, LONG_PRESS_MS)
            }
        }
        kick()
        return true
    }

    private fun select(i: Int) {
        sel = i
        Toaster.brand = tiles[sel].entry.color
        Sfx.play(Sfx.Id.POCKET_MOVE)
    }

    private fun swap(i: Int, j: Int) {
        tiles = tiles.toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t }
    }

    private fun openMenu() {
        menuOn = true
        menuSel = 0
        Sfx.play(Sfx.Id.POCKET_MENU)
        invalidate()
    }

    private fun pickMenu() {
        val pick = MENU[menuSel]
        menuOn = false
        Sfx.play(Sfx.Id.POCKET_OK)
        val t = tiles.getOrNull(sel) ?: return
        when (pick) {
            "MOVE" -> { moving = true; removeCallbacks(pulse); post(pulse) }
            "HIDE" -> {
                host.hide(t.entry)
                iconCache.remove(t.entry.key)
                setTiles(tiles.filter { it !== t && !it.isSettings }.map { it.entry }, keepSel = sel)
            }
        }
    }

    private fun dropMove() {
        moving = false
        host.saveOrder(tiles.filter { !it.isSettings }.map { it.entry.key })
        invalidate()
    }

    // ---------- launch: the tile hops, the screen goes white in hard steps ----------
    private fun go() {
        if (busy) return
        val t = tiles.getOrNull(sel) ?: return
        busy = true
        Sfx.play(Sfx.Id.POCKET_OK)
        launchT = 0
        kick()
        postDelayed({ if (busy) host.launch(t.entry) }, LAUNCH_AT_MS)
    }

    // ---------- frames: run only while something moves ----------
    private fun inMotion() = abs(sel - pos) > 0f || arrowL > 0 || arrowR > 0 || (launchT in 0..40)

    /** Redraw, and keep stepping frames while something is in motion. */
    private fun kick() {
        invalidate()
        if (!framePosted && inMotion()) { framePosted = true; postOnAnimation(frame) }
    }

    private fun step() {
        framePosted = false
        val d = sel - pos
        if (d != 0f) pos = if (abs(d) < 0.03f) sel.toFloat() else pos + d / 3
        if (arrowL > 0) arrowL--
        if (arrowR > 0) arrowR--
        if (launchT >= 0 && launchT <= 40) {
            launchT++
            hop = HOP[min(launchT, HOP.size - 1)]
            if (launchT > 8) fade = min(1f, ((launchT - 8) / 3) / 6f)  // white in 6 hard steps
        }
        kick()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        setLive(visibility == VISIBLE)
    }

    override fun onDetachedFromWindow() {
        setLive(false)
        super.onDetachedFromWindow()
    }

    private fun setLive(on: Boolean) {
        if (on == live) return
        live = on
        removeCallbacks(pulse)
        if (on) postDelayed(pulse, PULSE_MS)
    }

    // ---------- pixel primitives ----------
    private fun rect(x: Int, y: Int, w: Int, h: Int, col: Int) {
        if (w <= 0 || h <= 0) return
        px.color = col
        pc.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), px)
    }

    private fun inset(cut: IntArray, h: Int, k: Int) = when {
        k < cut.size -> cut[k]
        h - 1 - k < cut.size -> cut[h - 1 - k]
        else -> 0
    }

    /** Rounded box with stepped pixel corners; with a border, a 1-px outline that follows the steps. */
    private fun box(x: Int, y: Int, w: Int, h: Int, r: Int, fill: Int, border: Int? = null) {
        val cut = CORNER[r] ?: IntArray(0)
        for (k in 0 until h) {
            val a = inset(cut, h, k)
            if (border == null) { rect(x + a, y + k, w - 2 * a, 1, fill); continue }
            if (k == 0 || k == h - 1) { rect(x + a, y + k, w - 2 * a, 1, border); continue }
            val e = max(a, max(inset(cut, h, k - 1) - 1, inset(cut, h, k + 1) - 1))
            rect(x + a, y + k, e - a + 1, 1, border)
            rect(x + w - 1 - e, y + k, e - a + 1, 1, border)
            rect(x + e + 1, y + k, w - 2 * e - 2, 1, fill)
        }
    }

    private fun textW(s: String): Int {
        var w = 0
        for (ch in s) w += (GLYPHS[ch] ?: BLANK)[0].size + 1
        return max(0, w - 1)
    }

    private fun text(s: String, x0: Int, y: Int, col: Int) {
        var x = x0
        for (ch in s) {
            val g = GLYPHS[ch] ?: BLANK
            for (r in g.indices) {
                // runs of lit pixels as one rect
                var q = 0
                while (q < g[r].size) {
                    if (!g[r][q]) { q++; continue }
                    val s0 = q
                    while (q < g[r].size && g[r][q]) q++
                    rect(x + s0, y + r, q - s0, 1, col)
                }
            }
            x += g[0].size + 1
        }
    }

    private fun centered(s: String, y: Int, col: Int) = text(s, CX - textW(s) / 2, y, col)

    // ---------- drawing (mockup draw()) ----------
    override fun onDraw(canvas: Canvas) {
        val acc = accent()
        drawBackground()
        drawBar(acc)
        drawBox(acc)
        drawRow(acc)
        drawScroll(acc)
        if (fade > 0f) rect(0, 0, W, H, Color.argb((255 * fade).roundToInt(), 255, 255, 255))
        dst.set(0, 0, width, height)
        canvas.drawBitmap(screen, null, dst, blit)
        if (!firstFrameSent) { firstFrameSent = true; post { host.firstFrame() } }
    }

    private fun drawBackground() {
        rect(0, 0, W, H, 0xFFF6F6F6.toInt())
        var y = 17
        while (y < BAND) { rect(0, y, W, 1, 0xFFEFEFEF.toInt()); y += 2 }  // LCD lines
    }

    private fun drawBar(acc: Int) {
        for (i in BAR.indices) rect(0, i, W, 1, BAR[i])
        rect(0, 15, W, 1, 0xFFA0A0A0.toInt())
        val n = name()
        val nw = textW(n) + 10
        box(3, 3, nw, 11, 2, acc)
        rect(5, 12, nw - 4, 1, shade(acc, .78f))
        text(n, 8, 5, Color.WHITE)
        val now = Date()
        val colon = (SystemClock.elapsedRealtime() / 1000) % 2 == 0L   // blinks once a second
        val clock = hourFmt.format(now) + (if (colon) ":" else " ") + minFmt.format(now)
        var x = W - 4 - textW(clock); text(clock, x, 5, 0xFF383838.toInt())
        val date = dateFmt.format(now)
        x -= 10 + textW(date); text(date, x, 5, 0xFF606060.toInt())
        val temp = weather.temp
        x -= 10 + textW(temp); text(temp, x, 5, 0xFF606060.toInt())
        Dots.ICONS[weather.icon]?.let { icon ->
            val col = WX_COLOR[weather.icon] ?: 0xFF707070.toInt()
            for (r in 0 until 9) for (q in 0 until 9) if (icon[r][q]) rect(x - 12 + q, 4 + r, 1, 1, col)
        }
    }

    /** The message box: light gray, dark outline, big stepped corners, a pointer down at the selection. */
    private fun drawBox(acc: Int) {
        val outline = 0xFF6C6C6C.toInt()
        val fill = 0xFFE8E8E8.toInt()
        box(BOX_X, BOX_Y, BOX_W, BOX_H, 8, fill, outline)
        rect(BOX_X + 8, BOX_Y + 2, BOX_W - 16, 1, 0xFFF4F4F4.toInt())
        for (i in 0 until 7) {
            val w = 13 - 2 * i
            val x = CX - w / 2
            val y = BOX_Y + BOX_H - 1 + i
            rect(x, y, w, 1, fill)
            rect(x - 1, y, 1, 1, outline); rect(x + w, y, 1, 1, outline)
        }
        rect(CX, BOX_Y + BOX_H + 6, 1, 1, outline)
        if (menuOn) { drawMenu(acc); return }
        val t = tiles.getOrNull(sel) ?: return
        val ink = 0xFF404040.toInt()
        centered(t.entry.title, BOX_Y + 18, ink)
        centered(if (moving) "← → to move, OK to drop" else t.subtitle, BOX_Y + 34, ink)
    }

    private fun drawMenu(acc: Int) {
        centered("What would you like to do?", BOX_Y + 12, 0xFF404040.toInt())
        val bw = 70
        val gap = 8
        var x = CX - (3 * bw + 2 * gap) / 2
        val y = BOX_Y + 32
        for ((i, l) in MENU.withIndex()) {
            val on = i == menuSel
            val col = if (l == "HIDE") 0xFFE83838.toInt() else acc
            box(x, y + 2, bw, 16, 4, 0xFFA8A8A8.toInt())
            box(x, y, bw, 16, 4, if (on) tint(col, .82f) else 0xFFFAFAFA.toInt(), if (on) col else 0xFF9A9A9A.toInt())
            text(l, x + bw / 2 - textW(l) / 2, y + 5, if (on) shade(col, .8f) else 0xFF606060.toInt())
            x += bw + gap
        }
    }

    /** Center x of slot i while the row is at pos: the selection's neighbors sit 65 out, the rest 58 apart. */
    private fun slotX(i: Int): Int {
        val d = i - pos
        val a = abs(d)
        return (CX + sign(d) * (if (a <= 1) a * NEAR else NEAR + (a - 1) * PITCH)).roundToInt()
    }

    private fun wiggle(i: Int) = if (((SystemClock.uptimeMillis() / 133) + i) % 2 == 0L) 1 else -1

    private fun drawRow(acc: Int) {
        val n = tiles.size + EMPTY
        for (i in 0 until n) {
            if (i == sel) continue
            var x = slotX(i) - TILE / 2
            val y = ROW_CY - TILE / 2
            if (x + TILE < 0 || x > W) continue
            if (i >= tiles.size) { dotted(x, y, TILE, TILE); continue }
            if (moving) x += wiggle(i)
            box(x, y + 3, TILE, TILE, 6, 0xFFC4C4C4.toInt())                 // shadow ledge
            box(x, y, TILE, TILE, 6, Color.WHITE, 0xFFB4B4B4.toInt())
            for (r in TILE_SHADE.indices) rect(x + 4, y + TILE - 8 + r, TILE - 8, 1, TILE_SHADE[r])
            pc.drawBitmap(tiles[i].icon, (x + 10).toFloat(), (y + 10).toFloat(), null)
        }
        drawSelected(acc)
    }

    /** The selection: a taller frame in the accent color, the icon in a white box, START underneath. */
    private fun drawSelected(acc: Int) {
        val t = tiles.getOrNull(sel) ?: return
        val cx = slotX(sel)
        var x = cx - SEL_W / 2
        val y = SEL_TOP - hop - (if (moving) 4 else 0)
        if (moving) x += wiggle(sel)
        val d = if (moving || launchT >= 0) 0 else ((SystemClock.uptimeMillis() / PULSE_MS) % 2).toInt()  // breathes 1 px
        box(x - d, y + 3 - d, SEL_W + 2 * d, SEL_H + 2 * d, 8, shade(acc, .7f))
        box(x - d, y - d, SEL_W + 2 * d, SEL_H + 2 * d, 8, acc, shade(acc, .75f))
        rect(x + 6, y + 1 - d, SEL_W - 12, 1, tint(acc, .45f))
        box(x + 7, y + 7, TILE, TILE, 6, Color.WHITE, shade(acc, .8f))
        pc.drawBitmap(t.icon, (x + 17).toFloat(), (y + 17).toFloat(), null)
        val label = if (t.isSettings) "OPEN" else "START"
        text(label, cx - textW(label) / 2, y + SEL_H - 15, Color.WHITE)
    }

    private fun dotted(x: Int, y: Int, w: Int, h: Int) {
        val c = 0xFFC8C8C8.toInt()
        var i = 4
        while (i < w - 4) { rect(x + i, y, 1, 1, c); rect(x + i, y + h - 1, 1, 1, c); i += 2 }
        var j = 4
        while (j < h - 4) { rect(x, y + j, 1, 1, c); rect(x + w - 1, y + j, 1, 1, c); j += 2 }
    }

    /** The scrollbar band: edge to edge, accent arrow tabs at both ends, a window over the slots on screen. */
    private fun drawScroll(acc: Int) {
        val n = tiles.size + EMPTY
        val y = BAND
        val h = H - BAND
        rect(0, y, W, 1, 0xFF9C9C9C.toInt())
        rect(0, y + 1, W, h - 1, 0xFFDCDCDC.toInt())
        rect(0, y + 1, W, 1, 0xFFF2F2F2.toInt())
        tab(0, -1, arrowL > 0, acc)
        tab(W - 26, 1, arrowR > 0, acc)
        val tx = 40
        val tw = W - 80
        val my = y + 12
        val step = tw.toFloat() / (n - 1)
        for (i in 0 until n) {
            val mx = (tx + step * i).roundToInt()
            if (i < tiles.size) rect(mx - 1, my - 1, 3, 3, 0xFF505050.toInt()) else rect(mx, my, 1, 1, 0xFF9A9A9A.toInt())
            if (i < n - 1) { var k = 3; while (k < step - 2) { rect((mx + k), my, 1, 1, 0xFF9A9A9A.toInt()); k += 3 } }
        }
        val ww = (step * 7).roundToInt()
        val wx = max(tx - 8, min(tx + tw + 8 - ww, (tx + step * pos - ww / 2f).roundToInt()))
        box(wx, y + 4, ww, h - 8, 2, 0x8CFFFFFF.toInt(), acc)
        val sx = (tx + step * sel).roundToInt()
        rect(sx - 2, my - 2, 5, 5, 0xFF2878E8.toInt())
    }

    /** Arrow tab at an edge of the band; dir -1 points left, 1 points right. */
    private fun tab(x: Int, dir: Int, pressed: Boolean, acc: Int) {
        val y = BAND + 1
        val h = H - BAND - 1
        val w = 26
        rect(x, y, w, h, tint(acc, if (pressed) .55f else .72f))
        rect(if (dir < 0) x + w - 1 else x, y, 1, h, shade(acc, .8f))
        val cy = y + h / 2 + (if (pressed) 1 else 0)
        val ax = x + 10
        for (i in 0 until 6) {
            val hh = 11 - 2 * i
            rect(if (dir < 0) ax + 5 - i else ax + i, cy - hh / 2, 1, hh, acc)  // tallest column at the base, tip outward
        }
    }
}
