package com.chmouel.liseur.tts

import java.util.Locale

internal const val ACTION_PREVIOUS_SENTENCE = "com.chmouel.liseur.tts.PREVIOUS_SENTENCE"
internal const val ACTION_NEXT_SENTENCE = "com.chmouel.liseur.tts.NEXT_SENTENCE"
internal const val ACTION_CYCLE_SPEED = "com.chmouel.liseur.tts.CYCLE_SPEED"
internal const val ACTION_CYCLE_SLEEP_TIMER = "com.chmouel.liseur.tts.CYCLE_SLEEP_TIMER"

private val SLEEP_CYCLE_MINUTES = listOf(15, 30, 60)

internal fun nextReadAloudSpeed(current: Float): Float {
    val snapped = ReadAloudSpeed.of(current)
    val index = ReadAloudSpeed.STEPS.indexOf(snapped)
    return ReadAloudSpeed.STEPS[(index + 1) % ReadAloudSpeed.STEPS.size]
}

internal fun nextSleepTimerMinutes(current: SleepTimer?): Int? = when (current) {
    null -> SLEEP_CYCLE_MINUTES.first()
    SleepTimer.EndOfChapter -> null
    is SleepTimer.Timed -> SLEEP_CYCLE_MINUTES.firstOrNull { it > current.minutes }
}

internal fun formatSpeedCommandLabel(speed: Float, locale: Locale = Locale.getDefault()): String =
    ReadAloudSpeed.number(speed, locale) + "×"

internal fun resolveChapterTitleForResource(readingOrderTitle: String?, tocTitle: String?, index: Int): String? =
    tocTitle?.trim()?.takeIf(String::isNotEmpty)
        ?: readingOrderTitle?.trim()?.takeIf(String::isNotEmpty)
