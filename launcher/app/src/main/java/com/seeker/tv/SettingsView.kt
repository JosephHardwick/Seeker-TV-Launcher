package com.seeker.tv

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.min

/**
 * Seeker's own settings screen (spec v4), same canvas language as the wheel: dot-matrix headings,
 * mono values, cursor dot on the selected row. Three pages: the main list, HIDDEN APPS, ABOUT.
 * BACK goes up one level; BACK on the main list returns home (the wheel, or Flap's START).
 * M5: HOME MODE row (WHEEL / FLAP). In FLAP mode the list starts with APP LIST (the wheel for one
 * visit) and the calm rainbow strip runs across the top.
 * M6: HOME MODE cycles WHEEL / FLAP / INVADERS (both games get APP LIST and the strip), and SOUND: ON / OFF
 * mutes every mode.
 * M8: HOME MODE adds HUNT. In HUNT the same pages are drawn in the Hunt mockup's style instead: the
 * daytime scene, a black panel with a white border, square-pixel text and a `>` cursor.
 * v0.9.0: NAME (HELLO <NAME>) and WEATHER LOCATION take text from the TV keyboard. HOME MODE lists only the
 * modes built into the flavor and is left out of the wheel-only build.
 */
@SuppressLint("ViewConstructor")
class SettingsView(ctx: Context, private val host: Host) : View(ctx) {

    interface Host {
        fun openSystemSettings()
        fun openInputs()
        fun name(): String                // NAME: the greeting is HELLO <NAME>
        fun editName()
        fun weatherPlace(): String        // WEATHER LOCATION
        fun editWeatherPlace()
        fun allApps(): List<AppEntry>
        fun hiddenKeys(): Set<String>
        fun toggleHidden(entry: AppEntry)
        fun about(): List<Pair<String, String>>
        fun closeSettings()
        fun homeModes(): List<String>     // WHEEL plus the games built into this flavor
        fun homeMode(): String            // one of homeModes()
        fun cycleHomeMode()
        fun openAppList()
        fun soundOn(): Boolean
        fun toggleSound()
        fun statsOn(): Boolean            // v5: CPU / MEMORY / STORAGE widgets on the wheel
        fun toggleStats()
    }

    private companion object {
        const val W = 1920f
        const val H = 1080f
        const val BG = 0xFF0B0C0E.toInt()
        const val GRID_DOT = 0xFF16181C.toInt()
        const val VALUE = 0xFF8B9098.toInt()
        const val LEFT = 150f
        const val CURSOR_X = 101f
        const val TITLE_Y = 120f
        const val TITLE_CELL = 12f
        const val LIST_TOP = 330f
        const val VALUE_X = 1100f
        val MAIN = listOf("SYSTEM SETTINGS", "INPUTS", "HOME MODE", "SOUND", "STATS FOR NERDS", "NAME", "WEATHER LOCATION", "HIDDEN APPS", "ABOUT")
        const val ROW_SP = 104f
        const val LAST_ROW = 1000f  // lowest row center
        const val HUNT_VALUE = 0xFFBCBCBC.toInt()  // HUNT style: values and visible apps
        const val HUNT_DIM = 0xFF4C4C4C.toInt()    // HUNT style: hidden apps
    }

    private enum class Page { MAIN, HIDDEN, ABOUT }

    private var page = Page.MAIN
    private var mainSel = 0
    private var rows: List<String> = MAIN
    private val game get() = host.homeMode() != "WHEEL"  // FLAP, INVADERS or HUNT
    private val hunt get() = host.homeMode() == "HUNT"
    private var rainbowT = 0  // the mockup's frame counter t, for the strip's hue

    /** The main list; the game modes add APP LIST on top. HOME MODE only when a game is built in. */
    private fun mainRows(): List<String> {
        val main = if (host.homeModes().size > 1) MAIN else MAIN - "HOME MODE"
        return if (game) listOf("APP LIST") + main else main
    }
    private var appSel = 0
    private var apps: List<AppEntry> = emptyList()
    private var hidden: Set<String> = emptySet()
    private var about: List<Pair<String, String>> = emptyList()

    // HIDDEN APPS scrolls; animate the scroll like the wheel
    private val ease = PathInterpolator(.2f, .7f, .2f, 1f)
    private var scroll = 0f
    private var scrollAnim: ValueAnimator? = null

