package com.chmouel.liseur.ui.widget

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.chmouel.liseur.R
import com.chmouel.liseur.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LibraryAnchor = intPreferencesKey("library_anchor")
private val TargetAnchor = ActionParameters.Key<Int>("library_target_anchor")

class LibraryWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) = coroutineScope {
        val dao = context.container.database.bookDao()
        val generation = WidgetUpdater.generation.value
        val rows = MutableStateFlow(withContext(Dispatchers.IO) { libraryBooks(dao.allOnce()) })
        launch {
            WidgetUpdater.generation.collect { next ->
                if (next != generation) rows.value = withContext(Dispatchers.IO) { libraryBooks(dao.allOnce()) }
            }
        }
        val repository = widgetRepository(context)
        provideContent {
            GlanceTheme(colors = LiseurGlanceColorScheme.colors) {
                val books by rows.collectAsState()
                val size = LocalSize.current
                val grid = libraryGrid(size.width.value, size.height.value)
                val anchor = currentState(LibraryAnchor) ?: 0
                val start = libraryPageStart(anchor, books.size, grid.capacity)
                val page by produceState<List<WidgetBook>>(emptyList(), books, start, grid) {
                    value = withContext(Dispatchers.IO) {
                        books.drop(start).take(grid.capacity).map { book ->
                            with(repository) { book.toWidgetBook(context, null, withCover = false) }
                                .copy(cover = book.coverPath?.let { decodeCoverBitmap(it, maxEdge = 160) })
                        }
                    }
                }
                if (books.isEmpty()) {
                    EmptyShelf(context)
                } else {
                    Column(
                        modifier = GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(20.dp)
                            .background(GlanceTheme.colors.surface).padding(12.dp),
                    ) {
                        Row(
                            modifier = GlanceModifier.fillMaxWidth().height(40.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (size.width.value >= 240) Image(
                                provider = ImageProvider(R.drawable.ic_widget_library),
                                contentDescription = null,
                                modifier = GlanceModifier.size(20.dp),
                                colorFilter = androidx.glance.ColorFilter.tint(GlanceTheme.colors.onSurface),
                            )
                            if (size.width.value >= 240) Spacer(GlanceModifier.width(8.dp))
                            Text(
                                text = context.getString(R.string.widget_library_label),
                                modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity(WidgetLaunchActivity.intent(context))),
                                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp),
                                maxLines = 1,
                            )
                            PageArrow(context, previous = true, target = start - grid.capacity, enabled = start > 0)
                            PageArrow(context, previous = false, target = start + grid.capacity, enabled = start + grid.capacity < books.size)
                        }
                        Spacer(GlanceModifier.height(8.dp))
                        repeat(grid.rows) { row ->
                            if (row > 0) Spacer(GlanceModifier.height(8.dp))
                            Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                                repeat(grid.columns) { column ->
                                    if (column > 0) Spacer(GlanceModifier.width(8.dp))
                                    val book = page.getOrNull(row * grid.columns + column)
                                    Box(modifier = GlanceModifier.defaultWeight().fillMaxHeight()) {
                                        if (book != null) BookCoverImage(
                                            book,
                                            GlanceModifier.fillMaxSize().clickable(actionStartActivity(book.openIntent)),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun PageArrow(context: Context, previous: Boolean, target: Int, enabled: Boolean) {
    val modifier = GlanceModifier.size(40.dp).padding(8.dp)
    Image(
        provider = ImageProvider(if (previous) R.drawable.ic_widget_previous else R.drawable.ic_widget_next),
        contentDescription = context.getString(if (previous) R.string.widget_previous_books else R.string.widget_next_books),
        modifier = if (enabled) modifier.clickable(actionRunCallback<LibraryPageAction>(actionParametersOf(TargetAnchor to target))) else modifier,
        colorFilter = androidx.glance.ColorFilter.tint(if (enabled) GlanceTheme.colors.onSurface else widgetEdge),
    )
}

class LibraryPageAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val target = parameters[TargetAnchor] ?: return
        updateAppWidgetState(context, glanceId) { it[LibraryAnchor] = target.coerceAtLeast(0) }
        LibraryWidget().update(context, glanceId)
    }
}

class LibraryWidgetReceiver : LiseurWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LibraryWidget()
}
