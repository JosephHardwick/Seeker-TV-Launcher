package com.seeker.tv

/** The game HOME MODEs in the 'pong' flavor. WHEEL is always built in. */
object BuiltModes {
    val GAMES: List<GameMode> = listOf(GameMode("PONG") { c, h -> PongView(c, h) })
}
