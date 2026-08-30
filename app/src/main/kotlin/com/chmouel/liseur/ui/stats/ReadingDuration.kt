package com.chmouel.liseur.ui.stats

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.chmouel.liseur.R
import java.util.concurrent.TimeUnit

/**
 * A length of time, broken into the units a person would say it in.
 *
 * Pure, and separate from the composable that words it, so the rules
 * below can be written down as tests rather than reasoned about.
 */
sealed interface DurationParts {
    /** Nothing recorded at all. */
    data object None : DurationParts

    /** Something, but less than the smallest unit worth naming. */
    data object UnderMinute : DurationParts
    data class Minutes(val minutes: Int) : DurationParts
    data class Hours(val hours: Int, val minutes: Int) : DurationParts

    /** A day or more. [hours] is 0 for a whole number of days. */
    data class Days(val days: Int, val hours: Int) : DurationParts
}

/**
 * Splits [millis] into the largest units that still say something.
 *
 * Resolved to the minute and no finer. Seconds are noise here — nobody
 * reads for four minutes and eleven seconds, they read for a few
 * minutes — and showing them would invite the reader to watch a number
 * that is only ever an estimate tick upwards.
 *
 * Past a day the minutes go too. "17 d 4 h 23 min" is a figure nobody
 * reads to the end of, and the last term is a rounding error beside the
 * first.
 *
 * Every unit truncates, never rounds up. The dashboard keeps its
 * headline at or above the sum of the rows beneath it on purpose, and a
 * total that rounded up while its parts rounded down would break that
 * in the one direction that reads as a bug.
 *
 * Nothing above days. An all-time total of "312 d 4 h" is a large
 * number honestly stated; the same in weeks is arithmetic homework.
 */
fun durationParts(millis: Long): DurationParts {
    if (millis <= 0) return DurationParts.None
    if (millis < TimeUnit.MINUTES.toMillis(1)) return DurationParts.UnderMinute
    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(millis)
    val totalHours = totalMinutes / MINUTES_PER_HOUR
    if (totalHours >= HOURS_PER_DAY) {
        return DurationParts.Days(
            days = (totalHours / HOURS_PER_DAY).toInt(),
            hours = (totalHours % HOURS_PER_DAY).toInt(),
        )
    }
    val minutes = (totalMinutes % MINUTES_PER_HOUR).toInt()
    if (totalHours == 0L) return DurationParts.Minutes(minutes)
    return DurationParts.Hours(hours = totalHours.toInt(), minutes = minutes)
}

/** A length of time, said the way a person would say it. */
@Composable
fun readingDuration(millis: Long): String = when (val parts = durationParts(millis)) {
    DurationParts.None -> stringResource(R.string.duration_none)
    DurationParts.UnderMinute -> stringResource(R.string.duration_under_minute)
    is DurationParts.Minutes -> stringResource(R.string.duration_minutes, parts.minutes)
    is DurationParts.Hours -> if (parts.minutes == 0) {
        stringResource(R.string.duration_hours, parts.hours)
    } else {
        stringResource(R.string.duration_hours_minutes, parts.hours, parts.minutes)
    }

    is DurationParts.Days -> if (parts.hours == 0) {
        stringResource(R.string.duration_days, parts.days)
    } else {
        stringResource(R.string.duration_days_hours, parts.days, parts.hours)
    }
}

/**
 * The same length of time, shortened to fit above a bar.
 *
 * "1h20", "45m", "2d3h" — the abbreviations are unit symbols rather
 * than words, so they survive untranslated the way "km" does. Null
 * rather than a dash for an empty day: above a bar of nothing, any text
 * at all is clutter.
 *
 * A single day's bar rarely passes twenty-four hours, but it can: a
 * sitting that runs past midnight is counted whole on the day it ended.
 * "41h" above a bar is a figure the reader has to convert themselves.
 */
fun compactDuration(millis: Long): String? = when (val parts = durationParts(millis)) {
    DurationParts.None -> null
    // A sitting too short to name is still a bar the eye can see.
    // Captioning it "0m" would read as a fault rather than a moment.
    DurationParts.UnderMinute -> "1m"
    is DurationParts.Minutes -> "${parts.minutes}m"
    is DurationParts.Hours -> if (parts.minutes == 0) {
        "${parts.hours}h"
    } else {
        "${parts.hours}h${parts.minutes.toString().padStart(2, '0')}"
    }

    is DurationParts.Days -> if (parts.hours == 0) {
        "${parts.days}d"
    } else {
        "${parts.days}d${parts.hours}h"
    }
}

private const val MINUTES_PER_HOUR = 60L
private const val HOURS_PER_DAY = 24L
