package com.chmouel.liseur.ui.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
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
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
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

internal val CoverSmall = DpSize(110.dp, 165.dp)
internal val CoverLarge = DpSize(180.dp, 270.dp)
// Larger breakpoints give the reading total more breathing room.
internal val StatsCompact = DpSize(180.dp, 110.dp)
internal val StatsMedium = DpSize(250.dp, 180.dp)
internal val StatsRoomy = DpSize(250.dp, 230.dp)
internal val CoverStatsCompact = DpSize(250.dp, 110.dp)
internal val CoverStatsRoomy = DpSize(320.dp, 190.dp)

/** Glance's ColorProviders stop at surfaceVariant; paper card tint maps there. */
internal val widgetCard = ColorProvider(day = PaperHighest, night = NightSurfaceHighest)
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
                    modifier = GlanceModifier.semantics { contentDescription = book.title },
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

@Composable
internal fun HeroTime(
    label: String,
    value: String,
    modifier: GlanceModifier = GlanceModifier,
    compact: Boolean = false,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = TextStyle(
                color = GlanceTheme.colors.primary,
                fontSize = if (compact) 12.sp else 13.sp,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(if (compact) 4.dp else 6.dp))
        Text(
            text = value,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = if (compact) 22.sp else 36.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
            ),
            maxLines = 1,
        )
    }
}

@Composable
internal fun StatsScope(context: Context, stats: WidgetStats) {
    val figures = stats.figures
    val recent = figures.remoteUpdatedAt?.let { System.currentTimeMillis() - it in 0..3_600_000L } == true
    val label = when {
        figures.remoteCovered && recent -> R.string.widget_scope_all_devices
        figures.remoteUpdatedAt != null -> R.string.widget_scope_last_sync
        else -> R.string.widget_scope_this_device
    }
    Text(
        text = context.getString(label),
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
        maxLines = 1,
    )
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

private val trackColor = widgetEdge
private val fillColor = ColorProvider(day = Leather, night = LeatherNight)