    private val gridPaint = Paint().apply {
        val tile = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        Canvas(tile).drawCircle(12f, 12f, 1.7f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GRID_DOT })
        shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
            setLocalMatrix(Matrix().apply { setTranslate(12f, 12f) })
        }
    }
    private val glowPaint = Paint().apply {
        shader = RadialGradient(
            0f, 540f, 900f,
            intArrayOf(Dots.fade(Apps.SETTINGS_COLOR, 0.14f), Dots.fade(Apps.SETTINGS_COLOR, 0f)),
            floatArrayOf(0f, 0.7f), Shader.TileMode.CLAMP,
        ).apply { setLocalMatrix(Matrix().apply { setScale(1f, 700f / 900f, 0f, 540f) }) }
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, 500, false)
        textSize = 24f
        letterSpacing = 0.14f
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(BG)
    }

    /** Called each time the screen opens: start on the main list, top row. */
    fun open() {
        page = Page.MAIN
        rows = mainRows()
        mainSel = 0
        invalidate()
    }

    /** HOME MODE changed (here or by the MODE broadcast): rebuild the list, keep the cursor on its row. */
    fun modeChanged() {
        val at = rows.getOrNull(mainSel)
        rows = mainRows()
        mainSel = rows.indexOf(at).takeIf { it >= 0 } ?: 0
        invalidate()
    }

    private fun ms(d: Long): Long {
        val s = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        return if (s > 0f) (d / s).toLong() else d
    }

    // ---------- remote ----------
    fun onKey(e: KeyEvent): Boolean {
        val code = e.keyCode
        val isCenter = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
            code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A
        val handled = isCenter || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_UP ||
            code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        if (!handled) return false
        if (e.action != KeyEvent.ACTION_DOWN) return true
        // lists scroll on key repeat; OK and BACK act once
        if (e.repeatCount > 0 && code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN) return true

        when (page) {
            Page.MAIN -> when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> if (mainSel < rows.size - 1) mainSel++
                KeyEvent.KEYCODE_DPAD_UP -> if (mainSel > 0) mainSel--
                KeyEvent.KEYCODE_BACK -> { host.closeSettings(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> Unit
                else -> pickMain()
            }
            Page.HIDDEN -> when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> if (appSel < apps.size - 1) { appSel++; scrollTo(true) }
                KeyEvent.KEYCODE_DPAD_UP -> if (appSel > 0) { appSel--; scrollTo(true) }
                KeyEvent.KEYCODE_BACK -> page = Page.MAIN
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> Unit
                else -> apps.getOrNull(appSel)?.let { host.toggleHidden(it); hidden = host.hiddenKeys() }
            }
            Page.ABOUT -> if (code == KeyEvent.KEYCODE_BACK || isCenter) page = Page.MAIN
        }
        invalidate()
        return true
    }

    private fun pickMain() {
        when (rows[mainSel]) {
            "APP LIST" -> host.openAppList()
            "SYSTEM SETTINGS" -> host.openSystemSettings()
            "INPUTS" -> host.openInputs()
            "HOME MODE" -> { host.cycleHomeMode(); modeChanged() }
            "SOUND" -> host.toggleSound()
            "STATS FOR NERDS" -> host.toggleStats()
            "NAME" -> host.editName()
            "WEATHER LOCATION" -> host.editWeatherPlace()
            "HIDDEN APPS" -> {
                apps = host.allApps()
                hidden = host.hiddenKeys()
                appSel = 0
                page = Page.HIDDEN
                scrollTo(false)
            }
            "ABOUT" -> { about = host.about(); page = Page.ABOUT }
        }
    }

    // HIDDEN APPS rows
    private val rowCell = 7f
    private val rowSp = 84f
    private val visibleRows get() = if (hunt) 7 else 8

    /** Keep the selected row inside the window, scrolling a row at a time. */
    private fun scrollTo(animate: Boolean) {
        val first = (scroll / rowSp).toInt()
        val target = when {
            appSel < first -> appSel
            appSel >= first + visibleRows -> appSel - visibleRows + 1
            else -> first
        } * rowSp
        scrollAnim?.cancel()
        if (!animate || target == scroll) { scroll = target; return }
        val from = scroll
        scrollAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms(300)
            interpolator = ease
            addUpdateListener { scroll = from + (target - from) * ease.getInterpolation(it.animatedFraction); invalidate() }
            start()
        }
    }

    // ---------- drawing ----------
    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.scale(width / W, height / H)
        if (hunt) {
            drawHunt(canvas)
            canvas.restore()
            return
        }
        canvas.drawRect(0f, 0f, W, H, gridPaint)
        canvas.drawRect(0f, 0f, W, H, glowPaint)
        if (game) {
            val style = if (host.homeMode() == "INVADERS") Rainbow.INVADERS else Rainbow.FLAP
            Rainbow.draw(canvas, full = false, t = rainbowT++, style = style)
            postInvalidateOnAnimation()
        }  // backdrop, as in the mockup
        when (page) {
            Page.MAIN -> drawMain(canvas)
            Page.HIDDEN -> drawHidden(canvas)
            Page.ABOUT -> drawAbout(canvas)
        }
        canvas.restore()
    }

    private fun title(c: Canvas, t: String) = Dots.text(c, t, LEFT, TITLE_Y, TITLE_CELL, Dots.FG, true)

    private fun cursor(c: Canvas, cy: Float, color: Int) {
        fill.shader = RadialGradient(
            CURSOR_X, cy, 24f,
            intArrayOf(Dots.fade(color, 0.75f), Dots.fade(color, 0.3f), Dots.fade(color, 0f)),
            floatArrayOf(0.15f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawCircle(CURSOR_X, cy, 24f, fill)
        fill.shader = null
        fill.color = color
        c.drawCircle(CURSOR_X, cy, 5f, fill)
    }

    private fun value(c: Canvas, text: String, cy: Float, color: Int) {
        mono.color = color
        c.drawText(text, VALUE_X, cy + mono.textSize * 0.36f, mono)
    }

    private fun drawMain(c: Canvas) {
        title(c, "SETTINGS")
        val cell = 8f
        val sp = min(ROW_SP, (LAST_ROW - LIST_TOP) / (rows.size - 1))  // 8 rows in the game modes still fit
        for ((i, label) in rows.withIndex()) {
            val cy = LIST_TOP + i * sp
            val on = i == mainSel
            if (on) cursor(c, cy, Dots.FG)
            Dots.text(c, label, LEFT, cy - 3.5f * cell, cell, if (on) Dots.FG else Dots.MUTE, on, Dots.SEL_GRID)
            val v = when (label) {
                "HOME MODE" -> host.homeMode()
                "SOUND" -> if (host.soundOn()) "ON" else "OFF"
                "STATS FOR NERDS" -> if (host.statsOn()) "ON" else "OFF"
                "NAME" -> host.name()
                "WEATHER LOCATION" -> host.weatherPlace()
                "HIDDEN APPS" -> "${host.hiddenKeys().count { k -> host.allApps().any { it.key == k } }} HIDDEN"
                else -> null
            }
            if (v != null) value(c, v, cy, if (on) Dots.FG else VALUE)
        }
    }

    private fun drawHidden(c: Canvas) {
        title(c, "HIDDEN APPS")
        val top = LIST_TOP - 30f
        c.save()
        c.clipRect(0f, top - rowSp / 2, W, top + (visibleRows - 0.5f) * rowSp)
        for ((i, app) in apps.withIndex()) {
            val cy = top + i * rowSp - scroll
            if (cy < top - rowSp || cy > top + visibleRows * rowSp) continue
            val on = i == appSel
            val isHidden = app.key in hidden
            val color = when {
                isHidden && on -> Dots.MUTE
                isHidden -> Dots.DIM
                on -> app.color
                else -> Dots.MUTE
            }
            if (on) cursor(c, cy, if (isHidden) Dots.MUTE else app.color)
            val parts = app.parts
            if (on && !isHidden && parts != null) {
                var x = LEFT
                for ((txt, col) in parts) {
                    Dots.text(c, txt, x, cy - 3.5f * rowCell, rowCell, col, true, Dots.SEL_GRID)
                    x += txt.length * rowCell * 6
                }
            } else {
                Dots.text(c, app.dots, LEFT, cy - 3.5f * rowCell, rowCell, color, on, Dots.SEL_GRID)
            }
            value(c, if (isHidden) "HIDDEN" else "ON WHEEL", cy, if (on) Dots.FG else if (isHidden) Dots.DIM else VALUE)
        }
        c.restore()
    }

    private fun drawAbout(c: Canvas) {
        title(c, "ABOUT")
        val cell = 6f
        for ((i, kv) in about.withIndex()) {
            val cy = LIST_TOP + i * 90f
            Dots.text(c, kv.first, LEFT, cy - 3.5f * cell, cell, Dots.MUTE, false)
            value(c, kv.second, cy, Dots.FG)
        }
    }

    // ---------- HUNT style (mockup drawSettings(): black panel, white border, `>` cursor) ----------
    private val huntRect = Paint()

    private fun drawHunt(c: Canvas) {
        Pixel.backdrop(c, rainbowT++)
        postInvalidateOnAnimation()  // the clouds drift
        huntRect.color = Pixel.INK; c.drawRect(380f, 150f, 1540f, 930f, huntRect)
        huntRect.color = Pixel.PAPER; c.drawRect(392f, 162f, 1528f, 918f, huntRect)
        huntRect.color = Pixel.INK; c.drawRect(404f, 174f, 1516f, 906f, huntRect)
        when (page) {
            Page.MAIN -> drawHuntMain(c)
            Page.HIDDEN -> drawHuntHidden(c)
            Page.ABOUT -> drawHuntAbout(c)
        }
    }

    private fun huntTitle(c: Canvas, t: String) = Pixel.text(c, t, (W - Pixel.textW(t, 11f)) / 2, 200f, 11f, Pixel.PAPER)

    private fun drawHuntMain(c: Canvas) {
        huntTitle(c, "SETTINGS")
        // the mockup's 6 rows sit 95 apart from y=300 at cell 8; the 8 real rows need cell 7 to fit the panel
        val sp = min(95f, 550f / (rows.size - 1))
        for ((i, label) in rows.withIndex()) {
            val y = 300 + i * sp
            val on = i == mainSel
            val v = when (label) {
                "HOME MODE" -> host.homeMode()
                "SOUND" -> if (host.soundOn()) "ON" else "OFF"
                "STATS FOR NERDS" -> if (host.statsOn()) "ON" else "OFF"
                "NAME" -> host.name()
                "WEATHER LOCATION" -> host.weatherPlace()
                "HIDDEN APPS" -> "${host.hiddenKeys().count { k -> host.allApps().any { it.key == k } }}"
                else -> null
            }
            val full = if (v != null) "$label $v" else label
            val cell = min(7f, 1000f / (full.length * 6))  // long values (a long NAME or city) shrink to stay in the panel
            val x = (W - Pixel.textW(full, cell)) / 2
            Pixel.text(c, label, x, y, cell, if (on) Pixel.PAPER else Pixel.GRAY)
            if (v != null) Pixel.text(c, v, x + (label.length + 1) * cell * 6, y, cell, if (on) Pixel.SKY_BAND else HUNT_VALUE)
            if (on) Pixel.text(c, ">", x - 7.5f * cell, y, cell, Pixel.PAPER)
        }
    }

    private fun drawHuntHidden(c: Canvas) {
        huntTitle(c, "HIDDEN APPS")
        val top = 300f
        val cell = 6f
        c.save()
        c.clipRect(404f, top - 20, 1516f, top + (visibleRows - 1) * rowSp + 7 * cell + 20)
        for ((i, app) in apps.withIndex()) {
            val y = top + i * rowSp - scroll
            if (y < top - rowSp || y > top + visibleRows * rowSp) continue
            val on = i == appSel
            val isHidden = app.key in hidden
            val color = when {
                on -> if (isHidden) Pixel.GRAY else Pixel.PAPER
                isHidden -> HUNT_DIM
                else -> HUNT_VALUE
            }
            if (on) Pixel.text(c, ">", 440f, y, cell, Pixel.PAPER)
            Pixel.text(c, app.dots.take(20), 490f, y, cell, color)
            val st = if (isHidden) "HIDDEN" else "ON WHEEL"
            Pixel.text(c, st, 1470f - Pixel.textW(st, 5f), y + cell, 5f, if (on) Pixel.SKY_BAND else color)
        }
        c.restore()
    }

    private fun drawHuntAbout(c: Canvas) {
        huntTitle(c, "ABOUT")
        for ((i, kv) in about.withIndex()) {
            val y = 320 + i * 100f
            Pixel.text(c, kv.first, 490f, y, 5f, Pixel.GRAY)
            mono.color = Pixel.PAPER
            c.drawText(kv.second, 860f, y + 17.5f + mono.textSize * 0.36f, mono)
        }
    }
}
