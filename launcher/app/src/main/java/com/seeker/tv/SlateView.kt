package com.seeker.tv

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.animation.PathInterpolator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * SLATE, the Apple-like home screen (design/SLATE-SPEC.md). Same 1920x1080 reference canvas as the wheel,
 * ported from design/seeker-slate-mockup-v1.html. Unlike the games there's no frame loop: it draws only
 * while something animates, so a still screen costs nothing between the clock and stats ticks.
 */
@SuppressLint("ViewConstructor")
class SlateView(
    ctx: Context,
    private val host: SeekerView.Host,
    private val backdrop: Backdrop,
    private val placeName: () -> String,
) : View(ctx), HomeScreen {

    private companion object {
        const val W = 1920f
        const val H = 1080f
        const val BASE = 0xFF0A0A0C.toInt()
        const val TEXT = 0xFFF5F5F7.toInt()
        const val CARD_FILL = 0xB81C1C1E.toInt()   // #1C1C1E at 72%
        const val CARD_LINE = 0x14FFFFFF           // white 8%
        const val MENU_FILL = 0xFF2C2C2E.toInt()
        const val RED = 0xFFFF453A.toInt()
        // app row
        const val TILE_W = 336f
        const val TILE_H = 189f
        const val GAP = 36f
        const val SLOT = TILE_W + GAP
        const val ROW_X = 150f
        const val ROW_TOP = 610f
        const val ROW_CY = ROW_TOP + TILE_H / 2
        const val RIGHT = 1770f
        const val FOCUS_S = 1.14f
        const val TILE_R = 22f
        const val DIM = 0.18f                      // unfocused tiles: brightness 82%
        // settings button
        const val GEAR_CX = 1734f
        const val GEAR_CY = 966f
        const val GEAR_R = 36f
        // item menu
        const val MENU_W = 300f
        const val MENU_ROW = 64f
        const val MENU_PAD = 8f
        val MENU = listOf("Move", "Hide", "Cancel")
        // stats cards (top right)
        const val CARD_W = 360f
        const val CARD_H = 84f
        const val CARD_GAP = 12f
        const val CARD_TOP = 92f
        const val CARD_R = 20f
        const val RING_R = 24f
        const val RING_W = 7f
        const val HOT = 0.9f
        const val JUMP = 0.10f
        val W_LABELS = arrayOf("CPU", "MEMORY", "STORAGE")
        val W_COLORS = intArrayOf(0xFF30D158.toInt(), 0xFF0A84FF.toInt(), 0xFFFF9F0A.toInt())
        // timings
        const val LONG_PRESS_MS = 600L
        const val LAUNCH_AT_MS = 900L               // same as the wheel: the name shows before the app covers it
        const val PHOTO_MS = 30_000L
        val CONDITIONS = mapOf("sun" to "Sunny", "cloud" to "Cloudy", "rain" to "Rain", "snow" to "Snow", "storm" to "Thunderstorms")

        fun titleCase(s: String) = s.lowercase(Locale.US).split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.US) } }
        fun lum(c: Int) = (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f
        fun alpha(c: Int, a: Float) = Dots.fade(c, a.coerceIn(0f, 1f))
        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    }

    // css easings from the mockup
    private val spring = PathInterpolator(.25f, 1.25f, .5f, 1f)
    private val ease = PathInterpolator(.2f, .7f, .2f, 1f)
    private val takeoverEase = PathInterpolator(.3f, 0f, .1f, 1f)
    private val easeInOut = PathInterpolator(.42f, 0f, .58f, 1f)

    /** Undo animator_duration_scale (0.5 on these TVs) so 300 ms means 300 ms, as in SeekerView. */
    private fun ms(d: Long): Long {
        val s = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        return if (s > 0f) (d / s).toLong() else d
    }

    // ---------- state ----------
    private var apps: List<AppEntry> = emptyList()
    private var sel = 0
    private var onGear = false
    private var busy = false
    private var centerDown = false
    private var menuOpen = false
    private var menuSel = 0
    private var moving = false
    private var weather = Weather.Now("cloud", "--°", 0)
    private val longPress = Runnable { if (centerDown) { centerDown = false; openMenu() } }

    private val tz = TimeZone.getDefault()
    private val timeFmt = SimpleDateFormat("h:mm", Locale.US).apply { timeZone = tz }
    private val dateFmt = SimpleDateFormat("EEEE, MMMM d", Locale.US).apply { timeZone = tz }

    // focus / scroll / slide, css-transition style: from -> to over one animator
    private var focus = FloatArray(0); private var focusFrom = FloatArray(0); private var focusTo = FloatArray(0)
    private var slide = FloatArray(0); private var slideFrom = FloatArray(0)
    private var gear = 0f; private var gearFrom = 0f
    private var scroll = 0f; private var scrollFrom = 0f; private var scrollTo = 0f
    private val moveAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        interpolator = null
        addUpdateListener { stepMove(it.animatedFraction) }
    }
    private var nameAlpha = 0f
    private val nameAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        interpolator = ease
        addUpdateListener { nameAlpha = it.animatedValue as Float; invalidate() }
    }
    private var sheen = -1f   // 0..1 while the light sweeps the focused tile
    private val sheenAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        interpolator = ease
        addUpdateListener { sheen = it.animatedValue as Float; invalidate() }
        addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) { sheen = -1f } })
    }
    private var menuT = 0f
    private var menuAnim: ValueAnimator? = null
    private var wiggle = 0f
    private val wiggleAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = easeInOut
        addUpdateListener { wiggle = it.animatedValue as Float; invalidate() }
    }
    private var shrinkKey: String? = null
    private var shrink = 1f

    // launch: the focused tile grows to the full screen
    private var launching: AppEntry? = null
    private var grow = 0f
    private var flat = 0f
    private val growFrom = RectF()
    private var launchAnims = ArrayList<ValueAnimator>()

    // photos
    private var photo: Bitmap? = null
    private var prevPhoto: Bitmap? = null
    private var photoT = 1f
    private var credit = ""
    private var live = false
    private val rotate = Runnable { nextPhoto() }
    private val photoAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        interpolator = ease
        addUpdateListener { photoT = it.animatedValue as Float; invalidate() }
        addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) { prevPhoto = null } })
    }
    /** The photo on screen, for SLATE's settings backdrop. */
    val currentPhoto: Bitmap? get() = photo

    // stats
    private var statsOn = false
    private val statShown = FloatArray(3)
    private val statFrom = FloatArray(3)
    private val statTo = FloatArray(3)
    private val statDetail = arrayOf("", "", "")
    private val statsAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        interpolator = ease
        addUpdateListener { a ->
            val k = a.animatedValue as Float
            for (i in 0 until 3) statShown[i] = statFrom[i] + (statTo[i] - statFrom[i]) * k
            invalidate()
        }
    }

    // ---------- paints & caches ----------
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val scrim = Paint().apply {
        shader = LinearGradient(
            0f, 0f, 0f, H,
            intArrayOf(0x73000000, 0x33000000, 0x00000000, 0x99000000.toInt()),
            floatArrayOf(0f, .32f, .48f, 1f), Shader.TileMode.CLAMP,
        )
    }
    private fun text(weight: Int, size: Float, display: Boolean = size >= 40f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Inter.at(context, weight, display)
        textSize = size
        fontFeatureSettings = "tnum"
    }
    private val datePaint = text(600, 34f).apply { textAlign = Paint.Align.CENTER; color = alpha(Color.WHITE, .85f); setShadowLayer(8f, 0f, 1f, 0x40000000) }
    private val clockPaint = text(600, 230f).apply {
        textAlign = Paint.Align.CENTER
        letterSpacing = -0.035f
        color = alpha(Color.WHITE, .74f)  // translucent, iOS lock screen: the photo shows through
        setShadowLayer(20f, 0f, 2f, 0x2E000000)
    }
    private val wxPaint = text(500, 32f).apply { setShadowLayer(8f, 0f, 1f, 0x4D000000) }
    private val creditPaint = text(500, 20f).apply { color = alpha(Color.WHITE, .6f); setShadowLayer(6f, 0f, 1f, 0x80000000.toInt()) }
    private val namePaint = text(500, 30f).apply { textAlign = Paint.Align.CENTER; color = TEXT; setShadowLayer(8f, 0f, 1f, 0x80000000.toInt()) }
    private val menuPaint = text(500, 28f)
    private val cardLabel = text(600, 18f).apply { color = 0xFF8E8E93.toInt(); letterSpacing = 0.06f }
    private val cardValue = text(600, 34f, display = false).apply { color = TEXT }
    private val cardDetail = text(400, 20f).apply { color = 0xFF8E8E93.toInt(); textAlign = Paint.Align.RIGHT }
    private val launchName = text(600, 96f).apply { textAlign = Paint.Align.CENTER; letterSpacing = -0.02f }
    private val launchSub = text(400, 24f).apply { textAlign = Paint.Align.CENTER }

    /** Tile art per app key, baked once at the focused size: banner (or icon on the brand color), rounded. */
    private val art = HashMap<String, Bitmap>()
    private val gearPath = Path().apply {
        val body = Path().apply { addCircle(12f, 12f, 7.6f, Path.Direction.CW) }
        for (k in 0 until 8) {
            val tooth = Path().apply { addRoundRect(RectF(10.2f, 1.6f, 13.8f, 6f), .8f, .8f, Path.Direction.CW) }
            tooth.transform(Matrix().apply { setRotate(k * 45f, 12f, 12f) })
            body.op(tooth, Path.Op.UNION)
        }
        body.op(Path().apply { addCircle(12f, 12f, 3.4f, Path.Direction.CW) }, Path.Op.DIFFERENCE)
        set(body)
    }
    private val clip = Path()
    private val r = RectF()
    private val src = Rect()
    private var firstFrameSent = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(BASE)
    }

    // ---------- HomeScreen ----------
    override fun showApps(list: List<AppEntry>) {
        art.values.forEach { it.recycle() }
        art.clear()  // banners may have changed with an app update
        setApps(list)
    }

    override fun setWeather(w: Weather.Now) { weather = w; invalidate() }
    override fun tick() = invalidate()

    override fun hideStats() { statsAnim.cancel(); statsOn = false; for (i in 0 until 3) statShown[i] = 0f; invalidate() }

    override fun setStats(values: FloatArray, details: Array<String>) {
        var jump = !statsOn
        for (i in 0 until 3) {
            statFrom[i] = statShown[i]; statTo[i] = values[i]; statDetail[i] = details[i]
            if (abs(values[i] - statShown[i]) >= JUMP) jump = true
        }
        statsOn = true
        statsAnim.cancel()
        statsAnim.duration = ms(400)
        if (jump && statsAnim.duration > 0) statsAnim.start()
        else { for (i in 0 until 3) statShown[i] = statTo[i]; invalidate() }
    }

    override fun resetLaunch() {
        launchAnims.forEach { it.cancel() }
        launchAnims.clear()
        launching = null
        grow = 0f
        flat = 0f
        busy = false
        centerDown = false
        removeCallbacks(longPress)
        invalidate()
    }

    override fun resetModes() {
        if (menuOpen) closeMenu(animate = false)
        if (moving) setMoving(false)
    }

    override val isLaunching get() = busy

    /** A photo refresh finished: start the backdrop if it was still waiting for its first photo. */
    fun photosChanged() { if (photo == null && live) nextPhoto() }

    // ---------- layout ----------
    private fun setApps(list: List<AppEntry>, keepSel: Int = -1, slideFromIndex: Int = -1) {
        val keepKey = apps.getOrNull(sel)?.key
        apps = list
        val n = list.size
        focus = FloatArray(n); focusFrom = FloatArray(n); focusTo = FloatArray(n)
        slide = FloatArray(n); slideFrom = FloatArray(n)
        sel = if (keepSel >= 0) min(keepSel, max(0, n - 1))
            else list.indexOfFirst { it.key == keepKey }.takeIf { it >= 0 } ?: min(sel, max(0, n - 1))
        if (slideFromIndex >= 0) {
            // HIDE: the tiles after the gap start one slot to the right and glide in
            for (i in slideFromIndex until n) slide[i] = SLOT
            layout(animate = true, sweep = true)
        } else {
            layout(animate = false, sweep = false)
        }
    }

    /** New targets for focus, scroll and the settings button; animate there (mockup layout()). */
    private fun layout(animate: Boolean, sweep: Boolean) {
        for (i in apps.indices) focusTo[i] = if (i == sel && !onGear) 1f else 0f
        val x = ROW_X + sel * SLOT
        if (x - scrollTo + TILE_W > RIGHT) scrollTo = x + TILE_W - RIGHT
        if (x - scrollTo < ROW_X) scrollTo = x - ROW_X
        Toaster.brand = apps.getOrNull(sel)?.color ?: TEXT
        if (!animate) {
            focusTo.copyInto(focus); slide.fill(0f); gear = if (onGear) 1f else 0f; scroll = scrollTo
            nameAlpha = 1f
            invalidate()
            return
        }
        focus.copyInto(focusFrom); slide.copyInto(slideFrom); gearFrom = gear; scrollFrom = scroll
        moveAnim.cancel()
        moveAnim.duration = ms(350)
        moveAnim.start()
        nameAnim.cancel(); nameAlpha = 0f
        nameAnim.duration = ms(200)
        if (!onGear && !menuOpen && !moving) nameAnim.start()
        if (sweep && !onGear) { sheenAnim.cancel(); sheenAnim.duration = ms(700); sheenAnim.start() }
    }

    private fun stepMove(t: Float) {
        // focus springs over 300 ms, scroll and slides ease over 350 ms, both on one 350 ms animator
        val f = spring.getInterpolation(min(1f, t * 350f / 300f))
        val e = ease.getInterpolation(t)
        for (i in apps.indices) {
            focus[i] = lerp(focusFrom[i], focusTo[i], f)
            slide[i] = slideFrom[i] * (1 - e)
        }
        gear = lerp(gearFrom, if (onGear) 1f else 0f, f)
        scroll = lerp(scrollFrom, scrollTo, e)
        invalidate()
    }

    private fun tileCx(i: Int) = ROW_X + i * SLOT - scroll + slide[i] + TILE_W / 2

    // ---------- remote ----------
    override fun onKey(e: KeyEvent): Boolean {
        val code = e.keyCode
        val isCenter = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
            code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A
        val handled = isCenter || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_UP ||
            code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        if (!handled) return false
        if (busy) return true

        if (e.action == KeyEvent.ACTION_UP) {
            if (isCenter && centerDown) { centerDown = false; removeCallbacks(longPress); go() }
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return true
        // holding LEFT / RIGHT runs along the row; everything else acts once
        val sideways = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        if (e.repeatCount > 0 && !(sideways && !menuOpen && !onGear)) return true

        if (menuOpen) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> if (menuSel < MENU.size - 1) { menuSel++; Sfx.play(Sfx.Id.SLATE_TICK); invalidate() }
                KeyEvent.KEYCODE_DPAD_UP -> if (menuSel > 0) { menuSel--; Sfx.play(Sfx.Id.SLATE_TICK); invalidate() }
                KeyEvent.KEYCODE_BACK -> closeMenu()
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> Unit
                else -> pickMenu()
            }
            return true
        }
        if (moving) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (sel < apps.size - 1) moveTo(sel + 1)
                KeyEvent.KEYCODE_DPAD_LEFT -> if (sel > 0) moveTo(sel - 1)
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> Unit
                else -> setMoving(false)  // OK or BACK drops the app and saves
            }
            return true
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (!onGear && sel < apps.size - 1) { sel++; moveFocus(sweep = true) }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (!onGear && sel > 0) { sel--; moveFocus(sweep = true) }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (!onGear) { onGear = true; moveFocus(sweep = false) }
            KeyEvent.KEYCODE_DPAD_UP -> if (onGear) { onGear = false; moveFocus(sweep = true) }
            KeyEvent.KEYCODE_BACK -> host.back()
            else -> if (onGear) { Sfx.play(Sfx.Id.SLATE_OK); host.openSettings() } else if (apps.isNotEmpty()) {
                // launch on key up; held 600 ms = item menu
                centerDown = true
                postDelayed(longPress, LONG_PRESS_MS)
            }
        }
        return true
    }

    private fun moveFocus(sweep: Boolean) { Sfx.play(Sfx.Id.SLATE_TICK); layout(animate = true, sweep = sweep) }

    // ---------- item menu, move, hide ----------
    private fun openMenu() {
        if (apps.isEmpty()) return
        menuOpen = true
        menuSel = 0
        nameAnim.cancel(); nameAlpha = 0f
        Sfx.play(Sfx.Id.SLATE_MENU)
        fadeMenu(1f, true)
    }

    private fun closeMenu(animate: Boolean = true) {
        menuOpen = false
        fadeMenu(0f, animate)
        if (animate && !moving) { nameAnim.duration = ms(200); nameAnim.start() } else nameAlpha = 1f
    }

    private fun fadeMenu(to: Float, animate: Boolean) {
        menuAnim?.cancel()
        if (!animate) { menuT = to; invalidate(); return }
        menuAnim = ValueAnimator.ofFloat(menuT, to).apply {
            duration = ms(250)
            interpolator = null
            addUpdateListener { menuT = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun pickMenu() {
        val app = apps.getOrNull(sel)
        val pick = MENU[menuSel]
        Sfx.play(Sfx.Id.SLATE_OK)
        closeMenu(animate = pick == "Cancel")
        app ?: return
        when (pick) {
            "Move" -> setMoving(true)
            "Hide" -> hide(app)
        }
    }

    private fun setMoving(on: Boolean) {
        moving = on
        if (on) {
            nameAlpha = 0f
            wiggleAnim.duration = ms(260)
            wiggleAnim.start()
        } else {
            wiggleAnim.cancel()
            wiggle = 0f
            host.saveOrder(apps.map { it.key })
            nameAnim.duration = ms(200); nameAnim.start()
        }
        invalidate()
    }

    /** The moving tile swaps with its neighbor; both glide to their new slots. */
    private fun moveTo(j: Int) {
        val i = sel
        apps = apps.toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t }
        for (arr in arrayOf(focus, focusFrom, focusTo)) { val t = arr[i]; arr[i] = arr[j]; arr[j] = t }
        // each tile starts drawn where it was (its old slot plus any slide still running) and glides home
        val si = slide[i]; val sj = slide[j]
        slide[j] = si + (i - j) * SLOT
        slide[i] = sj + (j - i) * SLOT
        sel = j
        Sfx.play(Sfx.Id.SLATE_TICK)
        layout(animate = true, sweep = false)
    }

    /** HIDE: the tile shrinks away (250 ms), then the row closes the gap. */
    private fun hide(app: AppEntry) {
        busy = true
        shrinkKey = app.key
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = ms(250)
            interpolator = ease
            addUpdateListener { shrink = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    shrinkKey = null; shrink = 1f
                    host.hide(app)
                    val at = sel
                    art.remove(app.key)?.recycle()
                    setApps(apps.filter { it.key != app.key }, keepSel = at, slideFromIndex = at)
                    busy = false
                }
            })
            start()
        }
    }

    // ---------- launch: the tile grows to fill the screen ----------
    private fun go() {
        if (busy) return
        val entry = apps.getOrNull(sel) ?: return
        busy = true
        Sfx.play(Sfx.Id.SLATE_OK)
        launching = entry
        val cx = tileCx(sel)
        growFrom.set(cx - TILE_W * FOCUS_S / 2, ROW_CY - TILE_H * FOCUS_S / 2, cx + TILE_W * FOCUS_S / 2, ROW_CY + TILE_H * FOCUS_S / 2)
        launchAnims.forEach { it.cancel() }
        launchAnims.clear()
        launchAnims += ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms(520)
            interpolator = takeoverEase
            addUpdateListener { grow = it.animatedValue as Float; invalidate() }
        }
        launchAnims += ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = ms(300)
            duration = ms(300)
            interpolator = ease
            addUpdateListener { flat = it.animatedValue as Float; invalidate() }
        }
        launchAnims.forEach { it.start() }
        postDelayed({ if (busy) host.launch(entry) }, LAUNCH_AT_MS)  // the app comes up behind the color
    }

    // ---------- photos ----------
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        setLive(visibility == VISIBLE)
    }

    override fun onDetachedFromWindow() {
        setLive(false)
        super.onDetachedFromWindow()
    }

    /** Rotate only while on screen. */
    private fun setLive(on: Boolean) {
        if (on == live) return
        live = on
        removeCallbacks(rotate)
        if (on) { if (photo == null) nextPhoto() else postDelayed(rotate, PHOTO_MS) }
    }

    private fun nextPhoto() {
        removeCallbacks(rotate)
        val p = backdrop.next() ?: return  // nothing downloaded yet: photosChanged() starts it
        backdrop.decode(p) { bmp ->
            if (bmp != null) {
                prevPhoto = photo
                photo = bmp
                credit = p.caption
                photoAnim.cancel()
                photoT = 0f
                photoAnim.duration = ms(1200)
                photoAnim.start()
            }
            if (live) { removeCallbacks(rotate); postDelayed(rotate, PHOTO_MS) }
        }
    }

    // ---------- drawing ----------
    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.scale(width / W, height / H)
        drawBackdrop(canvas)
        drawTop(canvas)
        drawStats(canvas)
        drawRow(canvas)
        drawGear(canvas)
        drawMenu(canvas)
        drawLaunch(canvas)
        canvas.restore()
        if (!firstFrameSent) { firstFrameSent = true; post { host.firstFrame() } }
    }

    private fun drawPhoto(c: Canvas, bmp: Bitmap, a: Float) {
        // center-crop to 16:9
        val bw = bmp.width; val bh = bmp.height
        if (bw * 9 > bh * 16) { val w = bh * 16 / 9; src.set((bw - w) / 2, 0, (bw + w) / 2, bh) }
        else { val h = bw * 9 / 16; src.set(0, (bh - h) / 2, bw, (bh + h) / 2) }
        r.set(0f, 0f, W, H)
        bitmapPaint.alpha = (255 * a).toInt()
        c.drawBitmap(bmp, src, r, bitmapPaint)
        bitmapPaint.alpha = 255
    }

    private fun drawBackdrop(c: Canvas) {
        prevPhoto?.let { if (photoT < 1f) drawPhoto(c, it, 1f) }
        photo?.let { drawPhoto(c, it, photoT) }
        c.drawRect(0f, 0f, W, H, scrim)
        if (credit.isNotEmpty()) c.drawText(credit, 150f, 1009f, creditPaint)
    }

    /** Date / clock / weather, centered (iOS lock screen). */
    private fun drawTop(c: Canvas) {
        val now = Date()
        c.drawText(dateFmt.format(now), W / 2, 125f, datePaint)
        c.drawText(timeFmt.format(now), W / 2, 327f, clockPaint)
        // weather row: icon, temperature, condition · place, 14 px gaps, centered on the screen
        val temp = weather.temp
        val cond = "${CONDITIONS[weather.icon] ?: ""} · ${titleCase(placeName())}"
        val tw = wxPaint.measureText(temp)
        val cw = wxPaint.measureText(cond)
        val total = 44f + 14f + tw + 14f + cw
        var x = (W - total) / 2
        weatherIcon(c, weather.icon, x, 382f, 44f)
        x += 44f + 14f
        wxPaint.color = alpha(Color.WHITE, .85f); c.drawText(temp, x, 415f, wxPaint)
        x += tw + 14f
        wxPaint.color = alpha(Color.WHITE, .62f); c.drawText(cond, x, 415f, wxPaint)
    }

    /** Filled, multicolor, SF Symbols style; the shapes are the mockup's SVGs in a 56-unit box. */
    private fun weatherIcon(c: Canvas, icon: String, x: Float, y: Float, size: Float) {
        c.save()
        c.translate(x, y)
        c.scale(size / 56f, size / 56f)
        val yellow = 0xFFFFD60A.toInt()
        fun cloud(dy: Float) {
            fill.color = Color.WHITE
            c.drawCircle(16f, 33f + dy, 9f, fill)
            c.drawCircle(27f, 25f + dy, 12f, fill)
            c.drawCircle(39f, 31.5f + dy, 10.5f, fill)
            c.drawRect(16f, 30f + dy, 39f, 42f + dy, fill)
        }
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeWidth = 4f
        when (icon) {
            "sun" -> {
                fill.color = yellow
                c.drawCircle(28f, 28f, 11f, fill)
                stroke.color = yellow
                for (k in 0 until 8) {
                    val a = k * Math.PI / 4
                    val cos = Math.cos(a).toFloat(); val sin = Math.sin(a).toFloat()
                    c.drawLine(28 + cos * 17, 28 + sin * 17, 28 + cos * 24, 28 + sin * 24, stroke)
                }
            }
            "rain" -> {
                cloud(-6f)
                stroke.color = 0xFF64D2FF.toInt()
                for (lx in floatArrayOf(16f, 26f, 36f)) c.drawLine(lx, 41f, lx - 4, 51f, stroke)
            }
            "snow" -> {
                cloud(-6f)
                fill.color = Color.WHITE
                for ((px, py) in listOf(16f to 44f, 28f to 44f, 40f to 44f, 22f to 52f, 34f to 52f)) c.drawCircle(px, py, 2.6f, fill)
            }
            "storm" -> {
                cloud(-6f)
                fill.color = yellow
                c.drawPath(Path().apply {
                    moveTo(29f, 36f); lineTo(21f, 47f); lineTo(27f, 47f); lineTo(24f, 56f)
                    lineTo(34f, 43f); lineTo(28f, 43f); lineTo(31f, 36f); close()
                }, fill)
            }
            else -> cloud(0f)
        }
        c.restore()
    }

    /** STATS FOR NERDS: three cards, top right (same readings and rules as the wheel's v5 widgets). */
    private fun drawStats(c: Canvas) {
        if (!statsOn) return
        val x0 = RIGHT - CARD_W
        stroke.strokeWidth = 1f
        for (i in 0 until 3) {
            val y0 = CARD_TOP + i * (CARD_H + CARD_GAP)
            val v = statShown[i]
            val color = if (v >= HOT) RED else W_COLORS[i]
            fill.color = CARD_FILL
            c.drawRoundRect(x0, y0, x0 + CARD_W, y0 + CARD_H, CARD_R, CARD_R, fill)
            stroke.color = CARD_LINE
            c.drawRoundRect(x0 + .5f, y0 + .5f, x0 + CARD_W - .5f, y0 + CARD_H - .5f, CARD_R, CARD_R, stroke)
            val cx = x0 + 44f
            val cy = y0 + CARD_H / 2
            stroke.strokeWidth = RING_W; stroke.strokeCap = Paint.Cap.ROUND
            stroke.color = alpha(color, .2f)
            c.drawCircle(cx, cy, RING_R, stroke)
            if (v > 0.005f) {
                stroke.color = color
                c.drawArc(cx - RING_R, cy - RING_R, cx + RING_R, cy + RING_R, -90f, v * 360f, false, stroke)
            }
            stroke.strokeWidth = 1f
            c.drawText(W_LABELS[i], x0 + 84f, y0 + 31f, cardLabel)
            c.drawText("${Math.round(v * 100)}%", x0 + 84f, y0 + 65f, cardValue)
            c.drawText(statDetail[i].replace("GHZ", "GHz"), x0 + 340f, y0 + 65f, cardDetail)
        }
    }

    private fun drawRow(c: Canvas) {
        // unfocused tiles first, the focused one last (on top, with its shadow)
        for (i in apps.indices) if (i != sel) drawTile(c, i)
        if (apps.isNotEmpty()) drawTile(c, sel)
        // the name under the focused tile
        val app = apps.getOrNull(sel) ?: return
        val a = nameAlpha * (1f - gear) * (1f - menuT) * (if (moving) 0f else 1f)
        if (a > 0.01f && launching == null) {
            namePaint.alpha = (255 * a).toInt()
            c.drawText(app.title, tileCx(sel), ROW_CY + TILE_H * FOCUS_S / 2 + 56f, namePaint)
            namePaint.alpha = 255
        }
    }

    private fun drawTile(c: Canvas, i: Int) {
        val app = apps[i]
        val f = focus[i]
        val s = (1 + (FOCUS_S - 1) * f) * (if (app.key == shrinkKey) shrink else 1f)
        if (s <= 0.01f) return
        val cx = tileCx(i)
        val w = TILE_W * s
        val h = TILE_H * s
        if (cx + w / 2 < 0 || cx - w / 2 > W) return
        r.set(cx - w / 2, ROW_CY - h / 2, cx + w / 2, ROW_CY + h / 2)
        val rad = TILE_R * s
        c.save()
        if (moving) {
            val k = if (i % 2 == 0) wiggle else 1 - wiggle
            c.rotate(-1.2f + 2.4f * k, cx, ROW_CY)
        }
        if (f > 0.01f) {
            // box-shadow 0 30px 60px rgba(0,0,0,.6)
            shadow.color = BASE
            shadow.setShadowLayer(30f, 0f, 30f, alpha(Color.BLACK, .6f * min(1f, f)))
            c.drawRoundRect(r, rad, rad, shadow)
        }
        c.drawBitmap(tileArt(app), null, r, bitmapPaint)
        if (f < 1f) {
            fill.color = alpha(Color.BLACK, DIM * (1 - max(0f, f)))
            c.drawRoundRect(r, rad, rad, fill)
        }
        if (f > 0.01f) {
            stroke.strokeWidth = 1.5f
            stroke.color = alpha(Color.WHITE, .2f * min(1f, f))
            c.drawRoundRect(r.left + .75f, r.top + .75f, r.right - .75f, r.bottom - .75f, rad, rad, stroke)
        }
        if (i == sel && sheen in 0f..1f && !onGear) {
            // a white 18% diagonal band sweeps across once
            clip.reset(); clip.addRoundRect(r, rad, rad, Path.Direction.CW)
            c.clipPath(clip)
            val bx = lerp(r.left - w * 0.7f, r.right + w * 0.7f, sheen)
            fill.shader = LinearGradient(
                bx - w * .25f, r.top, bx + w * .25f, r.top + h * .35f,
                intArrayOf(0, 0x2EFFFFFF, 0), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP,
            )
            c.drawRect(r, fill)
            fill.shader = null
        }
        c.restore()
    }

    private fun tileArt(app: AppEntry): Bitmap = art.getOrPut(app.key) {
        val bw = (TILE_W * FOCUS_S).toInt()
        val bh = (TILE_H * FOCUS_S).toInt()
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val rect = RectF(0f, 0f, bw.toFloat(), bh.toFloat())
        val rad = TILE_R * FOCUS_S
        c.clipPath(Path().apply { addRoundRect(rect, rad, rad, Path.Direction.CW) })
        // brand gradient under everything (banners can be transparent)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, rect.right, rect.bottom, app.color, shade(app.color, .62f), Shader.TileMode.CLAMP)
        }
        c.drawRect(rect, p)
        val pm = context.packageManager
        val banner: Drawable? = app.component?.let { cn ->
            try { pm.getActivityBanner(cn) ?: pm.getApplicationBanner(cn.packageName) } catch (e: Exception) { null }
        }
        if (banner != null) {
            banner.setBounds(0, 0, bw, bh)
            banner.draw(c)
        } else {
            val icon = app.component?.let { cn -> try { pm.getActivityIcon(cn) } catch (e: Exception) { null } }
            if (icon != null) {
                val sz = (bh * 0.5f).toInt()
                icon.setBounds((bw - sz) / 2, (bh - sz) / 2, (bw + sz) / 2, (bh + sz) / 2)
                icon.draw(c)
            } else {
                val t = text(700, 50f).apply { textAlign = Paint.Align.CENTER; color = if (lum(app.color) > .45f) BASE else Color.WHITE }
                c.drawText(app.title, bw / 2f, bh / 2f + 18f, t)
            }
        }
        bmp
    }

    private fun shade(c: Int, k: Float) = Color.rgb((Color.red(c) * k).toInt(), (Color.green(c) * k).toInt(), (Color.blue(c) * k).toInt())

    private fun drawGear(c: Canvas) {
        val s = 1 + 0.1f * gear
        val rad = GEAR_R * s
        fill.color = blend(CARD_FILL, TEXT, min(1f, max(0f, gear)))
        c.drawCircle(GEAR_CX, GEAR_CY, rad, fill)
        c.save()
        c.translate(GEAR_CX - 18f * s, GEAR_CY - 18f * s)
        c.scale(1.5f * s, 1.5f * s)
        fill.color = blend(alpha(Color.WHITE, .8f), BASE, min(1f, max(0f, gear)))
        c.drawPath(gearPath, fill)
        c.restore()
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(),
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )

    /** Move / Hide / Cancel: a popover under the tile, scaling in from 90% with the spring. */
    private fun drawMenu(c: Canvas) {
        if (menuT <= 0f || apps.isEmpty()) return
        val cx = tileCx(sel)
        val top = ROW_CY + TILE_H * FOCUS_S / 2 + 20f
        val h = MENU_PAD * 2 + MENU.size * MENU_ROW
        val s = .9f + .1f * spring.getInterpolation(menuT)
        c.save()
        c.scale(s, s, cx, top)
        r.set(cx - MENU_W / 2, top, cx + MENU_W / 2, top + h)
        shadow.color = alpha(MENU_FILL, menuT)
        shadow.setShadowLayer(30f, 0f, 24f, alpha(Color.BLACK, .6f * menuT))
        c.drawRoundRect(r, 18f, 18f, shadow)
        for ((i, label) in MENU.withIndex()) {
            val ry = top + MENU_PAD + i * MENU_ROW
            if (i == menuSel) {
                fill.color = alpha(Color.WHITE, .12f * menuT)
                c.drawRoundRect(r.left + MENU_PAD, ry, r.right - MENU_PAD, ry + MENU_ROW, 12f, 12f, fill)
            }
            menuPaint.color = alpha(if (label == "Hide") RED else TEXT, menuT)
            c.drawText(label, r.left + MENU_PAD + 24f, ry + MENU_ROW / 2 + 10f, menuPaint)
        }
        c.restore()
    }

    private fun drawLaunch(c: Canvas) {
        val entry = launching ?: return
        r.set(lerp(growFrom.left, 0f, grow), lerp(growFrom.top, 0f, grow), lerp(growFrom.right, W, grow), lerp(growFrom.bottom, H, grow))
        val rad = TILE_R * FOCUS_S * (1 - grow)
        c.save()
        clip.reset(); clip.addRoundRect(r, rad, rad, Path.Direction.CW)
        c.clipPath(clip)
        c.drawBitmap(tileArt(entry), null, r, bitmapPaint)
        if (flat > 0f) {
            fill.color = alpha(entry.color, flat)
            c.drawRect(r, fill)
            val ink = if (lum(entry.color) > .45f) BASE else Color.WHITE
            launchName.color = alpha(ink, flat)
            c.drawText(entry.title, W / 2, 552f, launchName)
            launchSub.color = alpha(ink, .6f * flat)
            c.drawText("Opening", W / 2, 616f, launchSub)
        }
        c.restore()
    }
}
