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
internal val StatsCompact = DpSize(220.dp, 220.dp)
internal val StatsMedium = DpSize(340.dp, 220.dp)
internal val StatsRoomy = DpSize(340.dp, 360.dp)

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
internal fun StatsScope(context: Context, stats: WidgetStats, compact: Boolean = false) {
    val label = stats.scopeLabelResource(System.currentTimeMillis()) ?: return
    Text(
        text = context.getString(label),
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = if (compact) 8.sp else 11.sp),
    )
}

internal fun WidgetStats.scopeLabelResource(now: Long): Int? = when (scope(now)) {
    null, WidgetScope.THIS_DEVICE -> null
    WidgetScope.LAST_SYNC -> R.string.widget_scope_last_sync
}

private val trackColor = widgetEdge
private val fillColor = ColorProvider(day = Leather, night = LeatherNight)
