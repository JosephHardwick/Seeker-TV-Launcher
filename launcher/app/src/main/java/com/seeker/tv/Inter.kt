package com.seeker.tv

import android.content.Context
import android.graphics.Typeface
import android.util.Log

/**
 * SLATE's font: Inter (SIL Open Font License, assets/fonts/OFL.txt), one variable TTF with weight and
 * optical-size axes. San Francisco can't ship (Apple platforms only). Falls back to the system sans.
 */
object Inter {
    private val cache = HashMap<Int, Typeface>()

    /** [weight] 100..900; [display] uses the large optical size (tighter, for 40 px and up). */
    fun at(ctx: Context, weight: Int, display: Boolean = false): Typeface =
        cache.getOrPut(weight * 2 + if (display) 1 else 0) {
            try {
                Typeface.Builder(ctx.assets, "fonts/Inter.ttf")
                    .setFontVariationSettings("'wght' $weight, 'opsz' ${if (display) 32 else 14}")
                    .build()
            } catch (e: Exception) {
                Log.w("SeekerTV", "Inter failed, using sans: $e")
                Typeface.create(Typeface.SANS_SERIF, weight, false)
            }
        }
}
