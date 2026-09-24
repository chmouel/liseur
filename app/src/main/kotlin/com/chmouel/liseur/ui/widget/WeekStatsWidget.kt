package com.chmouel.liseur.ui.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.chmouel.liseur.MainActivity
import com.chmouel.liseur.R
import kotlinx.coroutines.coroutineScope

class WeekStatsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(StatsCompact, StatsRoomy))

    override suspend fun provideGlance(context: Context, id: GlanceId) = coroutineScope {
        val live = LiveSnapshot.start(context, id, this)
        provideContent {
            GlanceTheme(colors = LiseurGlanceColorScheme.colors) {
                val snapshot = live.observe()
                val onClick = snapshot.book?.openIntent
                    ?: Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                WidgetScaffold(onClick = onClick) {
                    StatsContent(context = context, stats = snapshot.stats)
                }
            }
        }
    }
}

@Composable
private fun StatsContent(context: Context, stats: WidgetStats) {
    val figures = stats.figures
    val roomy = sizeAtLeast(StatsRoomy)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(if (roomy) 14.dp else 8.dp)
            .background(widgetCard),
    ) {
        HeroTime(
            label = context.getString(figures.period.headingRes),
            value = stats.totalLabel,
            modifier = GlanceModifier.fillMaxWidth(),
            compact = !roomy,
        )
        Spacer(GlanceModifier.height(if (roomy) 10.dp else 6.dp))
        if (roomy) {
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                StatTile(
                    label = context.getString(R.string.reading_stats_streak),
                    value = figures.streakDays.toString(),
                    modifier = GlanceModifier.defaultWeight(),
                )
                Spacer(GlanceModifier.width(8.dp))
                StatTile(
                    label = context.getString(R.string.reading_stats_sessions),
                    value = figures.sessions.toString(),
                    modifier = GlanceModifier.defaultWeight(),
                )
                Spacer(GlanceModifier.width(8.dp))
                StatTile(
                    label = context.getString(R.string.reading_stats_books_read),
                    value = figures.booksRead.toString(),
                    modifier = GlanceModifier.defaultWeight(),
                )
            }
            if (figures.bars.isNotEmpty()) {
                Spacer(GlanceModifier.height(12.dp))
                Text(
                    text = context.getString(R.string.reading_stats_by_day).uppercase(),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Spacer(GlanceModifier.height(6.dp))
                StatsBars(stats = stats)
            }
        } else {
            CompactTally(
                left = figures.streakDays.toString(),
                leftLabel = context.getString(R.string.reading_stats_streak),
                right = figures.sessions.toString(),
                rightLabel = context.getString(R.string.reading_stats_sessions),
            )
        }
    }
}

@Composable
private fun CompactTally(
    left: String,
    leftLabel: String,
    right: String,
    rightLabel: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Tally(value = left, label = leftLabel)
        Spacer(GlanceModifier.width(16.dp))
        Tally(value = right, label = rightLabel)
    }
}

@Composable
private fun Tally(value: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = value,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
            ),
            maxLines = 1,
        )
        Spacer(GlanceModifier.width(4.dp))
        Text(
            text = label,
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 11.sp,
            ),
            maxLines = 1,
        )
    }
}

class WeekStatsWidgetReceiver : LiseurWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeekStatsWidget()
}
