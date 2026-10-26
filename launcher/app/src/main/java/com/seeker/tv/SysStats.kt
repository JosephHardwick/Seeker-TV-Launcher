package com.seeker.tv

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import java.io.File
import java.util.Locale

/**
 * The home screen's system widgets (DESIGN-SPEC v5): CPU, MEMORY, STORAGE as 0..1 plus a detail line.
 * CPU: apps can't read /proc/stat (Android 8+), but every core's cpuidle state times are readable, so
 * busy = 1 - (idle time across online cores) / (wall time x online cores) between two samples.
 * MEMORY: ActivityManager.MemoryInfo, used = total - avail (avail counts reclaimable cache, like Settings).
 * STORAGE: StatFs on the data partition, decimal GB like Settings.
 */
class SysStats(ctx: Context) {
    class Reading(val value: Float, val detail: String)

    private val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val cpuDir = File("/sys/devices/system/cpu")
    private var lastIdleUs = -1L
    private var lastWallUs = 0L
    private var lastCores = 0
    private var lastCpu = Reading(0f, "")
    private var smooth = false

    /** Busy share since the previous call; the first call only primes the counters (returns 0). */
    fun cpu(): Reading {
        val online = cores(read(File(cpuDir, "online")) ?: "0")
        var idle = 0L
        var maxKhz = 0L
        for (c in online) {
            val dir = File(cpuDir, "cpu$c")
            File(dir, "cpuidle").listFiles { f -> f.name.startsWith("state") }?.forEach { s ->
                idle += read(File(s, "time"))?.toLongOrNull() ?: 0L
            }
            maxKhz = maxOf(maxKhz, read(File(dir, "cpufreq/scaling_cur_freq"))?.toLongOrNull() ?: 0L)
        }
        val wall = SystemClock.elapsedRealtimeNanos() / 1000
        val ghz = if (maxKhz > 0) String.format(Locale.US, "%.1f GHZ", maxKhz / 1e6) else ""
        if (lastIdleUs >= 0 && online.size == lastCores && wall > lastWallUs) {
            val span = (wall - lastWallUs) * online.size
            val now = (1f - (idle - lastIdleUs).toFloat() / span).coerceIn(0f, 1f)
            lastCpu = Reading(if (smooth) (lastCpu.value + now) / 2 else now, ghz)  // average with the last reading: steadier ring
            smooth = true
        } else {
            lastCpu = Reading(lastCpu.value, ghz)  // first sample, or a core went on/offline: wait for the next one
        }
        lastIdleUs = idle; lastWallUs = wall; lastCores = online.size
        return lastCpu
    }

    fun memory(): Reading {
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        if (mi.totalMem <= 0) return Reading(0f, "")
        val used = mi.totalMem - mi.availMem
        return Reading((used.toFloat() / mi.totalMem).coerceIn(0f, 1f), String.format(Locale.US, "%.1f/%.1f GB", used / GIB, mi.totalMem / GIB))
    }

    fun storage(): Reading {
        val fs = StatFs(Environment.getDataDirectory().path)
        val total = fs.totalBytes
        if (total <= 0) return Reading(0f, "")
        val used = total - fs.availableBytes
        return Reading((used.toFloat() / total).coerceIn(0f, 1f), "${Math.round(used / 1e9)}/${Math.round(total / 1e9)} GB")
    }

    private companion object {
        const val GIB = 1024.0 * 1024 * 1024  // RAM in binary GB, like Settings' memory screen

        fun read(f: File): String? = try { f.readText().trim() } catch (e: Exception) { null }

        /** "0-3", "0,2-3" -> [0, 1, 2, 3] / [0, 2, 3]. */
        fun cores(s: String): List<Int> = s.split(',').flatMap { part ->
            val ends = part.trim().split('-').mapNotNull { it.toIntOrNull() }
            when (ends.size) { 1 -> listOf(ends[0]); 2 -> (ends[0]..ends[1]).toList(); else -> emptyList() }
        }
    }
}
