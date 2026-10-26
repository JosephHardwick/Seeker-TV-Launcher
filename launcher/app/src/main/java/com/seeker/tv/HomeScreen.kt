package com.seeker.tv

import android.view.KeyEvent

/**
 * A full home screen that owns the app list, clock, weather and stats: the wheel (SeekerView) or SLATE
 * (SlateView). MainActivity feeds whichever ones exist and shows the one HOME MODE picks.
 */
interface HomeScreen {
    fun showApps(list: List<AppEntry>)
    fun setWeather(w: Weather.Now)
    fun tick()                                        // the minute changed
    fun setStats(values: FloatArray, details: Array<String>)
    fun hideStats()
    fun resetLaunch()                                 // back in front, or the launch never happened
    fun resetModes()                                  // leaving: close menus, save a reorder
    val isLaunching: Boolean
    fun onKey(e: KeyEvent): Boolean
}
