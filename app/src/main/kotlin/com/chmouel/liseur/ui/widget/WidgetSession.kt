package com.chmouel.liseur.ui.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.glance.GlanceId
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.currentState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.chmouel.liseur.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.launch

/**
 * A widget's data for as long as its Glance session lives.
 *
 * Glance keeps a session alive between updates and only recomposes it,
 * so data read once in `provideGlance` would stay on screen until the
 * session happened to end. This reads again whenever [WidgetUpdater]
 * redraws or the widget is given another period. It lives outside the
 * composition because a responsive widget composes once per size, and
 * each size must not read the database on its own.
 */
internal class LiveSnapshot private constructor(
    private val context: Context,
    private val repository: WidgetRepository,
    private val content: WidgetContent,
    initial: WidgetSnapshot,
    private val initialPeriod: WidgetPeriod,
    private val initialGeneration: Long,
) {
    private val period = MutableStateFlow(initialPeriod)
    private val current = MutableStateFlow(initial)
    val snapshot: StateFlow<WidgetSnapshot> = current.asStateFlow()

    private suspend fun follow() {
        combine(period, WidgetUpdater.generation) { p, g -> p to g }
            .withIndex()
            .collectLatest { (index, key) ->
                if (index == 0 && key == initialPeriod to initialGeneration) return@collectLatest
                current.value = repository.load(context, key.first, content)
            }
    }

    /** Call from the composition with the period in the widget's state. */
    @Composable
    fun observe(): WidgetSnapshot {
        val wanted = WidgetPeriod.fromId(currentState(WidgetPeriodKey))
        SideEffect { period.value = wanted }
        val value by snapshot.collectAsState()
        return value
    }

    companion object {
        suspend fun start(
            context: Context,
            id: GlanceId,
            scope: CoroutineScope,
            content: WidgetContent,
        ): LiveSnapshot {
            val repository = widgetRepository(context)
            val generation = WidgetUpdater.generation.value
            val period = storedPeriod(context, id)
            val initial = repository.load(context, period, content)
            return LiveSnapshot(context, repository, content, initial, period, generation).also { live ->
                scope.launch { live.follow() }
            }
        }
    }
}

internal suspend fun storedPeriod(context: Context, id: GlanceId): WidgetPeriod =
    WidgetPeriod.fromId(
        getAppWidgetState(context, PreferencesGlanceStateDefinition, id)[WidgetPeriodKey],
    )

internal fun widgetRepository(context: Context): WidgetRepository {
    val database = context.container.database
    return WidgetRepository(
        bookDao = database.bookDao(),
        progressDao = database.readingProgressDao(),
        sessionDao = database.readingSessionDao(),
    )
}
