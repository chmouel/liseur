package com.chmouel.liseur.ui.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.ColorFilter
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
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.chmouel.liseur.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private val LibraryAnchor = intPreferencesKey("library_anchor")
private val TargetAnchor = ActionParameters.Key<Int>("library_target_anchor")

/**
 * A page of covers from the library, with arrows to move through it.
 *
 * The page is loaded before the first frame and kept on screen while the
 * next one loads: a session can end between two frames, and whatever it
 * drew last stays on the home screen until the library changes again.
 */
class LibraryWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) = coroutineScope {
        val repository = widgetRepository(context)
        val generation = WidgetUpdater.generation.value
        val anchor = getAppWidgetState<Preferences>(context, id)[LibraryAnchor] ?: 0
        val shelf = MutableStateFlow(repository.loadLibrary(context, anchor))
        val requested = MutableStateFlow(anchor)
        launch {
            var loaded = generation to anchor
            combine(WidgetUpdater.generation, requested) { next, target -> next to target }
                .collectLatest { wanted ->
                    if (wanted != loaded) {
                        shelf.value = repository.loadLibrary(context, wanted.second)
                        loaded = wanted
                    }
                }
        }
        provideContent {
            GlanceTheme(colors = LiseurGlanceColorScheme.colors) {
                val target = currentState(LibraryAnchor) ?: 0
                SideEffect { requested.value = target }
                val snapshot by shelf.collectAsState()
                if (snapshot.count == 0) EmptyShelf(context) else Shelf(context, snapshot)
            }
        }
    }
}

@Composable
private fun Shelf(context: Context, snapshot: LibrarySnapshot) {
    val size = LocalSize.current
    val grid = libraryGrid(size.width.value, size.height.value)
    val start = libraryPageStart(snapshot.anchor, snapshot.count, grid.capacity)
    val page = snapshot.page(start, grid.capacity)
    Column(
        modifier = GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(20.dp)
            .background(GlanceTheme.colors.surface).padding(12.dp),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (size.width.value >= 240) {
                Image(
                    provider = ImageProvider(R.drawable.ic_widget_library),
                    contentDescription = null,
                    modifier = GlanceModifier.size(20.dp),
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurface),
                )
                Spacer(GlanceModifier.width(8.dp))
            }
            Text(
                text = context.getString(R.string.widget_library_label),
                modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity(WidgetLaunchActivity.intent(context))),
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp),
                maxLines = 1,
            )
            PageArrow(context, previous = true, target = start - grid.capacity, enabled = start > 0)
            PageArrow(context, previous = false, target = start + grid.capacity, enabled = start + grid.capacity < snapshot.count)
        }
        Spacer(GlanceModifier.height(8.dp))
        if (grid.featuredLeft) {
            Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                val featured = page.getOrNull(0)
                val cell = (size.width.value - 24 - 8 * (grid.columns - 1)) / grid.columns
                val featuredWidth = cell * grid.featuredSpan + 8 * (grid.featuredSpan - 1)
                Box(modifier = GlanceModifier.width(featuredWidth.dp).fillMaxHeight()) {
                    if (featured != null) {
                        BookCoverImage(
                            featured,
                            GlanceModifier.fillMaxSize().clickable(actionStartActivity(featured.openIntent)),
                        )
                    }
                }
                val sideColumns = grid.sideColumns
                repeat(sideColumns) { column ->
                    Spacer(GlanceModifier.width(8.dp))
                    Column(modifier = GlanceModifier.defaultWeight().fillMaxHeight()) {
                        repeat(grid.rows) { row ->
                            if (row > 0) Spacer(GlanceModifier.height(8.dp))
                            val book = page.getOrNull(1 + row * sideColumns + column)
                            Box(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                                if (book != null) {
                                    BookCoverImage(
                                        book,
                                        GlanceModifier.fillMaxSize().clickable(actionStartActivity(book.openIntent)),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                repeat(grid.columns) { column ->
                    if (column > 0) Spacer(GlanceModifier.width(8.dp))
                    val book = page.getOrNull(column)
                    Box(modifier = GlanceModifier.defaultWeight().fillMaxHeight()) {
                        if (book != null) {
                            BookCoverImage(
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

@Composable
private fun PageArrow(context: Context, previous: Boolean, target: Int, enabled: Boolean) {
    val modifier = GlanceModifier.size(40.dp).padding(8.dp)
    Image(
        provider = ImageProvider(if (previous) R.drawable.ic_widget_previous else R.drawable.ic_widget_next),
        contentDescription = context.getString(if (previous) R.string.widget_previous_books else R.string.widget_next_books),
        modifier = if (enabled) modifier.clickable(actionRunCallback<LibraryPageAction>(actionParametersOf(TargetAnchor to target))) else modifier,
        colorFilter = ColorFilter.tint(if (enabled) GlanceTheme.colors.onSurface else widgetEdge),
    )
}

/** Moves one widget to another page; this tap is the only thing that redraws it outside a library change. */
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
