package com.chmouel.liseur.ui.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.chmouel.liseur.MainActivity
import com.chmouel.liseur.R
import kotlinx.coroutines.coroutineScope

class WeekStatsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(StatsCompact, StatsMedium, StatsRoomy))

    override suspend fun provideGlance(context: Context, id: GlanceId) = coroutineScope {
        val live = LiveSnapshot.start(context, id, this, WidgetContent.STATS)
        provideContent {
            GlanceTheme(colors = LiseurGlanceColorScheme.colors) {
                val snapshot = live.observe()
                val stats = snapshot.stats ?: return@GlanceTheme
                val onClick = MainActivity.widgetIntent(context, stats = true)
                WidgetScaffold(onClick = onClick) {
                    StatsContent(context = context, stats = stats)
                }
            }
        }
    }
}

@Composable
internal fun StatsContent(context: Context, stats: WidgetStats) {
    val roomy = sizeAtLeast(StatsMedium)
    val largeText = context.resources.configuration.fontScale > 1.2f
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(horizontal = if (roomy) 20.dp else 14.dp, vertical = if (roomy) 20.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeroTime(
            label = context.getString(stats.figures.period.headingRes),
            value = if (stats.figures.totalMs == 0L) context.getString(R.string.widget_duration_minutes, 0)
                else formatCompactDuration(context, stats.figures.totalMs),
            compact = !roomy,
        )
        Spacer(GlanceModifier.height(if (roomy) 12.dp else 6.dp))
        if (roomy || !largeText) Text(
            text = context.resources.getQuantityString(
                R.plurals.widget_streak_days, stats.figures.streakDays, stats.figures.streakDays,
            ),
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = if (roomy) 13.sp else 12.sp),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(if (roomy) 4.dp else 2.dp))
        StatsScope(context, stats)
    }
}

class WeekStatsWidgetReceiver : LiseurWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeekStatsWidget()
}
