package com.chmouel.liseur.vocabulary

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chmouel.liseur.R
import com.chmouel.liseur.ui.LocalEInk
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun VocabularyStatsScreen(repository: VocabularyRepository, book: String?, stats: VocabularyStats?, modifier: Modifier) {
    // The streak spans all history, even when it exceeds the chart and recall windows
    val reviews by remember(repository, book) { repository.dao.reviewsSince(0, book) }
        .collectAsStateWithLifecycle(initialValue = null)
    val (today, zone) = rememberVocabularyDay()
    val activity = remember(reviews, today, zone) { vocabularyActivity(reviews.orEmpty(), today, zone) }
    val locale = LocalConfiguration.current.locales[0]
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (stats != null) {
            listOf(
                R.string.vocabulary_total to stats.total,
                R.string.vocabulary_new to stats.newWords,
                R.string.vocabulary_learning to stats.total - stats.newWords - stats.learned,
                R.string.vocabulary_learned to stats.learned,
            ).chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (label, count) ->
                        StatCard(stringResource(label), NumberFormat.getIntegerInstance(locale).format(count), Modifier.weight(1f))
                    }
                }
            }
        }
        if (reviews != null) {
            if (!activity.hasReviews) {
                Text(stringResource(R.string.vocabulary_stats_empty), Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodyLarge)
            } else {
                ReviewChart(activity.days)
                StatCard(
                    stringResource(R.string.vocabulary_recall_rate),
                    activity.recallRate?.let { NumberFormat.getPercentInstance(locale).format(it) } ?: stringResource(R.string.vocabulary_no_recent_reviews),
                    Modifier.fillMaxWidth(),
                )
                StatCard(stringResource(R.string.vocabulary_day_streak), NumberFormat.getIntegerInstance(locale).format(activity.streak), Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun rememberVocabularyDay(): Pair<LocalDate, ZoneId> {
    fun currentDay(): Pair<LocalDate, ZoneId> {
        val zone = ZoneId.systemDefault()
        return LocalDate.now(zone) to zone
    }

    val context = LocalContext.current
    var day by remember { mutableStateOf(currentDay()) }
    LifecycleResumeEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                day = currentDay()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        day = currentDay()
        onPauseOrDispose { context.unregisterReceiver(receiver) }
    }
    return day
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.semantics(mergeDescendants = true) {}) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReviewChart(days: List<VocabularyDay>) {
    val locale = LocalConfiguration.current.locales[0]
    val date = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale) }
    val color = if (LocalEInk.current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant
    val daily = days.map { stringResource(R.string.vocabulary_daily_reviews, it.date.format(date), it.reviews) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.vocabulary_reviews_per_day), style = MaterialTheme.typography.titleMedium)
        Canvas(Modifier.fillMaxWidth().height(156.dp).semantics { contentDescription = daily.joinToString("; ") }) {
            val step = size.width / days.size
            val maximum = days.maxOf { it.reviews }.coerceAtLeast(1)
            drawLine(track, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            days.forEachIndexed { index, day ->
                if (day.reviews > 0) {
                    val height = day.reviews.toFloat() / maximum * size.height
                    drawRoundRect(
                        color, topLeft = Offset(index * step + step * 0.15f, size.height - height),
                        size = Size(step * 0.7f, height), cornerRadius = CornerRadius(3.dp.toPx()),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(days.first().date.format(date), style = MaterialTheme.typography.labelSmall)
            Text(days.last().date.format(date), style = MaterialTheme.typography.labelSmall)
        }
    }
}
