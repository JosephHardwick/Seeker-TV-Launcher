package com.seeker.tv

/**
 * Build-time feature level. 1 = wheel + clock + launch. 2 = adds greeting, date, weather, gear, takeover.
 * 3 = item menu (MOVE / HIDE), reorder, Seeker settings screen. 4 = NOTIFY toast overlay. M5 (Seeker Flap, HOME MODE) is a runtime setting, not a level.
 */
object Milestone {
    const val LEVEL = 4
    const val M2 = LEVEL >= 2
    const val M4 = LEVEL >= 4
}
