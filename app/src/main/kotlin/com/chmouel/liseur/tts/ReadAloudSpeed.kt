package com.chmouel.liseur.tts

import java.text.NumberFormat
import java.util.Locale

/** The speeds reading aloud offers, 1 being as the voice speaks. */
internal object ReadAloudSpeed {
    val STEPS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    /** The offered speed nearest [stored], so a value from an older or edited backup still plays. */
    fun of(stored: Float): Float =
        if (stored.isNaN()) 1f else STEPS.minBy { kotlin.math.abs(it - stored) }

    /** "1", "1.25" or "0,75": the number as [locale] writes it, without trailing zeros. */
    fun number(speed: Float, locale: Locale): String =
        NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }.format(speed)
}
