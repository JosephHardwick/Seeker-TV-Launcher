package com.seeker.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * SLATE's rotating photos (design/SLATE-SPEC.md): Bing's image-of-the-day archive, 1920x1080, no key.
 * Google TV's own Ambient photos can't be used: they come from Google's private servers through
 * com.google.android.apps.tv.dreamx and its files are private to it.
 * The list is refreshed once a day; the last [KEEP] photos stay in filesDir as JPEGs so the backdrop
 * works offline. Only the photo on screen and the next one are ever decoded.
 */
class Backdrop(ctx: Context) {
    class Photo(val id: String, val caption: String) {
        val file get() = id + ".jpg"
    }

    private val dir = File(ctx.filesDir, "backdrop").apply { mkdirs() }
    private val prefs = ctx.getSharedPreferences("backdrop", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var fetching = false

    /** Downloaded photos, newest first. */
    fun photos(): List<Photo> = (prefs.getString("list", null) ?: "").lines().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size != 2 || !File(dir, parts[0] + ".jpg").exists()) null else Photo(parts[0], parts[1])
    }

    /** The next photo in the rotation; carries on where it left off after a restart. */
    fun next(): Photo? {
        val list = photos()
        if (list.isEmpty()) return null
        val i = (prefs.getInt("i", -1) + 1) % list.size
        prefs.edit().putInt("i", i).apply()
        return list[i]
    }

    fun isStale(): Boolean = System.currentTimeMillis() - prefs.getLong("at", 0L) > REFRESH_MS

    /** Fetches the archive (two pages, ~15 photos), downloads the new ones, trims to [KEEP]. [done] runs if anything changed. */
    fun refresh(done: () -> Unit) {
        if (fetching) return
        fetching = true
        Thread({
            try {
                val fresh = ArrayList<Photo>()
                for (idx in intArrayOf(0, 7)) {
                    val images = JSONObject(get(ARCHIVE + idx)).getJSONArray("images")
                    for (k in 0 until images.length()) {
                        val o = images.getJSONObject(k)
                        val id = o.getString("urlbase").substringAfter("id=", "")
                        if (id.isEmpty() || id.contains('/') || fresh.any { it.id == id }) continue
                        fresh += Photo(id, o.optString("copyright").substringBefore(" (©").trim())
                    }
                }
                var changed = false
                for (p in fresh) {
                    val f = File(dir, p.file)
                    if (f.exists()) continue
                    download("https://www.bing.com/th?id=${p.id}_1920x1080.jpg", f)
                    changed = true
                }
                val all = (fresh + photos()).distinctBy { it.id }.take(KEEP)
                val keep = all.map { it.file }.toSet()
                dir.listFiles()?.forEach { if (it.name !in keep) { it.delete(); changed = true } }
                prefs.edit()
                    .putString("list", all.joinToString("\n") { "${it.id}\t${it.caption.replace('\t', ' ').replace('\n', ' ')}" })
                    .putLong("at", System.currentTimeMillis())
                    .apply()
                Log.i(TAG, "backdrop: ${all.size} photos")
                if (changed) main.post(done)
            } catch (e: Exception) {
                Log.w(TAG, "backdrop refresh failed, keeping cached: $e")
            } finally {
                fetching = false
            }
        }, "seeker-backdrop").start()
    }

    /** Decodes [p] on a background thread (RGB_565: half the memory, no alpha needed). */
    fun decode(p: Photo, done: (Bitmap?) -> Unit) {
        Thread({
            val bmp = try {
                BitmapFactory.decodeFile(File(dir, p.file).path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
            } catch (e: Throwable) {
                Log.w(TAG, "backdrop decode failed: $e")
                null
            }
            main.post { done(bmp) }
        }, "seeker-photo").start()
    }

    private companion object {
        const val TAG = "SeekerTV"
        const val KEEP = 30
        const val REFRESH_MS = 24 * 60 * 60 * 1000L
        const val ARCHIVE = "https://www.bing.com/HPImageArchive.aspx?format=js&n=8&mkt=en-US&idx="

        fun get(url: String): String {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            try {
                return conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }

        /** Writes to a temp file first so a half-downloaded photo never shows up in the list. */
        fun download(url: String, to: File) {
            val tmp = File(to.path + ".part")
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            try {
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                if (!tmp.renameTo(to)) tmp.delete()
            } finally {
                conn.disconnect()
                tmp.delete()
            }
        }
    }
}
