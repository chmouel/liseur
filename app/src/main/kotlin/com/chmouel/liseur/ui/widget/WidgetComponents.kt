package com.chmouel.liseur.ui.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.chmouel.liseur.MainActivity
import com.chmouel.liseur.R
import com.chmouel.liseur.ui.theme.Leather
import com.chmouel.liseur.ui.theme.LeatherNight
import com.chmouel.liseur.ui.theme.NightSurfaceHighest
import com.chmouel.liseur.ui.theme.PaperHighest
import kotlin.math.max

internal val CoverSmall = DpSize(110.dp, 165.dp)
internal val CoverLarge = DpSize(180.dp, 270.dp)
internal val StatsCompact = DpSize(180.dp, 110.dp)
internal val StatsRoomy = DpSize(250.dp, 180.dp)
internal val CoverStatsCompact = DpSize(250.dp, 110.dp)
internal val CoverStatsRoomy = DpSize(320.dp, 160.dp)

/** Glance's ColorProviders stop at surfaceVariant; paper card tint maps there. */
internal val widgetCard = ColorProvider(day = PaperHighest, night = NightSurfaceHighest)
internal val widgetTile = ColorProvider(
    day = com.chmouel.liseur.ui.theme.PaperRaised,
    night = com.chmouel.liseur.ui.theme.NightSurfaceLow,
)
internal val widgetEdge = ColorProvider(
    day = com.chmouel.liseur.ui.theme.Rule,
    night = com.chmouel.liseur.ui.theme.NightRule,
)

@Composable
internal fun WidgetScaffold(
    onClick: Intent,
    modifier: GlanceModifier = GlanceModifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .cornerRadius(16.dp)
            .background(GlanceTheme.colors.surface)
            .clickable(actionStartActivity(onClick)),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
internal fun EmptyShelf(context: Context) {
    val openApp = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    WidgetScaffold(onClick = openApp) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(16.dp)
                .background(widgetCard),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = context.getString(R.string.widget_empty_title),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Serif,
                    textAlign = TextAlign.Center,
                ),
            )
            Spacer(GlanceModifier.height(6.dp))
            Text(
                text = context.getString(R.string.widget_empty_detail),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 3,
            )
        }
    }
}

