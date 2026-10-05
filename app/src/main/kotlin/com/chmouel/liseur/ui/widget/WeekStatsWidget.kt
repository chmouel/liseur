package com.chmouel.liseur.ui.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import com.chmouel.liseur.R
import java.text.NumberFormat
import kotlinx.coroutines.coroutineScope

class WeekStatsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(StatsCompact, StatsMedium, StatsRoomy))

    override suspend fun provideGlance(context: Context, id: GlanceId) = coroutineScope {
        val live = LiveSnapshot.start(context, id, this, WidgetContent.STATS)
        provideContent {
            GlanceTheme(colors = LiseurGlanceColorScheme.colors) {
                val snapshot = live.observe()
                val stats = snapshot.stats ?: return@GlanceTheme
                val onClick = WidgetLaunchActivity.intent(context, stats = true)
                WidgetScaffold(onClick = onClick) {
                    StatsContent(context = context, stats = stats, book = snapshot.book)
                }
            }
        }
    }
}

@Composable
internal fun StatsContent(context: Context, stats: WidgetStats, book: WidgetBook?) {
    val size = LocalSize.current
    val fontScale = context.resources.configuration.fontScale
    val compact = statsCompactLayout(size.height.value, fontScale)
    val compactText = fontScale > 1.3f
    val horizontal = size.width >= 340.dp && fontScale <= 1.3f
    val padding = if (compact) 6.dp else 12.dp
    val headingSize = if (compactText) 8.sp else 12.sp
    val titleSize = if (compactText) 12.sp else 18.sp
    val progressSize = if (compactText) 8.sp else 12.sp
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(padding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = GlanceModifier.fillMaxWidth().let {
                if (book != null) it.clickable(actionStartActivity(book.openIntent)) else it
            },
        ) {
            Text(
                context.getString(R.string.widget_currently_reading),
                style = TextStyle(color = GlanceTheme.colors.primary, fontSize = headingSize),
            )
            Text(
                book?.title ?: context.getString(R.string.widget_no_book),
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = titleSize, fontFamily = FontFamily.Serif),
                maxLines = 2,
            )
            if (book != null) {
                val progress = book.progression?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
                val label = progress?.let { NumberFormat.getPercentInstance().format(it) }
                    ?: context.getString(R.string.widget_progress_unavailable)
                if (progress != null) {
                    Spacer(GlanceModifier.height(if (compact) 2.dp else 6.dp))
                    ProgressTrack(progress, GlanceModifier.fillMaxWidth().height(if (compact) 4.dp else 6.dp))
                }
                Text(label, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = progressSize))
            }
        }
        Spacer(GlanceModifier.height(if (compact) 2.dp else 10.dp))
        val labels = listOf(
            R.string.reading_stats_this_week_local,
            R.string.reading_stats_this_month_local,
            R.string.reading_stats_this_year_local,
        )
        val dashboard = GlanceModifier.fillMaxWidth()
            .clickable(actionStartActivity(WidgetLaunchActivity.intent(context, stats = true)))
        if (horizontal) {
            Row(modifier = dashboard) {
                stats.periods.forEachIndexed { index, period ->
                    PeriodTime(context, labels[index], period.totalMs, GlanceModifier.defaultWeight(), stacked = false, compactText = compactText)
                }
            }
        } else {
            Column(modifier = dashboard) {
                stats.periods.forEachIndexed { index, period ->
                    PeriodTime(context, labels[index], period.totalMs, GlanceModifier.fillMaxWidth(), stacked = true, compactText = compactText)
                }
            }
        }
        Spacer(GlanceModifier.height(if (compact) 2.dp else 8.dp))
        StatsScope(context, stats, compactText)
    }
}

internal fun statsCompactLayout(heightDp: Float, fontScale: Float): Boolean =
    fontScale > 1.3f || heightDp < StatsRoomy.height.value

@Composable
private fun PeriodTime(
    context: Context,
    labelRes: Int,
    millis: Long,
    modifier: GlanceModifier,
    stacked: Boolean,
    compactText: Boolean,
) {
    val label = context.getString(labelRes)
    val time = formatCompactDuration(context, millis)
    val accessible = modifier.padding(bottom = 4.dp).semantics { contentDescription = "$label: $time" }
    val valueStyle = TextStyle(
        color = GlanceTheme.colors.onSurface, fontSize = if (compactText) 10.sp else 18.sp,
        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif,
    )
    if (stacked) Row(accessible, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = GlanceModifier.defaultWeight(), style = TextStyle(color = GlanceTheme.colors.primary, fontSize = if (compactText) 8.sp else 12.sp))
        Text(time, modifier = GlanceModifier.defaultWeight(), style = valueStyle)
    } else Column(accessible) {
        Text(label, style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp))
        Text(time, style = valueStyle)
    }
}

class WeekStatsWidgetReceiver : LiseurWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeekStatsWidget()
}
