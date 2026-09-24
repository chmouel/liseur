package com.chmouel.liseur.ui.widget

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import kotlinx.coroutines.coroutineScope

class CoverOnlyWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(CoverSmall, CoverLarge))

    override suspend fun provideGlance(context: Context, id: GlanceId) = coroutineScope {
        val live = LiveSnapshot.start(context, id, this, WidgetContent.COVER)
        provideContent {
            GlanceTheme(colors = LiseurGlanceColorScheme.colors) {
                val book = live.observe().book
                if (book == null) {
                    EmptyShelf(context)
                } else {
                    WidgetScaffold(onClick = book.openIntent) {
                        BookCoverImage(
                            book = book,
                            modifier = GlanceModifier.fillMaxSize().padding(4.dp),
                        )
                    }
                }
            }
        }
    }
}

class CoverOnlyWidgetReceiver : LiseurWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CoverOnlyWidget()
}
