package com.chmouel.liseur.ui.stats

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.chmouel.liseur.R
import java.util.concurrent.TimeUnit

/**
 * A length of time, said the way a person would say it.
 *
 * Rounded to the minute and no finer. Seconds are noise here — nobody
 * reads for four minutes and eleven seconds, they read for a few
 * minutes — and showing them would invite the reader to watch a number
 * that is only ever an estimate tick upwards.
 */
@Composable
fun readingDuration(millis: Long): String {
    if (millis <= 0) return stringResource(R.string.duration_none)
    if (millis < TimeUnit.MINUTES.toMillis(1)) {
        return stringResource(R.string.duration_under_minute)
    }
    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(millis)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours == 0L -> stringResource(R.string.duration_minutes, minutes)
        minutes == 0L -> stringResource(R.string.duration_hours, hours)
        else -> stringResource(R.string.duration_hours_minutes, hours, minutes)
    }
}

/**
 * The same length of time, shortened to fit above a bar.
 *
 * "1h20", "45m" — the abbreviations are unit symbols rather than
 * words, so they survive untranslated the way "km" does. Null rather
 * than a dash for an empty day: above a bar of nothing, any text at
 * all is clutter.
 */
fun compactDuration(millis: Long): String? {
    if (millis <= 0) return null
    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(millis).coerceAtLeast(1)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours == 0L -> "${minutes}m"
        minutes == 0L -> "${hours}h"
        else -> "${hours}h${minutes.toString().padStart(2, '0')}"
    }
}