@Composable
internal fun BookCoverImage(
    book: WidgetBook,
    modifier: GlanceModifier = GlanceModifier,
) {
    val radius = 10.dp
    Box(
        modifier = modifier
            .cornerRadius(radius)
            .background(widgetEdge),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(1.dp)
                .cornerRadius(radius)
                .background(GlanceTheme.colors.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            val cover = book.cover
            if (cover != null) {
                Image(
                    provider = ImageProvider(cover),
                    contentDescription = book.title,
                    modifier = GlanceModifier.fillMaxSize().cornerRadius(9.dp),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    text = book.initials,
                    style = TextStyle(
                        color = GlanceTheme.colors.onPrimaryContainer,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Serif,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
        }
    }
}

@Composable
internal fun ProgressTrack(
    progression: Double?,
    modifier: GlanceModifier = GlanceModifier.fillMaxWidth().height(6.dp),
) {
    val fraction = ((progression ?: 0.0).coerceIn(0.0, 1.0)).toFloat()
    LinearProgressIndicator(
        progress = fraction,
        modifier = modifier,
        color = fillColor,
        backgroundColor = trackColor,
    )
}

/**
 * One bar per day of the widget's period, with the tallest bar's real
 * time as the scale.
 *
 * Glance truncates a container after ten children, so the bars are laid
 * out in rows sized by [barChunkSize]; a month would otherwise lose all
 * but its first days.
 */
@Composable
internal fun StatsBars(
    stats: WidgetStats,
    modifier: GlanceModifier = GlanceModifier.fillMaxWidth(),
    barsHeight: Dp = 36.dp,
) {
    val figures = stats.figures
    if (figures.bars.isEmpty()) return
    val peak = max(1L, figures.peakMs)
    val chunk = barChunkSize(figures.bars.size)
    val gap = if (chunk < figures.bars.size) 1.dp else 2.dp
    val described = stats.chartDescription?.let { spoken ->
        modifier.semantics { contentDescription = spoken }
    } ?: modifier
    Column(modifier = described) {
        stats.peakLabel?.let { label ->
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Spacer(GlanceModifier.defaultWeight())
                Text(
                    text = label,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
            }
            Spacer(GlanceModifier.height(2.dp))
        }
        Row(
            modifier = GlanceModifier.fillMaxWidth().height(barsHeight),
            verticalAlignment = Alignment.Bottom,
        ) {
            for (row in figures.bars.chunked(chunk)) {
                Row(
                    modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    for (bar in row) {
                        val ratio = (bar.totalMs.toFloat() / peak.toFloat()).coerceIn(0f, 1f)
                        Box(
                            modifier = GlanceModifier
                                .defaultWeight()
                                .fillMaxHeight()
                                .padding(horizontal = gap),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            Box(
                                modifier = GlanceModifier
                                    .fillMaxWidth()
                                    .height(MIN_BAR + (barsHeight - MIN_BAR) * ratio)
                                    .cornerRadius(2.dp)
                                    .background(barColor(figures, bar)),
                            ) {}
                        }
                    }
                    repeat(chunk - row.size) {
                        Box(modifier = GlanceModifier.defaultWeight().fillMaxHeight()) {}
                    }
                }
            }
        }
    }
}

private fun barColor(figures: PeriodStats, bar: WidgetBar): androidx.glance.unit.ColorProvider = when {
    bar.totalMs <= 0 -> trackColor
    figures.highlightsToday && bar.date != figures.today -> pastFillColor
    else -> fillColor
}

@Composable
internal fun StatTile(
    label: String,
    value: String,
    modifier: GlanceModifier = GlanceModifier,
) {
    Column(
        modifier = modifier
            .cornerRadius(12.dp)
            .background(widgetTile)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label.uppercase(),
            style = TextStyle(
                color = GlanceTheme.colors.primary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(2.dp))
        Text(
            text = value,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
            ),
            maxLines = 1,
        )
    }
}

@Composable
internal fun HeroTime(
    label: String,
    value: String,
    modifier: GlanceModifier = GlanceModifier,
    compact: Boolean = false,
) {
    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            style = TextStyle(
                color = GlanceTheme.colors.primary,
                fontSize = if (compact) 9.sp else 10.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(1.dp))
        Text(
            text = value,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = if (compact) 22.sp else 28.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
            ),
            maxLines = 1,
        )
    }
}

/** "Today", "This week" or "This month". */
internal val WidgetPeriod.headingRes: Int
    get() = when (this) {
        WidgetPeriod.DAY -> R.string.widget_period_today
        WidgetPeriod.WEEK -> R.string.reading_stats_this_week_local
        WidgetPeriod.MONTH -> R.string.reading_stats_this_month_local
    }

/** "1 h today", "3 h this week" or "12 h this month". */
internal fun periodTotal(context: Context, stats: WidgetStats): String = context.getString(
    when (stats.figures.period) {
        WidgetPeriod.DAY -> R.string.widget_total_today
        WidgetPeriod.WEEK -> R.string.reading_stats_week_total
        WidgetPeriod.MONTH -> R.string.widget_total_month
    },
    stats.totalLabel,
)

@Composable
internal fun sizeAtLeast(min: DpSize): Boolean {
    val size = LocalSize.current
    return size.width >= min.width && size.height >= min.height
}

// The card behind the chart is PaperHighest, so an empty day needs the rule colour to show at all.
private val trackColor = widgetEdge
private val fillColor = ColorProvider(day = Leather, night = LeatherNight)
private val pastFillColor = ColorProvider(
    day = Leather.copy(alpha = 0.4f),
    night = LeatherNight.copy(alpha = 0.4f),
)
// At 3dp the launcher drew nothing, so an empty day vanished from the chart; 6dp shows.
private val MIN_BAR = 6.dp
