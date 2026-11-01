package com.seeker.tv

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import java.net.ConnectException
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * PERFORMANCE (Seeker settings): what is using MEMORY, CPU and STORAGE, per app. Readings come from shell
 * commands over [LocalAdb] (normal apps can't see other apps on Android 14). LEFT/RIGHT switch tabs,
 * UP/DOWN scroll, BACK closes. Same 1920x1080 canvas and Inter type as SLATE.
 */
@SuppressLint("ViewConstructor")
class PerfView(ctx: Context, private val close: () -> Unit) : View(ctx) {

    private companion object {
        const val TAG = "SeekerTV"
        const val W = 1920f
        const val H = 1080f
        const val L = 150f
        const val R = 1770f
        const val BG = 0xFF0A0A0C.toInt()
        const val CARD = 0xFF1C1C1E.toInt()
        const val TEXT = 0xFFF5F5F7.toInt()
        const val TEXT2 = 0xFF8E8E93.toInt()
        const val TRACK = 0xFF2C2C2E.toInt()
        const val LIST_TOP = 600f
        const val ROW = 58f
        const val VISIBLE = 7
        val TABS = listOf("Memory", "CPU", "Storage")
        val TAB_COLORS = intArrayOf(0xFF0A84FF.toInt(), 0xFF30D158.toInt(), 0xFFFF9F0A.toInt())
        val REFRESH_MS = longArrayOf(5000, 1000, 60_000)   // CPU's own sample already takes 2 s
    }

    private var tab = 0
    private var sel = 0
    private var top = 0
    private var memory: PerfData.Memory? = null
    private var cpu: PerfData.Cpu? = null
    private var storage: PerfData.Storage? = null
    private val updated = LongArray(3)
    private var status = "Connecting…"
    private var error: String? = null

    private val main = Handler(context.mainLooper)
    private val worker = HandlerThread("seeker-perf").apply { start() }
    private val bg = Handler(worker.looper)
    private val data = PerfData(context, LocalAdb(context))
    @Volatile private var open = false

    private fun paint(weight: Int, size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Inter.at(context, weight, size >= 40f)
        textSize = size
        fontFeatureSettings = "tnum"
    }
    private val title = paint(700, 64f).apply { color = TEXT }
    private val tabText = paint(600, 26f).apply { textAlign = Paint.Align.CENTER }
    private val head = paint(600, 36f).apply { color = TEXT }
    private val body = paint(400, 22f).apply { color = TEXT2 }
    private val legend = paint(500, 22f).apply { color = TEXT }
    private val rowName = paint(500, 28f).apply { color = TEXT }
    private val rowTag = paint(400, 22f).apply { color = TEXT2 }
    private val rowValue = paint(600, 28f).apply { color = TEXT; textAlign = Paint.Align.RIGHT }
    private val small = paint(400, 22f).apply { color = TEXT2; textAlign = Paint.Align.RIGHT }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(BG)
    }

    /** Opened from settings: start reading. */
    fun start() {
        open = true
        sel = 0; top = 0
        bg.removeCallbacksAndMessages(null)
        bg.post(poll)
        invalidate()
    }

    fun stop() {
        open = false
        bg.removeCallbacksAndMessages(null)
    }

    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }

    /** Reads the shown tab, then the others once, then keeps the shown tab fresh. */
    private val poll = object : Runnable {
        override fun run() {
            if (!open) return
            val t = tab
            try {
                if (updated.all { it == 0L }) main.post { status = "Connecting… if the TV asks to allow debugging, choose Always allow"; invalidate() }
                read(t)
                for (o in 0 until 3) if (updated[o] == 0L && open) read(o)
                main.post { error = null; invalidate() }
            } catch (e: ConnectException) {
                fail("Network debugging is off on this TV. Turn it on in Developer options, or connect once from a PC with: adb tcpip 5555")
            } catch (e: LocalAdb.NotAllowed) {
                fail("Seeker wasn't allowed. Open this screen again and choose Always allow on the TV's prompt.")
            } catch (e: Exception) {
                Log.w(TAG, "perf read failed", e)
                fail("Couldn't read: ${e.message ?: e.javaClass.simpleName}")
            }
            if (open) bg.postDelayed(this, if (error != null) 10_000 else REFRESH_MS[tab])
        }
    }

    private fun read(t: Int) {
        when (t) {
            0 -> data.memory().let { m -> main.post { memory = m; invalidate() } }
            1 -> data.cpu().let { c -> main.post { cpu = c; invalidate() } }
            else -> data.storage().let { s -> main.post { storage = s; invalidate() } }
        }
        updated[t] = SystemClock.elapsedRealtime()
        main.post { status = ""; invalidate() }
    }

    private fun fail(msg: String) { main.post { error = msg; invalidate() } }

    // ---------- remote ----------
    fun onKey(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return true
        val n = rows().size
        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK -> { stop(); close(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (tab < 2) setTab(tab + 1)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (tab > 0) setTab(tab - 1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (sel < n - 1) sel++
            KeyEvent.KEYCODE_DPAD_UP -> if (sel > 0) sel--
            else -> return true
        }
        if (sel < top) top = sel
        if (sel >= top + VISIBLE) top = sel - VISIBLE + 1
        Sfx.play(Sfx.Id.SLATE_TICK)
        invalidate()
        return true
    }

    private fun setTab(t: Int) {
        tab = t; sel = 0; top = 0
        bg.removeCallbacksAndMessages(null)
        bg.post(poll)   // refresh the new tab now
    }

    private fun rows(): List<PerfData.Row> = when (tab) {
        0 -> memory?.rows
        1 -> cpu?.rows
        else -> storage?.rows
    } ?: emptyList()

    // ---------- drawing ----------
    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.scale(width / W, height / H)
        val c = canvas
        c.drawText("Performance", L, 150f, title)
        val ago = updated[tab].takeIf { it > 0 }?.let { "Updated ${max(0, (SystemClock.elapsedRealtime() - it) / 1000)} s ago" } ?: ""
        c.drawText(if (status.isNotEmpty()) "" else ago, R, 150f, small)
        drawTabs(c)
        val err = error
        if (err != null && updated[tab] == 0L) { message(c, err); canvas.restore(); return }
        if (updated[tab] == 0L) { message(c, status); canvas.restore(); return }
        when (tab) {
            0 -> memory?.let { drawMemory(c, it) }
            1 -> cpu?.let { drawCpu(c, it) }
            else -> storage?.let { drawStorage(c, it) }
        }
        drawList(c)
        if (err != null) { body.color = 0xFFFF9F0A.toInt(); c.drawText(err, L, 1050f, body); body.color = TEXT2 }
        canvas.restore()
    }

    private fun drawTabs(c: Canvas) {
        var x = L
        for ((i, t) in TABS.withIndex()) {
            val on = i == tab
            fill.color = if (on) TEXT else CARD
            c.drawRoundRect(x, 196f, x + 200f, 252f, 28f, 28f, fill)
            tabText.color = if (on) BG else TEXT
            c.drawText(t, x + 100f, 233f, tabText)
            x += 216f
        }
        body.textAlign = Paint.Align.RIGHT
        c.drawText("◀ ▶ switch   ▲ ▼ scroll   BACK close", R, 233f, body)
        body.textAlign = Paint.Align.LEFT
    }

    private fun message(c: Canvas, text: String) {
        fill.color = CARD
        c.drawRoundRect(L, 290f, R, 450f, 22f, 22f, fill)
        wrap(c, text, L + 40f, 360f, R - L - 80f, head)
    }

    /** Stacked bar of (value, color) parts with a legend underneath; the card is 290..470. */
    private fun bar(c: Canvas, headline: String, parts: List<Triple<String, Long, Int>>, note: String?) {
        fill.color = CARD
        c.drawRoundRect(L, 290f, R, 470f, 22f, 22f, fill)
        c.drawText(headline, L + 32f, 346f, head)
        val total = parts.sumOf { it.second }.coerceAtLeast(1)
        val x0 = L + 32f; val x1 = R - 32f; val y0 = 372f; val y1 = 398f
        fill.color = TRACK
        c.drawRoundRect(x0, y0, x1, y1, 13f, 13f, fill)
        c.save()
        c.clipRect(x0, y0, x1, y1)
        var x = x0
        for ((_, v, col) in parts) {
            val w = (x1 - x0) * v / total
            fill.color = col
            c.drawRect(x, y0, x + w, y1, fill)
            x += w
        }
        c.restore()
        var lx = x0
        for ((name, v, col) in parts) {
            fill.color = col
            c.drawCircle(lx + 8f, 438f, 8f, fill)
            val s = "$name ${size(v)}"
            c.drawText(s, lx + 24f, 446f, legend)
            lx += legend.measureText(s) + 56f
        }
        if (note != null) wrap(c, note, L, 510f, R - L, body)
    }

    private fun drawMemory(c: Canvas, m: PerfData.Memory) {
        bar(
            c,
            "${size(m.inUse + m.kernel)} in use, ${size(m.cachedApps)} cached apps, of ${size(m.total)}",
            listOf(
                Triple("Apps & services", m.inUse, 0xFF0A84FF.toInt()),
                Triple("System (kernel)", max(m.kernel, 0), 0xFF5E5CE6.toInt()),
                Triple("Cached apps", m.cachedApps, 0xFF64D2FF.toInt()),
                Triple("Cached files", m.cachedFiles, 0xFF48484A.toInt()),
                Triple("Free", m.free, 0xFF2C2C2E.toInt()),
            ).filter { it.second > 0 },
            "Cached apps are ones you used recently, kept in memory so they open instantly; Android frees them the moment " +
                "something needs the room. The MEMORY card counts them as used, which is why it sits near 75%.",
        )
    }

    private fun drawCpu(c: Canvas, u: PerfData.Cpu) {
        val busy = (u.busy * 10).toLong().coerceIn(0, 1000)
        bar(
            c,
            String.format(Locale.US, "%.0f%% busy across %d cores (last 2 s)", u.busy, Runtime.getRuntime().availableProcessors()),
            listOf(Triple("Busy", busy, 0xFF30D158.toInt()), Triple("Idle", 1000 - busy, 0xFF2C2C2E.toInt())),
            "Each app's share of the whole processor. Seeker itself shows up while this screen is reading.",
            )
    }

    private fun drawStorage(c: Canvas, s: PerfData.Storage) {
        val used = s.total - s.free
        val other = max(0L, used - s.apps - s.appData)
        bar(
            c,
            "${size(used)} used of ${size(s.total)}",
            listOf(
                Triple("Apps", s.apps, 0xFFFF9F0A.toInt()),
                Triple("App data", s.appData, 0xFFFFD60A.toInt()),
                Triple("Other", other, 0xFF8E8E93.toInt()),
                Triple("Free", s.free, 0xFF2C2C2E.toInt()),
            ).filter { it.second > 0 },
            "App sizes include each app's data and cache. Android refreshes these numbers about once a day.",
        )
    }

    private fun drawList(c: Canvas) {
        val rows = rows()
        if (rows.isEmpty()) return
        val maxV = rows.maxOf { it.value }.coerceAtLeast(1)
        val col = TAB_COLORS[tab]
        for (k in 0 until min(VISIBLE, rows.size - top)) {
            val i = top + k
            val r = rows[i]
            val y = LIST_TOP + k * ROW
            if (i == sel) { fill.color = CARD; c.drawRoundRect(L - 16f, y, R + 16f, y + ROW - 6f, 14f, 14f, fill) }
            c.drawText(ellipsize(r.name, rowName, 760f), L, y + 34f, rowName)
            c.drawText(r.tag, L + 820f, y + 34f, rowTag)
            fill.color = TRACK
            c.drawRoundRect(L + 1080f, y + 22f, R - 200f, y + 32f, 5f, 5f, fill)
            fill.color = col
            c.drawRoundRect(L + 1080f, y + 22f, L + 1080f + (R - 200f - L - 1080f) * r.value / maxV, y + 32f, 5f, 5f, fill)
            c.drawText(if (tab == 1) String.format(Locale.US, "%.1f%%", r.value / 10f) else size(r.value), R, y + 34f, rowValue)
        }
        if (rows.size > VISIBLE) {
            small.textAlign = Paint.Align.RIGHT
            c.drawText("${sel + 1} of ${rows.size}", R, LIST_TOP + VISIBLE * ROW + 30f, small)
        }
    }

    private fun size(bytes: Long): String {
        val mb = bytes / 1048576.0
        return if (mb >= 1000) String.format(Locale.US, "%.1f GB", mb / 1024) else String.format(Locale.US, "%.0f MB", mb)
    }

    private fun ellipsize(s: String, p: Paint, w: Float): String {
        if (p.measureText(s) <= w) return s
        var t = s
        while (t.length > 1 && p.measureText("$t…") > w) t = t.dropLast(1)
        return "$t…"
    }

    private fun wrap(c: Canvas, text: String, x: Float, y0: Float, w: Float, p: Paint) {
        var y = y0
        var line = ""
        for (word in text.split(' ')) {
            val t = if (line.isEmpty()) word else "$line $word"
            if (p.measureText(t) > w && line.isNotEmpty()) { c.drawText(line, x, y, p); y += p.textSize * 1.35f; line = word } else line = t
        }
        if (line.isNotEmpty()) c.drawText(line, x, y, p)
    }
}
