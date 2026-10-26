package com.seeker.tv

import android.app.Activity
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.widget.ArrayAdapter
import android.widget.ListView
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : Activity(), SeekerView.Host, SettingsView.Host, GameHost {

    companion object {
        private const val TAG = "SeekerTV"
        private const val MAX_CRASHES = 2
        private const val STATS_MS = 2000L
        private const val STORAGE_EVERY = 15  // x STATS_MS = 30 s
        private val ANIM_KEYS = arrayOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
        const val DEFAULT_NAME = "SEEKER"
        const val MAX_NAME = 12
        /** The characters the dot-matrix and pixel fonts can draw. */
        private val NAME_CHARS = Regex("[^A-Z0-9 +\\-.!&]")

        /** NAME setting: upper case, drawable characters only, at most [MAX_NAME]. Blank means the default. */
        fun cleanName(raw: String?): String =
            raw.orEmpty().uppercase(Locale.US).replace(NAME_CHARS, "").trim().take(MAX_NAME).trim().ifEmpty { DEFAULT_NAME }
    }

    private lateinit var prefs: SharedPreferences
    private var view: SeekerView? = null
    private var settings: SettingsView? = null
    /** Game screens built so far, by HOME MODE name. Only the flavor's BuiltModes can appear here. */
    private val games = HashMap<String, GameScreen>()
    /** Settings > APP LIST in a game mode: the wheel until the next launch, BACK or HOME. */
    private var wheelVisit = false
    private var shown: android.view.View? = null
    private var allApps: List<AppEntry> = emptyList()
    private var weather: Weather? = null
    private val main = Handler(Looper.getMainLooper())
    private var resumed = false

    private val weatherTick = object : Runnable {
        override fun run() {
            weather?.fetch { view?.setWeather(it) }
            main.postDelayed(this, Weather.REFRESH_MS)
        }
    }

    /** v5 widgets (STATS FOR NERDS): CPU and MEMORY every 2 s, STORAGE every 30 s, only while the wheel is the screen shown. */
    private var stats: SysStats? = null
    private var statsTicks = 0
    private var storage: SysStats.Reading? = null
    private val statsTick = object : Runnable {
        override fun run() {
            if (!statsOn()) return  // STATS FOR NERDS turned off: stop rescheduling
            val v = view
            val s = stats
            if (v != null && s != null && shown === v) {
                val cpu = s.cpu()
                val mem = s.memory()
                val disk = storage?.takeIf { statsTicks % STORAGE_EVERY != 0 } ?: s.storage().also { storage = it }
                statsTicks++
                v.setStats(floatArrayOf(cpu.value, mem.value, disk.value), arrayOf(cpu.detail, mem.detail, disk.detail))
            }
            main.postDelayed(this, STATS_MS)
        }
    }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { view?.tick() }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { reloadApps() }
    }

    /** HOME MODE can change from settings or from the com.seeker.tv.MODE broadcast (same process). */
    private val modeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "name") { main.post { view?.setGreeting(greetingText()); settings?.invalidate() }; return@OnSharedPreferenceChangeListener }
        if (key == "weather_rev") { main.post { refetchWeather() }; return@OnSharedPreferenceChangeListener }
        if (key != "home_mode") return@OnSharedPreferenceChangeListener
        main.post {
            val s = settings
            if (s != null && shown === s) s.modeChanged()
            else if (view?.isLaunching != true) { wheelVisit = false; showHome() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("seeker", MODE_PRIVATE)
        installCrashCounter()
        Log.i(TAG, "onCreate")
        Sfx.enabled = soundOn()
        Sfx.init(applicationContext)
        applyAnimationScales()

        val crashes = prefs.getInt("crashes", 0)
        if (crashes >= MAX_CRASHES) {
            Log.e(TAG, "$crashes crashes in a row, showing the fallback list")
            showFallback()
            return
        }
        try {
            val v = SeekerView(this, this)
            view = v
            v.setGreeting(greetingText())
            weather = Weather(this).also { v.setWeather(it.cached()) }
            stats = SysStats(this)
            allApps = Apps.load(this)
            v.setApps(Apps.wheel(allApps, prefs))
            showHome()
        } catch (e: Exception) {
            Log.e(TAG, "startup failed, showing the fallback list", e)
            view = null
            showFallback()
            return
        }
        NotifyService.start(this)
        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        prefs.registerOnSharedPreferenceChangeListener(modeListener)
    }

    override fun onStart() {
        super.onStart()
        if (view == null) return
        registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        view?.tick()
        main.removeCallbacks(weatherTick)
        if (Milestone.M2) if (weather?.isStale() != false) main.post(weatherTick) else main.postDelayed(weatherTick, Weather.REFRESH_MS)
        main.removeCallbacks(statsTick)
        startStats()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        Toaster.front = WeakReference(this)
        applyAnimationScales()
    }

    override fun onPause() {
        resumed = false
        if (Toaster.front?.get() === this) Toaster.front = null
        super.onPause()
    }

    override fun onStop() {
        if (view != null) {
            unregisterReceiver(timeReceiver)
            main.removeCallbacks(weatherTick)
            main.removeCallbacks(statsTick)
            view?.resetLaunch()  // come back to a clean wheel
            view?.resetModes()
            gamesToStart()       // the games: HOME always shows START, never mid-game
            if (wheelVisit) { wheelVisit = false; if (shown === view) showHome() }
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (view != null) {
            unregisterReceiver(packageReceiver)
            prefs.unregisterOnSharedPreferenceChangeListener(modeListener)
        }
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // HOME pressed while we're already up (once Seeker is HOME): back to the wheel
        if (!intent.hasCategory(Intent.CATEGORY_HOME) || view == null) return
        wheelVisit = false
        gamesToStart()
        if (shown !== view || homeMode() != "WHEEL") showHome()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "key $event")
        val handled = when (val cur = shown) {
            is SettingsView -> cur.onKey(event)
            is GameScreen -> cur.onKey(event)
            else -> view?.onKey(event) == true
        }
        return handled || super.dispatchKeyEvent(event)
    }

    // ---------- SeekerView.Host ----------
    override fun launch(entry: AppEntry) {
        val intent = entry.intent
        try {
            val opts = ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, 0)
            startActivity(intent, opts.toBundle())
            // If nothing came up in front of us, don't leave the color screen hanging.
            main.postDelayed({ if (resumed) { view?.resetLaunch(); gamesToStart() } }, 4000)
        } catch (e: Exception) {
            Log.e(TAG, "launch failed: ${entry.key}", e)
            view?.resetLaunch()
            gamesToStart()
        }
    }

    override fun openSettings() {
        val s = settings ?: SettingsView(this, this).also { settings = it }
        s.open()
        show(s)
    }

    override fun hide(entry: AppEntry) = Apps.setHidden(prefs, entry.key, true)

    override fun saveOrder(keys: List<String>) = Apps.saveOrder(prefs, keys)

    // ---------- SettingsView.Host ----------
    override fun openSystemSettings() = start(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    override fun openInputs() {
        val i = Apps.inputsIntent(this)
        if (i == null) Log.w(TAG, "no inputs activity on this TV") else start(i)
    }

    override fun name(): String = cleanName(prefs.getString("name", null))

    override fun editName() = askText("NAME", "Shown as HELLO <NAME>. Letters and numbers, up to $MAX_NAME.", name()) {
        prefs.edit().putString("name", cleanName(it)).apply()  // the listener redraws the greeting
    }

    override fun weatherPlace(): String = (weather?.place() ?: Weather.DEFAULT).name

    override fun editWeatherPlace() = askText("WEATHER LOCATION", "A city name, like Denver or Paris.", "") { q ->
        val w = weather ?: return@askText
        if (q.isBlank()) return@askText
        w.lookup(q) { p ->
            if (p == null) { Toaster.show(this, "WEATHER LOCATION", "No place called \"$q\".", "SEEKER"); return@lookup }
            w.setPlace(p)
            settings?.invalidate()
            refetchWeather()
        }
    }

    override fun allApps(): List<AppEntry> = allApps

    override fun hiddenKeys(): Set<String> = Apps.hidden(prefs)

    override fun toggleHidden(entry: AppEntry) {
        Apps.setHidden(prefs, entry.key, entry.key !in Apps.hidden(prefs))
        view?.setApps(Apps.wheel(allApps, prefs))
    }

    override fun about(): List<Pair<String, String>> {
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        val pss = android.os.Debug.getPss() / 1024
        val w = weather?.cached()
        val wx = if (w == null || w.at == 0L) "NEVER" else
            "${w.temp} ${w.icon.uppercase()} AT ${SimpleDateFormat("h:mm a", Locale.US).apply {
                timeZone = TimeZone.getDefault() }.format(Date(w.at))}"
        val home = packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName ?: "?"
        return listOf(
            "VERSION" to "$ver",
            "RAM" to "$pss MB PSS",
            "WEATHER" to wx,
            "HOME" to if (home == packageName) "YES, SEEKER IS HOME" else "NO ($home)",
        )
    }

    override fun closeSettings() = showHome()

    override fun homeModes(): List<String> = NotifyService.MODES

    override fun homeMode(): String = prefs.getString("home_mode", null)?.takeIf { it in NotifyService.MODES } ?: "WHEEL"

    override fun cycleHomeMode() {
        val next = NotifyService.MODES[(NotifyService.MODES.indexOf(homeMode()) + 1) % NotifyService.MODES.size]
        prefs.edit().putString("home_mode", next).apply()
        Log.i(TAG, "HOME MODE = $next (settings)")
    }

    override fun soundOn(): Boolean = prefs.getBoolean("sound", true)

    override fun toggleSound() {
        val on = !soundOn()
        prefs.edit().putBoolean("sound", on).apply()
        Sfx.enabled = on
        Log.i(TAG, "SOUND = ${if (on) "ON" else "OFF"}")
    }

    override fun statsOn(): Boolean = prefs.getBoolean("stats", false)  // v5, off by default

    override fun toggleStats() {
        val on = !statsOn()
        prefs.edit().putBoolean("stats", on).apply()
        main.removeCallbacks(statsTick)
        if (on) startStats() else view?.hideStats()
        Log.i(TAG, "STATS FOR NERDS = ${if (on) "ON" else "OFF"}")
    }

    /** Prime the idle counters so the first reading is a real 1 s sample. Nothing is sampled while STATS is OFF. */
    private fun startStats() {
        if (!statsOn()) return
        statsTicks = 0
        stats?.cpu()
        main.postDelayed(statsTick, 1000)
    }

    override fun openAppList() {
        val v = view ?: return
        wheelVisit = true
        show(v)
    }

    override fun back() {
        if (wheelVisit) { wheelVisit = false; showHome() }
    }

    // ---------- GameHost ----------
    override fun flapEntry(pkg: String): AppEntry? = allApps.firstOrNull { it.key == pkg }

    /** The app's launcher icon (not the TV banner) for the tube caps. */
    override fun flapIcon(entry: AppEntry): android.graphics.drawable.Drawable? = try {
        entry.component?.let { packageManager.getActivityIcon(it) } ?: packageManager.getApplicationIcon(entry.key)
    } catch (e: Exception) {
        null
    }

    override fun flapBest(): Int = prefs.getInt("flap_best", 0)

    override fun saveFlapBest(best: Int) = prefs.edit().putInt("flap_best", best).apply()

    override fun firstFrame() {
        // Survived startup and drew: clear the crash counter after a few quiet seconds.
        main.postDelayed({ prefs.edit().putInt("crashes", 0).apply() }, 5000)
    }

    // ---------- helpers ----------
    /** The home screen for the current HOME MODE: a game's START, or the wheel (also during an APP LIST visit). */
    private fun showHome() {
        val v = view ?: return
        when (if (wheelVisit) "WHEEL" else homeMode()) {
            "WHEEL" -> show(v)
            else -> {
                val mode = homeMode()
                val g = games[mode] ?: BuiltModes.GAMES.firstOrNull { it.name == mode }?.create?.invoke(this, this)
                    ?.also { games[mode] = it }
                if (g == null) { show(v); return }
                g.refreshApps()
                if (shown !== g) g.toStart()
                show(g as android.view.View)
            }
        }
    }

    private fun gamesToStart() = games.values.forEach { it.toStart() }

    private fun show(target: android.view.View) {
        if (shown !== target) {
            setContentView(target)
            shown = target
            // Every screen paints its own opaque background; skipping the window's saves a full-screen fill a frame.
            window.setBackgroundDrawable(null)
        }
        target.requestFocus()
    }

    private fun start(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "start failed: $intent", e)
        }
    }

    private fun reloadApps() {
        val v = view ?: return
        try {
            allApps = Apps.load(this)
            v.setApps(Apps.wheel(allApps, prefs))
            games.values.forEach { it.refreshApps() }
        } catch (e: Exception) {
            Log.e(TAG, "app reload failed", e)
        }
    }

    private fun greetingText(): String = "HELLO ${name()}"

    /** New WEATHER LOCATION: show the placeholder until the new reading arrives. */
    private fun refetchWeather() {
        val w = weather ?: return
        view?.setWeather(w.cached())
        w.fetch { view?.setWeather(it) }
    }

    /** One line of text from the TV's on-screen keyboard. [done] runs only on OK. */
    private fun askText(title: String, hint: String, current: String, done: (String) -> Unit) {
        val input = android.widget.EditText(this).apply {
            setText(current)
            setSelection(current.length)
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setMessage(hint)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> done(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        input.setOnEditorActionListener { _, _, _ -> done(input.text.toString()); dialog.dismiss(); true }
        dialog.show()
        input.requestFocus()
    }

    /** These keep reverting to 1.0 on this TV; needs WRITE_SECURE_SETTINGS granted over adb. */
    private fun applyAnimationScales() {
        try {
            for (k in ANIM_KEYS) {
                if (Settings.Global.getString(contentResolver, k) != "0.5") {
                    val ok = Settings.Global.putString(contentResolver, k, "0.5")
                    Log.i(TAG, "re-applied $k = 0.5 (ok=$ok, now ${Settings.Global.getString(contentResolver, k)})")
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "no WRITE_SECURE_SETTINGS, can't re-apply animation scales")
        }
    }

    private fun installCrashCounter() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev is CrashCounter) return
        Thread.setDefaultUncaughtExceptionHandler(CrashCounter(prefs, prev))
    }

    private class CrashCounter(
        val prefs: SharedPreferences,
        val prev: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try { prefs.edit().putInt("crashes", prefs.getInt("crashes", 0) + 1).commit() } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    /** Plain list of apps, no dot matrix. Used when the real screen keeps crashing. */
    private fun showFallback() {
        val apps = try { Apps.wheel(Apps.load(this), prefs) } catch (e: Exception) { emptyList() }
        val labels = listOf("Retry Seeker TV", "Settings") + apps.map { it.name }
        val list = ListView(this)
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        list.setOnItemClickListener { _, _, pos, _ ->
            when (pos) {
                0 -> { prefs.edit().putInt("crashes", 0).commit(); recreate() }
                1 -> startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else -> try { startActivity(apps[pos - 2].intent) } catch (e: Exception) { Log.e(TAG, "launch failed", e) }
            }
        }
        setContentView(list)
        list.requestFocus()
    }
}
