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
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
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
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.chmouel.liseur.R
import kotlinx.coroutines.coroutineScope

class CoverStatsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(CoverStatsCompact, CoverStatsRoomy))

    override suspend fun provideGlance(context: Context, id: GlanceId) = coroutineScope {
        val live = LiveSnapshot.start(context, id, this)
        provideContent {
            GlanceTheme(colors = LiseurGlanceColorScheme.colors) {
                val snapshot = live.observe()
                val book = snapshot.book
                if (book == null) {
                    EmptyShelf(context)
                } else {
                    WidgetScaffold(onClick = book.openIntent) {
                        CoverStatsContent(
                            context = context,
                            book = book,
                            stats = snapshot.stats,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverStatsContent(
    context: Context,
    book: WidgetBook,
    stats: WidgetStats,
) {
    val roomy = sizeAtLeast(CoverStatsRoomy)
    val coverWidth = if (roomy) 96.dp else 64.dp
    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(if (roomy) 12.dp else 8.dp)
            .background(widgetCard),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCoverImage(
            book = book,
            modifier = GlanceModifier
                .width(coverWidth)
                .fillMaxHeight(),
        )
        Column(
            modifier = GlanceModifier
                .defaultWeight()
                .fillMaxHeight()
                .padding(start = if (roomy) 12.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = context.getString(R.string.continue_reading).uppercase(),
                style = TextStyle(
                    color = GlanceTheme.colors.primary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(2.dp))
            Text(
                text = book.title,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = if (roomy) 15.sp else 14.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Serif,
                ),
                maxLines = if (roomy) 2 else 1,
            )
            if (roomy) {
                book.author?.let { author ->
                    Text(
                        text = author,
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 12.sp,
                        ),
                        maxLines = 1,
                    )
                }
            }
            Spacer(GlanceModifier.height(if (roomy) 8.dp else 6.dp))
            ProgressTrack(progression = book.progression)
            Spacer(GlanceModifier.height(if (roomy) 8.dp else 4.dp))
            Text(
                text = periodTotal(context, stats),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = if (roomy) 13.sp else 12.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
            if (roomy && stats.figures.bars.isNotEmpty()) {
                Spacer(GlanceModifier.height(6.dp))
                StatsBars(stats = stats, barsHeight = 24.dp)
            }
        }
    }
}

class CoverStatsWidgetReceiver : LiseurWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CoverStatsWidget()
}
