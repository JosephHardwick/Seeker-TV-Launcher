package com.seeker.tv

import android.content.Context
import android.content.pm.PackageManager

/**
 * The performance monitor's readings, from shell commands run over [LocalAdb]:
 * MEMORY = `dumpsys meminfo` (per process, by Android's memory category), CPU = `top` (per process over
 * 2 s), STORAGE = `dumpsys diskstats` (per app; Android refreshes these sizes about once a day).
 * All parsing is defensive: a format change gives an empty list, never a crash.
 */
class PerfData(private val ctx: Context, private val adb: LocalAdb) {

    /** One row: a process or app, its share, and a short tag (its memory category, "System", ...). */
    class Row(val name: String, val value: Long, val tag: String)

    class Memory(
        val total: Long, val inUse: Long, val kernel: Long, val cachedApps: Long, val cachedFiles: Long, val free: Long,
        val rows: List<Row>,
    )

    class Cpu(val busy: Float, val rows: List<Row>)           // values in tenths of a percent of the whole CPU

    class Storage(val total: Long, val free: Long, val apps: Long, val appData: Long, val rows: List<Row>)

    private val pm = ctx.packageManager
    private val labels = HashMap<String, String>()
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    // ---------- MEMORY ----------
    fun memory(): Memory {
        val text = adb.shell("dumpsys meminfo")
        val rows = ArrayList<Row>()
        var inOom = false
        var category = ""
        for (line in text.lines()) {
            if (line.startsWith("Total PSS by OOM adjustment")) { inOom = true; continue }
            if (line.startsWith("Total PSS by category")) inOom = false
            if (!inOom) continue
            val m = ENTRY.find(line) ?: continue
            // numbers are right-aligned: categories end their "K:" at column 11, processes indent further
            val isCategory = line.indexOf("K: ") <= 12
            val kb = num(m.groupValues[2])
            val name = m.groupValues[3]
            if (isCategory) category = CATEGORY[name] ?: name
            else if (!name.startsWith("(")) rows += Row(label(name.substringBefore(" (pid").trim()), kb * 1024, category)
        }
        val total = kb(text, TOTAL_RAM)
        val free = FREE_RAM.find(text)
        val used = USED_RAM.find(text)
        return Memory(
            total = total,
            inUse = used?.let { num(it.groupValues[2]) * 1024 } ?: 0,
            kernel = used?.let { num(it.groupValues[3]) * 1024 } ?: 0,
            cachedApps = free?.let { num(it.groupValues[2]) * 1024 } ?: 0,
            cachedFiles = free?.let { num(it.groupValues[3]) * 1024 } ?: 0,
            free = free?.let { num(it.groupValues[4]) * 1024 } ?: 0,
            rows = rows.sortedByDescending { it.value },
        )
    }

    // ---------- CPU ----------
    fun cpu(): Cpu {
        // two samples 2 s apart; the second is the real one. %CPU is per core (400% = all four busy).
        val text = adb.shell("top -b -n 2 -d 2 -q -o %CPU,NAME -s 1 -m 40")
        val blocks = text.trim().split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }
        val last = blocks.lastOrNull() ?: return Cpu(0f, emptyList())
        val byName = LinkedHashMap<String, Float>()
        for (line in last.lines()) {
            val m = CPU_LINE.find(line) ?: continue
            val pct = m.groupValues[1].toFloatOrNull() ?: continue
            val raw = m.groupValues[2].trim()
            if (raw == "top" || raw.startsWith("sh")) continue          // our own command
            val name = if (raw.startsWith("[")) "Linux kernel" else label(raw)
            byName[name] = (byName[name] ?: 0f) + pct
        }
        val rows = byName.map { (n, p) -> Row(n, (p * 10 / cores).toLong(), if (n == "Linux kernel") "System" else tagFor(n)) }
            .filter { it.value > 0 }.sortedByDescending { it.value }
        return Cpu(rows.sumOf { it.value } / 10f, rows)
    }

    // ---------- STORAGE ----------
    fun storage(): Storage {
        val text = adb.shell("dumpsys diskstats")
        val df = DATA_FREE.find(text)
        val names = array(text, "Package Names").map { it.trim('"') }
        val app = array(text, "App Sizes").map { it.toLongOrNull() ?: 0 }
        val data = array(text, "App Data Sizes").map { it.toLongOrNull() ?: 0 }
        val rows = names.indices.mapNotNull { i ->
            val bytes = (app.getOrNull(i) ?: 0) + (data.getOrNull(i) ?: 0)
            if (bytes <= 0) null else Row(label(names[i]), bytes, tagFor(names[i]))
        }.sortedByDescending { it.value }
        return Storage(
            total = df?.let { num(it.groupValues[2]) * 1024 } ?: 0,
            free = df?.let { num(it.groupValues[1]) * 1024 } ?: 0,
            apps = longAfter(text, "App Size:"),
            appData = longAfter(text, "App Data Size:"),
            rows = rows,
        )
    }

    // ---------- helpers ----------
    /** "com.disney.disneyplus:service" -> "Disney+" (the app's own label); system processes keep their name. */
    private fun label(process: String): String = labels.getOrPut(process) {
        val pkg = process.substringBefore(':')
        try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() + (if (':' in process) " (" + process.substringAfter(':').take(18) + ")" else "") }
        catch (e: PackageManager.NameNotFoundException) { FRIENDLY[process] ?: process }
    }

    private fun tagFor(name: String) = if (name.contains('.') || name in labels.values) "App" else "System"

    private fun num(s: String) = s.replace(",", "").toLongOrNull() ?: 0L
    private fun kb(text: String, r: Regex) = r.find(text)?.let { num(it.groupValues[1]) * 1024 } ?: 0L
    private fun longAfter(text: String, key: String) = text.lineSequence().firstOrNull { it.startsWith(key) }?.substringAfter(key)?.trim()?.toLongOrNull() ?: 0L
    private fun array(text: String, key: String): List<String> =
        text.lineSequence().firstOrNull { it.startsWith("$key:") }?.substringAfter('[')?.substringBeforeLast(']')
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    private companion object {
        val ENTRY = Regex("^( +)([\\d,]+)K: (.+)$")
        val TOTAL_RAM = Regex("Total RAM: ([\\d,]+)K")
        val FREE_RAM = Regex("Free RAM: ([\\d,]+)K \\(\\s*([\\d,]+)K cached pss \\+\\s*([\\d,]+)K cached kernel \\+\\s*([\\d,]+)K free")
        val USED_RAM = Regex("Used RAM: ([\\d,]+)K \\(\\s*([\\d,]+)K used pss \\+\\s*([\\d,]+)K kernel")
        val DATA_FREE = Regex("Data-Free: ([\\d,]+)K / ([\\d,]+)K total")
        val CPU_LINE = Regex("^\\s*([\\d.]+)\\s+(.+)$")
        /** Android's memory categories in plain words. */
        val CATEGORY = mapOf(
            "Native" to "System", "System" to "System", "Persistent" to "System", "Persistent Service" to "System",
            "Foreground" to "On screen", "Visible" to "Visible", "Perceptible" to "Playing / audible", "Perceptible Low" to "Background",
            "A Services" to "Background", "B Services" to "Background", "Home" to "Home screen", "Previous" to "Cached",
            "Heavy Weight" to "Background", "Backup" to "Background", "Cached" to "Cached",
        )
        val FRIENDLY = mapOf(
            "system" to "Android system", "surfaceflinger" to "Display (SurfaceFlinger)", "audioserver" to "Audio",
            "system_server" to "Android system", "lmkd" to "Memory manager", "media.metrics" to "Media",
        )
    }
}
